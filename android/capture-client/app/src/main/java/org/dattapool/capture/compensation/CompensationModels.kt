package org.dattapool.capture.compensation

import com.google.gson.annotations.SerializedName

/**
 * Representation of a DattaPool DTTA reward asset.
 * Internal/machine-readable ticker is DTTA; human-facing display symbol is ₿DATA.
 */
data class CompensationAsset(
    @SerializedName("asset_id") val assetId: String,
    @SerializedName("ticker") val ticker: String = "DTTA",
    @SerializedName("display_symbol") val displaySymbol: String = "₿DATA"
)

/**
 * Details of the transfer/settlement method (on-chain Taproot Asset or Lightning).
 */
data class CompensationTransfer(
    @SerializedName("method") val method: String,
    @SerializedName("txid") val txid: String? = null,
    @SerializedName("payment_hash") val paymentHash: String? = null,
    @SerializedName("timestamp") val timestamp: String,
    @SerializedName("status") val status: String
)

/**
 * Versioned DattaPool Remuneration Receipt model (datapool.compensation.v1).
 * Associates a verified capture session and its cryptographic hashes with a DTTA / ₿DATA reward.
 */
data class CompensationReceipt(
    @SerializedName("type") val type: String = "datapool.compensation.v1",
    @SerializedName("capture_id") val captureId: String,
    @SerializedName("video_sha256") val videoSha256: String,
    @SerializedName("manifest_sha256") val manifestSha256: String,
    @SerializedName("worker_pubkey") val workerPubkey: String,
    @SerializedName("asset") val asset: CompensationAsset,
    @SerializedName("amount_atomic") val amountAtomic: Long,
    @SerializedName("amount_display") val amountDisplay: String,
    @SerializedName("reason") val reason: String,
    @SerializedName("transfer") val transfer: CompensationTransfer
)

/**
 * Lifecycle states of compensation for a capture.
 */
enum class CompensationState {
    NONE,
    PENDING_VERIFICATION,
    VERIFIED,
    REWARD_ASSIGNED,
    PAYMENT_BROADCAST,
    CONFIRMED,
    FAILED
}

/**
 * Locally persisted compensation entity for capture sessions.
 */
data class CompensationRecord(
    val captureId: String,
    val assetId: String,
    val ticker: String = "DTTA",
    val displaySymbol: String = "₿DATA",
    val amountAtomic: Long,
    val amountDisplay: String,
    val reason: String,
    val transferMethod: String,
    val txid: String? = null,
    val paymentHash: String? = null,
    val transferStatus: String,
    val receiptSha256: String,
    val receiptPath: String,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Reference to a bundled file and its cryptographic hash.
 */
data class BundleFileRef(
    @SerializedName("file") val file: String,
    @SerializedName("sha256") val sha256: String
)

/**
 * Higher-level capture bundle index (bundle.json) maintaining a clean provenance DAG.
 */
data class CaptureBundleIndex(
    @SerializedName("capture_manifest") val captureManifest: BundleFileRef,
    @SerializedName("video") val video: BundleFileRef,
    @SerializedName("compensation") val compensation: BundleFileRef? = null,
    @SerializedName("telemetry") val telemetry: BundleFileRef? = null
)
