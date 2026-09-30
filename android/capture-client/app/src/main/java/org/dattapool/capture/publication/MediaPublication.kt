package org.dattapool.capture.publication

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import org.dattapool.capture.crypto.CanonicalJson
import org.dattapool.capture.crypto.KeyPair

/**
 * Protocol-level structure representing the publication of a DattaPool capture to an external media platform.
 */
data class MediaPublication(
    @SerializedName("protocol") val protocol: String = "dattapool",
    @SerializedName("type") val type: String = "media_publication",
    @SerializedName("schema_version") val schemaVersion: String = "0.1.0",

    @SerializedName("session_id") val sessionId: String,
    @SerializedName("manifest_hash") val manifestHash: String,

    @SerializedName("capture_claim_asset_id") val captureClaimAssetId: String? = null,

    @SerializedName("platform") val platform: String = "youtube",
    @SerializedName("video_id") val videoId: String,
    @SerializedName("requested_visibility") val requestedVisibility: String = "unlisted",
    @SerializedName("actual_visibility") val actualVisibility: String = "unlisted",
    @SerializedName("watch_url") val watchUrl: String = "https://youtu.be/$videoId",

    @SerializedName("source_video_sha256") val sourceVideoSha256: String,

    @SerializedName("published_by_nostr_pubkey") val publishedByNostrPubkey: String,
    @SerializedName("published_at") val publishedAt: Long = System.currentTimeMillis() / 1000,
    @SerializedName("source_bundle_uri") val sourceBundleUri: String? = null,
    @SerializedName("nostr_availability_event_id") val nostrAvailabilityEventId: String? = null
) {
    fun toPrettyJson(): String {
        return GsonBuilder().setPrettyPrinting().create().toJson(this)
    }

    fun toCanonicalJson(): String {
        return CanonicalJson.canonicalize(this)
    }

    fun computeSha256(): String {
        return KeyPair.sha256Hex(toCanonicalJson().toByteArray(Charsets.UTF_8))
    }

    companion object {
        fun fromJson(json: String): MediaPublication {
            return Gson().fromJson(json, MediaPublication::class.java)
        }
    }
}
