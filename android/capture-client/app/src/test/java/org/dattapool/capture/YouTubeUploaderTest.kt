package org.dattapool.capture

import kotlinx.coroutines.runBlocking
import org.dattapool.capture.youtube.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class YouTubeUploaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testMockUploadSuccess() = runBlocking {
        val testVideoFile = tempFolder.newFile("capture.mp4").apply {
            writeBytes(ByteArray(1024 * 512) { 0x42 }) // 512 KB
        }

        val uploader = DefaultYouTubeUploader()
        var lastProgress = 0

        val result = uploader.uploadVideo(
            videoFile = testVideoFile,
            title = "DattaPool Capture | manipulation | P4 | 833ab02a",
            description = "Test description",
            tags = listOf("dattapool", "robotics"),
            requestedVisibility = YouTubeVisibility.UNLISTED,
            accessToken = "mock_test_token",
            onProgress = { percent, bytes, total ->
                lastProgress = percent
            }
        )

        assertTrue(result is YouTubeUploadResult.Success)
        val success = result as YouTubeUploadResult.Success
        assertTrue(success.videoId.isNotBlank())
        assertEquals("unlisted", success.requestedVisibility)
        assertEquals("unlisted", success.actualVisibility)
        assertEquals(100, lastProgress)
    }

    @Test
    fun testUploadMissingFileFails() = runBlocking {
        val nonExistentFile = File(tempFolder.root, "does_not_exist.mp4")
        val uploader = DefaultYouTubeUploader()

        val result = uploader.uploadVideo(
            videoFile = nonExistentFile,
            title = "Test",
            description = "Test",
            tags = emptyList(),
            requestedVisibility = YouTubeVisibility.UNLISTED,
            accessToken = "mock_test_token"
        )

        assertTrue(result is YouTubeUploadResult.Failure)
        val failure = result as YouTubeUploadResult.Failure
        assertFalse(failure.isRecoverable)
    }

    @Test
    fun testRequestedVisibilityConversion() {
        assertEquals("unlisted", YouTubeVisibility.UNLISTED.apiValue)
        assertEquals("public", YouTubeVisibility.PUBLIC.apiValue)
        assertEquals("private", YouTubeVisibility.PRIVATE.apiValue)

        assertEquals(YouTubeVisibility.UNLISTED, YouTubeVisibility.fromString("unlisted"))
        assertEquals(YouTubeVisibility.PUBLIC, YouTubeVisibility.fromString("PUBLIC"))
        assertEquals(YouTubeVisibility.PRIVATE, YouTubeVisibility.fromString("Private"))
        assertEquals(YouTubeVisibility.UNLISTED, YouTubeVisibility.fromString("unknown_fallback"))
    }
}
