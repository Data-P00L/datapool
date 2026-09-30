package org.dattapool.capture.compensation

data class VerificationResult(
    val isValid: Boolean,
    val errors: List<String> = emptyList()
) {
    fun errorSummary(): String = errors.joinToString("; ")
}

object CompensationVerifier {

    const val SUPPORTED_SCHEMA_TYPE = "datapool.compensation.v1"
    const val EXPECTED_TICKER = "DTTA"

    /**
     * Validates a CompensationReceipt against expected capture ID, video SHA-256, and manifest SHA-256.
     * Checks schema version, asset validity, atomic amounts, display formatting, and timestamp syntax.
     */
    fun verifyCompensationReceipt(
        receipt: CompensationReceipt,
        expectedCaptureId: String,
        expectedVideoSha256: String,
        expectedManifestSha256: String
    ): VerificationResult {
        val errors = mutableListOf<String>()

        if (receipt.type != SUPPORTED_SCHEMA_TYPE) {
            errors.add("Unsupported schema type '${receipt.type}', expected '$SUPPORTED_SCHEMA_TYPE'")
        }

        if (receipt.captureId != expectedCaptureId) {
            errors.add("Capture ID mismatch: receipt has '${receipt.captureId}', expected '$expectedCaptureId'")
        }

        if (!receipt.videoSha256.equals(expectedVideoSha256, ignoreCase = true)) {
            errors.add("Video SHA-256 mismatch: receipt has '${receipt.videoSha256}', expected '$expectedVideoSha256'")
        }

        if (!receipt.manifestSha256.equals(expectedManifestSha256, ignoreCase = true)) {
            errors.add("Manifest SHA-256 mismatch: receipt has '${receipt.manifestSha256}', expected '$expectedManifestSha256'")
        }

        if (receipt.asset.ticker != EXPECTED_TICKER) {
            errors.add("Asset ticker mismatch: receipt has '${receipt.asset.ticker}', expected '$EXPECTED_TICKER'")
        }

        if (receipt.asset.assetId.isBlank()) {
            errors.add("Asset ID cannot be empty")
        }

        if (receipt.amountAtomic < 0) {
            errors.add("Amount atomic cannot be negative: ${receipt.amountAtomic}")
        }

        val expectedDisplay = CompensationFormatters.formatAtomicAmount(receipt.amountAtomic)
        if (receipt.amountDisplay != expectedDisplay) {
            errors.add("Amount display mismatch: receipt has '${receipt.amountDisplay}', expected '$expectedDisplay' for ${receipt.amountAtomic} atomic units")
        }

        if (!CompensationFormatters.isValidIsoUtc(receipt.transfer.timestamp)) {
            errors.add("Invalid ISO-8601 UTC timestamp format: '${receipt.transfer.timestamp}'")
        }

        return VerificationResult(
            isValid = errors.isEmpty(),
            errors = errors
        )
    }
}
