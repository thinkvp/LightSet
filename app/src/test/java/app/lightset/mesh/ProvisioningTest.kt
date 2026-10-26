package app.lightset.mesh

import app.lightset.ble.hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ProvisioningTest {
    @Test
    fun k1MatchesSpecSample() {
        // Mesh Profile §8.1.3.
        val k = MeshCrypto.k1(
            h("3216d1509884b533248541792b877f98"),
            h("2ba14ffa0df84a2831938d57d276cab4"),
            h("5a09d60797eeb4478aada59db3352a0d"),
        )
        assertEquals("f6ed15a8934afbe7d83e8dcb57fcf5d7", k.hex())
    }

    @Test
    fun ecdhAgreesBothWays() {
        val a = Provisioning.newKeyPair()
        val b = Provisioning.newKeyPair()
        val ab = Provisioning.ecdh(a, Provisioning.publicKeyBytes(b))
        val ba = Provisioning.ecdh(b, Provisioning.publicKeyBytes(a))
        assertEquals(32, ab.size)
        assertArrayEquals(ab, ba)
    }

    /** A provisioner and a simulated device run the exchange; the device must recover the data. */
    @Test
    fun fullExchangeWithSimulatedDevice() {
        val invite = Provisioning.invite().copyOfRange(1, 2)
        val caps = h("0100010000000000000000") // 1 element, P-256, no OOB
        val start = Provisioning.start().copyOfRange(1, 6)

        val prov = Provisioning.newKeyPair()
        val dev = Provisioning.newKeyPair()
        val provPub = Provisioning.publicKeyBytes(prov)
        val devPub = Provisioning.publicKeyBytes(dev)

        val ps = Provisioning.Session(invite, caps, start, provPub, devPub, Provisioning.ecdh(prov, devPub))
        val ds = Provisioning.Session(invite, caps, start, provPub, devPub, Provisioning.ecdh(dev, provPub))

        val provRandom = Provisioning.random16()
        val devRandom = Provisioning.random16()
        // Each side checks the other's confirmation against its random.
        assertArrayEquals(ps.confirmation(devRandom), ds.confirmation(devRandom))
        assertArrayEquals(ds.confirmation(provRandom), ps.confirmation(provRandom))

        val netKey = h("D605BE48189BE64572434CCE4B5ABDAF")
        val provKeys = ps.keys(provRandom, devRandom)
        val pdu = Provisioning.data(provKeys, netKey, keyIndex = 0, ivIndex = 0, unicast = 0x0003)
        assertEquals(1 + 25 + 8, pdu.size)

        val devKeys = ds.keys(provRandom, devRandom)
        val plain = MeshCrypto.ccmDecrypt(devKeys.sessionKey, devKeys.sessionNonce, pdu.copyOfRange(1, pdu.size), 8)
        assertNotNull(plain)
        assertEquals("d605be48189be64572434cce4b5abdaf" + "0000" + "00" + "00000000" + "0003", plain!!.hex())
        assertArrayEquals(provKeys.deviceKey, devKeys.deviceKey)
    }
}
