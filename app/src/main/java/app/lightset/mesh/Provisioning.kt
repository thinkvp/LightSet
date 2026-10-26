package app.lightset.mesh

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement

/**
 * Bluetooth Mesh provisioning (Mesh Profile §5.4) with FIPS P-256 ECDH and no OOB
 * authentication, which is what Nanlite lights advertise. Pure protocol: the
 * Bluetooth transport lives in [Provisioner].
 */
object Provisioning {
    const val INVITE = 0x00
    const val CAPABILITIES = 0x01
    const val START = 0x02
    const val PUBLIC_KEY = 0x03
    const val CONFIRMATION = 0x05
    const val RANDOM = 0x06
    const val DATA = 0x07
    const val COMPLETE = 0x08
    const val FAILED = 0x09

    data class Capabilities(val raw: ByteArray) {
        val elements: Int get() = raw[0].toInt() and 0xFF
        val supportsP256: Boolean get() = (((raw[1].toInt() and 0xFF) shl 8) or (raw[2].toInt() and 0xFF)) and 1 != 0
    }

    fun invite(attentionSeconds: Int = 5) = byteArrayOf(INVITE.toByte(), attentionSeconds.toByte())

    /** Start: P-256, no OOB public key, no OOB authentication. */
    fun start() = byteArrayOf(START.toByte(), 0, 0, 0, 0, 0)

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    /** Uncompressed X ‖ Y, 64 bytes, as sent in the Public Key PDU. */
    fun publicKeyBytes(kp: KeyPair): ByteArray {
        val w = (kp.public as ECPublicKey).w
        return fixed32(w.affineX) + fixed32(w.affineY)
    }

    fun ecdh(kp: KeyPair, peer: ByteArray): ByteArray {
        require(peer.size == 64)
        val params = (kp.public as ECPublicKey).params
        val point = ECPoint(BigInteger(1, peer.copyOfRange(0, 32)), BigInteger(1, peer.copyOfRange(32, 64)))
        val pub = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
        return KeyAgreement.getInstance("ECDH").run {
            init(kp.private)
            doPhase(pub, true)
            generateSecret()
        }
    }

    private fun fixed32(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }

    fun random16(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    /** Keys derived from the exchange. AuthValue is all zeros (no OOB). */
    class Session(
        inviteParams: ByteArray,
        capabilitiesParams: ByteArray,
        startParams: ByteArray,
        provisionerKey: ByteArray,
        deviceKey: ByteArray,
        private val ecdh: ByteArray,
    ) {
        val confirmationSalt: ByteArray =
            MeshCrypto.s1(inviteParams + capabilitiesParams + startParams + provisionerKey + deviceKey)
        private val confirmationKey = MeshCrypto.k1(ecdh, confirmationSalt, "prck".toByteArray())
        private val authValue = ByteArray(16)

        fun confirmation(random: ByteArray): ByteArray = MeshCrypto.cmac(confirmationKey, random + authValue)

        /** Session key, session nonce and device key, once both randoms are known. */
        class Keys(val sessionKey: ByteArray, val sessionNonce: ByteArray, val deviceKey: ByteArray)

        fun keys(provisionerRandom: ByteArray, deviceRandom: ByteArray): Keys {
            val salt = MeshCrypto.s1(confirmationSalt + provisionerRandom + deviceRandom)
            val nonce = MeshCrypto.k1(ecdh, salt, "prsn".toByteArray())
            return Keys(
                sessionKey = MeshCrypto.k1(ecdh, salt, "prsk".toByteArray()),
                sessionNonce = nonce.copyOfRange(3, 16),
                deviceKey = MeshCrypto.k1(ecdh, salt, "prdk".toByteArray()),
            )
        }
    }

    /** Provisioning Data: NetKey ‖ key index ‖ flags ‖ IV index ‖ unicast, encrypted, + 8-byte MIC. */
    fun data(keys: Session.Keys, netKey: ByteArray, keyIndex: Int, ivIndex: Int, unicast: Int): ByteArray {
        val plain = netKey +
            byteArrayOf((keyIndex ushr 8).toByte(), keyIndex.toByte(), 0x00) +
            MeshCrypto.be32(ivIndex) +
            byteArrayOf((unicast ushr 8).toByte(), unicast.toByte())
        return byteArrayOf(DATA.toByte()) + MeshCrypto.ccmEncrypt(keys.sessionKey, keys.sessionNonce, plain, 8)
    }

    fun failureReason(code: Int): String = when (code) {
        0x01 -> "invalid PDU"
        0x02 -> "invalid format"
        0x03 -> "unexpected PDU"
        0x04 -> "confirmation failed"
        0x05 -> "out of resources"
        0x06 -> "decryption failed"
        0x07 -> "unexpected error"
        0x08 -> "cannot assign addresses"
        else -> "error $code"
    }
}
