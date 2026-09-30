package org.dattapool.capture.compensation

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dattapool.capture.crypto.KeyPair
import org.dattapool.capture.protocol.CaptureResult
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID

interface CompensationRepository {
    suspend fun getCompensation(captureId: String): CompensationReceipt?
    suspend fun getCompensationRecord(captureId: String): CompensationRecord?
    suspend fun saveCompensation(receipt: CompensationReceipt, sessionDir: File? = null): CompensationRecord
    fun getDttaAssetId(): String
    fun setDttaAssetId(assetId: String)
    suspend fun simulateReward(
        captureResult: CaptureResult,
        customAssetId: String? = null
    ): CompensationReceipt
}

class LocalCompensationRepository(
    private val context: Context
) : CompensationRepository {

    companion object {
        private const val TAG = "DATTA_COMPENSATION"
        private const val PREFS_NAME = "datapool_compensation_prefs"
        private const val KEY_DTTA_ASSET_ID = "datapool_dtta_asset_id"
        private const val KEY_PREFIX_RECEIPT = "receipt_"
        private const val KEY_PREFIX_RECORD = "record_"

        // Default Regtest DTTA asset ID fallback
        const val DEFAULT_DTTA_ASSET_ID = "93afb66f20e6a117079d34208a0df68c67ecba5dc2cd15bc2a0e4e7e6840742f"
        const val DEFAULT_REWARD_ATOMIC = 25000L // 25.000 ₿DATA
        const val DEFAULT_REWARD_REASON = "verified_egocentric_capture"
        const val DEFAULT_TRANSFER_METHOD = "taproot_asset_onchain"
    }

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    override fun getDttaAssetId(): String {
        return prefs.getString(KEY_DTTA_ASSET_ID, null) ?: DEFAULT_DTTA_ASSET_ID
    }

    override fun setDttaAssetId(assetId: String) {
        prefs.edit().putString(KEY_DTTA_ASSET_ID, assetId).apply()
        Log.i(TAG, "Configured DATAPOOL_DTTA_ASSET_ID: $assetId")
    }

    override suspend fun getCompensation(captureId: String): CompensationReceipt? = withContext(Dispatchers.IO) {
        val json = prefs.getString(KEY_PREFIX_RECEIPT + captureId, null) ?: return@withContext null
        try {
            gson.fromJson(json, CompensationReceipt::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse stored compensation receipt for capture $captureId", e)
            null
        }
    }

    override suspend fun getCompensationRecord(captureId: String): CompensationRecord? = withContext(Dispatchers.IO) {
        val json = prefs.getString(KEY_PREFIX_RECORD + captureId, null) ?: return@withContext null
        try {
            gson.fromJson(json, CompensationRecord::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse stored compensation record for capture $captureId", e)
            null
        }
    }

    override suspend fun saveCompensation(
        receipt: CompensationReceipt,
        sessionDir: File?
    ): CompensationRecord = withContext(Dispatchers.IO) {
        val receiptJson = gson.toJson(receipt)
        val receiptSha256 = receipt.computeSha256()

        var receiptFile: File? = null
        if (sessionDir != null && sessionDir.exists()) {
            receiptFile = File(sessionDir, "compensation.json")
            val tempFile = File(sessionDir, "compensation.json.tmp")
            tempFile.writeText(receiptJson)
            if (!tempFile.renameTo(receiptFile)) {
                // If rename fails across filesystems, write directly
                receiptFile.writeText(receiptJson)
                tempFile.delete()
            }
            updateBundleIndex(sessionDir, receiptFile, receiptSha256)
        }

        val now = System.currentTimeMillis()
        val record = CompensationRecord(
            captureId = receipt.captureId,
            assetId = receipt.asset.assetId,
            ticker = receipt.asset.ticker,
            displaySymbol = receipt.asset.displaySymbol,
            amountAtomic = receipt.amountAtomic,
            amountDisplay = receipt.amountDisplay,
            reason = receipt.reason,
            transferMethod = receipt.transfer.method,
            txid = receipt.transfer.txid,
            paymentHash = receipt.transfer.paymentHash,
            transferStatus = receipt.transfer.status,
            receiptSha256 = receiptSha256,
            receiptPath = receiptFile?.absolutePath ?: "",
            createdAt = now,
            updatedAt = now
        )

        prefs.edit()
            .putString(KEY_PREFIX_RECEIPT + receipt.captureId, receiptJson)
            .putString(KEY_PREFIX_RECORD + receipt.captureId, gson.toJson(record))
            .apply()

        Log.i(TAG, "Compensation receipt created for capture ${receipt.captureId}")
        Log.i(TAG, "Receipt SHA256: $receiptSha256")
        Log.i(TAG, "Transfer status: ${receipt.transfer.status}")

        record
    }

    override suspend fun simulateReward(
        captureResult: CaptureResult,
        customAssetId: String?
    ): CompensationReceipt = withContext(Dispatchers.IO) {
        val captureId = captureResult.manifest.session.id
        val videoSha256 = captureResult.manifest.capture.videoSha256
        val manifestSha256 = captureResult.manifestHash
        val workerPubkey = captureResult.manifest.worker.nostrPubKey
        val assetId = customAssetId ?: getDttaAssetId()

        val amountAtomic = DEFAULT_REWARD_ATOMIC
        val amountDisplay = CompensationFormatters.formatAtomicAmount(amountAtomic)
        val randomTxSuffix = UUID.randomUUID().toString().replace("-", "").take(16)
        val mockTxid = "regtest_tx_$randomTxSuffix"

        val transfer = CompensationTransfer(
            method = DEFAULT_TRANSFER_METHOD,
            txid = mockTxid,
            paymentHash = null,
            timestamp = CompensationFormatters.nowIsoUtc(),
            status = "confirmed"
        )

        val receipt = CompensationReceipt(
            type = "datapool.compensation.v1",
            captureId = captureId,
            videoSha256 = videoSha256,
            manifestSha256 = manifestSha256,
            workerPubkey = workerPubkey,
            asset = CompensationAsset(
                assetId = assetId,
                ticker = "DTTA",
                displaySymbol = "₿DATA"
            ),
            amountAtomic = amountAtomic,
            amountDisplay = amountDisplay,
            reason = DEFAULT_REWARD_REASON,
            transfer = transfer
        )

        saveCompensation(receipt, captureResult.sessionDir)
        receipt
    }

    private fun updateBundleIndex(sessionDir: File, compensationFile: File, compensationSha256: String) {
        try {
            val manifestFile = File(sessionDir, "capture-manifest.json")
            val videoFile = File(sessionDir, "capture.mp4").takeIf { it.exists() } ?: File(sessionDir, "video.mp4")
            val telemetryFile = File(sessionDir, "telemetry.jsonl")

            val manifestHash = if (manifestFile.exists()) computeFileSha256(manifestFile) else ""
            val videoHash = if (videoFile.exists()) computeFileSha256(videoFile) else ""
            val telemetryHash = if (telemetryFile.exists()) computeFileSha256(telemetryFile) else null

            val bundleIndex = CaptureBundleIndex(
                captureManifest = BundleFileRef(file = manifestFile.name, sha256 = manifestHash),
                video = BundleFileRef(file = videoFile.name, sha256 = videoHash),
                compensation = BundleFileRef(file = compensationFile.name, sha256 = compensationSha256),
                telemetry = if (telemetryFile.exists() && telemetryHash != null) {
                    BundleFileRef(file = telemetryFile.name, sha256 = telemetryHash)
                } else null
            )

            val bundleFile = File(sessionDir, "bundle.json")
            bundleFile.writeText(gson.toJson(bundleIndex))
            Log.d(TAG, "Updated bundle.json with compensation reference in ${sessionDir.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update bundle.json", e)
        }
    }

    private fun computeFileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buf).also { read = it } != -1) {
                digest.update(buf, 0, read)
            }
        }
        return KeyPair.sha256Hex(digest.digest())
    }
}
