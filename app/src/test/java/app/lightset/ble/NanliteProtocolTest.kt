package app.lightset.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NanliteProtocolTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun matchesPublishedExample() {
        // "Set brightness to 50%" from the reverse-engineering notes.
        val packet = NanliteProtocol.writeRequest(3, 1, 1, hex("00320004"))
        assertEquals("fa55001101032001010032000401bd0d0a", packet.hex())
    }

    @Test
    fun readRequestIsWellFormed() {
        val p = NanliteProtocol.readRequest(7, 1, 3)
        val payloads = NanliteProtocol.Deframer().feed(p)
        assertEquals(1, payloads.size)
        val pkt = NanliteProtocol.Packet.parse(payloads[0])!!
        assertEquals(7, pkt.seq)
        assertEquals(NanliteProtocol.FLAG_READ, pkt.flags)
        assertEquals(3, pkt.param)
    }

    @Test
    fun deframerHandlesFragmentsAndJunk() {
        val reply = NanliteProtocol.frame(hex("07020103157c0000")) // CCT 5500 K
        val stream = "+++".toByteArray() + byteArrayOf(0x01) + reply
        val d = NanliteProtocol.Deframer()
        val out = stream.toList().chunked(6).flatMap { d.feed(it.toByteArray()) }
        assertEquals(1, out.size)
        val pkt = NanliteProtocol.Packet.parse(out[0])!!
        assertEquals(NanliteProtocol.FLAG_READ_RESPONSE, pkt.flags)
        assertEquals(5500, pkt.u16)
    }

    @Test
    fun deframerDropsBadChecksum() {
        val bad = NanliteProtocol.frame(hex("0702010100320000")).also { it[it.size - 3] = (it[it.size - 3] + 1).toByte() }
        assertTrue(NanliteProtocol.Deframer().feed(bad).isEmpty())
    }

    @Test
    fun signedGreenMagenta() {
        val pkt = NanliteProtocol.Packet.parse(hex("01020104fff70000"))!!
        assertEquals(-9, pkt.s16)
    }

    @Test
    fun setModeMatchesPublishedHsiExample() {
        // "switches the device to HSI mode": brightness 11, hue 0, saturation 100.
        val p = NanliteProtocol.setMode(0x23, 11, NanliteProtocol.MODE_HSI, 0x01, NanliteProtocol.hsiParams(0, 100))
        assertEquals("2324000401010108000b0301000064", p.hex())
    }

    @Test
    fun setModeCctParamsMatchPublishedExample() {
        // "02:02 010e10000001": 3600 K, G/M 0.
        assertEquals("010e10000001", NanliteProtocol.cctParams(3600, 0).hex())
        assertEquals("010e10ffec01", NanliteProtocol.cctParams(3600, -20).hex())
    }
}
