package org.dattapool.capture.publication

import com.google.gson.annotations.SerializedName
import org.dattapool.capture.crypto.CanonicalJson
import org.dattapool.capture.crypto.KeyPair
import org.dattapool.capture.nostr.NostrEvent

/**
 * Media metadata embedded within dataset availability announcements.
 */
data class MediaInfo(
    @SerializedName("platform") val platform: String = "youtube",
    @SerializedName("video_id") val videoId: String,
    @SerializedName("visibility") val visibility: String
)

/**
 * Payload content for Nostr dataset availability announcements.
 */
data class DatasetAvailabilityPayload(
    @SerializedName("protocol") val protocol: String = "dattapool",
    @SerializedName("type") val type: String = "dataset_availability",
    @SerializedName("schema_version") val schemaVersion: String = "0.1.0",
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("manifest_hash") val manifestHash: String,
    @SerializedName("media") val media: MediaInfo,
    @SerializedName("capture_claim_asset_id") val captureClaimAssetId: String? = null,
    @SerializedName("source_bundle_uri") val sourceBundleUri: String? = null
)

/**
 * Factory for creating signed Nostr availability announcement events.
 */
object AvailabilityRecord {

    /**
     * Builds and signs a Nostr Kind 30078 event announcing dataset availability on YouTube.
     */
    fun createAvailabilityEvent(
        workerKey: KeyPair,
        sessionId: String,
        manifestHash: String,
        videoId: String,
        visibility: String,
        captureClaimAssetId: String? = null,
        sourceBundleUri: String? = null,
        createdAt: Long = System.currentTimeMillis() / 1000
    ): NostrEvent {
        val payload = DatasetAvailabilityPayload(
            sessionId = sessionId,
            manifestHash = manifestHash,
            media = MediaInfo(platform = "youtube", videoId = videoId, visibility = visibility),
            captureClaimAssetId = captureClaimAssetId,
            sourceBundleUri = sourceBundleUri
        )

        val contentJson = CanonicalJson.canonicalize(payload)

        val tags = mutableListOf(
            listOf("d", sessionId),
            listOf("protocol", "dattapool"),
            listOf("type", "dataset_availability"),
            listOf("platform", "youtube"),
            listOf("video_id", videoId),
            listOf("visibility", visibility),
            listOf("manifest_hash", manifestHash)
        )

        if (!captureClaimAssetId.isNullOrBlank()) {
            tags.add(listOf("claim_asset_id", captureClaimAssetId))
        }

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
