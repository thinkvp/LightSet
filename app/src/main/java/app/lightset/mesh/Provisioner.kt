package app.lightset.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import app.lightset.ble.GattClient
import app.lightset.ble.hex
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * Adds a light that's waiting to join (advertising Mesh Provisioning, 0x1827) to our
 * network over PB-GATT: invite, key exchange, confirmations, then the network key and
 * an address. Nanlite (Feasycom) lights then install their default app key themselves.
 */
@SuppressLint("MissingPermission")
class Provisioner(private val context: Context, private val log: (String) -> Unit) {
    data class Result(val deviceKey: ByteArray, val elements: Int)

    suspend fun provision(
        device: BluetoothDevice,
        netKey: ByteArray,
        ivIndex: Int,
        unicast: Int,
        progress: (String) -> Unit = {},
    ): Result {
        val inbox = Channel<ByteArray>(Channel.UNLIMITED)
        var rx = ByteArray(0)
        progress("Connecting…")
        val gatt = GattClient.connect(context, device, onNotify = { uuid, bytes ->
            if (uuid != DATA_OUT || bytes.isEmpty()) return@connect
            val sar = (bytes[0].toInt() and 0xC0) ushr 6
            val type = bytes[0].toInt() and 0x3F
            val body = bytes.copyOfRange(1, bytes.size)
            if (type != PROXY_TYPE_PROVISIONING) return@connect
            when (sar) {
                0 -> inbox.trySend(body)
                1 -> rx = body
                2 -> rx += body
                3 -> { inbox.trySend(rx + body); rx = ByteArray(0) }
            }
        })
        try {
            val mtu = gatt.requestMtu(69)
            gatt.discoverServices()
            val svc = checkNotNull(gatt.services.firstOrNull { it.uuid == SERVICE }) {
                "This light isn't waiting to join — reset its Bluetooth first"
            }
            val dataIn = checkNotNull(svc.getCharacteristic(DATA_IN))
            gatt.enableNotifications(checkNotNull(svc.getCharacteristic(DATA_OUT)))

            suspend fun send(pdu: ByteArray) {
                log("prov → ${pdu.hex()}")
                val room = mtu - 3 - 1
                if (pdu.size <= room) {
                    gatt.write(dataIn, byteArrayOf(PROXY_TYPE_PROVISIONING.toByte()) + pdu, noResponse = true)
                    return
                }
                val parts = pdu.toList().chunked(room).map { it.toByteArray() }
                parts.forEachIndexed { i, p ->
                    val sar = when (i) { 0 -> 1; parts.lastIndex -> 3; else -> 2 }
                    gatt.write(dataIn, byteArrayOf(((sar shl 6) or PROXY_TYPE_PROVISIONING).toByte()) + p, noResponse = true)
                }
            }

            suspend fun expect(type: Int): ByteArray {
                val pdu = withTimeout(15_000) { inbox.receive() }
                log("prov ← ${pdu.hex()}")
                val t = pdu[0].toInt() and 0xFF
                if (t == Provisioning.FAILED) {
                    error("Light refused: ${Provisioning.failureReason(pdu.getOrNull(1)?.toInt() ?: 0)}")
                }
                check(t == type) { "Unexpected provisioning step $t (wanted $type)" }
                return pdu.copyOfRange(1, pdu.size)
            }

            progress("Exchanging keys…")
            val invite = Provisioning.invite()
            send(invite)
            val caps = Provisioning.Capabilities(expect(Provisioning.CAPABILITIES))
            check(caps.supportsP256) { "Light doesn't support the standard key exchange" }

            val start = Provisioning.start()
            send(start)
            val kp = Provisioning.newKeyPair()
            val ourPub = Provisioning.publicKeyBytes(kp)
            send(byteArrayOf(Provisioning.PUBLIC_KEY.toByte()) + ourPub)
            val theirPub = expect(Provisioning.PUBLIC_KEY)

            val session = Provisioning.Session(
                invite.copyOfRange(1, invite.size), caps.raw, start.copyOfRange(1, start.size),
                ourPub, theirPub, Provisioning.ecdh(kp, theirPub),
            )
            progress("Confirming…")
            val ourRandom = Provisioning.random16()
            send(byteArrayOf(Provisioning.CONFIRMATION.toByte()) + session.confirmation(ourRandom))
            val theirConfirmation = expect(Provisioning.CONFIRMATION)
            send(byteArrayOf(Provisioning.RANDOM.toByte()) + ourRandom)
            val theirRandom = expect(Provisioning.RANDOM)
            check(session.confirmation(theirRandom).contentEquals(theirConfirmation)) {
                "Light's confirmation didn't match — try again"
            }

            progress("Sending network key…")
            val keys = session.keys(ourRandom, theirRandom)
            send(Provisioning.data(keys, netKey, keyIndex = 0, ivIndex = ivIndex, unicast = unicast))
            expect(Provisioning.COMPLETE)
            progress("Joined")
            return Result(keys.deviceKey, caps.elements)
        } finally {
            gatt.close()
        }
    }

    companion object {
        const val PROXY_TYPE_PROVISIONING = 0x03
        val SERVICE: UUID = UUID.fromString("00001827-0000-1000-8000-00805f9b34fb")
        val DATA_IN: UUID = UUID.fromString("00002adb-0000-1000-8000-00805f9b34fb")
        val DATA_OUT: UUID = UUID.fromString("00002adc-0000-1000-8000-00805f9b34fb")
    }
}
