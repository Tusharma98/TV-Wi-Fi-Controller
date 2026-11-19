package app.tvremote.ime

import app.tvremote.imeproto.ImeProto as P
import org.junit.Assert.*
import org.junit.Test

/** Identical test lives in the phone app: both copies of ImeProto.kt must produce exactly these bytes. */
class ImeProtoTest {
    private val key = ByteArray(32) { it.toByte() }
    private val cid = ByteArray(16) { 0x11 }; private val ns = ByteArray(16) { 0x22 }; private val nc = ByteArray(16) { 0x33 }

    @Test fun goldenVectors() {
        assertEquals("f7470cb682e83c6a45c216812bcf96f5668b72f9556e1e7f626cc4c3819cf1aa", P.hex(P.mac(key, "auth", byteArrayOf(1, 2, 3), byteArrayOf(4, 5))))
        assertEquals("c241fc98a69cb81e3d0829860642f2200dac8158eb8545d30da0b8b54f395bc8", P.hex(P.authProof(key, cid, ns, nc)))
        assertEquals("675a508742c2efd29ae183b61b03c2defdf1bf29ddfbd292dab7b5dab42e8cbe", P.hex(P.srvProof(key, nc, ns)))
        val k = P.sessionKeys(key, ns, nc)
        assertEquals("303b6226da86e6e8f1cc72bd72f2eb81bd58dd090427da83643fb599c8c9efac", P.hex(k.c2s))
        assertEquals("684ddf35671ec864e13f03382199a6b5b86fbca7fd90f0dd7ec177d7ee689181", P.hex(k.s2c))
        assertEquals("00000000000000005e57737a99ab2e917c4ee6d306cdbbea557487de", P.Sealer(k.c2s, P.DIR_C2S).seal("K 19"))
        assertEquals("7249912c2fd65f159e3b481ca2be80550a2f17958e45f916a1166a142e31f1d3", P.hex(P.pinProof("c", "123456", byteArrayOf(9), byteArrayOf(8), "phone".toByteArray())))
    }
    @Test fun partBoundariesAreUnambiguous() {
        assertFalse(P.same(P.mac(key, "x", byteArrayOf(1, 2), byteArrayOf(3)), P.mac(key, "x", byteArrayOf(1), byteArrayOf(2, 3))))
    }
    @Test fun sealOpenRoundTripAndDirectionBinding() {
        val k = P.sessionKeys(key, ns, nc)
        val f = P.Sealer(k.c2s, P.DIR_C2S).seal("T héllo")
        assertEquals("T héllo", P.Opener(k.c2s, P.DIR_C2S).open(f))
        assertNull("a client->server frame cannot be bounced back as server->client", P.Opener(k.c2s, P.DIR_S2C).open(f))
        assertNull(P.Opener(k.s2c, P.DIR_C2S).open(f))
    }
    @Test fun openerRejectsReplayAndReorder() {
        val k = P.sessionKeys(key, ns, nc); val s = P.Sealer(k.c2s, 1); val o = P.Opener(k.c2s, 1)
        val a = s.seal("a"); val b = s.seal("b")
        assertEquals("b", o.open(b)); assertNull(o.open(a)); assertNull(o.open(b))
    }
    @Test fun ecdhAgrees() {
        val a = P.Ecdh(); val b = P.Ecdh()
        assertArrayEquals(a.shared(b.pub), b.shared(a.pub))
    }
    @Test fun hexRejectsGarbage() { assertNull(P.unhex("zz")); assertNull(P.unhex("abc")); assertTrue(P.isHex(P.randomHex(16), 16)) }
}
