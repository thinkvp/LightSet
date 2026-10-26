package app.lightset.mesh

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Bluetooth Mesh security toolbox (Mesh Profile spec §3.8): AES-CMAC, k1–k4, s1, AES-CCM. */
object MeshCrypto {
    private fun aes(key: ByteArray, block: ByteArray): ByteArray =
        Cipher.getInstance("AES/ECB/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            doFinal(block)
        }

    /** AES-CMAC (RFC 4493). */
    fun cmac(key: ByteArray, msg: ByteArray): ByteArray {
        fun shift(b: ByteArray): ByteArray {
            val out = ByteArray(16)
            for (i in 0 until 16) {
                out[i] = ((b[i].toInt() shl 1) or (if (i < 15) (b[i + 1].toInt() and 0xFF) ushr 7 else 0)).toByte()
            }
            if (b[0].toInt() and 0x80 != 0) out[15] = (out[15].toInt() xor 0x87).toByte()
            return out
        }
        val l = aes(key, ByteArray(16))
        val k1 = shift(l)
        val k2 = shift(k1)
        val n = maxOf(1, (msg.size + 15) / 16)
        val complete = msg.isNotEmpty() && msg.size % 16 == 0
        val last = ByteArray(16)
        if (complete) {
            for (i in 0 until 16) last[i] = (msg[(n - 1) * 16 + i].toInt() xor k1[i].toInt()).toByte()
        } else {
            val rem = msg.size - (n - 1) * 16
            for (i in 0 until 16) {
                val v = when {
                    i < rem -> msg[(n - 1) * 16 + i].toInt()
                    i == rem -> 0x80
                    else -> 0
                }
                last[i] = (v xor k2[i].toInt()).toByte()
            }
        }
        var x = ByteArray(16)
        for (b in 0 until n - 1) {
            x = aes(key, ByteArray(16) { (x[it].toInt() xor msg[b * 16 + it].toInt()).toByte() })
        }
        return aes(key, ByteArray(16) { (x[it].toInt() xor last[it].toInt()).toByte() })
    }

    fun s1(m: ByteArray): ByteArray = cmac(ByteArray(16), m)
    fun s1(m: String): ByteArray = s1(m.toByteArray(Charsets.US_ASCII))

    fun k1(n: ByteArray, salt: ByteArray, p: ByteArray): ByteArray = cmac(cmac(salt, n), p)

    data class NetworkKeys(val nid: Int, val encryptionKey: ByteArray, val privacyKey: ByteArray)

    /** k2 with P = 0x00: the master-security network keys. */
    fun k2(netKey: ByteArray, p: ByteArray = byteArrayOf(0)): NetworkKeys {
        val t = cmac(s1("smk2"), netKey)
        val t1 = cmac(t, p + 0x01)
        val t2 = cmac(t, t1 + p + 0x02)
        val t3 = cmac(t, t2 + p + 0x03)
        return NetworkKeys(t1[15].toInt() and 0x7F, t2, t3)
    }

    /** Application key identifier. */
    fun k4(appKey: ByteArray): Int {
        val t = cmac(s1("smk4"), appKey)
        return cmac(t, "id6".toByteArray(Charsets.US_ASCII) + 0x01)[15].toInt() and 0x3F
    }

    /** AES-CCM with a 13-byte nonce (L = 2) and no associated data. Returns ciphertext ‖ MIC. */
    fun ccmEncrypt(key: ByteArray, nonce: ByteArray, plain: ByteArray, micSize: Int): ByteArray {
        val tag = cbcMac(key, nonce, plain, micSize)
        val out = ctr(key, nonce, plain)
        val s0 = aes(key, counterBlock(nonce, 0))
        return out + ByteArray(micSize) { (tag[it].toInt() xor s0[it].toInt()).toByte() }
    }

    /** Returns the plaintext, or null if the MIC doesn't verify. */
    fun ccmDecrypt(key: ByteArray, nonce: ByteArray, cipherAndMic: ByteArray, micSize: Int): ByteArray? {
        if (cipherAndMic.size < micSize) return null
        val c = cipherAndMic.copyOfRange(0, cipherAndMic.size - micSize)
        val mic = cipherAndMic.copyOfRange(cipherAndMic.size - micSize, cipherAndMic.size)
        val plain = ctr(key, nonce, c)
        val tag = cbcMac(key, nonce, plain, micSize)
        val s0 = aes(key, counterBlock(nonce, 0))
        val expected = ByteArray(micSize) { (tag[it].toInt() xor s0[it].toInt()).toByte() }
        return if (expected.contentEquals(mic)) plain else null
    }

    private fun counterBlock(nonce: ByteArray, i: Int) =
        byteArrayOf(0x01) + nonce + byteArrayOf((i shr 8).toByte(), i.toByte())

    private fun ctr(key: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        var i = 0
        while (i < data.size) {
            val s = aes(key, counterBlock(nonce, i / 16 + 1))
            for (j in 0 until minOf(16, data.size - i)) out[i + j] = (data[i + j].toInt() xor s[j].toInt()).toByte()
            i += 16
        }
        return out
    }

    private fun cbcMac(key: ByteArray, nonce: ByteArray, plain: ByteArray, micSize: Int): ByteArray {
        val flags = 8 * ((micSize - 2) / 2) + 1
        var x = aes(key, byteArrayOf(flags.toByte()) + nonce + byteArrayOf((plain.size shr 8).toByte(), plain.size.toByte()))
        var i = 0
        while (i < plain.size) {
            val block = ByteArray(16)
            plain.copyInto(block, 0, i, minOf(i + 16, plain.size))
            x = aes(key, ByteArray(16) { (x[it].toInt() xor block[it].toInt()).toByte() })
            i += 16
        }
        return x
    }

    fun obfuscationPad(privacyKey: ByteArray, ivIndex: Int, privacyRandom: ByteArray): ByteArray =
        aes(privacyKey, ByteArray(5) + be32(ivIndex) + privacyRandom.copyOf(7))

    fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
}

/** A decoded network PDU. [transport] excludes the NetMIC. */
data class NetworkPdu(
    val ivi: Int, val nid: Int, val ctl: Boolean, val ttl: Int,
    val seq: Int, val src: Int, val dst: Int, val transport: ByteArray,
)

/** Network-layer decode/encode for one subnet. */
class MeshNetwork(netKey: ByteArray, val ivIndex: Int) {
    val keys = MeshCrypto.k2(netKey)

    /** Network nonce, or the proxy nonce used for proxy configuration messages. */
    private fun nonce(hdr: ByteArray, proxy: Boolean): ByteArray =
        if (proxy) byteArrayOf(0x03, 0x00) + hdr.copyOfRange(1, 6) + byteArrayOf(0, 0) + MeshCrypto.be32(ivIndex)
        else byteArrayOf(0x00) + hdr + byteArrayOf(0, 0) + MeshCrypto.be32(ivIndex)

    fun decode(pdu: ByteArray, proxy: Boolean = false): NetworkPdu? {
        if (pdu.size < 14 || (pdu[0].toInt() and 0x7F) != keys.nid) return null
        val pad = MeshCrypto.obfuscationPad(keys.privacyKey, ivIndex, pdu.copyOfRange(7, 14))
        val hdr = ByteArray(6) { (pdu[1 + it].toInt() xor pad[it].toInt()).toByte() }
        val ctl = hdr[0].toInt() and 0x80 != 0
        val micSize = if (ctl) 8 else 4
        val nonce = nonce(hdr, proxy)
        val plain = MeshCrypto.ccmDecrypt(keys.encryptionKey, nonce, pdu.copyOfRange(7, pdu.size), micSize) ?: return null
        return NetworkPdu(
            ivi = (pdu[0].toInt() ushr 7) and 1,
            nid = pdu[0].toInt() and 0x7F,
            ctl = ctl,
            ttl = hdr[0].toInt() and 0x7F,
            seq = ((hdr[1].toInt() and 0xFF) shl 16) or ((hdr[2].toInt() and 0xFF) shl 8) or (hdr[3].toInt() and 0xFF),
            src = ((hdr[4].toInt() and 0xFF) shl 8) or (hdr[5].toInt() and 0xFF),
            dst = ((plain[0].toInt() and 0xFF) shl 8) or (plain[1].toInt() and 0xFF),
            transport = plain.copyOfRange(2, plain.size),
        )
    }

    fun encode(
        ctl: Boolean, ttl: Int, seq: Int, src: Int, dst: Int, transport: ByteArray, proxy: Boolean = false,
    ): ByteArray {
        val hdr = byteArrayOf(
            ((if (ctl) 0x80 else 0) or ttl).toByte(),
            (seq ushr 16).toByte(), (seq ushr 8).toByte(), seq.toByte(),
            (src ushr 8).toByte(), src.toByte(),
        )
        val nonce = nonce(hdr, proxy)
        val enc = MeshCrypto.ccmEncrypt(
            keys.encryptionKey, nonce,
            byteArrayOf((dst ushr 8).toByte(), dst.toByte()) + transport,
            if (ctl) 8 else 4,
        )
        val pad = MeshCrypto.obfuscationPad(keys.privacyKey, ivIndex, enc.copyOfRange(0, 7))
        val obf = ByteArray(6) { (hdr[it].toInt() xor pad[it].toInt()).toByte() }
        return byteArrayOf((((ivIndex and 1) shl 7) or keys.nid).toByte()) + obf + enc
    }

    /** Application/device nonce for upper transport. */
    fun accessNonce(device: Boolean, szmic: Boolean, seq: Int, src: Int, dst: Int): ByteArray = byteArrayOf(
        if (device) 0x02 else 0x01,
        if (szmic) 0x80.toByte() else 0,
        (seq ushr 16).toByte(), (seq ushr 8).toByte(), seq.toByte(),
        (src ushr 8).toByte(), src.toByte(),
        (dst ushr 8).toByte(), dst.toByte(),
    ) + MeshCrypto.be32(ivIndex)
}
