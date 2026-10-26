package app.lightset.ble

import java.util.UUID

/**
 * Nanlite Bluetooth "app protocol", as reverse-engineered on a PavoTube II 6C:
 * https://gist.github.com/vmedea/434694c11092261fcac401b7a4b9a741
 *
 * The light's BLE module is a UART bridge. Packets are framed as
 * `FA 55 <len:u16be> 01 <payload> <checksum:u16be> 0D 0A`, where len counts the whole
 * frame and checksum is the byte sum of header + payload, plus one. Short payloads
 * are `seq flags group param <4 bytes>`. All integers are big-endian.
 */
object NanliteProtocol {
    val SERVICE: UUID = UUID.fromString("0003cdd0-0000-1000-8000-00805f9b0131")
    /** Notify: light → phone. */
    val UART_TX: UUID = UUID.fromString("0003cdd1-0000-1000-8000-00805f9b0131")
    /** Write: phone → light. */
    val UART_RX: UUID = UUID.fromString("0003cdd2-0000-1000-8000-00805f9b0131")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** A serial-style notify/write pair the app protocol might run over. */
    data class UartChannel(val label: String, val service: UUID, val tx: UUID, val rx: UUID)

    /**
     * Where to look for the app protocol: the documented USR-module service, and the
     * vendor `fff0` pair found on a Forza 60C (Silicon Labs, Bluetooth Mesh firmware).
     */
    val UART_CANDIDATES = listOf(
        UartChannel("cdd0 (documented)", SERVICE, UART_TX, UART_RX),
        UartChannel(
            "fff0 (vendor)",
            UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb"),
        ),
    )

    /** Standard services that are safe to read for identification. */
    val INFO_SERVICES: Set<UUID> = setOf(
        UUID.fromString("00001800-0000-1000-8000-00805f9b34fb"), // Generic Access
        UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb"), // Device Information
    )

    const val FLAG_READ = 0x01
    const val FLAG_READ_RESPONSE = 0x02
    const val FLAG_EXTENDED = 0x04
    const val FLAG_WRITE = 0x20

    const val GROUP_GENERAL = 0x01

    /** Parameters in the general group that the probe reads. */
    enum class Param(val id: Int, val label: String, val signed: Boolean = false) {
        BRIGHTNESS(0x01, "Brightness"),
        CCT(0x03, "CCT"),
        GM(0x04, "G/M", signed = true),
        HUE(0x05, "Hue"),
        SATURATION(0x0C, "Saturation"),
    }

    private const val HEADER = 5
    private const val TRAILER = 4

    fun frame(payload: ByteArray): ByteArray {
        val len = HEADER + payload.size + TRAILER
        val out = ByteArray(len)
        out[0] = 0xFA.toByte()
        out[1] = 0x55
        out[2] = (len shr 8).toByte()
        out[3] = len.toByte()
        out[4] = 0x01
        payload.copyInto(out, HEADER)
        val sum = checksum(out, HEADER + payload.size)
        out[len - 4] = (sum shr 8).toByte()
        out[len - 3] = sum.toByte()
        out[len - 2] = 0x0D
        out[len - 1] = 0x0A
        return out
    }

    fun readRequest(seq: Int, group: Int, param: Int): ByteArray =
        frame(byteArrayOf(seq.toByte(), FLAG_READ.toByte(), group.toByte(), param.toByte(), 0, 0, 0, 0))

    fun writeRequest(seq: Int, group: Int, param: Int, value: ByteArray): ByteArray {
        require(value.size == 4)
        return frame(byteArrayOf(seq.toByte(), FLAG_WRITE.toByte(), group.toByte(), param.toByte()) + value)
    }

    const val MODE_CCT = 0x02
    const val MODE_HSI = 0x03

    /**
     * Extended "set_mode" packet: switches the light's mode (like its MODE button) and
     * sets brightness and that mode's values in one go. Layout from the notes:
     * `seq 24 0004 0101 01 | ssize brightness:u16 mode submode params…`.
     */
    fun setMode(seq: Int, brightness: Int, mode: Int, submode: Int, params: ByteArray): ByteArray {
        val body = byteArrayOf(
            (1 + 2 + 1 + 1 + params.size).toByte(),
            (brightness ushr 8).toByte(), brightness.toByte(),
            mode.toByte(), submode.toByte(),
        ) + params
        return byteArrayOf(seq.toByte(), (FLAG_WRITE or FLAG_EXTENDED).toByte(), 0x00, 0x04, 0x01, 0x01, 0x01) + body
    }

    fun hsiParams(hue: Int, sat: Int) = byteArrayOf((hue ushr 8).toByte(), hue.toByte(), sat.toByte())

    fun cctParams(cct: Int, gm: Int) =
        byteArrayOf(0x01, (cct ushr 8).toByte(), cct.toByte(), (gm ushr 8).toByte(), gm.toByte(), 0x01)

    private fun checksum(bytes: ByteArray, count: Int): Int {
        var sum = 1
        for (i in 0 until count) sum += bytes[i].toInt() and 0xFF
        return sum and 0xFFFF
    }

    /** A decoded short packet. */
    data class Packet(val seq: Int, val flags: Int, val group: Int, val param: Int, val data: ByteArray) {
        val u16: Int get() = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        val s16: Int get() = u16.toShort().toInt()

        companion object {
            fun parse(payload: ByteArray): Packet? {
                if (payload.size < 8 || payload[1].toInt() and FLAG_EXTENDED != 0) return null
                return Packet(
                    seq = payload[0].toInt() and 0xFF,
                    flags = payload[1].toInt() and 0xFF,
                    group = payload[2].toInt() and 0xFF,
                    param = payload[3].toInt() and 0xFF,
                    data = payload.copyOfRange(4, 8),
                )
            }
        }

        override fun equals(other: Any?) = other is Packet && seq == other.seq && flags == other.flags &&
            group == other.group && param == other.param && data.contentEquals(other.data)

        override fun hashCode() = arrayOf(seq, flags, group, param, data.contentHashCode()).contentHashCode()
    }

    /**
     * Reassembles frames from notification fragments. Skips the keep-alive bytes and
     * the stray "+++" the BLE module emits, and drops frames with a bad checksum.
     */
    class Deframer {
        private var buf = ByteArray(0)

        fun feed(bytes: ByteArray): List<ByteArray> {
            buf += bytes
            val out = mutableListOf<ByteArray>()
            while (true) {
                val start = (0 until buf.size - 1).firstOrNull {
                    buf[it] == 0xFA.toByte() && buf[it + 1] == 0x55.toByte()
                }
                if (start == null) {
                    // Keep a trailing 0xFA in case the next fragment starts with 0x55.
                    buf = if (buf.isNotEmpty() && buf.last() == 0xFA.toByte()) byteArrayOf(0xFA.toByte()) else ByteArray(0)
                    return out
                }
                if (start > 0) buf = buf.copyOfRange(start, buf.size)
                if (buf.size < 4) return out
                val len = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
                if (len < HEADER + TRAILER || len > 512) {
                    buf = buf.copyOfRange(2, buf.size)
                    continue
                }
                if (buf.size < len) return out
                val frame = buf.copyOfRange(0, len)
                buf = buf.copyOfRange(len, buf.size)
                val sum = ((frame[len - 4].toInt() and 0xFF) shl 8) or (frame[len - 3].toInt() and 0xFF)
                if (sum == checksum(frame, len - TRAILER)) out += frame.copyOfRange(HEADER, len - TRAILER)
            }
        }
    }
}

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
