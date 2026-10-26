package app.lightset.mesh

import app.lightset.ble.hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

fun h(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

/** Sample data from the Bluetooth Mesh Profile specification, §8. */
class MeshCryptoTest {
    @Test
    fun s1() = assertEquals("b73cefbd641ef2ea598c2b6efb62f79c", MeshCrypto.s1("test").hex())

    @Test
    fun k2() {
        val k = MeshCrypto.k2(h("f7a2a44f8e8a8029064f173ddc1e2b00"))
        assertEquals(0x7f, k.nid)
        assertEquals("9f589181a0f50de73c8070c7a6d27f46", k.encryptionKey.hex())
        assertEquals("4c715bd4a64b938f99b453351653124f", k.privacyKey.hex())
    }

    @Test
    fun k4() {
        assertEquals(0x38, MeshCrypto.k4(h("3216d1509884b533248541792b877f98")))
        assertEquals(0x26, MeshCrypto.k4(h("63964771734fbd76e3b40519d1d94a48")))
    }

    @Test
    fun decodesSampleMessage1() {
        val net = MeshNetwork(h("7dd7364cd842ad18c17c2b820c84c3d6"), 0x12345678)
        val p = net.decode(h("68eca487516765b5e5bfdacbaf6cb7fb6bff871f035444ce83a670df"))
        assertNotNull(p)
        p!!
        assertEquals(true, p.ctl)
        assertEquals(0, p.ttl)
        assertEquals(1, p.seq)
        assertEquals(0x1201, p.src)
        assertEquals(0xfffd, p.dst)
        assertEquals("034b50057e400000010000", p.transport.hex())
    }

    @Test
    fun encodeRoundTrip() {
        val net = MeshNetwork(h("7dd7364cd842ad18c17c2b820c84c3d6"), 0x12345678)
        val pdu = net.encode(true, 0, 1, 0x1201, 0xfffd, h("034b50057e400000010000"))
        assertArrayEquals(h("68eca487516765b5e5bfdacbaf6cb7fb6bff871f035444ce83a670df"), pdu)
    }
}
