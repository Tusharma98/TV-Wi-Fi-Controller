package app.tvremote.remote

import app.tvremote.imeproto.ImeProto
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

class ImeTv(val tvId: String, val name: String, val host: String, val token: ByteArray)
class Found(val host: String, val tvId: String, val name: String, val paired: Boolean)

interface ImeStore {
    fun clientId(): String
    fun tvs(): List<ImeTv>
    fun save(t: ImeTv)
    fun remove(tvId: String)
    fun last(): String?
    fun setLast(tvId: String)
}
interface ImeFinder { fun scan(): List<Found> }

/**
 * Phone side of the TV keyboard link. Pure JVM (no Android classes) so it is unit-tested against a real server.
 * Handles: handshake + mutual authentication, sealed frames, heartbeat, reconnect with back-off (also across TV reboots),
 * finding a paired TV again after its IP changed, several paired TVs, and PIN pairing.
 */
class ImeClient(
    private val store: ImeStore, private val finder: ImeFinder, private val clientName: String,
    private val changed: () -> Unit = { }, private val hint: (String) -> Unit = { },
    private val t: Timing = Timing(),
    private val onExt: (String) -> Unit = { }                 // Accessibility add-on replies from the TV (app list, icons, messages, file ticket)
) {
    class Timing(
        val port: Int = ImeProto.PORT, val connectMs: Int = 3000, val pingMs: Long = 5000, val deadMs: Int = 15000,
        val backoffMs: List<Long> = listOf(2000, 3000, 5000, 8000, 10000), val unpairedRetryMs: Long = 15000
    )
    enum class State { IDLE, CONNECTING, READY, OFFLINE, NEEDS_PAIR }

    @Volatile var state = State.IDLE; private set
    @Volatile var caps: Set<String> = emptySet(); private set
    @Volatile var input = false; private set
    @Volatile var tvName = ""; private set
    @Volatile var lastError: String? = null; private set

    @Volatile private var wanted = false
    private var gen = 0
    private var worker: Thread? = null
    @Volatile private var sess: Live? = null
    private val tx = Executors.newSingleThreadExecutor { r -> Thread(r, "ime-tx").apply { isDaemon = true } }

    val active: Boolean get() = wanted

    private class Live(val sock: Socket, val sealer: ImeProto.Sealer) {
        val out = sock.getOutputStream()
        fun send(line: String) { synchronized(out) { out.write((sealer.seal(line) + "\n").toByteArray()); out.flush() } }
        fun close() { try { sock.close() } catch (e: Exception) { } }
    }

    // ---------- control ----------
    @Synchronized fun start() {
        if (wanted) return
        wanted = true; gen++
        val g = gen
        worker = Thread({ loop(g) }, "ime-link").apply { isDaemon = true; start() }
    }

    @Synchronized fun stop() {
        wanted = false; gen++
        sess?.close(); worker?.interrupt()
        set(State.IDLE)
    }

    /** Send a command line ("K 19", "T hi", "R hi"). false = not delivered. Never blocks the caller. */
    fun send(line: String): Boolean {
        val s = sess ?: return false
        if (state != State.READY) return false
        tx.execute { try { s.send(line) } catch (e: Exception) { s.close() } }
        return true
    }

    // ---------- connection loop ----------
    private fun alive(g: Int) = wanted && g == gen
    private fun set(s: State) { if (state != s) { state = s; changed() } }

    private fun nap(g: Int, ms: Long) { if (!alive(g)) return; try { Thread.sleep(ms) } catch (e: InterruptedException) { } }

    private fun loop(g: Int) {
        var misses = 0
        try {
            while (alive(g)) {
                val tvs = store.tvs()
                if (tvs.isEmpty()) break                                     // nothing paired: nothing to do
                set(State.CONNECTING)
                val target = pick(tvs)
                if (target == null) { set(State.OFFLINE); nap(g, t.backoffMs[minOf(misses++, t.backoffMs.size - 1)]); continue }
                val (tv, host) = target
                try {
                    val ok = session(g, tv, host)
                    if (ok) misses = 0 else misses++
                } catch (e: Exception) { misses++ }
                sess = null; caps = emptySet(); input = false
                if (!alive(g)) break
                if (state != State.NEEDS_PAIR) set(State.OFFLINE)
                nap(g, if (state == State.NEEDS_PAIR) t.unpairedRetryMs else t.backoffMs[minOf(maxOf(misses - 1, 0), t.backoffMs.size - 1)])
            }
        } finally { if (g == gen) { wanted = false; set(State.IDLE) } }
    }

    /** Which paired TV to talk to and where it is now (saved address first, then a network scan: DHCP changes, several TVs). */
    private fun pick(tvs: List<ImeTv>): Pair<ImeTv, String>? {
        val ordered = tvs.sortedByDescending { it.tvId == store.last() }
        for (tv in ordered) {
            val f = hello(tv.host, t.port, store.clientId(), t.connectMs)
            if (f != null && f.tvId == tv.tvId) return tv to tv.host
        }
        val found = try { finder.scan() } catch (e: Exception) { emptyList() }
        for (tv in ordered) found.firstOrNull { it.tvId == tv.tvId }?.let { f ->
            val moved = ImeTv(tv.tvId, f.name.ifBlank { tv.name }, f.host, tv.token)
            store.save(moved); return moved to f.host
        }
        return null
    }

    private fun readLine(i: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = i.read()
            if (b < 0) return if (sb.isEmpty()) null else throw IOException("eof mid-line")
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length >= ImeProto.MAX_LINE) throw IOException("line too long")
            sb.append(b.toChar())
        }
    }

    /** returns true when the session was established (and has now ended). */
    private fun session(g: Int, tv: ImeTv, host: String): Boolean {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, t.port), t.connectMs); s.tcpNoDelay = true; s.soTimeout = t.connectMs
            try { s.trafficClass = 0x10 } catch (e: Exception) { }                    // IPTOS_LOWDELAY
            val i: InputStream = java.io.BufferedInputStream(s.getInputStream(), 4096)   // one syscall per burst instead of one per byte
            val o = s.getOutputStream()
            o.write("HI ${ImeProto.VERSION} ${store.clientId()}\n".toByteArray()); o.flush()
            val srv = (readLine(i) ?: throw IOException("closed")).split(' ')
            if (srv.size < 5 || srv[0] != "SRV" || srv[1] != ImeProto.VERSION.toString()) { lastError = "This TV keyboard speaks a different protocol version"; hint(lastError!!); return false }
            if (srv[2] != tv.tvId) return false                                     // another TV took this address
            if (srv[4] != "1") { lastError = "The TV no longer knows this phone. Pair again."; set(State.NEEDS_PAIR); hint(lastError!!); return false }
            tvName = ImeProto.dec(srv.getOrElse(5) { "" }).ifBlank { tv.name }
            val nonceS = ImeProto.unhex(srv[3]) ?: throw IOException("bad nonce")
            val nonceC = ImeProto.unhex(ImeProto.randomHex(16))!!
            o.write("AUTH ${ImeProto.hex(nonceC)} ${ImeProto.hex(ImeProto.authProof(tv.token, ImeProto.unhex(store.clientId())!!, nonceS, nonceC))}\n".toByteArray()); o.flush()
            val ok = (readLine(i) ?: throw IOException("closed")).split(' ')
            if (ok[0] == "NO") {
                lastError = when (ok.getOrNull(1)) { "BUSY" -> "That TV is used by another phone"; "UNPAIRED" -> "The TV no longer knows this phone. Pair again."; else -> "TV rejected the connection" }
                if (ok.getOrNull(1) == "UNPAIRED") set(State.NEEDS_PAIR)
                hint(lastError!!); return false
            }
            if (ok[0] != "OK" || ok.size < 2 || !ImeProto.same(ImeProto.srvProof(tv.token, nonceC, nonceS), ImeProto.unhex(ok[1]) ?: ByteArray(0))) { lastError = "The TV did not prove it knows the pairing - not trusted"; hint(lastError!!); return false }
            val keys = ImeProto.sessionKeys(tv.token, nonceS, nonceC)
            val live = Live(s, ImeProto.Sealer(keys.c2s, ImeProto.DIR_C2S))
            val op = ImeProto.Opener(keys.s2c, ImeProto.DIR_S2C)
            s.soTimeout = t.deadMs                                                  // TV answers every ping: silence this long = dead link
            sess = live; store.setLast(tv.tvId); lastError = null
            set(State.READY)
            val ping = Thread({
                var n = 0
                while (alive(g) && sess === live) { try { live.send("PING " + n++); Thread.sleep(t.pingMs) } catch (e: Exception) { break } }
            }, "ime-ping").apply { isDaemon = true; start() }
            val ext = StringBuilder()
            try {
                while (alive(g)) {
                    val line = readLine(i) ?: break
                    val msg = op.open(line) ?: break                                // forged / replayed: drop
                    val p = msg.split(' ')
                    when (p[0]) {
                        "XP" -> { if (ext.length < 400_000) ext.append(p.getOrElse(1) { "" }) }          // part of a long reply
                        "X" -> { ext.append(p.getOrElse(1) { "" }); val full = ImeProto.dec(ext.toString()); ext.setLength(0); if (full.isNotEmpty()) try { onExt(full) } catch (e: Exception) { } }
                        "CAPS" -> { caps = p.getOrElse(1) { "" }.split(',').filter { it.isNotEmpty() }.toSet(); changed() }
                        "STATE" -> { input = p.getOrNull(1) == "input=1"; changed() }
                        "ERR" -> if (p.getOrNull(1) == "noinput") { input = false; hint("Select a text field on the TV first"); changed() }
                    }
                }
            } finally { ping.interrupt() }
            return true
        } finally { try { s.close() } catch (e: Exception) { } }
    }

    // ---------- pairing ----------
    private class PairCtx(val sock: Socket, val i: InputStream, val host: String, val tvId: String, val tvName: String, val ecdh: ImeProto.Ecdh, val pubC: ByteArray, val pubS: ByteArray, val z: ByteArray, val name: ByteArray)
    @Volatile private var pairing: PairCtx? = null

    sealed class PairResult {
        object Paired : PairResult()
        class WrongPin(val left: Int) : PairResult()
        class Failed(val why: String) : PairResult()
    }

    /** Step 1: connect, ask the TV to take part. cb(null) = the TV is waiting for the PIN; cb(text) = failure reason. */
    fun beginPairing(host: String, cb: (String?) -> Unit) {
        cancelPairing()
        Thread({
            try {
                val s = Socket(); s.connect(InetSocketAddress(host, t.port), t.connectMs); s.tcpNoDelay = true; s.soTimeout = 10000
                val i = s.getInputStream(); val o = s.getOutputStream()
                o.write("HI ${ImeProto.VERSION} ${store.clientId()}\n".toByteArray()); o.flush()
                val srv = (readLine(i) ?: throw IOException("closed")).split(' ')
                if (srv.size < 5 || srv[0] != "SRV" || srv[1] != ImeProto.VERSION.toString()) { s.close(); cb("This TV keyboard speaks a different protocol version"); return@Thread }
                val ecdh = ImeProto.Ecdh()
                val name = clientName.take(32)
                o.write("PAIR ${ImeProto.hex(ecdh.pub)} ${ImeProto.enc(name)}\n".toByteArray()); o.flush()
                val r = (readLine(i) ?: throw IOException("closed")).split(' ')
                if (r[0] == "NO") { s.close(); cb("On the TV open 'TV Remote Keyboard' and press 'Pair a new phone' first"); return@Thread }
                if (r[0] != "PUB" || r.size < 2) { s.close(); cb("Unexpected answer from the TV"); return@Thread }
                val pubS = ImeProto.unhex(r[1]) ?: throw IOException("bad key")
                val z = ecdh.shared(pubS)
                pairing = PairCtx(s, i, host, srv[2], ImeProto.dec(srv.getOrElse(5) { "" }), ecdh, ecdh.pub, pubS, z, name.toByteArray())
                cb(null)
            } catch (e: Exception) { cancelPairing(); cb(when (e) { is java.net.ConnectException, is java.net.SocketTimeoutException -> "TV not reachable. Is it on, same Wi-Fi, TV Remote Keyboard installed and selected?"; else -> e.message ?: "Pairing failed" }) }
        }, "ime-pair").start()
    }

    /** Step 2: prove we saw the PIN. WrongPin keeps the session so the PIN can be retyped. */
    fun finishPairing(pin: String, cb: (PairResult) -> Unit) {
        Thread({
            val p = pairing
            if (p == null) { cb(PairResult.Failed("Pairing session ended - start again")); return@Thread }
            try {
                val o = p.sock.getOutputStream()
                val proof = ImeProto.pinProof("c", pin.trim(), p.pubC, p.pubS, p.name)
                o.write("PROOF ${ImeProto.hex(proof)}\n".toByteArray()); o.flush()
                val r = (readLine(p.i) ?: throw IOException("closed")).split(' ')
                when {
                    r[0] == "OK" && r.size >= 2 && ImeProto.same(ImeProto.pinProof("s", pin.trim(), p.pubC, p.pubS, p.name), ImeProto.unhex(r[1]) ?: ByteArray(0)) -> {
                        store.save(ImeTv(p.tvId, p.tvName, p.host, ImeProto.token(p.z, p.pubC, p.pubS))); store.setLast(p.tvId)
                        cancelPairing(); cb(PairResult.Paired)
                    }
                    r[0] == "OK" -> { cancelPairing(); cb(PairResult.Failed("The TV could not prove it knows the PIN - not trusted")) }
                    r[0] == "NO" && r.getOrNull(1) == "PIN" -> { val left = r.getOrNull(2)?.toIntOrNull() ?: 0; if (left <= 0) cancelPairing(); cb(PairResult.WrongPin(left)) }
                    else -> { cancelPairing(); cb(PairResult.Failed("Pairing window closed on the TV - start again")) }
                }
            } catch (e: Exception) { cancelPairing(); cb(PairResult.Failed(e.message ?: "Pairing failed")) }
        }, "ime-pair2").start()
    }

    fun cancelPairing() { try { pairing?.sock?.close() } catch (e: Exception) { }; pairing = null }

    companion object {
        /** HI/SRV only: who is at this address (used to find TVs on the network and to re-find a paired TV). */
        fun hello(host: String, port: Int, clientId: String, timeoutMs: Int): Found? {
            val s = Socket()
            return try {
                s.connect(InetSocketAddress(host, port), timeoutMs); s.soTimeout = timeoutMs
                s.getOutputStream().write("HI ${ImeProto.VERSION} $clientId\n".toByteArray()); s.getOutputStream().flush()
                val b = StringBuilder(); val i = s.getInputStream()
                while (b.length < 512) { val c = i.read(); if (c < 0 || c == '\n'.code) break; b.append(c.toChar()) }
                val l = b.toString().trim().split(' ')
                if (l.size >= 5 && l[0] == "SRV" && l[1] == ImeProto.VERSION.toString()) Found(host, l[2], ImeProto.dec(l.getOrElse(5) { "" }), l[4] == "1") else null
            } catch (e: Exception) { null } finally { try { s.close() } catch (e: Exception) { } }
        }
    }
}
