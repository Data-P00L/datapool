package org.dattapool.capture.protocol

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dattapool.capture.camera.CameraRecorder
import org.dattapool.capture.crypto.*
import org.dattapool.capture.nostr.NostrEvent
import org.dattapool.capture.nostr.WitnessClient
import org.dattapool.capture.sensor.SensorCollector
import org.dattapool.capture.sensor.TelemetryRecord
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

data class ActiveSession(
    val sessionId: String,
    val sessionNonceHex: String,
    val nonceCommitmentHex: String,
    val workerKey: KeyPair,
    val deviceKey: KeyPair,
    val sessionKey: KeyPair,
    val startedAt: Long,
    val skillTag: String,
    val freshness: FreshnessReference,
    val nostrEvent: NostrEvent?,
    val isWitnessed: Boolean,
    val targetLevel: String,
    val videoOutputFile: File,
    val sessionDir: File
)

data class CaptureResult(
    val manifest: CaptureManifest,
    val manifestHash: String,
    val provenanceLevel: String,
    val sessionDir: File,
    val manifestFile: File,
    val videoFile: File,
    val telemetryFile: File,
    val nostrEventFile: File?
)

class CaptureEngine(
    private val context: Context
) {
    private val sensorCollector = SensorCollector(context)
    private val witnessClient = WitnessClient()

    fun getSensorCollector(): SensorCollector = sensorCollector

    fun buildWorkerDeviceAuthPayload(workerPubKey: String, devicePubKey: String, issuedAt: Long, expiresAt: Long): ByteArray {
        val msg = "DATTA_WORKER_DEVICE_AUTHORIZATION_V1\n" +
                "worker_nostr_pubkey:$workerPubKey\n" +
                "device_pubkey:$devicePubKey\n" +
                "issued_at:$issuedAt\n" +
                "expires_at:$expiresAt"
        return msg.toByteArray(Charsets.UTF_8)
    }

    fun buildDeviceSessionAuthPayload(devicePubKey: String, sessionPubKey: String, sessionId: String, nonceCommitment: String, issuedAt: Long, expiresAt: Long): ByteArray {
        val msg = "DATTA_DEVICE_SESSION_AUTHORIZATION_V1\n" +
                "device_pubkey:$devicePubKey\n" +
                "session_pubkey:$sessionPubKey\n" +
                "session_id:$sessionId\n" +
                "session_nonce_commitment:$nonceCommitment\n" +
                "issued_at:$issuedAt\n" +
                "expires_at:$expiresAt"
        return msg.toByteArray(Charsets.UTF_8)
    }

    fun buildCaptureSealPayload(
        sessionId: String,
        chunkCount: Int,
        captureChainRoot: String,
        videoMerkleRoot: String,
        telemetryMerkleRoot: String,
        videoSha256: String,
        startedAt: Long,
        endedAt: Long,
        nostrEventId: String?
    ): ByteArray {
        val msg = "DATTA_CAPTURE_SEAL_V1\n" +
                "session_id:$sessionId\n" +
                "chunk_count:$chunkCount\n" +
                "capture_chain_root:$captureChainRoot\n" +
                "video_merkle_root:$videoMerkleRoot\n" +
                "telemetry_merkle_root:$telemetryMerkleRoot\n" +
                "video_sha256:$videoSha256\n" +
                "started_at:$startedAt\n" +
                "ended_at:$endedAt\n" +
                "nostr_event_id:${nostrEventId ?: ""}"
        return msg.toByteArray(Charsets.UTF_8)
    }

    suspend fun startSession(
        workerKey: KeyPair,
        deviceKey: KeyPair,
        skillTag: String,
        cameraRecorder: CameraRecorder
    ): ActiveSession = withContext(Dispatchers.IO) {
        val random = SecureRandom()
        val nonceBytes = ByteArray(32).also { random.nextBytes(it) }
        val sessionNonceHex = nonceBytes.toHex()

        val idBytes = ByteArray(16).also { random.nextBytes(it) }
        val sessionId = idBytes.toHex()

        val sessionKey = KeyPair.generate()
        val now = System.currentTimeMillis() / 1000

        // Compute nonce commitment: SHA256(DATTA_NONCE_COMMITMENT_V1 || session_id || nonce)
        val ncDigest = MessageDigest.getInstance("SHA-256")
        ncDigest.update("DATTA_NONCE_COMMITMENT_V1".toByteArray(Charsets.UTF_8))
        ncDigest.update(sessionId.toByteArray(Charsets.UTF_8))
        ncDigest.update(nonceBytes)
        val nonceCommitmentHex = ncDigest.digest().toHex()

        // Query freshness
        val freshResp = witnessClient.queryFreshness()
        val freshness = if (freshResp.type == "bitcoin_block") {
            FreshnessReference(
                type = "bitcoin_block",
                blockHeight = freshResp.block_height,
                blockHash = freshResp.block_hash
            )
        } else {
            FreshnessReference(type = "local_only")
        }

        // Build pre-capture Nostr witness event
        val nostrEvent = NostrEvent.createSessionStartEvent(
            workerKey = workerKey,
            sessionId = sessionId,
            devicePubKey = deviceKey.publicKeyHex,
            sessionPubKey = sessionKey.publicKeyHex,
            nonceCommitment = nonceCommitmentHex,
            freshnessType = freshness.type,
            blockHeight = freshness.blockHeight ?: 0L,
            blockHash = freshness.blockHash ?: "",
            skillTag = skillTag,
            createdAt = now
        )

        // Try publishing
        val pubResp = witnessClient.publishEvent(nostrEvent)
        val isWitnessed = pubResp.success
        val targetLevel = if (isWitnessed && freshness.type == "bitcoin_block") "P4" else "P3"

        // Prepare session directory
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val sessionDir = File(baseDir, "session-$sessionId").also { it.mkdirs() }
        val videoFile = File(sessionDir, "capture.mp4")

        // Start sensors & camera
        sensorCollector.start()
        cameraRecorder.startRecording(videoFile) {}

        ActiveSession(
            sessionId = sessionId,
            sessionNonceHex = sessionNonceHex,
            nonceCommitmentHex = nonceCommitmentHex,
            workerKey = workerKey,
            deviceKey = deviceKey,
            sessionKey = sessionKey,
            startedAt = now,
            skillTag = skillTag,
            freshness = freshness,
            nostrEvent = nostrEvent,
            isWitnessed = isWitnessed,
            targetLevel = targetLevel,
            videoOutputFile = videoFile,
            sessionDir = sessionDir
        )
    }

    suspend fun stopAndSeal(
        session: ActiveSession,
        cameraRecorder: CameraRecorder
    ): CaptureResult = withContext(Dispatchers.IO) {
        cameraRecorder.stopRecording()
        val telemetryRecords = sensorCollector.stop()
        val endedAt = System.currentTimeMillis() / 1000

        // Give camera 500ms to finalize video file
        kotlinx.coroutines.delay(500)

        val videoFile = session.videoOutputFile
        require(videoFile.exists() && videoFile.length() > 0) { "Video file empty or missing" }

        // 1. Chunk video into 64KB blocks
        val chunkSize = 64 * 1024
        val videoChunkHashes = mutableListOf<String>()
        val totalVideoDigest = MessageDigest.getInstance("SHA-256")

        FileInputStream(videoFile).use { input ->
            val buf = ByteArray(chunkSize)
            var bytesRead: Int
            while (input.read(buf).also { bytesRead = it } != -1) {
                val chunk = if (bytesRead == chunkSize) buf else buf.copyOf(bytesRead)
                totalVideoDigest.update(chunk)
                videoChunkHashes.add(KeyPair.sha256Hex(chunk))
            }
        }
        val videoSha256 = totalVideoDigest.digest().toHex()
        val chunkCount = videoChunkHashes.size
        val videoMerkleRoot = MerkleTree.computeRoot(videoChunkHashes)

        // 2. Batch telemetry into chunkCount chunks
        val telemetryChunks = SensorCollector.chunkRecords(telemetryRecords, chunkCount)
        val telemetryChunkHashes = telemetryChunks.map { it.hashChunk() }
        val telemetryMerkleRoot = MerkleTree.computeRoot(telemetryChunkHashes)

        // Write telemetry.jsonl
        val telemetryFile = File(session.sessionDir, "telemetry.jsonl")
        sensorCollector.writeToJsonl(telemetryFile, telemetryRecords)

        // 3. Compute continuous temporal hash chain
        val hashChain = ContinuousHashChain(session.sessionId, session.nonceCommitmentHex)
        val durationSec = 10.0
        val chunkDuration = durationSec / chunkCount.toDouble()

        for (k in 0 until chunkCount) {
            val startOffset = k * chunkDuration
            val endOffset = (k + 1) * chunkDuration
            hashChain.appendChunk(
                ChunkTemporalBinding(
                    index = k,
                    startOffsetSec = startOffset,
                    endOffsetSec = endOffset,
                    videoChunkHash = videoChunkHashes[k],
                    telemetryChunkHash = telemetryChunkHashes[k]
                )
            )
        }
        val captureChainRoot = hashChain.rootHex()

        // 4. Create Capture Seal
        val nostrEventId = if (session.isWitnessed && session.nostrEvent != null) session.nostrEvent.id else null
        val sealPayload = buildCaptureSealPayload(
            sessionId = session.sessionId,
            chunkCount = chunkCount,
            captureChainRoot = captureChainRoot,
            videoMerkleRoot = videoMerkleRoot,
            telemetryMerkleRoot = telemetryMerkleRoot,
            videoSha256 = videoSha256,
            startedAt = session.startedAt,
            endedAt = endedAt,
            nostrEventId = nostrEventId
        )

        val sessionSealSig = session.sessionKey.signMessage(sealPayload)
        val deviceSealSig = session.deviceKey.signMessage(sealPayload)

        val captureSeal = CaptureSeal(
            sessionId = session.sessionId,
            chunkCount = chunkCount,
            captureChainRoot = captureChainRoot,
            videoMerkleRoot = videoMerkleRoot,
            telemetryMerkleRoot = telemetryMerkleRoot,
            videoSha256 = videoSha256,
            captureStartedAt = session.startedAt,
            captureEndedAt = endedAt,
            sessionStartNostrEventId = nostrEventId,
            sessionSealSignature = sessionSealSig,
            deviceSealSignature = deviceSealSig
        )

        // 5. Build Authorizations
        val issuedAt = session.startedAt
        val expiresAt = issuedAt + 86400

        val workerDevicePayload = buildWorkerDeviceAuthPayload(session.workerKey.publicKeyHex, session.deviceKey.publicKeyHex, issuedAt, expiresAt)
        val workerDeviceSig = session.workerKey.signMessage(workerDevicePayload)

        val deviceSessionPayload = buildDeviceSessionAuthPayload(session.deviceKey.publicKeyHex, session.sessionKey.publicKeyHex, session.sessionId, session.nonceCommitmentHex, issuedAt, expiresAt)
        val deviceSessionSig = session.deviceKey.signMessage(deviceSessionPayload)

        val authorization = Authorization(
            workerDeviceAuth = WorkerDeviceAuthorization(
                workerPubKey = session.workerKey.publicKeyHex,
                devicePubKey = session.deviceKey.publicKeyHex,
                issuedAt = issuedAt,
                expiresAt = expiresAt,
                workerSignature = workerDeviceSig
            ),
            deviceSessionAuth = DeviceSessionAuthorization(
                devicePubKey = session.deviceKey.publicKeyHex,
                sessionPubKey = session.sessionKey.publicKeyHex,
                sessionId = session.sessionId,
                nonceCommitment = session.nonceCommitmentHex,
                issuedAt = issuedAt,
                expiresAt = expiresAt,
                deviceSignature = deviceSessionSig
            ),
            workerSignature = workerDeviceSig,
            expiresAt = expiresAt
        )

        // 6. Build Manifest
        val witnessInfo = if (session.nostrEvent != null) {
            WitnessInfo(
                nostrEventId = session.nostrEvent.id,
                relayUrls = listOf("mock://local.nostr.witness"),
                published = session.isWitnessed
            )
        } else null

        val manifest = CaptureManifest(
            worker = WorkerIdentity(nostrPubKey = session.workerKey.publicKeyHex),
            device = DeviceIdentity(pubKey = session.deviceKey.publicKeyHex),
            session = SessionInfo(
                id = session.sessionId,
                pubKey = session.sessionKey.publicKeyHex,
                nonceCommitment = session.nonceCommitmentHex,
                startedAt = session.startedAt,
                endedAt = endedAt,
                freshness = session.freshness
            ),
            witness = witnessInfo,
            capture = CaptureSummary(
                videoSha256 = videoSha256,
                videoMerkleRoot = videoMerkleRoot,
                telemetryMerkleRoot = telemetryMerkleRoot,
                captureChainRoot = captureChainRoot,
                chunkCount = chunkCount
            ),
            task = TaskInfo(skillTag = session.skillTag),
            authorization = authorization,
            seal = captureSeal,
            signatures = Signatures(
                sessionSealSignature = sessionSealSig,
                deviceSealSignature = deviceSealSig,
                sessionSignature = sessionSealSig,
                deviceSignature = deviceSealSig
            ),
            provenance = ProvenanceMetadata(level = session.targetLevel)
        )

        // 7. Write Manifest & Nostr Event JSON files
        val manifestFile = File(session.sessionDir, "capture-manifest.json")
        val gsonPretty = GsonBuilder().setPrettyPrinting().create()
        manifestFile.writeText(gsonPretty.toJson(manifest))

        var nostrFile: File? = null
        if (session.nostrEvent != null) {
            nostrFile = File(session.sessionDir, "session-start-event.json")
            nostrFile.writeText(gsonPretty.toJson(session.nostrEvent))
        }

        // Canonical hash of manifest
        val canonicalManifest = CanonicalJson.canonicalize(manifest)
        val manifestHash = KeyPair.sha256Hex(canonicalManifest.toByteArray(Charsets.UTF_8))

        CaptureResult(
            manifest = manifest,
            manifestHash = manifestHash,
            provenanceLevel = session.targetLevel,
            sessionDir = session.sessionDir,
            manifestFile = manifestFile,
            videoFile = videoFile,
            telemetryFile = telemetryFile,
            nostrEventFile = nostrFile
        )
    }
}
