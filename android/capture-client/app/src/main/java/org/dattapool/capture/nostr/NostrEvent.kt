package org.dattapool.capture.nostr

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import org.dattapool.capture.crypto.CanonicalJson
import org.dattapool.capture.crypto.KeyPair
import org.dattapool.capture.crypto.toHex

data class NostrEvent(
    @SerializedName("id") var id: String = "",
    @SerializedName("pubkey") var pubkey: String = "",
    @SerializedName("created_at") var createdAt: Long = 0,
    @SerializedName("kind") var kind: Int = 30078,
    @SerializedName("tags") var tags: List<List<String>> = emptyList(),
    @SerializedName("content") var content: String = "",
    @SerializedName("sig") var sig: String = ""
) {
    fun computeId(): String {
        val arr = listOf(
            0,
            pubkey,
            createdAt,
            kind,
            tags,
            content
        )
        val json = Gson().toJson(arr)
        return KeyPair.sha256Hex(json.toByteArray(Charsets.UTF_8))
    }

    fun sign(workerKey: KeyPair) {
        pubkey = workerKey.publicKeyHex
        id = computeId()
        sig = workerKey.signMessage(id)
    }

    companion object {
        fun createSessionStartEvent(
            workerKey: KeyPair,
            sessionId: String,
            devicePubKey: String,
            sessionPubKey: String,
            nonceCommitment: String,
            freshnessType: String,
            blockHeight: Long,
            blockHash: String,
            skillTag: String,
            createdAt: Long = System.currentTimeMillis() / 1000
        ): NostrEvent {
            val contentMap = mapOf(
                "protocol" to "dattapool",
                "schema_version" to "0.4.0",
                "event_type" to "capture_session_start",
                "session_id" to sessionId,
                "device_pubkey" to devicePubKey,
                "session_pubkey" to sessionPubKey,
                "nonce_commitment" to nonceCommitment,
                "freshness" to mapOf(
                    "type" to freshnessType,
                    "block_height" to blockHeight,
                    "block_hash" to blockHash
                ),
                "skill_tag" to skillTag
            )
            val contentJson = CanonicalJson.canonicalize(contentMap)

            val tags = listOf(
                listOf("d", sessionId),
                listOf("protocol", "dattapool"),
                listOf("version", "0.4.0"),
                listOf("device", devicePubKey),
                listOf("session", sessionPubKey),
                listOf("commitment", nonceCommitment),
                listOf("freshness_type", freshnessType),
                listOf("block_height", blockHeight.toString()),
                listOf("block_hash", blockHash),
                listOf("skill", skillTag)
            )

            val event = NostrEvent(
                createdAt = createdAt,
                kind = 30078,
                tags = tags,
                content = contentJson
            )
            event.sign(workerKey)
            return event
        }
    }
}
