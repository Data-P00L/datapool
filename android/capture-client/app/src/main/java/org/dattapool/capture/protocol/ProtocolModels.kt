package org.dattapool.capture.protocol

import com.google.gson.annotations.SerializedName

data class WorkerIdentity(
    @SerializedName("type") val type: String = "human",
    @SerializedName("nostr_pubkey") val nostrPubKey: String
)

data class DeviceIdentity(
    @SerializedName("pubkey") val pubKey: String,
    @SerializedName("type") val type: String = "android_phone"
)

data class FreshnessReference(
    @SerializedName("type") val type: String,
    @SerializedName("block_height") val blockHeight: Long? = null,
    @SerializedName("block_hash") val blockHash: String? = null
)

data class WitnessInfo(
    @SerializedName("nostr_event_id") val nostrEventId: String,
    @SerializedName("relay_urls") val relayUrls: List<String> = emptyList(),
    @SerializedName("published") val published: Boolean = false
)

data class SessionInfo(
    @SerializedName("id") val id: String,
    @SerializedName("pubkey") val pubKey: String,
    @SerializedName("nonce_commitment") val nonceCommitment: String,
    @SerializedName("started_at") val startedAt: Long,
    @SerializedName("ended_at") val endedAt: Long,
    @SerializedName("freshness") val freshness: FreshnessReference
)

data class CaptureSummary(
    @SerializedName("video_sha256") val videoSha256: String,
    @SerializedName("video_merkle_root") val videoMerkleRoot: String,
    @SerializedName("telemetry_merkle_root") val telemetryMerkleRoot: String,
    @SerializedName("capture_chain_root") val captureChainRoot: String,
    @SerializedName("chunk_count") val chunkCount: Int
)

data class TaskInfo(
    @SerializedName("skill_tag") val skillTag: String
)

data class WorkerDeviceAuthorization(
    @SerializedName("worker_nostr_pubkey") val workerPubKey: String,
    @SerializedName("device_pubkey") val devicePubKey: String,
    @SerializedName("issued_at") val issuedAt: Long,
    @SerializedName("expires_at") val expiresAt: Long,
    @SerializedName("worker_signature") val workerSignature: String
)

data class DeviceSessionAuthorization(
    @SerializedName("device_pubkey") val devicePubKey: String,
    @SerializedName("session_pubkey") val sessionPubKey: String,
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("nonce_commitment") val nonceCommitment: String,
    @SerializedName("issued_at") val issuedAt: Long,
    @SerializedName("expires_at") val expiresAt: Long,
    @SerializedName("device_signature") val deviceSignature: String
)

data class Authorization(
    @SerializedName("worker_device_authorization") val workerDeviceAuth: WorkerDeviceAuthorization,
    @SerializedName("device_session_authorization") val deviceSessionAuth: DeviceSessionAuthorization,
    @SerializedName("worker_signature") val workerSignature: String,
    @SerializedName("expires_at") val expiresAt: Long
)

data class CaptureSeal(
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("chunk_count") val chunkCount: Int,
    @SerializedName("capture_chain_root") val captureChainRoot: String,
    @SerializedName("video_merkle_root") val videoMerkleRoot: String,
    @SerializedName("telemetry_merkle_root") val telemetryMerkleRoot: String,
    @SerializedName("video_sha256") val videoSha256: String,
    @SerializedName("capture_started_at") val captureStartedAt: Long,
    @SerializedName("capture_ended_at") val captureEndedAt: Long,
    @SerializedName("session_start_nostr_event_id") val sessionStartNostrEventId: String? = null,
    @SerializedName("session_seal_signature") val sessionSealSignature: String,
    @SerializedName("device_seal_signature") val deviceSealSignature: String
)

data class Signatures(
    @SerializedName("session_seal_signature") val sessionSealSignature: String,
    @SerializedName("device_seal_signature") val deviceSealSignature: String,
    @SerializedName("session_signature") val sessionSignature: String,
    @SerializedName("device_signature") val deviceSignature: String
)

data class ProvenanceMetadata(
    @SerializedName("level") val level: String
)

data class CaptureManifest(
    @SerializedName("protocol") val protocol: String = "dattapool",
    @SerializedName("schema_version") val schemaVersion: String = "0.4.0",
    @SerializedName("worker") val worker: WorkerIdentity,
    @SerializedName("device") val device: DeviceIdentity,
    @SerializedName("session") val session: SessionInfo,
    @SerializedName("witness") val witness: WitnessInfo? = null,
    @SerializedName("capture") val capture: CaptureSummary,
    @SerializedName("task") val task: TaskInfo,
    @SerializedName("authorization") val authorization: Authorization,
    @SerializedName("seal") val seal: CaptureSeal,
    @SerializedName("signatures") val signatures: Signatures,
    @SerializedName("provenance") val provenance: ProvenanceMetadata
)
