package app.lightset.mesh

import app.lightset.ble.hex
import org.junit.Test

/**
 * Exploration against traffic captured from a Forza 60C provisioned into a test
 * nRF Mesh network (throwaway keys). Prints what it finds.
 */
class Forza60cCaptureTest {
    private val net = MeshNetwork(h("D605BE48189BE64572434CCE4B5ABDAF"), 0)
    private val ourAppKeys = mapOf(
        "ours idx0" to h("E1849066CED57E905F64C3EB292E52D5"),
        "ours idx1" to h("5B407C79BBB1758C19D33074E590E9F6"),
    )

    /** Candidate fixed app keys seen in vendor SDKs and the spec's sample data. */
    private val candidates = mapOf(
        "spec/telink sample" to h("63964771734fbd76e3b40519d1d94a48"),
        "spec sample 2" to h("3216d1509884b533248541792b877f98"),
        "zeros" to ByteArray(16),
        "0x11 repeated" to ByteArray(16) { 0x11 },
        "0..f" to ByteArray(16) { it.toByte() },
    )

    @Test
    fun decodeExchange() {
        val pdus = listOf(
            "sent read 1" to "2D428669F3AAB6B178B9CDC40F5D573C2CA846A61363E054FB74773826",
            "reply 1" to "2D6885AADA354EACCD4AC86BC6E141BB5A8C1A6C7D5D10C0995D2D1CBC",
            "sent write" to "2D375A3B34B2B0EB2FB90D196E5EAB62DFE3C6CE249BDCE8E65C8F24E7",
            "sent read 2" to "2D74AC3D51DA6FBD90B9DBFA32560CC0E1C8ABCC3277A2950BC6BF3025",
        )
        for ((name, hex) in pdus) {
            val pdu = h(hex)
            val p = net.decode(pdu)
            println("$name: $p transport=${p?.transport?.hex()}")
            p ?: continue
            val ltHdr = p.transport[0].toInt() and 0xFF
            val seg = ltHdr and 0x80 != 0
            val akf = ltHdr and 0x40 != 0
            val aid = ltHdr and 0x3F
            println("  seg=$seg akf=$akf aid=%02x".format(aid))
            (ourAppKeys + candidates).forEach { (k, key) ->
                println("  candidate $k aid=%02x".format(MeshCrypto.k4(key)))
            }
            if (!seg) {
                val upper = p.transport.copyOfRange(1, p.transport.size)
                for ((k, key) in ourAppKeys + candidates) {
                    val nonce = net.accessNonce(device = !akf, szmic = false, seq = p.seq, src = p.src, dst = p.dst)
                    MeshCrypto.ccmDecrypt(key, nonce, upper, 4)?.let { println("  DECRYPTED with $k: ${it.hex()}") }
                }
            }
        }
    }
}
