package org.dattapool.capture

import com.google.gson.Gson
import org.dattapool.capture.crypto.KeyPair
import org.dattapool.capture.publication.AvailabilityRecord
import org.dattapool.capture.publication.DatasetAvailabilityPayload
import org.junit.Assert.*
import org.junit.Test

class NostrAvailabilityTest {

    @Test
    fun testCreateAvailabilityEvent() {
        val workerKey = KeyPair.generate()
        val sessionId = "833ab02adca3ac6680542d616e77bf5f"
        val manifestHash = "51b8803ea5e955e039f2df35bcc9198ae3bb6bee81ef0d5e76fac11d1e9f4ed2"
        val videoId = "abc123xyz"
        val visibility = "unlisted"
        val claimAssetId = "60dfeafdde09a8343ac58ae7332565e463469e80245ace60a981a5c07985bc88"

        val event = AvailabilityRecord.createAvailabilityEvent(
            workerKey = workerKey,
            sessionId = sessionId,
            manifestHash = manifestHash,
            videoId = videoId,
            visibility = visibility,
            captureClaimAssetId = claimAssetId
        )

        assertEquals(workerKey.publicKeyHex, event.pubkey)
        assertEquals(30078, event.kind)
        assertEquals(event.computeId(), event.id)
        assertTrue(event.sig.isNotBlank())

        // Verify signature
        val isSigValid = KeyPair.verifySignature(event.id, event.sig, workerKey.publicKeyHex)
        assertTrue("Nostr event signature must be valid", isSigValid)

        // Verify tags
        val tagMap = event.tags.associate { it[0] to it.getOrNull(1) }
        assertEquals(sessionId, tagMap["d"])
        assertEquals("dattapool", tagMap["protocol"])
        assertEquals("dataset_availability", tagMap["type"])
        assertEquals("youtube", tagMap["platform"])
        assertEquals(videoId, tagMap["video_id"])
        assertEquals(visibility, tagMap["visibility"])
        assertEquals(manifestHash, tagMap["manifest_hash"])
        assertEquals(claimAssetId, tagMap["claim_asset_id"])

        // Verify content JSON
        val payload = Gson().fromJson(event.content, DatasetAvailabilityPayload::class.java)
        assertEquals("dattapool", payload.protocol)
        assertEquals("dataset_availability", payload.type)
        assertEquals(sessionId, payload.sessionId)
        assertEquals(manifestHash, payload.manifestHash)
        assertEquals("youtube", payload.media.platform)
        assertEquals(videoId, payload.media.videoId)
        assertEquals(visibility, payload.media.visibility)
        assertEquals(claimAssetId, payload.captureClaimAssetId)
    }

    @Test
    fun testTamperedEventSignatureFails() {
        val workerKey = KeyPair.generate()
        val event = AvailabilityRecord.createAvailabilityEvent(
            workerKey = workerKey,
            sessionId = "session_1",
            manifestHash = "hash_1",
            videoId = "vid_1",
            visibility = "unlisted"
        )

        // Tamper with content
        event.content = event.content.replace("unlisted", "public")

        // Recomputed ID should no longer match signed ID
        assertNotEquals(event.id, event.computeId())

        // If someone tampers with event.id, signature verification fails
        val tamperedId = event.computeId()
        assertFalse(KeyPair.verifySignature(tamperedId, event.sig, workerKey.publicKeyHex))
    }
}
