package org.dattapool.capture.publication

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dattapool.capture.nostr.NostrEvent
import java.io.File

/**
 * Repository interface for managing persistent media publication records associated with capture sessions.
 */
interface PublicationRepository {
    suspend fun savePublication(sessionDir: File, publication: MediaPublication, nostrEvent: NostrEvent? = null)
    suspend fun getPublication(sessionDir: File): MediaPublication?
    suspend fun getPublicationBySessionId(sessionId: String): MediaPublication?
    suspend fun getNostrAvailabilityEvent(sessionDir: File): NostrEvent?
    suspend fun isPublished(sessionDir: File): Boolean
}

class LocalPublicationRepository(
    private val context: Context
) : PublicationRepository {

    companion object {
        private const val TAG = "PublicationRepo"
        const val PUBLICATION_FILE_NAME = "publication.json"
        const val AVAILABILITY_EVENT_FILE_NAME = "nostr-availability-event.json"
    }

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    override suspend fun savePublication(
        sessionDir: File,
        publication: MediaPublication,
        nostrEvent: NostrEvent?
    ) {
        withContext(Dispatchers.IO) {
            try {
                if (!sessionDir.exists()) {
                    sessionDir.mkdirs()
                }
                val pubFile = File(sessionDir, PUBLICATION_FILE_NAME)
                pubFile.writeText(gson.toJson(publication))

                if (nostrEvent != null) {
                    val eventFile = File(sessionDir, AVAILABILITY_EVENT_FILE_NAME)
                    eventFile.writeText(gson.toJson(nostrEvent))
                }

                // Also persist index in shared preferences for quick lookup
                val prefs = context.getSharedPreferences("dattapool_publications", Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("pub_${publication.sessionId}", gson.toJson(publication))
                    .putString("session_dir_${publication.sessionId}", sessionDir.absolutePath)
                    .apply()

                Log.i(TAG, "Saved publication record for session ${publication.sessionId} (Video ID: ${publication.videoId})")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save publication record for session ${publication.sessionId}", e)
                throw e
            }
        }
    }

    override suspend fun getPublication(sessionDir: File): MediaPublication? = withContext(Dispatchers.IO) {
        try {
            val pubFile = File(sessionDir, PUBLICATION_FILE_NAME)
            if (pubFile.exists() && pubFile.length() > 0) {
                val content = pubFile.readText()
                gson.fromJson(content, MediaPublication::class.java)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error loading publication file from ${sessionDir.name}: ${e.message}")
            null
        }
    }

    override suspend fun getPublicationBySessionId(sessionId: String): MediaPublication? = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("dattapool_publications", Context.MODE_PRIVATE)
        val json = prefs.getString("pub_$sessionId", null)
        if (json != null) {
            try {
                return@withContext gson.fromJson(json, MediaPublication::class.java)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse publication from prefs for $sessionId: ${e.message}")
            }
        }

        // Fallback: check session directory
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val sessionDir = File(baseDir, "session-$sessionId")
        getPublication(sessionDir)
    }

    override suspend fun getNostrAvailabilityEvent(sessionDir: File): NostrEvent? = withContext(Dispatchers.IO) {
        try {
            val eventFile = File(sessionDir, AVAILABILITY_EVENT_FILE_NAME)
            if (eventFile.exists() && eventFile.length() > 0) {
                val content = eventFile.readText()
                gson.fromJson(content, NostrEvent::class.java)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error loading availability event from ${sessionDir.name}: ${e.message}")
            null
        }
    }

    override suspend fun isPublished(sessionDir: File): Boolean {
        return getPublication(sessionDir) != null
    }
}
