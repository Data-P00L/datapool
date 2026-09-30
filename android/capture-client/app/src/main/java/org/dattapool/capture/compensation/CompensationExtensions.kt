package org.dattapool.capture.compensation

import org.dattapool.capture.crypto.CanonicalJson
import org.dattapool.capture.crypto.KeyPair

/**
 * Deterministically serializes the CompensationReceipt to RFC-8785 canonical JSON.
 * UTF-8, no pretty-print whitespace, deterministic field ordering, integer atomic amounts.
 */
fun CompensationReceipt.toCanonicalJson(): String {
    return CanonicalJson.canonicalize(this)
}

/**
 * Computes the lowercase hexadecimal SHA-256 hash of the canonical JSON serialization.
 */
fun CompensationReceipt.computeSha256(): String {
    val canonicalBytes = toCanonicalJson().toByteArray(Charsets.UTF_8)
    return KeyPair.sha256Hex(canonicalBytes)
}
