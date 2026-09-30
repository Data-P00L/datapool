package org.dattapool.capture.crypto

import java.security.MessageDigest
import java.util.Locale

data class ChunkTemporalBinding(
    val index: Int,
    val startOffsetSec: Double,
    val endOffsetSec: Double,
    val videoChunkHash: String,
    val telemetryChunkHash: String
)

class ContinuousHashChain(
    val sessionId: String,
    val nonceCommitmentHex: String
) {
    var currentHash: ByteArray
    val history = mutableListOf<String>()

    init {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("DATTA_CAPTURE_CHUNK_V1".toByteArray(Charsets.UTF_8))
        md.update(sessionId.toByteArray(Charsets.UTF_8))
        md.update(nonceCommitmentHex.toByteArray(Charsets.UTF_8))
        currentHash = md.digest()
        history.add(currentHash.toHex())
    }

    fun appendChunk(chunk: ChunkTemporalBinding) {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("DATTA_CAPTURE_CHUNK_V1".toByteArray(Charsets.UTF_8))
        md.update(currentHash)
        md.update(sessionId.toByteArray(Charsets.UTF_8))
        md.update(chunk.index.toString().toByteArray(Charsets.UTF_8))
        md.update(String.format(Locale.US, "%.3f", chunk.startOffsetSec).toByteArray(Charsets.UTF_8))
        md.update(String.format(Locale.US, "%.3f", chunk.endOffsetSec).toByteArray(Charsets.UTF_8))
        md.update(chunk.videoChunkHash.hexToByteArray())
        md.update(chunk.telemetryChunkHash.hexToByteArray())

        currentHash = md.digest()
        history.add(currentHash.toHex())
    }

    fun rootHex(): String = currentHash.toHex()
}
