package app.tvremote.remote

import app.tvremote.imeproto.ImeProto as P
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MemImeStore : ImeStore {
    private val cid = P.randomHex(16); private val m = LinkedHashMap<String, ImeTv>(); private var last: String? = null
    @Synchronized override fun clientId() = cid
    @Synchronized override fun tvs() = m.values.toList()
    @Synchronized override fun save(t: ImeTv) { m[t.tvId] = t }
    @Synchronized override fun remove(tvId: String) { m.remove(tvId) }
    @Synchronized override fun last() = last
    @Synchronized override fun setLast(tvId: String) { last = tvId }
}

fun until(ms: Long = 8000, what: String, c: () -> Boolean) {
    val end = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < end) { if (c()) return; Thread.sleep(15) }
    throw AssertionError("timed out: $what")
}

class ImeClientTest {
    private lateinit var tv: FakeImeTv
    private lateinit var store: MemImeStore
    private lateinit var client: ImeClient
    private val hints = CopyOnWriteArrayList<String>()
    private var scan: List<Found> = emptyList()

    private fun timing(port: Int) = ImeClient.Timing(port = port, connectMs = 600, pingMs = 100, deadMs = 700, backoffMs = listOf(80, 80, 80, 80, 80), unpairedRetryMs = 150)
    private fun newClient(port: Int = tv.port) = ImeClient(store, object : ImeFinder { override fun scan() = scan }, "Test phone", hint = { hints.add(it) }, t = timing(port))

    @Before fun setUp() { tv = FakeImeTv("tv0000000000aaaa", "Living room"); tv.start(); store = MemImeStore(); client = newClient() }
    @After fun tearDown() { client.stop(); tv.stop() }

    private fun pairIt(host: String = "127.0.0.1", c: ImeClient = client, t: FakeImeTv = tv) {
        t.pin = "424242"
        val l = CountDownLatch(1); var err: String? = "x"
        c.beginPairing(host) { err = it; l.countDown() }; assertTrue(l.await(5, TimeUnit.SECONDS)); assertNull(err)
        val r = CountDownLatch(1); var res: ImeClient.PairResult? = null
        c.finishPairing("424242") { res = it; r.countDown() }; assertTrue(r.await(5, TimeUnit.SECONDS))
        assertTrue(res is ImeClient.PairResult.Paired)
    }

    @Test fun pairThenControlTheTv() {
        pairIt(); client.start()
        until(what = "READY") { client.state == ImeClient.State.READY }
        until(what = "caps") { "vol" in client.caps && client.input }
        assertEquals("Living room", client.tvName)
        assertTrue(client.send("K 19")); assertTrue(client.send("T hi%21"))
        assertEquals("K 19", tv.received.poll(3, TimeUnit.SECONDS)); assertEquals("T hi%21", tv.received.poll(3, TimeUnit.SECONDS))
    }

    @Test fun extRepliesFromTheTvAreReassembledAndExtLinesAreSent() {
        val got = CopyOnWriteArrayList<String>()
        client = ImeClient(store, object : ImeFinder { override fun scan() = scan }, "Test phone", hint = { }, t = timing(tv.port), onExt = { got.add(it) })
        pairIt(); client.start()
        until(what = "READY") { client.state == ImeClient.State.READY }
        val big = "APPS " + "x".repeat(7000); val e = P.enc(big)
        tv.push("XP " + e.substring(0, 3000)); tv.push("XP " + e.substring(3000, 6000)); tv.push("X " + e.substring(6000))
        tv.push("X " + P.enc("MSG hi there"))
        until(what = "two ext replies") { got.size == 2 }
        assertEquals(big, got[0]); assertEquals("MSG hi there", got[1])
        assertTrue(client.send("X " + P.enc("M 1 2")))
        assertEquals("X M+1+2", tv.received.poll(3, TimeUnit.SECONDS))
    }

    @Test fun nothingIsSentWhileDisconnected() { assertFalse(client.send("K 19")) }

    @Test fun wrongPinCanBeRetypedOnTheSameSession() {
        tv.pin = "111111"
        val l = CountDownLatch(1); client.beginPairing("127.0.0.1") { l.countDown() }; l.await(5, TimeUnit.SECONDS)
        val a = CountDownLatch(1); var r1: ImeClient.PairResult? = null; client.finishPairing("000000") { r1 = it; a.countDown() }; a.await(5, TimeUnit.SECONDS)
        assertTrue(r1 is ImeClient.PairResult.WrongPin)
        val b = CountDownLatch(1); var r2: ImeClient.PairResult? = null; client.finishPairing("111111") { r2 = it; b.countDown() }; b.await(5, TimeUnit.SECONDS)
        assertTrue(r2 is ImeClient.PairResult.Paired)
    }

    @Test fun pairingWithoutThePairingWindowOpenExplainsWhatToDo() {
        val l = CountDownLatch(1); var err: String? = null
        client.beginPairing("127.0.0.1") { err = it; l.countDown() }; l.await(5, TimeUnit.SECONDS)
        assertTrue(err!!.contains("Pair a new phone"))
    }

    @Test fun tvOfflineThenRecoversAfterRebootWithoutRepairing() {
        pairIt(); client.start(); until(what = "READY") { client.state == ImeClient.State.READY }
        tv.stop()
        until(what = "OFFLINE") { client.state == ImeClient.State.OFFLINE || client.state == ImeClient.State.CONNECTING }
        assertFalse("send is refused while the TV is off", client.send("K 19"))
        tv.start()
        until(what = "READY again") { client.state == ImeClient.State.READY }
        assertEquals("no second pairing was needed", 1, tv.pairings.get())
        assertTrue(client.send("K 20")); assertEquals("K 20", tv.received.poll(3, TimeUnit.SECONDS))
    }

    @Test fun tvThatForgotThePhoneAsksForRepairing() {
        pairIt(); client.start(); until(what = "READY") { client.state == ImeClient.State.READY }
        tv.forget(); tv.reboot()
        until(what = "NEEDS_PAIR") { client.state == ImeClient.State.NEEDS_PAIR }
        assertTrue(hints.any { it.contains("Pair again") })
        pairIt()                                                     // pair again: no restart of the client needed
        until(what = "READY after re-pair") { client.state == ImeClient.State.READY }
    }

    @Test fun noPairedTvMeansNothingRuns() { client.start(); until(what = "IDLE") { client.state == ImeClient.State.IDLE && !client.active } }

    @Test fun findsThePairedTvAgainAfterItsAddressChanged() {
        pairIt()
        store.save(ImeTv("tv0000000000aaaa", "Living room", "203.0.113.1", store.tvs()[0].token))      // unreachable old address
        scan = listOf(Found("127.0.0.1", "tv0000000000aaaa", "Living room", true))
        client.start(); until(what = "READY via scan") { client.state == ImeClient.State.READY }
        assertEquals("127.0.0.1", store.tvs()[0].host)
    }

    @Test fun anotherTvAtTheSameAddressIsNotTrusted() {
        pairIt()
        client.stop(); tv.stop()
        val other = FakeImeTv("tvOTHER000000bbbb", "Neighbour TV", port = tv.port); other.start()
        try { client.start(); Thread.sleep(700); assertNotEquals(ImeClient.State.READY, client.state); assertEquals(0, other.sessions.get()) } finally { client.stop(); other.stop() }
    }

    @Test fun twoPairedTvsAndSwitchingBetweenThem() {
        val b = FakeImeTv("tv0000000000bbbb", "Bedroom", bind = "127.0.0.2", port = tv.port); b.start()
        try {
            val c = newClient(); pairIt("127.0.0.1", c, tv); pairIt("127.0.0.2", c, b)
            assertEquals(2, store.tvs().size); assertEquals("tv0000000000bbbb", store.last())
            c.start(); until(what = "READY on last-used TV") { c.state == ImeClient.State.READY }
            assertEquals("Bedroom", c.tvName); assertEquals(1, b.sessions.get()); assertEquals(0, tv.sessions.get())
            c.stop(); store.setLast("tv0000000000aaaa"); c.start()
            until(what = "READY on the other TV") { c.state == ImeClient.State.READY && tv.sessions.get() == 1 }
            c.stop()
        } finally { b.stop() }
    }

    @Test fun deadLinkIsDetectedByTheHeartbeat() {
        pairIt(); client.start(); until(what = "READY") { client.state == ImeClient.State.READY }
        tv.muted = true
        until(3000, "link declared dead") { client.state != ImeClient.State.READY }
        tv.muted = false
        until(what = "READY again") { client.state == ImeClient.State.READY }
    }

    @Test fun impostorTvThatCannotProveThePairingIsRejected() {
        pairIt(); tv.impostor = true; tv.reboot()
        client.start(); Thread.sleep(800)
        assertNotEquals(ImeClient.State.READY, client.state); assertTrue(hints.any { it.contains("not trusted") })
    }

    @Test fun noFocusedFieldIsSurfacedToTheUser() {
        pairIt(); client.start(); until(what = "READY") { client.state == ImeClient.State.READY }
        tv.push("ERR noinput 19")
        until(what = "hint") { hints.any { it.contains("text field") } }; assertFalse(client.input)
        tv.push("STATE input=1"); until(what = "input back") { client.input }
    }
}
