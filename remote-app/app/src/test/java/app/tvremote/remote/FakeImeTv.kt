package app.tvremote.remote

import app.tvremote.imeproto.ImeProto as P
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/** A TV keyboard server written from the wire spec only (independent of the real server code). */
class FakeImeTv(val tvId: String, val name: String, private val bind: String = "127.0.0.1", var port: Int = 0) {
    private val paired = HashMap<String, ByteArray>()
    @Volatile var pin: String? = null
    @Volatile var muted = false                       // stop answering pings (half-open link)
    @Volatile var impostor = false                    // claims to be paired but cannot prove it
    val received = LinkedBlockingQueue<String>()
    val sessions = AtomicInteger(); val pairings = AtomicInteger()
    private var srv: ServerSocket? = null
    private val open = CopyOnWriteArrayList<Socket>()
    @Volatile private var live: Pair<Socket, P.Sealer>? = null

    fun start() {
        var s: ServerSocket? = null
        for (n in 1..40) {                                   // a just-closed listener can linger for a moment on Linux: retry
            val c = ServerSocket(); c.reuseAddress = true
            try { c.bind(InetSocketAddress(InetAddress.getByName(bind), port)); s = c; break } catch (e: java.net.BindException) { c.close(); Thread.sleep(50) }
        }
        if (s == null) throw java.net.BindException("fake TV cannot bind port $port")
        port = s.localPort; srv = s
        val sock = s
        Thread({ while (!sock.isClosed) { val c = try { sock.accept() } catch (e: Exception) { break }; open.add(c); Thread { try { handle(c) } catch (e: Exception) { } finally { try { c.close() } catch (x: Exception) { } } }.apply { isDaemon = true; start() } } }).apply { isDaemon = true; start() }
    }
    fun stop() { try { srv?.close() } catch (e: Exception) { }; open.forEach { try { it.close() } catch (e: Exception) { } }; open.clear(); live = null }   // "TV powered off"
    fun reboot() { stop(); start() }
    fun forget() { paired.clear() }

    private fun rl(i: java.io.InputStream): String? { val b = StringBuilder(); while (true) { val c = i.read(); if (c < 0) return null; if (c == '\n'.code) return b.toString(); b.append(c.toChar()) } }

    fun push(msg: String) { live?.let { (s, sl) -> synchronized(s) { s.getOutputStream().write((sl.seal(msg) + "\n").toByteArray()); s.getOutputStream().flush() } } }

    private fun handle(c: Socket) {
        val i = c.getInputStream(); val o = c.getOutputStream()
        fun w(l: String) { synchronized(o) { o.write((l + "\n").toByteArray()); o.flush() } }
        val hi = (rl(i) ?: return).split(' '); val cid = hi[2]
        val ns = P.randomHex(16)
        w("SRV 1 $tvId $ns ${if (paired.containsKey(cid) || impostor) 1 else 0} ${P.enc(name)}")
        val l = (rl(i) ?: return).split(' ')
        if (l[0] == "PAIR") {
            val p = pin ?: run { w("NO CLOSED"); return }
            val pubC = P.unhex(l[1])!!; val e = P.Ecdh(); val z = e.shared(pubC); w("PUB ${P.hex(e.pub)}")
            val nm = P.dec(l[2]).toByteArray()
            while (true) {
                val r = (rl(i) ?: return).split(' ')
                if (P.same(P.pinProof("c", p, pubC, e.pub, nm), P.unhex(r[1])!!)) {
                    paired[cid] = P.token(z, pubC, e.pub); pin = null; pairings.incrementAndGet()
                    w("OK ${P.hex(P.pinProof("s", p, pubC, e.pub, nm))}"); return
                } else w("NO PIN 4")
            }
        }
        if (l[0] != "AUTH") return
        val nc = P.unhex(l[1])!!
        if (impostor) { w("OK ${P.hex(ByteArray(32))}"); return }                 // fake proof
        val tok = paired[cid] ?: run { w("NO UNPAIRED"); return }
        if (!P.same(P.authProof(tok, P.unhex(cid)!!, P.unhex(ns)!!, nc), P.unhex(l[2])!!)) { w("NO BAD"); return }
        val keys = P.sessionKeys(tok, P.unhex(ns)!!, nc); val sl = P.Sealer(keys.s2c, P.DIR_S2C); val op = P.Opener(keys.c2s, P.DIR_C2S)
        w("OK ${P.hex(P.srvProof(tok, nc, P.unhex(ns)!!))}")
        live = c to sl; sessions.incrementAndGet()
        push("CAPS keys,text,vol,media"); push("STATE input=1")
        while (true) {
            val m = op.open(rl(i) ?: return) ?: return
            if (m.startsWith("PING")) { if (!muted) push("PONG " + m.substring(5)) } else received.add(m)
        }
    }
}
