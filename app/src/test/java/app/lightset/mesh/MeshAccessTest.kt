package app.lightset.mesh

import app.lightset.ble.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Checked against an exchange captured between nRF Mesh and a Forza 60C (test network keys). */
class MeshAccessTest {
    private val net = MeshNetwork(h("D605BE48189BE64572434CCE4B5ABDAF"), 0)
    private val access = MeshAccess(net)
    private val ourKey = h("5B407C79BBB1758C19D33074E590E9F6")
    private val lightDefaultKey = h("63964771734fbd76e3b40519d1d94a48")

    @Test
    fun reproducesCapturedRequest() {
        val pdu = access.encodeAccess(ourKey, seq = 18, src = 0x0001, dst = 0x0002, ttl = 5,
            access = h("c111110101010100000000"))
        assertEquals("2D428669F3AAB6B178B9CDC40F5D573C2CA846A61363E054FB74773826".lowercase(), pdu.hex())
    }

    @Test
    fun decodesCapturedReply() {
        val pdu = net.decode(h("2D6885AADA354EACCD4AC86BC6E141BB5A8C1A6C7D5D10C0995D2D1CBC"))!!
        val a = access.decodeAccess(pdu, mapOf("ours" to ourKey, "default" to lightDefaultKey))
        assertNotNull(a)
        assertEquals("default", a!!.keyName)
        assertEquals("c111110102010100320000", a.payload.hex())
    }

    @Test
    fun proxyConfigRoundTrip() {
        val pdu = access.proxyAddToFilter(seq = 7, src = 0x0010, address = 0x0010)
        val back = net.decode(pdu, proxy = true)!!
        assertEquals(true, back.ctl)
        assertEquals(0, back.ttl)
        assertEquals(0x0000, back.dst)
        assertEquals("010010", back.transport.hex())
    }

    @Test
    fun vendorOpcode() = assertEquals("c11111", MeshAccess.vendorOpcode(0x01, 0x1111).hex())
}

class SegmentationTest {
    private val net = MeshNetwork(h("D605BE48189BE64572434CCE4B5ABDAF"), 0)
    private val access = MeshAccess(net)
    private val key = h("63964771734fbd76e3b40519d1d94a48")

    @Test
    fun segmentedRoundTrip() {
        // A set_mode (HSI) packet as a vendor message: 3 + 15 = 18 bytes, too long for one PDU.
        val msg = h("c11111" + "2324000401010108000b0301000064")
        var seq = 8190 // crosses the 13-bit SeqZero wrap
        val pdus = access.encodeSegmented(key, { seq++ }, src = 0x0010, dst = 0x0002, ttl = 5, access = msg)
        assertEquals(2, pdus.size)
        val r = access.Reassembler(mapOf("default" to key))
        var out: MeshAccess.Access? = null
        for (p in pdus.reversed()) out = r.add(net.decode(p)!!) ?: out
        assertNotNull(out)
        assertEquals(msg.hex(), out!!.payload.hex())
        assertEquals(8190, out.seq)
    }
}

class DeviceKeyTest {
    private val net = MeshNetwork(h("D605BE48189BE64572434CCE4B5ABDAF"), 0)
    private val access = MeshAccess(net)
    private val devKey = h("9d6dd0e96eb25dc19a40ed9914f8f03f")

    @Test
    fun appKeyAddRoundTripsSegmented() {
        // Config AppKey Add: opcode 0x00, NetKeyIndex|AppKeyIndex (0,0), AppKey.
        val msg = h("00" + "000000" + "63964771734fbd76e3b40519d1d94a48")
        var seq = 40
        val pdus = access.encodeSegmented(devKey, { seq++ }, src = 0x0010, dst = 0x0003, ttl = 5, access = msg, deviceKey = true)
        assertEquals(2, pdus.size)
        val first = net.decode(pdus[0])!!
        assertEquals(0x80, first.transport[0].toInt() and 0xFF) // SEG=1, AKF=0, AID=0
        val r = access.Reassembler(emptyMap()) { if (it == 0x0010) devKey else null }
        var out: MeshAccess.Access? = null
        for (p in pdus) out = r.add(net.decode(p)!!) ?: out
        assertEquals(msg.hex(), out!!.payload.hex())
        assertEquals("device", out.keyName)
    }

    @Test
    fun unsegmentedDeviceKeyRoundTrip() {
        val msg = h("803d" + "0300" + "0000" + "11111111") // Model App Bind, vendor 0x1111/0x1111
        val pdu = access.encodeAccess(devKey, seq = 7, src = 0x0003, dst = 0x0010, ttl = 5, access = msg, deviceKey = true)
        val a = access.decodeAccess(net.decode(pdu)!!, emptyMap()) { if (it == 0x0003) devKey else null }
        assertEquals(msg.hex(), a!!.payload.hex())
    }
}

