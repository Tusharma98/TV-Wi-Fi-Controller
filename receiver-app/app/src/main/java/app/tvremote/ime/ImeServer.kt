package app.tvremote.ime

import app.tvremote.imeproto.ImeProto
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

enum class InjectResult { OK, NO_INPUT, UNSUPPORTED }

/** What the TV can really do. Implemented by the Android layer; faked in tests. */
interface Injector {
    fun inputActive(): Boolean
    fun key(code: Int, action: Char): InjectResult      // action: P press, D down, U up
    fun text(s: String, replace: Boolean): InjectResult
    fun caps(): String
    /** Accessibility add-on: one opaque line from the phone (mouse, app list, file ticket...). Must return fast (hand work to another thread). */
    fun ext(line: String, reply: (String) -> Unit) { }
}

class Paired(val clientId: String, val name: String, val token: ByteArray)
interface PairStorage {
    fun load(clientId: String): Paired?
    fun save(p: Paired)
    fun all(): List<Paired>
    fun clear()
}

interface ServerEvents { fun changed() }

class Limits(
    val handshakeMs: Int = 10_000, val idleMs: Int = 15_000, val maxPending: Int = 4,
    val ipFails: Int = 5, val ipLockMs: Long = 60_000, val pinAttempts: Int = 5, val pinWindowMs: Long = 120_000
)

/**
 * LAN server of the TV keyboard. Nothing is accepted before authentication:
 *  - paired phones prove knowledge of a 256-bit token (challenge/response, fresh nonces both ways),
 *  - new phones need the one-time PIN that is on the TV screen (window of 2 minutes, 5 tries),
 *  - every later frame is AES-GCM sealed, so a LAN device without the token cannot inject or replay anything.
 */
class ImeServer(
    private val port: Int, val tvId: String, private val tvName: String,
    private val store: PairStorage, private val inj: Injector,
    private val events: ServerEvents = object : ServerEvents { override fun changed() { } },
    private val limits: Limits = Limits(),
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    private class Window(val pin: String, val until: Long, var left: Int)
    private class Fails(var n: Int, var since: Long)
    private inner class Session(val clientId: String, val sock: Socket, val sealer: ImeProto.Sealer) {
        val out: OutputStream = sock.getOutputStream()
        fun send(line: String) { synchronized(out) { out.write((sealer.seal(line) + "\n").toByteArray()); out.flush() } }
        /** Long replies (app list, icons) are split so every sealed line stays under MAX_LINE. */
        fun sendExt(text: String) {
            val e = ImeProto.enc(text)
            var o = 0
            while (e.length - o > EXT_CHUNK) { send("XP " + e.substring(o, o + EXT_CHUNK)); o += EXT_CHUNK }
            send("X " + e.substring(o))
        }
        fun close() { try { sock.close() } catch (e: Exception) { } }
    }

    @Volatile private var window: Window? = null
    @Volatile private var session: Session? = null
    @Volatile private var srv: ServerSocket? = null
    private val pending = AtomicInteger()
    private val fails = HashMap<String, Fails>()
    private val lock = Any()

    val boundPort: Int get() = srv?.localPort ?: -1
    val activeClient: String? get() = session?.clientId

    // ---------- lifecycle ----------
    @Synchronized fun start() {
        if (srv != null) return
        val s = ServerSocket(); s.reuseAddress = true; s.bind(InetSocketAddress(port)); srv = s
        Thread({ acceptLoop(s) }, "ime-accept").apply { isDaemon = true; start() }
    }

    @Synchronized fun stop() {
        try { srv?.close() } catch (e: Exception) { }
        srv = null; session?.close(); session = null; window = null
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val c = try { s.accept() } catch (e: Exception) { break }
            if (pending.incrementAndGet() > limits.maxPending) { pending.decrementAndGet(); try { c.close() } catch (e: Exception) { }; continue }
            Thread({ try { handle(c) } catch (e: Exception) { } finally { pending.decrementAndGet(); try { c.close() } catch (e: Exception) { } } }, "ime-conn").apply { isDaemon = true; start() }
        }
    }

    // ---------- pairing window ----------
    /** Shows a fresh one-time PIN; closes by itself after the window or the allowed wrong tries. */
    fun openPairing(): String {
        val w = Window(ImeProto.randomPin(), now() + limits.pinWindowMs, limits.pinAttempts)
        window = w; events.changed(); return w.pin
    }
    fun closePairing() { window = null; events.changed() }
    fun pairingPin(): String? = openWindow()?.pin
    fun pairingSecondsLeft(): Int = openWindow()?.let { ((it.until - now()) / 1000).toInt().coerceAtLeast(0) } ?: 0
    private fun openWindow(): Window? { val w = window ?: return null; if (now() > w.until || w.left <= 0) { window = null; return null }; return w }

    fun forgetAll() { store.clear(); session?.close(); events.changed() }
    fun pairedCount() = store.all().size

    /** The TV's abilities changed (Accessibility turned on / off): tell the phone so it can show / hide the extra tools. */
    fun capsChanged() { try { session?.send("CAPS " + inj.caps()); session?.send("STATE input=" + if (inj.inputActive()) 1 else 0) } catch (e: Exception) { } }

    /** Called by the keyboard service when an editor gains / loses focus. */
    fun inputChanged() { try { session?.send("STATE input=" + if (inj.inputActive()) 1 else 0) } catch (e: Exception) { } }

    // ---------- per-IP lockout ----------
    private fun locked(ip: String): Boolean = synchronized(fails) { val f = fails[ip] ?: return false; if (now() - f.since > limits.ipLockMs) { fails.remove(ip); false } else f.n >= limits.ipFails }
    private fun fail(ip: String) = synchronized(fails) { val f = fails.getOrPut(ip) { Fails(0, now()) }; if (f.n == 0) f.since = now(); f.n++ }

    // ---------- connection ----------
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

    private fun handle(s: Socket) {
        val ip = s.inetAddress.hostAddress ?: "?"
        if (locked(ip)) return
        s.soTimeout = limits.handshakeMs; s.tcpNoDelay = true
        try { s.trafficClass = 0x10 } catch (e: Exception) { }                  // IPTOS_LOWDELAY
        val i: InputStream = java.io.BufferedInputStream(s.getInputStream(), 4096)   // one syscall per burst instead of one per byte
        val o = s.getOutputStream()
        fun plain(l: String) { synchronized(o) { o.write((l + "\n").toByteArray()); o.flush() } }

        val hi = (readLine(i) ?: return).split(' ')
        if (hi.size != 3 || hi[0] != "HI" || hi[1] != ImeProto.VERSION.toString() || !ImeProto.isHex(hi[2], 16)) { fail(ip); return }
        val clientId = hi[2]
        val known = store.load(clientId)
        val nonceS = ImeProto.randomHex(16)
        plain("SRV ${ImeProto.VERSION} $tvId $nonceS ${if (known != null) 1 else 0} ${ImeProto.enc(tvName)}")

        val l = (readLine(i) ?: return).split(' ')
        when (l[0]) {
            "AUTH" -> auth(s, ip, l, clientId, known, nonceS, ::plain, i)
            "PAIR" -> pair(ip, l, clientId, ::plain, i)
            else -> fail(ip)
        }
    }

    private fun pair(ip: String, l: List<String>, clientId: String, plain: (String) -> Unit, i: InputStream) {
        val w0 = openWindow()
        if (l.size != 3 || w0 == null) { plain("NO CLOSED"); if (l.size != 3) fail(ip); return }
        val pubC = ImeProto.unhex(l[1]) ?: run { fail(ip); return }
        val nameBytes = ImeProto.dec(l[2]).take(32).toByteArray(Charsets.UTF_8)
        val ecdh = ImeProto.Ecdh()
        val z = try { ecdh.shared(pubC) } catch (e: Exception) { fail(ip); return }
        val pubS = ecdh.pub
        plain("PUB ${ImeProto.hex(pubS)}")
        while (true) {
            val p = (readLine(i) ?: return).split(' ')
            val w = openWindow() ?: run { plain("NO CLOSED"); return }
            val proof = if (p.size == 2 && p[0] == "PROOF") ImeProto.unhex(p[1]) else null
            if (proof == null) { fail(ip); return }
            if (ImeProto.same(ImeProto.pinProof("c", w.pin, pubC, pubS, nameBytes), proof)) {
                store.save(Paired(clientId, String(nameBytes, Charsets.UTF_8), ImeProto.token(z, pubC, pubS)))
                window = null                                        // PIN is single use
                plain("OK ${ImeProto.hex(ImeProto.pinProof("s", w.pin, pubC, pubS, nameBytes))}")
                events.changed(); return
            }
            fail(ip); w.left--
            if (w.left <= 0) window = null
            plain("NO PIN ${maxOf(w.left, 0)}"); events.changed()
            if (w.left <= 0) return
        }
    }

    private fun auth(s: Socket, ip: String, l: List<String>, clientId: String, known: Paired?, nonceS: String, plain: (String) -> Unit, i: InputStream) {
        if (known == null) { fail(ip); plain("NO UNPAIRED"); return }
        if (l.size != 3 || !ImeProto.isHex(l[1], 16)) { fail(ip); return }
        val nonceC = ImeProto.unhex(l[1])!!
        val proof = ImeProto.unhex(l[2])
        val want = ImeProto.authProof(known.token, ImeProto.unhex(clientId)!!, ImeProto.unhex(nonceS)!!, nonceC)
        if (proof == null || !ImeProto.same(want, proof)) { fail(ip); plain("NO BAD"); return }

        val keys = ImeProto.sessionKeys(known.token, ImeProto.unhex(nonceS)!!, nonceC)
        val sess = Session(clientId, s, ImeProto.Sealer(keys.s2c, ImeProto.DIR_S2C))
        synchronized(lock) {
            val cur = session
            if (cur != null && cur.clientId != clientId) { plain("NO BUSY"); return }       // one remote at a time
            cur?.close()                                                                       // same phone reconnecting after a Wi-Fi drop
            session = sess
        }
        plain("OK ${ImeProto.hex(ImeProto.srvProof(known.token, nonceC, ImeProto.unhex(nonceS)!!))}")
        events.changed()
        try { serve(sess, ImeProto.Opener(keys.c2s, ImeProto.DIR_C2S), s, i) } finally { synchronized(lock) { if (session === sess) session = null }; events.changed() }
    }

    private fun serve(sess: Session, op: ImeProto.Opener, s: Socket, i: InputStream) {
        s.soTimeout = limits.idleMs                       // the phone pings every 5 s: silence = gone
        sess.send("CAPS " + inj.caps()); sess.send("STATE input=" + if (inj.inputActive()) 1 else 0)
        while (true) {
            val line = readLine(i) ?: return
            val msg = op.open(line) ?: return              // forged / replayed / corrupted: drop the connection
            val p = msg.split(' ')
            when (p[0]) {
                "PING" -> sess.send("PONG " + p.getOrElse(1) { "0" })
                "G" -> { sess.send("CAPS " + inj.caps()); sess.send("STATE input=" + if (inj.inputActive()) 1 else 0) }
                "K" -> {
                    val code = p.getOrNull(1)?.toIntOrNull()
                    val act = p.getOrNull(2)?.firstOrNull() ?: 'P'
                    if (code == null || code !in 1..400 || act !in "PDU") { sess.send("ERR badcmd"); continue }
                    when (inj.key(code, act)) {
                        InjectResult.OK -> { }
                        InjectResult.NO_INPUT -> sess.send("ERR noinput $code")
                        InjectResult.UNSUPPORTED -> sess.send("ERR unsupported $code")
                    }
                }
                "T", "R" -> {
                    val t = ImeProto.dec(p.getOrElse(1) { "" })
                    if (t.isEmpty() || t.length > 2000) { sess.send("ERR badcmd"); continue }
                    if (inj.text(t, p[0] == "R") == InjectResult.NO_INPUT) sess.send("ERR noinput text")
                }
                "X" -> {
                    val t = ImeProto.dec(p.getOrElse(1) { "" })
                    if (t.isEmpty() || t.length > 1500) { sess.send("ERR badcmd"); continue }
                    inj.ext(t) { r -> try { sess.sendExt(r) } catch (e: Exception) { sess.close() } }
                }
                else -> sess.send("ERR badcmd")
            }
        }
    }

    companion object { const val EXT_CHUNK = 3000 }
}
