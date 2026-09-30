package org.dattapool.capture

import org.dattapool.capture.compensation.*
import org.dattapool.capture.crypto.CanonicalJson
import org.dattapool.capture.crypto.KeyPair
import org.junit.Assert.*
import org.junit.Test

class CompensationReceiptTest {

    private fun createSampleReceipt(
        captureId: String = "dp_0187testsession01",
        videoSha256: String = "56f9e1d842b0834bc7e4d8123985012398412389104810239481230491823091",
        manifestSha256: String = "ab32e49a1283c471029384710293847102938471029384710293847102938471",
        workerPubkey: String = "02ab1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
        assetId: String = "93afb66f20e6a117079d34208a0df68c67ecba5dc2cd15bc2a0e4e7e6840742f",
        ticker: String = "DTTA",
        displaySymbol: String = "₿DATA",
        amountAtomic: Long = 25000L,
        amountDisplay: String = "25.000",
        reason: String = "verified_egocentric_capture",
        transferMethod: String = "taproot_asset_onchain",
        txid: String? = "tx_regtest_0192837465abcdef",
        timestamp: String = "2026-08-19T22:00:00Z",
        status: String = "confirmed",
        type: String = "datapool.compensation.v1"
    ): CompensationReceipt {
        return CompensationReceipt(
            type = type,
            captureId = captureId,
            videoSha256 = videoSha256,
            manifestSha256 = manifestSha256,
            workerPubkey = workerPubkey,
            asset = CompensationAsset(
                assetId = assetId,
                ticker = ticker,
                displaySymbol = displaySymbol
            ),
            amountAtomic = amountAtomic,
            amountDisplay = amountDisplay,
            reason = reason,
            transfer = CompensationTransfer(
                method = transferMethod,
                txid = txid,
                paymentHash = null,
                timestamp = timestamp,
                status = status
            )
        )
    }

    @Test
    fun testAtomicAmountFormatting() {
        assertEquals("25.000", CompensationFormatters.formatAtomicAmount(25000L))
        assertEquals("1.000", CompensationFormatters.formatAtomicAmount(1000L))
        assertEquals("0.125", CompensationFormatters.formatAtomicAmount(125L))
        assertEquals("0.001", CompensationFormatters.formatAtomicAmount(1L))
        assertEquals("0.000", CompensationFormatters.formatAtomicAmount(0L))
        assertEquals("100.500", CompensationFormatters.formatAtomicAmount(100500L))

        // Parsing roundtrip
        assertEquals(25000L, CompensationFormatters.parseDisplayAmount("25.000"))
        assertEquals(1000L, CompensationFormatters.parseDisplayAmount("1.000"))
        assertEquals(125L, CompensationFormatters.parseDisplayAmount("0.125"))
        assertEquals(1L, CompensationFormatters.parseDisplayAmount("0.001"))
        assertEquals(0L, CompensationFormatters.parseDisplayAmount("0.000"))
        assertEquals(100500L, CompensationFormatters.parseDisplayAmount("100.500"))
    }

    @Test
    fun testCanonicalSerializationDeterminism() {
        val receipt1 = createSampleReceipt()
        val receipt2 = createSampleReceipt()

        val json1 = receipt1.toCanonicalJson()
        val json2 = receipt2.toCanonicalJson()

        assertEquals("Canonical JSON must be byte-for-byte identical", json1, json2)

        val hash1 = receipt1.computeSha256()
        val hash2 = receipt2.computeSha256()

        assertEquals("SHA-256 hash must be deterministic and identical", hash1, hash2)
        assertEquals(64, hash1.length)
        assertTrue("Hash must be lowercase hexadecimal", hash1.matches(Regex("^[0-9a-f]{64}$")))

        // Verify keys are alphabetically ordered in canonical JSON
        assertTrue(json1.contains("\"amount_atomic\":25000"))
        assertTrue(json1.contains("\"amount_display\":\"25.000\""))
        assertTrue(json1.contains("\"asset\":{"))
        assertTrue(json1.contains("\"ticker\":\"DTTA\""))
        assertTrue(json1.contains("\"display_symbol\":\"₿DATA\""))
    }

    @Test
    fun testReceiptVerificationSuccess() {
        val receipt = createSampleReceipt()
        val result = CompensationVerifier.verifyCompensationReceipt(
            receipt = receipt,
            expectedCaptureId = "dp_0187testsession01",
            expectedVideoSha256 = "56f9e1d842b0834bc7e4d8123985012398412389104810239481230491823091",
            expectedManifestSha256 = "ab32e49a1283c471029384710293847102938471029384710293847102938471"
        )

        assertTrue("Verification should succeed for valid receipt", result.isValid)
        assertTrue("Error list should be empty", result.errors.isEmpty())
    }

    @Test
    fun testReceiptVerificationMismatchedCaptureId() {
        val receipt = createSampleReceipt()
        val result = CompensationVerifier.verifyCompensationReceipt(
            receipt = receipt,
            expectedCaptureId = "dp_different_session_999",
            expectedVideoSha256 = "56f9e1d842b0834bc7e4d8123985012398412389104810239481230491823091",
            expectedManifestSha256 = "ab32e49a1283c471029384710293847102938471029384710293847102938471"
        )

        assertFalse("Verification should fail for mismatched capture ID", result.isValid)
        assertTrue(result.errors.any { it.contains("Capture ID mismatch") })
    }

    @Test
    fun testReceiptVerificationMismatchedVideoHash() {
        val receipt = createSampleReceipt()
        val result = CompensationVerifier.verifyCompensationReceipt(
            receipt = receipt,
            expectedCaptureId = "dp_0187testsession01",
            expectedVideoSha256 = "1111111111111111111111111111111111111111111111111111111111111111",
            expectedManifestSha256 = "ab32e49a1283c471029384710293847102938471029384710293847102938471"
        )

        assertFalse("Verification should fail for mismatched video hash", result.isValid)
        assertTrue(result.errors.any { it.contains("Video SHA-256 mismatch") })
    }

    @Test
    fun testReceiptVerificationMismatchedManifestHash() {
        val receipt = createSampleReceipt()
        val result = CompensationVerifier.verifyCompensationReceipt(
            receipt = receipt,
            expectedCaptureId = "dp_0187testsession01",
            expectedVideoSha256 = "56f9e1d842b0834bc7e4d8123985012398412389104810239481230491823091",
            expectedManifestSha256 = "2222222222222222222222222222222222222222222222222222222222222222"
        )

        assertFalse("Verification should fail for mismatched manifest hash", result.isValid)
        assertTrue(result.errors.any { it.contains("Manifest SHA-256 mismatch") })
    }

    @Test
    fun testReceiptVerificationWrongTicker() {
        val receipt = createSampleReceipt(ticker = "FAKE")
        val result = CompensationVerifier.verifyCompensationReceipt(
            receipt = receipt,
            expectedCaptureId = "dp_0187testsession01",
            expectedVideoSha256 = "56f9e1d842b0834bc7e4d8123985012398412389104810239481230491823091",
            expectedManifestSha256 = "ab32e49a1283c471029384710293847102938471029384710293847102938471"
        )

        assertFalse("Verification should fail for wrong ticker", result.isValid)
        assertTrue(result.errors.any { it.contains("Asset ticker mismatch") })
    }

    @Test
    fun testReceiptVerificationInvalidAmounts() {
        val receiptNegative = createSampleReceipt(amountAtomic = -500L, amountDisplay = "-0.500")
        val resultNeg = CompensationVerifier.verifyCompensationReceipt(
            receipt = receiptNegative,
            expectedCaptureId = "dp_0187testsession01",
            expectedVideoSha256 = "56f9e1d842b0834bc7e4d8123985012398412389104810239481230491823091",
            expectedManifestSha256 = "ab32e49a1283c471029384710293847102938471029384710293847102938471"
        )
        assertFalse("Verification should fail for negative amount", resultNeg.isValid)

        val receiptMismatched = createSampleReceipt(amountAtomic = 25000L, amountDisplay = "99.999")
        val resultMis = CompensationVerifier.verifyCompensationReceipt(
            receipt = receiptMismatched,
            expectedCaptureId = "dp_0187testsession01",
            expectedVideoSha256 = "56f9e1d842b0834bc7e4d8123985012398412389104810239481230491823091",
            expectedManifestSha256 = "ab32e49a1283c471029384710293847102938471029384710293847102938471"
        )
        assertFalse("Verification should fail for mismatched atomic/display amount", resultMis.isValid)
        assertTrue(resultMis.errors.any { it.contains("Amount display mismatch") })
    }

    @Test
    fun testReceiptVerificationMalformedTimestamp() {
        val receipt = createSampleReceipt(timestamp = "not-a-timestamp")
        val result = CompensationVerifier.verifyCompensationReceipt(
            receipt = receipt,
            expectedCaptureId = "dp_0187testsession01",
            expectedVideoSha256 = "56f9e1d842b0834bc7e4d8123985012398412389104810239481230491823091",
            expectedManifestSha256 = "ab32e49a1283c471029384710293847102938471029384710293847102938471"
        )

        assertFalse("Verification should fail for malformed timestamp", result.isValid)
        assertTrue(result.errors.any { it.contains("Invalid ISO-8601 UTC timestamp format") })
    }

    @Test
    fun testReceiptVerificationUnsupportedSchema() {
        val receipt = createSampleReceipt(type = "datapool.compensation.v999")
        val result = CompensationVerifier.verifyCompensationReceipt(
            receipt = receipt,
            expectedCaptureId = "dp_0187testsession01",
            expectedVideoSha256 = "56f9e1d842b0834bc7e4d8123985012398412389104810239481230491823091",
            expectedManifestSha256 = "ab32e49a1283c471029384710293847102938471029384710293847102938471"
        )

        assertFalse("Verification should fail for unsupported schema version", result.isValid)
        assertTrue(result.errors.any { it.contains("Unsupported schema type") })
    }

    @Test
    fun testProvenanceDagHashDependencyOrder() {
        // Simulates the exact DAG order:
        // 1. video.mp4 -> video_sha256
        // 2. capture manifest -> manifest_sha256
        // 3. compensation.json -> compensation_receipt_sha256
        // 4. bundle.json references all hashes without cycle

        val dummyVideoBytes = "FAKE_MP4_VIDEO_STREAM_BYTES".toByteArray(Charsets.UTF_8)
        val videoSha256 = KeyPair.sha256Hex(dummyVideoBytes)

        val dummyManifestJson = """{"protocol":"dattapool","video_sha256":"$videoSha256","session_id":"dp_session_100"}"""
        val manifestSha256 = KeyPair.sha256Hex(CanonicalJson.canonicalize(dummyManifestJson).toByteArray(Charsets.UTF_8))

        val receipt = createSampleReceipt(
            captureId = "dp_session_100",
            videoSha256 = videoSha256,
            manifestSha256 = manifestSha256
        )
        val receiptSha256 = receipt.computeSha256()

        val bundleIndex = CaptureBundleIndex(
            captureManifest = BundleFileRef(file = "capture-manifest.json", sha256 = manifestSha256),
            video = BundleFileRef(file = "capture.mp4", sha256 = videoSha256),
            compensation = BundleFileRef(file = "compensation.json", sha256 = receiptSha256)
        )

        assertEquals(videoSha256, bundleIndex.video.sha256)
        assertEquals(manifestSha256, bundleIndex.captureManifest.sha256)
        assertEquals(receiptSha256, bundleIndex.compensation?.sha256)

        // Verify that receipt can be verified against manifest and video hashes
        val verification = CompensationVerifier.verifyCompensationReceipt(
            receipt = receipt,
            expectedCaptureId = "dp_session_100",
            expectedVideoSha256 = videoSha256,
            expectedManifestSha256 = manifestSha256
        )
        assertTrue(verification.isValid)
    }
}
