package org.dattapool.capture.crypto

/**
 * Binary Merkle Tree implementation matching Go's internal/crypto/merkle.go.
 */
object MerkleTree {

    fun computeRoot(leafHashes: List<String>): String {
        require(leafHashes.isNotEmpty()) { "Empty leaf hashes" }

        var current = leafHashes.map { it.hexToByteArray() }

        while (current.size > 1) {
            val next = mutableListOf<ByteArray>()
            var i = 0
            while (i < current.size) {
                val left = current[i]
                val right = if (i + 1 < current.size) current[i + 1] else current[i]
                val combined = left + right
                next.add(KeyPair.sha256(combined))
                i += 2
            }
            current = next
        }

        return current[0].toHex()
    }
}
