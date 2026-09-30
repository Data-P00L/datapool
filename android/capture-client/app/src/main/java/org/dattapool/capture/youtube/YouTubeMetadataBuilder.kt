package org.dattapool.capture.youtube

import org.dattapool.capture.protocol.CaptureManifest

/**
 * Parsed structured DattaPool metadata from YouTube description block.
 */
data class DattaPoolDescriptionMetadata(
    val protocol: String = "dattapool",
    val schema: String = "0.4.0",
    val type: String = "capture_publication",
    val sessionId: String,
    val workerNostrPubkey: String,
    val manifestHash: String,
    val captureChainRoot: String,
    val tapClaimAssetId: String? = null,
    val provenanceLevel: String,
    val skillTag: String
)

/**
 * Builder for generating deterministic, machine-readable DattaPool metadata for YouTube.
 */
object YouTubeMetadataBuilder {

    const val DATTAPOOL_BLOCK_START = "--- DATTAPOOL ---"
    const val DATTAPOOL_BLOCK_END = "--- END DATTAPOOL ---"

    /**
     * Builds standard YouTube title: DattaPool Capture | <skill_tag> | <provenance_level> | <short_session_id>
     */
    fun buildTitle(skillTag: String, provenanceLevel: String, sessionId: String): String {
        val shortSessionId = if (sessionId.length >= 8) sessionId.substring(0, 8) else sessionId
        val normalizedSkill = if (skillTag.isNotBlank()) skillTag else "robotics_manipulation"
        val normalizedLevel = if (provenanceLevel.isNotBlank()) provenanceLevel else "P3"
        return "DattaPool Capture | $normalizedSkill | $normalizedLevel | $shortSessionId"
    }

    /**
     * Builds standardized tags for discovery.
     */
    fun buildTags(skillTag: String, provenanceLevel: String): List<String> {
        val tags = mutableListOf(
            "dattapool",
            "robotics",
            "robotics-data",
            "egocentric",
            "robot-training-data",
            "embodied-ai"
        )
        if (provenanceLevel.isNotBlank() && !tags.contains(provenanceLevel)) {
            tags.add(provenanceLevel)
        }
        val cleanSkill = skillTag.trim().lowercase().replace(" ", "-")
        if (cleanSkill.isNotBlank() && !tags.contains(cleanSkill)) {
            tags.add(cleanSkill)
        }
        return tags
    }

    /**
     * Generates a deterministic description containing a human-readable header
     * and a machine-readable DattaPool block.
     */
    fun buildDescription(
        manifest: CaptureManifest,
        manifestHash: String,
        tapClaimAssetId: String? = null
    ): String {
        val skill = manifest.task.skillTag.ifBlank { "robotics_manipulation" }
        val level = manifest.provenance.level.ifBlank { "P3" }
        val sessionId = manifest.session.id
        val workerPubkey = manifest.worker.nostrPubKey
        val chainRoot = manifest.capture.captureChainRoot

        val sb = StringBuilder()
        sb.append("DattaPool Robotics Capture\n\n")
        sb.append("Skill: ").append(skill).append("\n")
        sb.append("Provenance: ").append(level).append("\n")
        sb.append("Session: ").append(sessionId).append("\n\n")

        sb.append(DATTAPOOL_BLOCK_START).append("\n")
        sb.append("protocol=dattapool\n")
        sb.append("schema=0.4.0\n")
        sb.append("type=capture_publication\n")
        sb.append("session_id=").append(sessionId).append("\n")
        sb.append("worker_nostr_pubkey=").append(workerPubkey).append("\n")
        sb.append("manifest_hash=").append(manifestHash).append("\n")
        sb.append("capture_chain_root=").append(chainRoot).append("\n")
        if (!tapClaimAssetId.isNullOrBlank()) {
            sb.append("tap_claim_asset_id=").append(tapClaimAssetId).append("\n")
        }
        sb.append("provenance_level=").append(level).append("\n")
        sb.append("skill_tag=").append(skill).append("\n")
        sb.append(DATTAPOOL_BLOCK_END)

        return sb.toString()
    }

    /**
     * Parses the DattaPool metadata block from a YouTube video description.
     */
    fun parseDescription(description: String): DattaPoolDescriptionMetadata? {
        if (!description.contains(DATTAPOOL_BLOCK_START) || !description.contains(DATTAPOOL_BLOCK_END)) {
            return null
        }

        val startIndex = description.indexOf(DATTAPOOL_BLOCK_START) + DATTAPOOL_BLOCK_START.length
        val endIndex = description.indexOf(DATTAPOOL_BLOCK_END)
        if (startIndex >= endIndex) {
            return null
        }

        val blockText = description.substring(startIndex, endIndex).trim()
        val lines = blockText.lines()
        val map = mutableMapOf<String, String>()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val parts = trimmed.split("=", limit = 2)
            if (parts.size == 2) {
                map[parts[0].trim()] = parts[1].trim()
            }
        }

        val sessionId = map["session_id"] ?: return null
        val manifestHash = map["manifest_hash"] ?: return null
        val workerPubkey = map["worker_nostr_pubkey"] ?: return null
        val chainRoot = map["capture_chain_root"] ?: return null
        val provenanceLevel = map["provenance_level"] ?: "P3"
        val skillTag = map["skill_tag"] ?: "robotics_manipulation"

        return DattaPoolDescriptionMetadata(
            protocol = map["protocol"] ?: "dattapool",
            schema = map["schema"] ?: "0.4.0",
            type = map["type"] ?: "capture_publication",
            sessionId = sessionId,
            workerNostrPubkey = workerPubkey,
            manifestHash = manifestHash,
            captureChainRoot = chainRoot,
            tapClaimAssetId = map["tap_claim_asset_id"],
            provenanceLevel = provenanceLevel,
            skillTag = skillTag
        )
    }
}
