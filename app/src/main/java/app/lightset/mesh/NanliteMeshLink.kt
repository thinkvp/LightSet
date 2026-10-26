package app.lightset.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import app.lightset.ble.GattClient
import app.lightset.ble.NanliteProtocol
import app.lightset.ble.hex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Talks the Nanlite app protocol to one light over Bluetooth Mesh, through the
 * light's own GATT proxy. Nanlite short packets (seq, flags, group, param, 4 bytes)
 * travel as vendor messages: opcode 0x01 for company 0x1111 (Feasycom firmware).
 */
@SuppressLint("MissingPermission")
class NanliteMeshLink(
    private val context: Context,
    netKey: ByteArray,
    ivIndex: Int,
    /** Our unicast address. Must not be shared with another client, for replay protection. */
    private val src: Int,
    private val appKeys: Map<String, ByteArray>,
    private val log: (String) -> Unit,
    private val onDisconnect: () -> Unit = {},
    /** Device key of the node at an address, for configuration messages. */
    private val deviceKeys: (Int) -> ByteArray? = { null },
) {
    private val net = MeshNetwork(netKey, ivIndex)
    private val access = MeshAccess(net)
    private val reassembler = access.Reassembler(appKeys, deviceKeys)

    /** Background sends (segment acks) triggered from Bluetooth callbacks. */
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    /** Configuration requests awaiting a reply: (node, reply opcode) → params. */
    private val configWaiting = ConcurrentHashMap<Pair<Int, String>, CompletableDeferred<ByteArray>>()
    private val prefs = context.getSharedPreferences("mesh", Context.MODE_PRIVATE)
    private var client: GattClient? = null
    private var dataIn: BluetoothGattCharacteristic? = null
    private val waiting = ConcurrentHashMap<Int, CompletableDeferred<NanliteProtocol.Packet>>()
    private var nanliteSeq = 0
    private var rxBuf = ByteArray(0)

    var sendKey: String = appKeys.keys.first()

    val connected: Boolean get() = client?.isConnected == true

    suspend fun connect(device: BluetoothDevice) {
        log("Connecting to ${device.address}…")
        val c = GattClient.connect(context, device, onNotify = { uuid, bytes -> onNotify(uuid, bytes) },
            onDisconnect = { log("Disconnected"); onDisconnect() })
        client = c
        val mtu = c.requestMtu(69)
        log("MTU $mtu")
        c.discoverServices()
        val proxy = checkNotNull(c.services.firstOrNull { it.uuid == PROXY_SERVICE }) {
            "Light isn't in a mesh network (no proxy service)"
        }
        dataIn = checkNotNull(proxy.getCharacteristic(PROXY_DATA_IN))
        c.enableNotifications(checkNotNull(proxy.getCharacteristic(PROXY_DATA_OUT)))
        // Ask the proxy to forward messages addressed to us.
        sendProxy(MeshAccess.PROXY_TYPE_CONFIG, access.proxyAddToFilter(nextSeq(), src, src))
        log("Connected; proxy filter set for %04x".format(src))
    }

    fun close() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        client?.close()
        client = null
    }

    /** Sends a Nanlite short packet and, for reads, waits for the matching reply. */
    suspend fun request(dst: Int, flags: Int, group: Int, param: Int, data: ByteArray, await: Boolean): NanliteProtocol.Packet? {
        require(data.size == 4)
        nanliteSeq = (nanliteSeq % 255) + 1
        val payload = byteArrayOf(nanliteSeq.toByte(), flags.toByte(), group.toByte(), param.toByte()) + data
        val reply = CompletableDeferred<NanliteProtocol.Packet>()
        if (await) waiting[nanliteSeq] = reply
        val msg = MeshAccess.vendorOpcode(0x01, COMPANY_ID) + payload
        val key = appKeys.getValue(sendKey)
        val seq = nextSeq()
        log("→ [%s seq %d] %s".format(sendKey, seq, msg.hex()))
        sendProxy(MeshAccess.PROXY_TYPE_NETWORK, access.encodeAccess(key, seq, src, dst, ttl = 5, access = msg))
        if (!await) return null
        return withTimeoutOrNull(2500) { reply.await() }.also { waiting.remove(nanliteSeq) }
    }

    /**
     * Sends an extended Nanlite packet (e.g. set_mode). [body] is everything after the
     * sequence byte; long ones go out as mesh segments.
     */
    suspend fun sendExtended(dst: Int, body: (seq: Int) -> ByteArray) {
        nanliteSeq = (nanliteSeq % 255) + 1
        val msg = MeshAccess.vendorOpcode(0x01, COMPANY_ID) + body(nanliteSeq)
        val key = appKeys.getValue(sendKey)
        val pdus = if (msg.size <= 11) {
            listOf(access.encodeAccess(key, nextSeq(), src, dst, ttl = 5, access = msg))
        } else {
            access.encodeSegmented(key, ::nextSeq, src, dst, ttl = 5, access = msg)
        }
        log("→ [%s, %d seg] %s".format(sendKey, pdus.size, msg.hex()))
        for (p in pdus) {
            sendProxy(MeshAccess.PROXY_TYPE_NETWORK, p)
            kotlinx.coroutines.delay(15)
        }
    }

    /**
     * Sends a configuration message encrypted with the node's device key and waits for
     * the reply with [replyOpcode]; returns its parameters, or null if none came. Tries
     * twice, as configuration messages are acknowledged only by their status reply.
     */
    suspend fun configRequest(dst: Int, devKey: ByteArray, msg: ByteArray, replyOpcode: ByteArray): ByteArray? {
        val key = dst to replyOpcode.hex()
        repeat(2) {
            val reply = CompletableDeferred<ByteArray>()
            configWaiting[key] = reply
            val pdus = if (msg.size <= 11) {
                listOf(access.encodeAccess(devKey, nextSeq(), src, dst, ttl = 5, access = msg, deviceKey = true))
            } else {
                access.encodeSegmented(devKey, ::nextSeq, src, dst, ttl = 5, access = msg, deviceKey = true)
            }
            log("→ [device, %d seg] to %04x %s".format(pdus.size, dst, msg.hex()))
            for (p in pdus) {
                sendProxy(MeshAccess.PROXY_TYPE_NETWORK, p)
                kotlinx.coroutines.delay(15)
            }
            val r = withTimeoutOrNull(4000) { reply.await() }
            configWaiting.remove(key)
            if (r != null) return r
        }
        return null
    }

    suspend fun read(dst: Int, param: Int) =
        request(dst, NanliteProtocol.FLAG_READ, NanliteProtocol.GROUP_GENERAL, param, ByteArray(4), await = true)

    suspend fun write(dst: Int, param: Int, value: Int, trailer: Int) = request(
        dst, NanliteProtocol.FLAG_WRITE, NanliteProtocol.GROUP_GENERAL, param,
        byteArrayOf((value ushr 8).toByte(), value.toByte(), (trailer ushr 8).toByte(), trailer.toByte()),
        await = false,
    )

    private suspend fun sendProxy(type: Int, pdu: ByteArray) {
        val c = checkNotNull(client) { "Not connected" }
        val frame = MeshAccess.proxyPdu(type, pdu)
        check(frame.size <= c.mtu - 3) { "MTU ${c.mtu} too small for ${frame.size}-byte proxy PDU" }
        c.write(checkNotNull(dataIn), frame, noResponse = true)
    }

    private fun nextSeq(): Int {
        val key = "seq_%04x".format(src)
        val s = prefs.getInt(key, 1)
        prefs.edit().putInt(key, s + 1).commit()
        return s
    }

    private fun onNotify(uuid: UUID, bytes: ByteArray) {
        if (uuid != PROXY_DATA_OUT || bytes.isEmpty()) return
        val sar = (bytes[0].toInt() and 0xC0) ushr 6
        val type = bytes[0].toInt() and 0x3F
        val body = bytes.copyOfRange(1, bytes.size)
        when (sar) {
            0 -> handle(type, body)
            1 -> rxBuf = body
            2 -> rxBuf += body
            3 -> { handle(type, rxBuf + body); rxBuf = ByteArray(0) }
        }
    }

    private fun handle(type: Int, pdu: ByteArray) {
        when (type) {
            MeshAccess.PROXY_TYPE_NETWORK -> {
                val p = net.decode(pdu) ?: return log("← undecodable network PDU ${pdu.hex()}")
                ackIfSegmented(p)
                val a = access.decodeAccess(p, appKeys, deviceKeys) ?: reassembler.add(p)
                if (a == null) {
                    log("← from %04x seq %d (ctl=%s) transport %s".format(p.src, p.seq, p.ctl, p.transport.hex()))
                    return
                }
                log("← [%s] from %04x: %s".format(a.keyName, a.src, a.payload.hex()))
                if (a.keyName == "device") {
                    // Opcodes are 1 byte (0x00–0x7E) or 2 bytes (0x80–0xBF first byte).
                    val opLen = if (a.payload[0].toInt() and 0x80 != 0) 2 else 1
                    val op = a.payload.copyOfRange(0, opLen).hex()
                    configWaiting.remove(a.src to op)?.complete(a.payload.copyOfRange(opLen, a.payload.size))
                    return
                }
                if (a.payload.size >= 11 && a.payload.copyOfRange(1, 3).contentEquals(byteArrayOf(0x11, 0x11))) {
                    NanliteProtocol.Packet.parse(a.payload.copyOfRange(3, a.payload.size))?.let { pkt ->
                        waiting.remove(pkt.seq)?.complete(pkt)
                    }
                }
            }
            MeshAccess.PROXY_TYPE_CONFIG -> {
                val p = net.decode(pdu, proxy = true)
                log("← proxy config ${p?.transport?.hex() ?: "(undecodable) " + pdu.hex()}")
            }
            else -> Unit // mesh beacons
        }
    }

    /** Segments seen per (source, SeqZero), to acknowledge once all have arrived. */
    private val segmentsSeen = ConcurrentHashMap<Pair<Int, Int>, Int>()

    private fun ackIfSegmented(p: NetworkPdu) {
        val t = p.transport
        if (p.ctl || p.dst != src || t.size < 4 || t[0].toInt() and 0x80 == 0) return
        val seqZero = ((t[1].toInt() and 0x7F) shl 6) or ((t[2].toInt() and 0xFF) ushr 2)
        val segO = ((t[2].toInt() and 0x03) shl 3) or ((t[3].toInt() and 0xFF) ushr 5)
        val segN = t[3].toInt() and 0x1F
        val key = p.src to seqZero
        val seen = (segmentsSeen[key] ?: 0) or (1 shl segO)
        segmentsSeen[key] = seen
        val all = if (segN >= 31) -1 else (1 shl (segN + 1)) - 1
        if (seen != all) return
        scope.launch {
            runCatching {
                sendProxy(MeshAccess.PROXY_TYPE_NETWORK, access.segmentAck(nextSeq(), src, p.src, seqZero, segN))
            }
        }
    }

    companion object {
        const val COMPANY_ID = 0x1111
        val PROXY_SERVICE: UUID = UUID.fromString("00001828-0000-1000-8000-00805f9b34fb")
        val PROXY_DATA_IN: UUID = UUID.fromString("00002add-0000-1000-8000-00805f9b34fb")
        val PROXY_DATA_OUT: UUID = UUID.fromString("00002ade-0000-1000-8000-00805f9b34fb")

        /** AppKey index 0 that Feasycom firmware installs: the Mesh spec's sample key. */
        val NANLITE_DEFAULT_APP_KEY: ByteArray = byteArrayOf(
            0x63, 0x96.toByte(), 0x47, 0x71, 0x73, 0x4f, 0xbd.toByte(), 0x76,
            0xe3.toByte(), 0xb4.toByte(), 0x05, 0x19, 0xd1.toByte(), 0xd9.toByte(), 0x4a, 0x48,
        )
    }
}
