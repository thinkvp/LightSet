package app.lightset.mesh

/**
 * Unsegmented access messages and proxy configuration over one subnet. Enough to
 * talk to a single Nanlite node through its GATT proxy.
 */
class MeshAccess(private val net: MeshNetwork) {
    data class Access(val src: Int, val dst: Int, val seq: Int, val payload: ByteArray, val keyName: String)

    /** Network PDU carrying [access] (≤ 11 bytes) encrypted with [appKey]. */
    fun encodeAccess(
        appKey: ByteArray, seq: Int, src: Int, dst: Int, ttl: Int, access: ByteArray, deviceKey: Boolean = false,
    ): ByteArray {
        require(access.size <= 11) { "Access payload too long for an unsegmented message" }
        val nonce = net.accessNonce(device = deviceKey, szmic = false, seq = seq, src = src, dst = dst)
        val upper = MeshCrypto.ccmEncrypt(appKey, nonce, access, 4)
        val lower = byteArrayOf(akfAid(appKey, deviceKey).toByte()) + upper
        return net.encode(false, ttl, seq, src, dst, lower)
    }

    /** Lower-transport AKF/AID bits: application key with its AID, or device key (0). */
    private fun akfAid(key: ByteArray, deviceKey: Boolean) = if (deviceKey) 0x00 else 0x40 or MeshCrypto.k4(key)

    /**
     * Network PDUs carrying a long [access] message as lower-transport segments of up to
     * 12 bytes each. [nextSeq] gives each segment its own sequence number; the first one
     * is the SeqAuth the whole message is encrypted under.
     */
    fun encodeSegmented(
        appKey: ByteArray, nextSeq: () -> Int, src: Int, dst: Int, ttl: Int, access: ByteArray, deviceKey: Boolean = false,
    ): List<ByteArray> {
        val seqAuth = nextSeq()
        val nonce = net.accessNonce(device = deviceKey, szmic = false, seq = seqAuth, src = src, dst = dst)
        val upper = MeshCrypto.ccmEncrypt(appKey, nonce, access, 4)
        val chunks = upper.toList().chunked(12).map { it.toByteArray() }
        val segN = chunks.size - 1
        require(segN < 32) { "Message too long" }
        val seqZero = seqAuth and 0x1FFF
        val akfAid = akfAid(appKey, deviceKey)
        return chunks.mapIndexed { segO, chunk ->
            val hdr = byteArrayOf(
                (0x80 or akfAid).toByte(),
                (seqZero ushr 6).toByte(),
                (((seqZero and 0x3F) shl 2) or (segO ushr 3)).toByte(),
                (((segO and 0x07) shl 5) or segN).toByte(),
            )
            net.encode(false, ttl, if (segO == 0) seqAuth else nextSeq(), src, dst, hdr + chunk)
        }
    }

    /** Collects segments of incoming access messages and decrypts each once complete. */
    inner class Reassembler(
        private val appKeys: Map<String, ByteArray>,
        /** Device key of the node at an address, for configuration replies. */
        private val deviceKeys: (Int) -> ByteArray? = { null },
    ) {
        private val pending = HashMap<Pair<Int, Int>, Array<ByteArray?>>()

        /** Returns the decoded message once [pdu] completes one, else null. */
        fun add(pdu: NetworkPdu): Access? {
            if (pdu.ctl || pdu.transport.size < 5) return null
            val t = pdu.transport
            val b0 = t[0].toInt() and 0xFF
            if (b0 and 0x80 == 0) return null
            val akf = b0 and 0x40 != 0
            val aid = b0 and 0x3F
            val seqZero = ((t[1].toInt() and 0x7F) shl 6) or ((t[2].toInt() and 0xFF) ushr 2)
            val segO = ((t[2].toInt() and 0x03) shl 3) or ((t[3].toInt() and 0xFF) ushr 5)
            val segN = t[3].toInt() and 0x1F
            val key = pdu.src to seqZero
            val parts = pending.getOrPut(key) { arrayOfNulls(segN + 1) }
            if (segO > segN || parts.size != segN + 1) return null
            parts[segO] = t.copyOfRange(4, t.size)
            if (parts.any { it == null }) return null
            pending.remove(key)
            val upper = parts.fold(ByteArray(0)) { acc, p -> acc + p!! }
            val seqAuth = pdu.seq - ((pdu.seq - seqZero) and 0x1FFF)
            val nonce = net.accessNonce(device = !akf, szmic = false, seq = seqAuth, src = pdu.src, dst = pdu.dst)
            if (!akf) {
                val dk = deviceKeys(pdu.src) ?: return null
                return MeshCrypto.ccmDecrypt(dk, nonce, upper, 4)?.let { Access(pdu.src, pdu.dst, seqAuth, it, "device") }
            }
            for ((name, k) in appKeys) {
                if (MeshCrypto.k4(k) != aid) continue
                MeshCrypto.ccmDecrypt(k, nonce, upper, 4)?.let { return Access(pdu.src, pdu.dst, seqAuth, it, name) }
            }
            return null
        }
    }

    /** Decrypts an unsegmented access message with whichever of [appKeys] matches its AID. */
    fun decodeAccess(
        pdu: NetworkPdu, appKeys: Map<String, ByteArray>, deviceKeys: (Int) -> ByteArray? = { null },
    ): Access? {
        if (pdu.ctl || pdu.transport.isEmpty()) return null
        val hdr = pdu.transport[0].toInt() and 0xFF
        if (hdr and 0x80 != 0) return null // segmented: see Reassembler
        val aid = hdr and 0x3F
        if (hdr and 0x40 == 0) {
            val dk = deviceKeys(pdu.src) ?: return null
            val nonce = net.accessNonce(device = true, szmic = false, seq = pdu.seq, src = pdu.src, dst = pdu.dst)
            return MeshCrypto.ccmDecrypt(dk, nonce, pdu.transport.copyOfRange(1, pdu.transport.size), 4)
                ?.let { Access(pdu.src, pdu.dst, pdu.seq, it, "device") }
        }
        val nonce = net.accessNonce(device = false, szmic = false, seq = pdu.seq, src = pdu.src, dst = pdu.dst)
        for ((name, key) in appKeys) {
            if (MeshCrypto.k4(key) != aid) continue
            val plain = MeshCrypto.ccmDecrypt(key, nonce, pdu.transport.copyOfRange(1, pdu.transport.size), 4)
            if (plain != null) return Access(pdu.src, pdu.dst, pdu.seq, plain, name)
        }
        return null
    }

    /**
     * Segment Acknowledgement (lower-transport control opcode 0x00) telling [dst] which
     * segments of its message [seqZero] arrived, so it stops retransmitting.
     */
    fun segmentAck(seq: Int, src: Int, dst: Int, seqZero: Int, segN: Int): ByteArray {
        val block = if (segN >= 31) -1 else (1 shl (segN + 1)) - 1
        val transport = byteArrayOf(
            0x00,
            (seqZero ushr 6).toByte(),
            ((seqZero and 0x3F) shl 2).toByte(),
            (block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte(),
        )
        return net.encode(ctl = true, ttl = 5, seq = seq, src = src, dst = dst, transport = transport)
    }

    /** Proxy configuration "Add Addresses to Filter", so the proxy forwards replies to [address]. */
    fun proxyAddToFilter(seq: Int, src: Int, address: Int): ByteArray =
        net.encode(
            ctl = true, ttl = 0, seq = seq, src = src, dst = 0x0000,
            transport = byteArrayOf(0x01, (address ushr 8).toByte(), address.toByte()),
            proxy = true,
        )

    companion object {
        const val PROXY_TYPE_NETWORK = 0x00
        const val PROXY_TYPE_CONFIG = 0x02

        /** Single-segment proxy PDU (needs an MTU large enough for the whole message). */
        fun proxyPdu(type: Int, pdu: ByteArray): ByteArray = byteArrayOf(type.toByte()) + pdu

        /** Vendor opcode for company [cid]: 0b11xxxxxx followed by the company ID, little-endian. */
        fun vendorOpcode(op: Int, cid: Int): ByteArray =
            byteArrayOf((0xC0 or op).toByte(), cid.toByte(), (cid ushr 8).toByte())
    }
}
