package org.dattapool.capture.crypto

import java.math.BigInteger
import java.security.*
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.*

/**
 * Standard curve keypair and signing matching DattaPool's Go cryptographic implementation.
 * Private key: 32-byte hex (64 chars)
 * Public key:  32-byte hex X-coordinate (64 chars)
 * Signature:   64-byte hex R || S (128 chars)
 */
data class KeyPair(
    val privateKeyHex: String,
    val publicKeyHex: String
) {
    companion object {
        init {
            Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
        }

        private val ecSpec: ECParameterSpec by lazy {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"))
            val kp = kpg.generateKeyPair()
            (kp.public as ECPublicKey).params
        }

        fun generate(): KeyPair {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
            val kp = kpg.generateKeyPair()

            val priv = kp.private as ECPrivateKey
            val pub = kp.public as ECPublicKey

            val privBytes = toFixedByteArray(priv.s, 32)
            val pubBytes = toFixedByteArray(pub.w.affineX, 32)

            return KeyPair(
                privateKeyHex = privBytes.toHex(),
                publicKeyHex = pubBytes.toHex()
            )
        }

        fun fromHex(privHex: String): KeyPair {
            val privBytes = privHex.trim().hexToByteArray()
            require(privBytes.size == 32) { "Expected 32-byte private key, got ${privBytes.size}" }

            val s = BigInteger(1, privBytes)

            // Derive public key point via BouncyCastle EC
            val bcCurve = org.bouncycastle.jce.ECNamedCurveTable.getParameterSpec("secp256r1")
            val q = bcCurve.g.multiply(s).normalize()
            val pubX = q.affineXCoord.toBigInteger()

            val pubBytes = toFixedByteArray(pubX, 32)

            return KeyPair(
                privateKeyHex = privBytes.toHex(),
                publicKeyHex = pubBytes.toHex()
            )
        }

        fun toFixedByteArray(value: BigInteger, length: Int): ByteArray {
            val raw = value.toByteArray()
            if (raw.size == length) return raw
            val out = ByteArray(length)
            if (raw.size > length) {
                System.arraycopy(raw, raw.size - length, out, 0, length)
            } else {
                System.arraycopy(raw, 0, out, length - raw.size, raw.size)
            }
            return out
        }

        fun sha256(data: ByteArray): ByteArray {
            return MessageDigest.getInstance("SHA-256").digest(data)
        }

        fun sha256Hex(data: ByteArray): String {
            return sha256(data).toHex()
        }

        fun sha256Hex(text: String): String {
            return sha256(text.toByteArray(Charsets.UTF_8)).toHex()
        }

        fun verifySignature(message: ByteArray, signatureConcatHex: String, publicKeyHex: String): Boolean {
            return try {
                val pubBytes = publicKeyHex.trim().hexToByteArray()
                val bcCurve = org.bouncycastle.jce.ECNamedCurveTable.getParameterSpec("secp256r1")
                val kf = KeyFactory.getInstance("EC")
                val derSig = concatToDer(signatureConcatHex.trim().hexToByteArray())

                for (prefix in listOf(0x02.toByte(), 0x03.toByte())) {
                    try {
                        val point = bcCurve.curve.decodePoint(byteArrayOf(prefix) + pubBytes)
                        val ecPoint = ECPoint(point.affineXCoord.toBigInteger(), point.affineYCoord.toBigInteger())
                        val pubSpec = ECPublicKeySpec(ecPoint, ecSpec)
                        val pubKey = kf.generatePublic(pubSpec)

                        val verifier = Signature.getInstance("SHA256withECDSA")
                        verifier.initVerify(pubKey)
                        verifier.update(message)
                        if (verifier.verify(derSig)) {
                            return true
                        }
                    } catch (e: Exception) {
                        // Continue to next parity prefix
                    }
                }
                false
            } catch (e: Exception) {
                false
            }
        }

        fun verifySignature(text: String, signatureConcatHex: String, publicKeyHex: String): Boolean {
            return verifySignature(text.toByteArray(Charsets.UTF_8), signatureConcatHex, publicKeyHex)
        }

        private fun concatToDer(concat: ByteArray): ByteArray {
            require(concat.size == 64) { "Expected 64 bytes signature (R || S)" }
            val r = BigInteger(1, concat.copyOfRange(0, 32))
            val s = BigInteger(1, concat.copyOfRange(32, 64))

            val rBytes = r.toByteArray()
            val sBytes = s.toByteArray()

            val der = ByteArray(6 + rBytes.size + sBytes.size)
            var idx = 0
            der[idx++] = 0x30
            der[idx++] = (4 + rBytes.size + sBytes.size).toByte()
            der[idx++] = 0x02
            der[idx++] = rBytes.size.toByte()
            System.arraycopy(rBytes, 0, der, idx, rBytes.size)
            idx += rBytes.size
            der[idx++] = 0x02
            der[idx++] = sBytes.size.toByte()
            System.arraycopy(sBytes, 0, der, idx, sBytes.size)
            return der
        }
    }

    fun signMessage(message: ByteArray): String {
        val s = BigInteger(1, privateKeyHex.hexToByteArray())
        val kf = KeyFactory.getInstance("EC")
        val privSpec = ECPrivateKeySpec(s, ecSpec)
        val privKey = kf.generatePrivate(privSpec)

        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(privKey, SecureRandom())
        signer.update(message)
        val derSig = signer.sign()

        // Decode DER ASN.1 into 64-byte R || S
        return derToConcatHex(derSig)
    }

    fun signMessage(text: String): String {
        return signMessage(text.toByteArray(Charsets.UTF_8))
    }

    private fun derToConcatHex(der: ByteArray): String {
        var offset = 0
        require(der[offset++] == 0x30.toByte())
        val len = der[offset++].toInt() and 0xFF

        require(der[offset++] == 0x02.toByte())
        val rLen = der[offset++].toInt() and 0xFF
        val rBytes = der.copyOfRange(offset, offset + rLen)
        offset += rLen

        require(der[offset++] == 0x02.toByte())
        val sLen = der[offset++].toInt() and 0xFF
        val sBytes = der.copyOfRange(offset, offset + sLen)

        val r = BigInteger(1, rBytes)
        val s = BigInteger(1, sBytes)

        val rFixed = toFixedByteArray(r, 32)
        val sFixed = toFixedByteArray(s, 32)

        return (rFixed + sFixed).toHex()
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToByteArray(): ByteArray {
    val clean = this.trim()
    val len = clean.length
    val data = ByteArray(len / 2)
    for (i in 0 until len step 2) {
        data[i / 2] = ((Character.digit(clean[i], 16) shl 4) + Character.digit(clean[i + 1], 16)).toByte()
    }
    return data
}
