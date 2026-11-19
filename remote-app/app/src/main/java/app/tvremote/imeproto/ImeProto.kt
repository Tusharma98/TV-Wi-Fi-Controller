package app.tvremote.imeproto

import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Wire protocol v1 of the TV keyboard (IME) link. THIS FILE IS IDENTICAL IN THE TV APK AND THE PHONE APP - change both or neither
 * (the golden-vector test in each project fails if they drift).
 *
 *  Plain handshake lines (TCP, '\n' terminated, max 8 KiB):
 *    C: HI 1 <clientId 32hex>
 *    S: SRV 1 <tvId 16hex> <nonceS 32hex> <paired 0|1> <tvName urlenc>
 *   paired phone:
 *    C: AUTH <nonceC 32hex> <authProof hex>        S: OK <srvProof hex>   |   NO UNPAIRED|BAD|BUSY
 *   new phone (TV is showing a one-time PIN):
 *    C: PAIR <pubC hex> <name urlenc>              S: PUB <pubS hex>      |   NO CLOSED
 *    C: PROOF <hex>                                S: OK <proof hex>      |   NO PIN <attemptsLeft>
 *  After OK every line in both directions is sealed: hex( counter(8) || AES-256-GCM ) - confidential, authenticated, replay-proof.
 *   C->S  K <code> [P|D|U]   key (press / down / up)        T <urlenc> insert text      R <urlenc> replace field text
 *         PING <n>           heartbeat                      G  ask for CAPS + STATE
 *   S->C  CAPS <csv>         what this TV can inject         STATE input=<0|1>  an editor has focus
 *         ERR <noinput|unsupported|badcmd> [detail]          PONG <n>
 *  Optional Accessibility extension (older peers answer ERR badcmd / ignore it; the keyboard link never depends on it):
 *   C->S  X <urlenc line>    mouse / app list / file ticket / Wi-Fi settings ... (opaque line handled by the TV's Accessibility add-on)
 *   S->C  X <urlenc line>    reply; long replies are split:  XP <part> ... XP <part> X <last part>  (concatenate, then urldecode)
 *         CAPS also carries "a11y" while the TV's Accessibility service is on.
 */
object ImeProto {
    const val VERSION = 1
    const val PORT = 8770
    const val MAX_LINE = 8192
    const val DIR_C2S = 1
    const val DIR_S2C = 2
    private val rnd = SecureRandom()

    fun hex(b: ByteArray): String { val sb = StringBuilder(b.size * 2); for (x in b) sb.append("0123456789abcdef"[(x.toInt() shr 4) and 15]).append("0123456789abcdef"[x.toInt() and 15]); return sb.toString() }
    fun unhex(s: String): ByteArray? {
        if (s.length % 2 != 0) return null
        val o = ByteArray(s.length / 2)
        for (i in o.indices) { val h = Character.digit(s[i * 2], 16); val l = Character.digit(s[i * 2 + 1], 16); if (h < 0 || l < 0) return null; o[i] = ((h shl 4) or l).toByte() }
        return o
    }
    fun randomHex(bytes: Int): String = ByteArray(bytes).also { rnd.nextBytes(it) }.let { hex(it) }
    fun randomPin(): String = String.format("%06d", rnd.nextInt(1_000_000))
    fun isHex(s: String, bytes: Int) = s.length == bytes * 2 && unhex(s) != null

    /** HMAC-SHA256 over a label and length-prefixed parts (no ambiguity between part boundaries). */
    fun mac(key: ByteArray, label: String, vararg parts: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256"); m.init(SecretKeySpec(key, "HmacSHA256"))
        m.update(label.toByteArray(Charsets.UTF_8)); m.update(0)
        for (p in parts) { m.update(ByteBuffer.allocate(4).putInt(p.size).array()); m.update(p) }
        return m.doFinal()
    }
    fun same(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)

    /** Ephemeral P-256 key agreement: the token is derived on both sides and never travels over the network. */
    class Ecdh {
        private val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val pub: ByteArray get() = kp.public.encoded
        fun shared(peerPub: ByteArray): ByteArray {
            val pk = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerPub))
            return KeyAgreement.getInstance("ECDH").apply { init(kp.private); doPhase(pk, true) }.generateSecret()
        }
    }

    fun token(z: ByteArray, pubC: ByteArray, pubS: ByteArray) = mac(z, "token", pubC, pubS)
    fun pinProof(role: String, pin: String, pubC: ByteArray, pubS: ByteArray, name: ByteArray) = mac(pin.toByteArray(Charsets.UTF_8), "pin-$role", pubC, pubS, name)
    fun authProof(token: ByteArray, clientId: ByteArray, nonceS: ByteArray, nonceC: ByteArray) = mac(token, "auth", clientId, nonceS, nonceC)
    fun srvProof(token: ByteArray, nonceC: ByteArray, nonceS: ByteArray) = mac(token, "srv", nonceC, nonceS)

    class SessionKeys(val c2s: ByteArray, val s2c: ByteArray)
    fun sessionKeys(token: ByteArray, nonceS: ByteArray, nonceC: ByteArray) = SessionKeys(mac(token, "c2s", nonceS, nonceC), mac(token, "s2c", nonceS, nonceC))

    private fun iv(dir: Int, n: Long): ByteArray = ByteBuffer.allocate(12).put(dir.toByte()).put(0).put(0).put(0).putLong(n).array()

    /** One per direction. seal+write must happen under one lock so counters reach the wire in order. */
    class Sealer(key: ByteArray, private val dir: Int) {
        private var ctr = 0L
        private val k = SecretKeySpec(key, "AES")                              // built once: key setup per message was a big part of the lag on slow TVs
        private val c: Cipher = Cipher.getInstance("AES/GCM/NoPadding")
        private val aad = byteArrayOf(dir.toByte())
        @Synchronized fun seal(plain: String): String {
            val n = ctr++
            c.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, iv(dir, n)))   // new IV every time: allowed on a reused Cipher
            c.updateAAD(aad)
            return hex(ByteBuffer.allocate(8).putLong(n).array() + c.doFinal(plain.toByteArray(Charsets.UTF_8)))
        }
    }

    class Opener(key: ByteArray, private val dir: Int) {
        private var last = -1L
        private val k = SecretKeySpec(key, "AES")
        private val c: Cipher = Cipher.getInstance("AES/GCM/NoPadding")
        private val aad = byteArrayOf(dir.toByte())
        /** null = forged, corrupted or replayed: the caller must drop the connection. */
        @Synchronized fun open(line: String): String? {
            val raw = unhex(line) ?: return null
            if (raw.size < 8 + 16) return null
            val n = ByteBuffer.wrap(raw, 0, 8).long
            if (n <= last) return null
            return try {
                c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, iv(dir, n)))
                c.updateAAD(aad)
                val p = String(c.doFinal(raw, 8, raw.size - 8), Charsets.UTF_8)
                last = n; p
            } catch (e: Exception) { null }
        }
    }

    fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
    fun dec(s: String): String = try { java.net.URLDecoder.decode(s, "UTF-8") } catch (e: Exception) { "" }
}
