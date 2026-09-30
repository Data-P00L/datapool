package org.dattapool.capture.nostr

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class FreshnessResponse(
    val type: String = "local_only",
    val block_height: Long = 0,
    val block_hash: String = ""
)

data class PublishResponse(
    val success: Boolean = false,
    val event_id: String = "",
    val message: String = ""
)

class WitnessClient(
    private val baseUrl: String = "http://127.0.0.1:8090"
) {

    suspend fun queryFreshness(): FreshnessResponse = withContext(Dispatchers.IO) {
        try {
            val url = URL("$baseUrl/freshness")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "GET"

            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                Gson().fromJson(body, FreshnessResponse::class.java)
            } else {
                FreshnessResponse(type = "local_only")
            }
        } catch (e: Exception) {
            FreshnessResponse(type = "local_only")
        }
    }

    suspend fun publishEvent(event: NostrEvent): PublishResponse = withContext(Dispatchers.IO) {
        try {
            val url = URL("$baseUrl/nostr/event")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val json = Gson().toJson(event)
            OutputStreamWriter(conn.outputStream).use { it.write(json) }

            if (conn.responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                Gson().fromJson(body, PublishResponse::class.java)
            } else {
                PublishResponse(success = false, message = "HTTP ${conn.responseCode}")
            }
        } catch (e: Exception) {
            PublishResponse(success = false, message = e.message ?: "Connection failed")
        }
    }
}
