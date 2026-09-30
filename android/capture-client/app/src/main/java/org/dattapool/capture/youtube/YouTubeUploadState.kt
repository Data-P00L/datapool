package org.dattapool.capture.youtube

import com.google.gson.annotations.SerializedName

/**
 * Visibility options supported for YouTube publication.
 */
enum class YouTubeVisibility(val apiValue: String, val displayLabel: String) {
    @SerializedName("unlisted")
    UNLISTED("unlisted", "Unlisted"),

    @SerializedName("public")
    PUBLIC("public", "Public"),

    @SerializedName("private")
    PRIVATE("private", "Private");

    companion object {
        fun fromString(value: String): YouTubeVisibility {
            return entries.firstOrNull { it.apiValue.equals(value, ignoreCase = true) } ?: UNLISTED
        }
    }
}

/**
 * Explicit state progression for YouTube publication.
 */
enum class YouTubeUploadStatus {
    NOT_PUBLISHED,
    AUTH_REQUIRED,
    AUTHORIZING,
    READY,
    UPLOADING,
    PROCESSING,
    PUBLISHED,
    NOSTR_ANNOUNCING,
    AVAILABLE,
    FAILED
}

/**
 * Detailed progress state during an active upload.
 */
data class UploadProgress(
    val status: YouTubeUploadStatus = YouTubeUploadStatus.NOT_PUBLISHED,
    val progressPercent: Int = 0,
    val bytesUploaded: Long = 0L,
    val totalBytes: Long = 0L,
    val errorMessage: String? = null
)
