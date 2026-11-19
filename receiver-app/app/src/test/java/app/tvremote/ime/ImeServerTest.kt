package app.tvremote.ime

import app.tvremote.imeproto.ImeProto as P
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList

class FakeInjector : Injector {
    @Volatile var input = true
    val calls = CopyOnWriteArrayList<String>()
    override fun inputActive() = input
    override fun key(code: Int, action: Char): InjectResult {
        if (code == 26) return InjectResult.UNSUPPORTED                    // Power: not injectable
        if (code in setOf(19, 20, 21, 22, 23, 66, 4) && !input) return InjectResult.NO_INPUT
        calls.add("K $code$action"); return InjectResult.OK
    }
    override fun text(s: String, replace: Boolean): InjectResult { if (!input) return InjectResult.NO_INPUT; calls.add((if (replace) "R " else "T ") + s); return InjectResult.OK }
    @Volatile var capsStr = "keys,text,vol,media"
    @Volatile var extReply: String? = null
    override fun caps() = capsStr
    override fun ext(line: String, reply: (String) -> Unit) { calls.add("X $line"); extReply?.let { reply(it) } }
}

class MemStore : PairStorage {
    private val m = HashMap<String, Paired>()
    @Synchronized override fun load(clientId: String) = m[clientId]
    @Synchronized override fun save(p: Paired) { m[p.clientId] = p }
    @Synchronized override fun all() = m.values.toList()
    @Synchronized override fun clear() { m.clear() }
}

/** Speaks the wire protocol straight from the spec, independent of the phone app. */
class Raw(port: Int) {
    val s = Socket("127.0.0.1", port).apply { soTimeout = 3000 }
    private val i = s.getInputStream(); private val o = s.getOutputStream()
    fun line(l: String) { o.write((l + "\n").toByteArray()); o.flush() }
    fun read(): String? {
        return try {
            val b = StringBuilder()
            var out: String? = null
            while (true) { val c = i.read(); if (c < 0) break; if (c == '\n'.code) { out = b.toString(); break }; b.append(c.toChar()) }
            out
        } catch (e: SocketTimeoutException) { "TIMEOUT" } catch (e: Exception) { null }
    }
    fun hi(cid: String): List<String> { line("HI 1 $cid"); return read()!!.split(' ') }
    fun close() = s.close()
}

class Sess(val raw: Raw, keys: P.SessionKeys) {
    val sealer = P.Sealer(keys.c2s, P.DIR_C2S); val opener = P.Opener(keys.s2c, P.DIR_S2C)
    fun send(m: String) = raw.line(sealer.seal(m))
    fun recv(): String? { val l = raw.read() ?: return null; return if (l == "TIMEOUT") "TIMEOUT" else opener.open(l) }
    fun recvUntil(prefix: String): String? { repeat(6) { val m = recv() ?: return null; if (m.startsWith(prefix)) return m }; return null }
}

class ImeServerTest {
    private val cid = P.randomHex(16)
    private lateinit var inj: FakeInjector
    private lateinit var store: MemStore
    private lateinit var srv: ImeServer
    private var clock = 1_000L
    private val port get() = srv.boundPort

    private fun server(limits: Limits = Limits(handshakeMs = 3000, idleMs = 3000)) {
        inj = FakeInjector(); store = MemStore()
        srv = ImeServer(0, "tv00000000000001", "Living room TV", store, inj, limits = limits, now = { clock }); srv.start()
    }
    @Before fun setUp() = server()
    @After fun tearDown() = srv.stop()

    private fun pair(id: String = cid, pin: String, name: String = "My phone"): ByteArray? {
        val r = Raw(port); try {
            r.hi(id)
            val e = P.Ecdh(); r.line("PAIR ${P.hex(e.pub)} ${P.enc(name)}")
            val a = r.read()!!.split(' '); if (a[0] != "PUB") return null
            val pubS = P.unhex(a[1])!!; val z = e.shared(pubS)
            r.line("PROOF ${P.hex(P.pinProof("c", pin, e.pub, pubS, name.toByteArray()))}")
            val ok = r.read()!!.split(' ')
            return if (ok[0] == "OK" && P.same(P.unhex(ok[1])!!, P.pinProof("s", pin, e.pub, pubS, name.toByteArray()))) P.token(z, e.pub, pubS) else null
        } finally { r.close() }
    }

    private fun auth(id: String = cid, token: ByteArray): Sess? {
        val r = Raw(port); val srvL = r.hi(id)
        val ns = P.unhex(srvL[3])!!; val nc = P.unhex(P.randomHex(16))!!
        r.line("AUTH ${P.hex(nc)} ${P.hex(P.authProof(token, P.unhex(id)!!, ns, nc))}")
        val ok = r.read()!!.split(' ')
        if (ok[0] != "OK") { r.close(); return null }
        assertTrue("server proves it knows the token", P.same(P.srvProof(token, nc, ns), P.unhex(ok[1])!!))
        return Sess(r, P.sessionKeys(token, ns, nc))
    }

    private fun waitCalls(n: Int) { val end = System.currentTimeMillis() + 3000; while (inj.calls.size < n && System.currentTimeMillis() < end) Thread.sleep(10) }

    // ---------- the happy path ----------
    @Test fun pairWithPinThenControl() {
        val pin = srv.openPairing()
        val token = pair(pin = pin)!!
        val s = auth(token = token)!!
        assertEquals("CAPS keys,text,vol,media", s.recv()); assertEquals("STATE input=1", s.recv())
        s.send("K 19"); s.send("K 23 D"); s.send("T hello%21"); s.send("R new")
        waitCalls(4)
        assertEquals(listOf("K 19P", "K 23D", "T hello!", "R new"), inj.calls.toList())
        s.send("PING 7"); assertEquals("PONG 7", s.recvUntil("PONG"))
    }

    // ---------- Accessibility add-on channel ----------
    @Test fun extLineReachesTheAddOnAndTheReplyComesBack() {
        inj.extReply = "MSG hello there"
        val s = auth(token = pair(pin = srv.openPairing())!!)!!
        s.recvUntil("STATE")
        s.send("X " + P.enc("M 3 -4")); waitCalls(1)
        assertEquals(listOf("X M 3 -4"), inj.calls.toList())
        assertEquals("X " + P.enc("MSG hello there"), s.recvUntil("X "))
    }
    @Test fun longRepliesAreSplitAndReassemble() {
        val big = "APPS " + (1..900).joinToString(",") { "App%20$it|com.example.app$it" }          // ~25 KB: far above one sealed line
        inj.extReply = big
        val s = auth(token = pair(pin = srv.openPairing())!!)!!
        s.recvUntil("STATE")
        s.send("X " + P.enc("LIST"))
        val sb = StringBuilder(); var parts = 0
        while (true) { val m = s.recv()!!; if (m.startsWith("XP ")) { sb.append(m.substring(3)); parts++ } else if (m.startsWith("X ")) { sb.append(m.substring(2)); break } }
        assertTrue("was split into several lines", parts >= 2)
        assertEquals(big, P.dec(sb.toString()))
    }
    @Test fun capsChangeIsPushedToTheConnectedPhone() {
        val s = auth(token = pair(pin = srv.openPairing())!!)!!
        s.recvUntil("STATE")
        inj.capsStr = "keys,text,vol,media,a11y"; srv.capsChanged()
        assertEquals("CAPS keys,text,vol,media,a11y", s.recvUntil("CAPS"))
    }
    @Test fun extWithoutAuthenticationIsDropped() {
        val r = Raw(port); r.hi(cid); r.line("X M+1+1"); assertNull(r.read()); assertTrue(inj.calls.isEmpty())
    }

    // ---------- LAN authentication is enforced ----------
    @Test fun nothingWorksBeforeAuthentication() {
        val r = Raw(port); r.hi(cid); r.line("K 19"); r.line("T pwned")
        assertNull("connection is dropped", r.read()); assertTrue(inj.calls.isEmpty())
    }
    @Test fun strangerIsToldUnpaired() {
        val r = Raw(port); val l = r.hi(cid); assertEquals("0", l[4])
        r.line("AUTH ${P.randomHex(16)} ${P.randomHex(32)}"); assertEquals("NO UNPAIRED", r.read())
    }
    @Test fun wrongTokenIsRejected() {
        val pin = srv.openPairing(); pair(pin = pin)!!
        assertNull(auth(token = ByteArray(32) { 1 })); assertTrue(inj.calls.isEmpty())
    }
    @Test fun pairingNeedsAnOpenWindow() { assertNull(pair(pin = "123456")) }
    @Test fun pinIsSingleUse() {
        val pin = srv.openPairing(); assertNotNull(pair(pin = pin))
        assertNull("same PIN again", pair(id = P.randomHex(16), pin = pin))
    }
    @Test fun fiveWrongPinsCloseTheWindow() {
        val pin = srv.openPairing(); val wrong = if (pin == "000000") "000001" else "000000"
        val r = Raw(port); r.hi(cid); val e = P.Ecdh(); r.line("PAIR ${P.hex(e.pub)} x")
        val pubS = P.unhex(r.read()!!.split(' ')[1])!!
        val replies = (1..5).map { r.line("PROOF ${P.hex(P.pinProof("c", wrong, e.pub, pubS, "x".toByteArray()))}"); r.read() }
        assertEquals(listOf("NO PIN 4", "NO PIN 3", "NO PIN 2", "NO PIN 1", "NO PIN 0"), replies)
        val lockedOut = Raw(port); lockedOut.line("HI 1 $cid"); assertNull("5 failures also lock this address out", lockedOut.read())
        clock += 61_000                                    // lockout over; the window must STILL be closed
        assertNull("window is closed, even for the right PIN", pair(id = P.randomHex(16), pin = pin))
        assertNull(srv.pairingPin())
    }
    @Test fun pairingWindowExpires() {
        val pin = srv.openPairing(); assertTrue(srv.pairingSecondsLeft() > 100)
        clock += 121_000
        assertNull(pair(pin = pin)); assertEquals(0, srv.pairingSecondsLeft())
    }
    @Test fun repeatedFailuresLockTheAddressOut() {
        repeat(5) { val r = Raw(port); r.hi(cid); r.line("AUTH ${P.randomHex(16)} ${P.randomHex(32)}"); r.read(); r.close() }
        val r = Raw(port); r.line("HI 1 $cid"); assertNull("locked out: no answer at all", r.read())
        clock += 61_000; val r2 = Raw(port); assertEquals("SRV", r2.hi(cid)[0])
    }
    @Test fun garbageAndOversizedLinesAreDropped() {
        val a = Raw(port); a.line("GET / HTTP/1.1"); assertNull(a.read())
        val b = Raw(port); b.line("HI 1 " + "z".repeat(10000)); assertNull(b.read())
    }
    @Test fun tamperedFrameDropsTheConnection() {
        val pin = srv.openPairing(); val token = pair(pin = pin)!!
        val s = auth(token = token)!!; s.recv(); s.recv()
        val good = s.sealer.seal("K 19"); val bad = good.dropLast(2) + (if (good.endsWith("00")) "01" else "00")
        s.raw.line(bad); assertNull(s.recv()); Thread.sleep(100); assertTrue(inj.calls.isEmpty())
    }
    @Test fun replayedFrameDropsTheConnection() {
        val pin = srv.openPairing(); val token = pair(pin = pin)!!
        val s = auth(token = token)!!; s.recv(); s.recv()
        val f = s.sealer.seal("K 19"); s.raw.line(f); waitCalls(1)
        s.raw.line(f)                                  // attacker replays a captured frame
        assertNull(s.recv()); Thread.sleep(100); assertEquals(1, inj.calls.size)
    }
    @Test fun frameFromAnotherSessionIsUseless() {
        val pin = srv.openPairing(); val token = pair(pin = pin)!!
        val s1 = auth(token = token)!!; s1.recv(); s1.recv(); val captured = s1.sealer.seal("K 20"); s1.raw.close()
        val s2 = auth(token = token)!!; s2.recv(); s2.recv()
        s2.raw.line(captured); assertNull(s2.recv()); assertTrue(inj.calls.isEmpty())
    }

    // ---------- sessions ----------
    @Test fun secondPhoneIsToldBusyButSamePhoneCanReconnect() {
        val pin = srv.openPairing(); val t1 = pair(pin = pin)!!
        val other = P.randomHex(16); val pin2 = srv.openPairing(); val t2 = pair(id = other, pin = pin2)!!
        val a = auth(token = t1)!!
        assertNull("other phone while the first is active", auth(id = other, token = t2))
        val a2 = auth(token = t1)                                   // same phone, new connection (Wi-Fi blip)
        assertNotNull(a2); assertNull("old connection was replaced", a.recvUntil("never"))
    }
    @Test fun forgetAllRevokesEveryPhone() {
        val pin = srv.openPairing(); val token = pair(pin = pin)!!
        assertNotNull(auth(token = token)); srv.forgetAll()
        assertNull(auth(token = token)); assertEquals(0, srv.pairedCount())
    }
    @Test fun idleConnectionIsClosed() {
        srv.stop(); server(Limits(handshakeMs = 3000, idleMs = 400))
        val pin = srv.openPairing(); val token = pair(pin = pin)!!
        val s = auth(token = token)!!; s.recv(); s.recv()
        assertNull("no heartbeat -> server hangs up", s.recv())
    }
    @Test fun inputStateIsPushedToThePhone() {
        val pin = srv.openPairing(); val token = pair(pin = pin)!!
        val s = auth(token = token)!!; s.recv(); s.recv()
        inj.input = false; srv.inputChanged(); assertEquals("STATE input=0", s.recv())
    }

    // ---------- honest error reporting for IME limits ----------
    @Test fun keysWithoutAFocusedFieldAreReported() {
        val pin = srv.openPairing(); val token = pair(pin = pin)!!
        val s = auth(token = token)!!; s.recv(); s.recv(); inj.input = false
        s.send("K 19"); assertEquals("ERR noinput 19", s.recvUntil("ERR"))
        s.send("T abc"); assertEquals("ERR noinput text", s.recvUntil("ERR"))
    }
    @Test fun powerIsReportedUnsupported() {
        val pin = srv.openPairing(); val token = pair(pin = pin)!!
        val s = auth(token = token)!!; s.recv(); s.recv()
        s.send("K 26"); assertEquals("ERR unsupported 26", s.recvUntil("ERR"))
        s.send("K 99999"); assertEquals("ERR badcmd", s.recvUntil("ERR"))
        s.send("BOGUS"); assertEquals("ERR badcmd", s.recvUntil("ERR"))
    }
}
