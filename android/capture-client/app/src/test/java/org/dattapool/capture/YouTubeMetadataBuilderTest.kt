package org.dattapool.capture

import org.dattapool.capture.protocol.*
import org.dattapool.capture.youtube.DattaPoolDescriptionMetadata
import org.dattapool.capture.youtube.YouTubeMetadataBuilder
import org.junit.Assert.*
import org.junit.Test

class YouTubeMetadataBuilderTest {

    private fun createTestManifest(
        sessionId: String = "833ab02adca3ac6680542d616e77bf5f",
        workerPubkey: String = "0ead498f89be73a48abd4253ab2a95675a20f867d0a038ebaf11d6900d4d05d4",
        chainRoot: String = "f566881b48364607b1c125c812ca281c5765561c5c4264c9d4d01bb11fb040a1",
        skillTag: String = "general_manipulation",
        level: String = "P4"
    ): CaptureManifest {
        return CaptureManifest(
            protocol = "dattapool",
            schemaVersion = "0.4.0",
            worker = WorkerIdentity(type = "human", nostrPubKey = workerPubkey),
            device = DeviceIdentity(pubKey = "11".repeat(32)),
            session = SessionInfo(
                id = sessionId,
                pubKey = "22".repeat(32),
                nonceCommitment = "33".repeat(32),
                startedAt = 1700000000,
                endedAt = 1700000060,
                freshness = FreshnessReference(type = "local_only")
            ),
            capture = CaptureSummary(
                videoSha256 = "44".repeat(32),
                videoMerkleRoot = "55".repeat(32),
                telemetryMerkleRoot = "66".repeat(32),
                captureChainRoot = chainRoot,
                chunkCount = 10
            ),
            task = TaskInfo(skillTag = skillTag),
            authorization = Authorization(
                workerDeviceAuth = WorkerDeviceAuthorization(
                    workerPubKey = workerPubkey,
                    devicePubKey = "11".repeat(32),
                    issuedAt = 1700000000,
                    expiresAt = 1700086400,
                    workerSignature = "sig1"
                ),
                deviceSessionAuth = DeviceSessionAuthorization(
                    devicePubKey = "11".repeat(32),
                    sessionPubKey = "22".repeat(32),
                    sessionId = sessionId,
                    nonceCommitment = "33".repeat(32),
                    issuedAt = 1700000000,
                    expiresAt = 1700086400,
                    deviceSignature = "sig2"
                ),
                workerSignature = "sig1",
                expiresAt = 1700086400
            ),
            seal = CaptureSeal(
                sessionId = sessionId,
                chunkCount = 10,
                captureChainRoot = chainRoot,
                videoMerkleRoot = "55".repeat(32),
                telemetryMerkleRoot = "66".repeat(32),
                videoSha256 = "44".repeat(32),
                captureStartedAt = 1700000000,
                captureEndedAt = 1700000060,
                sessionSealSignature = "seal1",
                deviceSealSignature = "seal2"
            ),
            signatures = Signatures(
                sessionSealSignature = "seal1",
                deviceSealSignature = "seal2",
                sessionSignature = "seal1",
                deviceSignature = "seal2"
            ),
            provenance = ProvenanceMetadata(level = level)
        )
    }

    @Test
    fun testTitleFormat() {
        val title = YouTubeMetadataBuilder.buildTitle(
            skillTag = "general_manipulation",
            provenanceLevel = "P4",
            sessionId = "833ab02adca3ac6680542d616e77bf5f"
        )
        assertEquals("DattaPool Capture | general_manipulation | P4 | 833ab02a", title)
    }

    @Test
    fun testTagsGeneration() {
        val tags = YouTubeMetadataBuilder.buildTags(
            skillTag = "general_manipulation",
            provenanceLevel = "P4"
        )
        assertTrue(tags.contains("dattapool"))
        assertTrue(tags.contains("robotics"))
        assertTrue(tags.contains("robotics-data"))
        assertTrue(tags.contains("egocentric"))
        assertTrue(tags.contains("robot-training-data"))
        assertTrue(tags.contains("embodied-ai"))
        assertTrue(tags.contains("P4"))
        assertTrue(tags.contains("general_manipulation"))
    }

    @Test
    fun testDescriptionDeterminismAndKeys() {
        val manifest = createTestManifest()
        val manifestHash = "51b8803ea5e955e039f2df35bcc9198ae3bb6bee81ef0d5e76fac11d1e9f4ed2"
        val tapClaimId = "60dfeafdde09a8343ac58ae7332565e463469e80245ace60a981a5c07985bc88"

        val desc1 = YouTubeMetadataBuilder.buildDescription(manifest, manifestHash, tapClaimId)
        val desc2 = YouTubeMetadataBuilder.buildDescription(manifest, manifestHash, tapClaimId)

        assertEquals("Description generation must be strictly deterministic", desc1, desc2)

        // Required markers and keys
        assertTrue(desc1.contains("--- DATTAPOOL ---"))
        assertTrue(desc1.contains("--- END DATTAPOOL ---"))
        assertTrue(desc1.contains("protocol=dattapool"))
        assertTrue(desc1.contains("schema=0.4.0"))
        assertTrue(desc1.contains("type=capture_publication"))
        assertTrue(desc1.contains("session_id=833ab02adca3ac6680542d616e77bf5f"))
        assertTrue(desc1.contains("worker_nostr_pubkey=0ead498f89be73a48abd4253ab2a95675a20f867d0a038ebaf11d6900d4d05d4"))
        assertTrue(desc1.contains("manifest_hash=51b8803ea5e955e039f2df35bcc9198ae3bb6bee81ef0d5e76fac11d1e9f4ed2"))
        assertTrue(desc1.contains("capture_chain_root=f566881b48364607b1c125c812ca281c5765561c5c4264c9d4d01bb11fb040a1"))
        assertTrue(desc1.contains("tap_claim_asset_id=60dfeafdde09a8343ac58ae7332565e463469e80245ace60a981a5c07985bc88"))
        assertTrue(desc1.contains("provenance_level=P4"))
        assertTrue(desc1.contains("skill_tag=general_manipulation"))

        // Security check: NO private keys or tokens
        assertFalse(desc1.contains("nsec"))
        assertFalse(desc1.contains("worker_priv"))
        assertFalse(desc1.contains("device_priv"))
        assertFalse(desc1.contains("session_priv"))
        assertFalse(desc1.contains("oauth"))
        assertFalse(desc1.contains("access_token"))
        assertFalse(desc1.contains("/data/user/"))
    }

    @Test
    fun testParseDescription() {
        val manifest = createTestManifest()
        val manifestHash = "51b8803ea5e955e039f2df35bcc9198ae3bb6bee81ef0d5e76fac11d1e9f4ed2"
        val tapClaimId = "60dfeafdde09a8343ac58ae7332565e463469e80245ace60a981a5c07985bc88"

        val description = YouTubeMetadataBuilder.buildDescription(manifest, manifestHash, tapClaimId)
        val parsed: DattaPoolDescriptionMetadata? = YouTubeMetadataBuilder.parseDescription(description)

        assertNotNull("Parser should successfully extract metadata", parsed)
        assertEquals("833ab02adca3ac6680542d616e77bf5f", parsed?.sessionId)
        assertEquals("0ead498f89be73a48abd4253ab2a95675a20f867d0a038ebaf11d6900d4d05d4", parsed?.workerNostrPubkey)
        assertEquals("51b8803ea5e955e039f2df35bcc9198ae3bb6bee81ef0d5e76fac11d1e9f4ed2", parsed?.manifestHash)
        assertEquals("f566881b48364607b1c125c812ca281c5765561c5c4264c9d4d01bb11fb040a1", parsed?.captureChainRoot)
        assertEquals(tapClaimId, parsed?.tapClaimAssetId)
        assertEquals("P4", parsed?.provenanceLevel)
        assertEquals("general_manipulation", parsed?.skillTag)
    }

    @Test
    fun testParseDescriptionWithoutTapClaim() {
        val manifest = createTestManifest()
        val manifestHash = "51b8803ea5e955e039f2df35bcc9198ae3bb6bee81ef0d5e76fac11d1e9f4ed2"

        val description = YouTubeMetadataBuilder.buildDescription(manifest, manifestHash, null)
        val parsed = YouTubeMetadataBuilder.parseDescription(description)

        assertNotNull(parsed)
        assertEquals("833ab02adca3ac6680542d616e77bf5f", parsed?.sessionId)
        assertNull(parsed?.tapClaimAssetId)
    }

    @Test
    fun testParseInvalidDescription() {
        assertNull(YouTubeMetadataBuilder.parseDescription("Just a normal video description without DattaPool"))
        assertNull(YouTubeMetadataBuilder.parseDescription("--- DATTAPOOL ---\nsome broken text"))
    }
}
