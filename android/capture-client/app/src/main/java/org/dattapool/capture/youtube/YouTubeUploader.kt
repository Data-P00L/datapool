package org.dattapool.capture.youtube

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.dattapool.capture.crypto.KeyPair
import java.io.*
import java.net.HttpURLConnection
import java.net.URL

/**
 * Result of a YouTube video upload attempt.
 */
sealed class YouTubeUploadResult {
    data class Success(
        val videoId: String,
        val watchUrl: String,
        val requestedVisibility: String,
        val actualVisibility: String,
        val uploadedAt: Long = System.currentTimeMillis() / 1000
    ) : YouTubeUploadResult()

    data class Failure(
        val errorMessage: String,
        val isRecoverable: Boolean = true
    ) : YouTubeUploadResult()
}

/**
 * Interface defining YouTube video upload operations.
 */
interface YouTubeUploader {
    suspend fun uploadVideo(
        videoFile: File,
        title: String,
        description: String,
        tags: List<String>,
        requestedVisibility: YouTubeVisibility,
        accessToken: String,
        onProgress: ((progressPercent: Int, bytesUploaded: Long, totalBytes: Long) -> Unit)? = null
    ): YouTubeUploadResult
}

/**
 * Default implementation of YouTubeUploader supporting Resumable Upload protocol.
 */
class DefaultYouTubeUploader : YouTubeUploader {

    companion object {
        private const val TAG = "YouTubeUploader"
        private const val RESUMABLE_UPLOAD_INIT_URL =
            "https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&part=snippet,status"
        private const val CHUNK_SIZE = 1024 * 1024 // 1MB chunks for resumable streaming
    }

    override suspend fun uploadVideo(
        videoFile: File,
        title: String,
        description: String,
        tags: List<String>,
        requestedVisibility: YouTubeVisibility,
        accessToken: String,
        onProgress: ((progressPercent: Int, bytesUploaded: Long, totalBytes: Long) -> Unit)?
    ): YouTubeUploadResult = withContext(Dispatchers.IO) {
        if (!videoFile.exists() || videoFile.length() == 0L) {
            return@withContext YouTubeUploadResult.Failure("Video file does not exist or is empty", isRecoverable = false)
        }

        val totalBytes = videoFile.length()

        // Handle mock / test token gracefully
        if (accessToken.startsWith("ya29.dattapool_test_token_") || accessToken == "mock_test_token") {
            return@withContext executeMockUpload(
                videoFile = videoFile,
                requestedVisibility = requestedVisibility,
                totalBytes = totalBytes,
                onProgress = onProgress
            )
        }

        try {
            // Step 1: Initiate Resumable Upload Session
            val initUrl = URL(RESUMABLE_UPLOAD_INIT_URL)
            val initConn = initUrl.openConnection() as HttpURLConnection
            initConn.requestMethod = "POST"
            initConn.connectTimeout = 15000
            initConn.readTimeout = 15000
            initConn.doOutput = true
            initConn.setRequestProperty("Authorization", "Bearer $accessToken")
            initConn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            initConn.setRequestProperty("X-Upload-Content-Length", totalBytes.toString())
            initConn.setRequestProperty("X-Upload-Content-Type", "video/mp4")

            // Build metadata payload
            val metadataJson = buildMetadataPayload(title, description, tags, requestedVisibility)
            OutputStreamWriter(initConn.outputStream, Charsets.UTF_8).use { it.write(metadataJson) }

            val initResponseCode = initConn.responseCode
            if (initResponseCode != 200 && initResponseCode != 201) {
                val errorBody = initConn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $initResponseCode"
                Log.e(TAG, "Failed to initiate resumable upload ($initResponseCode): $errorBody")
                
                val parsedError = if (errorBody.contains("youtubeSignupRequired", ignoreCase = true)) {
                    "YouTube Channel Required: The Google account does not have a YouTube channel created yet. Please visit https://www.youtube.com/create_channel to create your channel, then retry."
                } else if (errorBody.contains("quotaExceeded", ignoreCase = true)) {
                    "YouTube API Quota Exceeded: The daily upload quota for this API project has been reached."
                } else if (errorBody.contains("invalid_token", ignoreCase = true) || errorBody.contains("ACCESS_TOKEN_SCOPE_INSUFFICIENT", ignoreCase = true)) {
                    "OAuth Token Invalid or Scope Insufficient: Please generate a fresh token with 'youtube.upload' scope."
                } else {
                    "Upload initiation failed ($initResponseCode): $errorBody"
                }

                return@withContext YouTubeUploadResult.Failure(parsedError)
            }

            val uploadLocation = initConn.getHeaderField("Location")
            if (uploadLocation.isNullOrBlank()) {
                return@withContext YouTubeUploadResult.Failure("No upload location returned by YouTube API")
            }

            // Step 2: Stream Video Content via Resumable Location
            return@withContext streamVideoChunks(
                uploadLocation = uploadLocation,
                videoFile = videoFile,
                totalBytes = totalBytes,
                requestedVisibility = requestedVisibility,
                onProgress = onProgress
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception during YouTube video upload", e)
            YouTubeUploadResult.Failure(e.message ?: "Network error during upload", isRecoverable = true)
        }
    }

    private fun buildMetadataPayload(
        title: String,
        description: String,
        tags: List<String>,
        visibility: YouTubeVisibility
    ): String {
        val snippet = JsonObject().apply {
            addProperty("title", title)
            addProperty("description", description)
            addProperty("categoryId", "28") // Science & Technology
            val tagsArray = com.google.gson.JsonArray()
            tags.forEach { tagsArray.add(it) }
            add("tags", tagsArray)
        }

        val status = JsonObject().apply {
            addProperty("privacyStatus", visibility.apiValue)
            addProperty("selfDeclaredMadeForKids", false)
        }

        val root = JsonObject().apply {
            add("snippet", snippet)
            add("status", status)
        }

        return root.toString()
    }

    private suspend fun streamVideoChunks(
        uploadLocation: String,
        videoFile: File,
        totalBytes: Long,
        requestedVisibility: YouTubeVisibility,
        onProgress: ((progressPercent: Int, bytesUploaded: Long, totalBytes: Long) -> Unit)?
    ): YouTubeUploadResult {
        var bytesUploaded = 0L
        val inputStream = FileInputStream(videoFile)

        try {
            val buffer = ByteArray(CHUNK_SIZE)
            var bytesRead: Int

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                val chunkEnd = bytesUploaded + bytesRead - 1
                val chunkConn = URL(uploadLocation).openConnection() as HttpURLConnection
                chunkConn.requestMethod = "PUT"
                chunkConn.connectTimeout = 30000
                chunkConn.readTimeout = 30000
                chunkConn.doOutput = true
                chunkConn.setRequestProperty("Content-Type", "video/mp4")
                chunkConn.setRequestProperty("Content-Length", bytesRead.toString())
                chunkConn.setRequestProperty("Content-Range", "bytes $bytesUploaded-$chunkEnd/$totalBytes")

                chunkConn.outputStream.use { out ->
                    out.write(buffer, 0, bytesRead)
                }

                val responseCode = chunkConn.responseCode
                bytesUploaded += bytesRead
                val progressPercent = ((bytesUploaded.toDouble() / totalBytes.toDouble()) * 100).toInt().coerceIn(0, 100)
                onProgress?.invoke(progressPercent, bytesUploaded, totalBytes)

                // 308 Resume Incomplete is expected during chunk uploads
                if (responseCode == 308) {
                    continue
                } else if (responseCode == 200 || responseCode == 201) {
                    // Upload completed! Parse final video resource
                    val responseBody = chunkConn.inputStream.bufferedReader().use { it.readText() }
                    return parseVideoResponse(responseBody, requestedVisibility)
                } else {
                    val errorText = chunkConn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $responseCode"
                    return YouTubeUploadResult.Failure("Upload failed ($responseCode): $errorText")
                }
            }

            return YouTubeUploadResult.Failure("Upload terminated unexpectedly before finalization")
        } finally {
            inputStream.close()
        }
    }

    private fun parseVideoResponse(
        responseBody: String,
        requestedVisibility: YouTubeVisibility
    ): YouTubeUploadResult {
        try {
            val json = Gson().fromJson(responseBody, JsonObject::class.java)
            val videoId = json.get("id")?.asString ?: return YouTubeUploadResult.Failure("Missing video ID in response")

            val statusObj = json.getAsJsonObject("status")
            val returnedPrivacy = statusObj?.get("privacyStatus")?.asString ?: requestedVisibility.apiValue

            val watchUrl = "https://youtu.be/$videoId"
            Log.i(TAG, "YouTube upload succeeded: Video ID = $videoId, requested = ${requestedVisibility.apiValue}, actual = $returnedPrivacy")

            return YouTubeUploadResult.Success(
                videoId = videoId,
                watchUrl = watchUrl,
                requestedVisibility = requestedVisibility.apiValue,
                actualVisibility = returnedPrivacy
            )
        } catch (e: Exception) {
            return YouTubeUploadResult.Failure("Failed to parse YouTube response: ${e.message}")
        }
    }

    private suspend fun executeMockUpload(
        videoFile: File,
        requestedVisibility: YouTubeVisibility,
        totalBytes: Long,
        onProgress: ((progressPercent: Int, bytesUploaded: Long, totalBytes: Long) -> Unit)?
    ): YouTubeUploadResult {
        Log.i(TAG, "Executing simulated YouTube upload for testing (file size: $totalBytes bytes)")

        // Simulate progress stages
        val steps = listOf(15, 45, 75, 100)
        for (p in steps) {
            delay(100)
            val currentBytes = ((totalBytes * p) / 100)
            onProgress?.invoke(p, currentBytes, totalBytes)
        }

        // Generate deterministic video ID based on file hash and timestamp
        val fileHash = KeyPair.sha256Hex(videoFile.name.toByteArray())
        val mockVideoId = "dp_" + fileHash.take(8) + (System.currentTimeMillis() % 1000)

        // For testing requested vs actual visibility restriction, default to matching requested
        val actualVisibility = requestedVisibility.apiValue

        return YouTubeUploadResult.Success(
            videoId = mockVideoId,
            watchUrl = "https://youtu.be/$mockVideoId",
            requestedVisibility = requestedVisibility.apiValue,
            actualVisibility = actualVisibility
        )
    }
}
