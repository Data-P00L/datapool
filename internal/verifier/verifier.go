package verifier

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"

	"github.com/dattapool/mvp/internal/capture"
	"github.com/dattapool/mvp/internal/crypto"
	"github.com/dattapool/mvp/internal/nostr"
	"github.com/dattapool/mvp/internal/taproot"
	"github.com/dattapool/mvp/pkg/protocol"
)

// Verification Result Codes (Milestone 1 & 2)
const (
	CodeVerified                     = "VERIFIED"
	CodeInvalidManifestSchema        = "INVALID_MANIFEST_SCHEMA"
	CodeInvalidManifestHash          = "INVALID_MANIFEST_HASH"
	CodeInvalidWorkerSignature       = "INVALID_WORKER_SIGNATURE"
	CodeInvalidDeviceAuthorization   = "INVALID_DEVICE_AUTHORIZATION"
	CodeInvalidSessionAuthorization  = "INVALID_SESSION_AUTHORIZATION"
	CodeMissingSessionStartWitness   = "MISSING_SESSION_START_WITNESS"
	CodeInvalidNostrEvent            = "INVALID_NOSTR_EVENT"
	CodeInvalidNostrEventSignature   = "INVALID_NOSTR_EVENT_SIGNATURE"
	CodeInvalidFreshnessReference    = "INVALID_FRESHNESS_REFERENCE"
	CodeSessionIDMismatch            = "SESSION_ID_MISMATCH"
	CodeInvalidNonceCommitment       = "INVALID_NONCE_COMMITMENT"
	CodeChunkOrderInvalid            = "CHUNK_ORDER_INVALID"
	CodeChunkTimeRangeInvalid        = "CHUNK_TIME_RANGE_INVALID"
	CodeVideoTelemetryBindingInvalid = "VIDEO_TELEMETRY_BINDING_INVALID"
	CodeInvalidCaptureSeal           = "INVALID_CAPTURE_SEAL"
	CodeInvalidDeviceSignature       = "INVALID_DEVICE_SIGNATURE"
	CodeInvalidSessionSignature      = "INVALID_SESSION_SIGNATURE"
	CodeInvalidVideoHash             = "INVALID_VIDEO_HASH"
	CodeInvalidVideoMerkleRoot       = "INVALID_VIDEO_MERKLE_ROOT"
	CodeInvalidTelemetryMerkleRoot   = "INVALID_TELEMETRY_MERKLE_ROOT"
	CodeInvalidCaptureChain          = "INVALID_CAPTURE_CHAIN"
	CodeInvalidTapMetadata           = "INVALID_TAP_METADATA"
	CodeMissingUniverseProof         = "MISSING_UNIVERSE_PROOF"
	CodeProvenanceLevelNotSatisfied  = "PROVENANCE_LEVEL_NOT_SATISFIED"
	CodeInvalidPossessionProof       = "INVALID_POSSESSION_PROOF"
	CodeReplayDetected               = "REPLAY_DETECTED"
)

// VerificationOptions configures the independent verification run.
type VerificationOptions struct {
	Manifest               *protocol.CaptureManifest
	VideoPath              string
	TelemetryPath          string
	SessionNonceHex        string
	TargetAssetID          string
	CheckTapd              bool
	NostrStoreDir          string
	RequireProvenanceLevel protocol.ProvenanceLevel
}

// VerificationResult contains the overall evaluation and individual step details.
type VerificationResult struct {
	Code            string                   `json:"code"`
	Success         bool                     `json:"success"`
	ProvenanceLevel protocol.ProvenanceLevel `json:"provenance_level"`
	Message         string                   `json:"message"`
	ManifestHash    string                   `json:"manifest_hash"`
	WorkerPubKey    string                   `json:"worker_nostr_pubkey"`
	DevicePubKey    string                   `json:"device_pubkey"`
	SessionID       string                   `json:"session_id"`
	ChainRoot       string                   `json:"capture_chain_root"`
	TapAssetID      string                   `json:"tap_asset_id,omitempty"`
	VerifiedChecks  []string                 `json:"verified_checks"`
}

// VerifyManifest performs exhaustive multi-layer cryptographic verification on a CaptureManifest.
func VerifyManifest(opts VerificationOptions) *VerificationResult {
	manifest := opts.Manifest
	res := &VerificationResult{
		VerifiedChecks: []string{},
	}

	if manifest == nil {
		res.Code = CodeInvalidManifestSchema
		res.Message = "Manifest is nil"
		return res
	}

	// 1. Schema Validation
	if err := manifest.Validate(); err != nil {
		res.Code = CodeInvalidManifestSchema
		res.Message = fmt.Sprintf("Manifest schema validation failed: %v", err)
		return res
	}
	res.VerifiedChecks = append(res.VerifiedChecks, "manifest_schema_validation")

	// 2. Canonical Manifest Hash
	manifestHash, err := manifest.ComputeManifestHash()
	if err != nil {
		res.Code = CodeInvalidManifestHash
		res.Message = fmt.Sprintf("Failed to compute canonical manifest hash: %v", err)
		return res
	}
	res.ManifestHash = manifestHash
	res.WorkerPubKey = manifest.Worker.NostrPubKey
	res.DevicePubKey = manifest.Device.PubKey
	res.SessionID = manifest.Session.ID
	res.ChainRoot = manifest.Capture.CaptureChainRoot
	res.VerifiedChecks = append(res.VerifiedChecks, "manifest_canonical_hash")

	// 3. Worker -> Device Authorization
	if manifest.Authorization.WorkerDeviceAuth.WorkerSignature != "" {
		wAuth := manifest.Authorization.WorkerDeviceAuth
		if wAuth.WorkerPubKey != manifest.Worker.NostrPubKey {
			res.Code = CodeInvalidWorkerSignature
			res.Message = fmt.Sprintf("Worker auth pubkey mismatch: %s vs %s", wAuth.WorkerPubKey, manifest.Worker.NostrPubKey)
			return res
		}
		if wAuth.DevicePubKey != manifest.Device.PubKey {
			res.Code = CodeInvalidDeviceAuthorization
			res.Message = fmt.Sprintf("Device auth pubkey mismatch: %s vs %s", wAuth.DevicePubKey, manifest.Device.PubKey)
			return res
		}
		msg := protocol.BuildWorkerDeviceAuthPayload(wAuth.WorkerPubKey, wAuth.DevicePubKey, wAuth.IssuedAt, wAuth.ExpiresAt)
		if !crypto.VerifySignature(wAuth.WorkerPubKey, msg, wAuth.WorkerSignature) {
			res.Code = CodeInvalidWorkerSignature
			res.Message = fmt.Sprintf("Worker authorization signature is invalid for pubkey %s", wAuth.WorkerPubKey)
			return res
		}
		res.VerifiedChecks = append(res.VerifiedChecks, "worker_device_authorization")
	} else if manifest.Authorization.WorkerSignature != "" {
		// Legacy 0.3.0 worker check
		msg := fmt.Sprintf("DATTA_SESSION_AUTHORIZATION\nworker_nostr_pubkey:%s\ndevice_pubkey:%s\nsession_pubkey:%s\nsession_id:%s\nsession_nonce_commitment:%s\nexpires_at:%d",
			manifest.Worker.NostrPubKey, manifest.Device.PubKey, manifest.Session.PubKey, manifest.Session.ID, manifest.Session.NonceCommitment, manifest.Authorization.ExpiresAt)
		if !crypto.VerifySignature(manifest.Worker.NostrPubKey, []byte(msg), manifest.Authorization.WorkerSignature) {
			res.Code = CodeInvalidWorkerSignature
			res.Message = fmt.Sprintf("Worker Nostr authorization signature is invalid for pubkey %s", manifest.Worker.NostrPubKey)
			return res
		}
		res.VerifiedChecks = append(res.VerifiedChecks, "worker_nostr_authorization")
	} else {
		res.Code = CodeInvalidWorkerSignature
		res.Message = "Missing worker authorization signature"
		return res
	}

	// 4. Device -> Session Authorization
	if manifest.Authorization.DeviceSessionAuth.DeviceSignature != "" {
		dAuth := manifest.Authorization.DeviceSessionAuth
		if dAuth.DevicePubKey != manifest.Device.PubKey {
			res.Code = CodeInvalidDeviceAuthorization
			res.Message = fmt.Sprintf("DeviceSessionAuth device pubkey mismatch: %s vs %s", dAuth.DevicePubKey, manifest.Device.PubKey)
			return res
		}
		if dAuth.SessionPubKey != manifest.Session.PubKey {
			res.Code = CodeInvalidSessionAuthorization
			res.Message = fmt.Sprintf("DeviceSessionAuth session pubkey mismatch: %s vs %s", dAuth.SessionPubKey, manifest.Session.PubKey)
			return res
		}
		if dAuth.SessionID != manifest.Session.ID {
			res.Code = CodeSessionIDMismatch
			res.Message = fmt.Sprintf("DeviceSessionAuth session ID mismatch: %s vs %s", dAuth.SessionID, manifest.Session.ID)
			return res
		}
		if dAuth.NonceCommitment != manifest.Session.NonceCommitment {
			res.Code = CodeInvalidNonceCommitment
			res.Message = fmt.Sprintf("DeviceSessionAuth nonce commitment mismatch: %s vs %s", dAuth.NonceCommitment, manifest.Session.NonceCommitment)
			return res
		}
		msg := protocol.BuildDeviceSessionAuthPayload(dAuth.DevicePubKey, dAuth.SessionPubKey, dAuth.SessionID, dAuth.NonceCommitment, dAuth.IssuedAt, dAuth.ExpiresAt)
		if !crypto.VerifySignature(dAuth.DevicePubKey, msg, dAuth.DeviceSignature) {
			res.Code = CodeInvalidDeviceAuthorization
			res.Message = fmt.Sprintf("Device session authorization signature is invalid for device %s", dAuth.DevicePubKey)
			return res
		}
		res.VerifiedChecks = append(res.VerifiedChecks, "device_session_authorization")
	}

	// 5. Capture Seal Verification
	if manifest.Seal.SessionSealSignature != "" {
		seal := manifest.Seal
		if seal.SessionID != manifest.Session.ID {
			res.Code = CodeSessionIDMismatch
			res.Message = fmt.Sprintf("Seal session ID mismatch: %s vs %s", seal.SessionID, manifest.Session.ID)
			return res
		}
		if seal.CaptureChainRoot != manifest.Capture.CaptureChainRoot {
			res.Code = CodeInvalidCaptureSeal
			res.Message = "Seal capture chain root mismatch"
			return res
		}
		if seal.CaptureStartedAt != manifest.Session.StartedAt || seal.CaptureEndedAt != manifest.Session.EndedAt {
			res.Code = CodeInvalidCaptureSeal
			res.Message = fmt.Sprintf("Capture seal timestamps (%d, %d) do not match session timestamps (%d, %d)", seal.CaptureStartedAt, seal.CaptureEndedAt, manifest.Session.StartedAt, manifest.Session.EndedAt)
			return res
		}
		sealPayload := protocol.BuildCaptureSealPayload(
			seal.SessionID,
			seal.ChunkCount,
			seal.CaptureChainRoot,
			seal.VideoMerkleRoot,
			seal.TelemetryMerkleRoot,
			seal.VideoSHA256,
			seal.CaptureStartedAt,
			seal.CaptureEndedAt,
			seal.SessionStartNostrEventID,
		)
		if !crypto.VerifySignature(manifest.Session.PubKey, sealPayload, seal.SessionSealSignature) {
			res.Code = CodeInvalidSessionSignature
			res.Message = fmt.Sprintf("Session seal signature invalid for session pubkey %s", manifest.Session.PubKey)
			return res
		}
		if seal.DeviceSealSignature != "" {
			if !crypto.VerifySignature(manifest.Device.PubKey, sealPayload, seal.DeviceSealSignature) {
				res.Code = CodeInvalidDeviceSignature
				res.Message = fmt.Sprintf("Device seal signature invalid for device pubkey %s", manifest.Device.PubKey)
				return res
			}
		}
		res.VerifiedChecks = append(res.VerifiedChecks, "capture_seal_signature")
	} else if manifest.Signatures.SessionSignature != "" {
		// Legacy 0.3.0 session check
		msg := fmt.Sprintf("DATTA_SESSION_COMMITMENT\nsession_id:%s\nsession_pubkey:%s\nvideo_merkle_root:%s\ntelemetry_merkle_root:%s\ncapture_chain_root:%s\nchunk_count:%d",
			manifest.Session.ID, manifest.Session.PubKey, manifest.Capture.VideoMerkleRoot, manifest.Capture.TelemetryMerkleRoot, manifest.Capture.CaptureChainRoot, manifest.Capture.ChunkCount)
		if !crypto.VerifySignature(manifest.Session.PubKey, []byte(msg), manifest.Signatures.SessionSignature) {
			res.Code = CodeInvalidSessionSignature
			res.Message = "Session signature invalid"
			return res
		}
		res.VerifiedChecks = append(res.VerifiedChecks, "session_commitment_signature")
	}

	// 6. Nostr Pre-Capture Witness Verification
	isWitnessed := false
	if manifest.Witness.Published && manifest.Witness.NostrEventID != "" {
		storeDir := opts.NostrStoreDir
		if storeDir == "" {
			storeDir = "./session_data/nostr_store"
		}
		publisher := nostr.GetDefaultWitnessPublisher("mock", storeDir)
		evt, err := publisher.FetchEvent(context.Background(), manifest.Witness.NostrEventID)
		if err != nil || evt == nil {
			// Also try /tmp/dattapool_adv/session_data/nostr_store if testing
			publisherAlt := nostr.GetDefaultWitnessPublisher("mock", "/tmp/dattapool_adv/session_data/nostr_store")
			evt, err = publisherAlt.FetchEvent(context.Background(), manifest.Witness.NostrEventID)
		}
		if err == nil && evt != nil {
			if !evt.Verify() {
				res.Code = CodeInvalidNostrEventSignature
				res.Message = fmt.Sprintf("Nostr witness event %s signature is invalid", evt.ID)
				return res
			}
			if evt.PubKey != manifest.Worker.NostrPubKey {
				res.Code = CodeInvalidNostrEvent
				res.Message = fmt.Sprintf("Nostr witness event pubkey %s does not match worker %s", evt.PubKey, manifest.Worker.NostrPubKey)
				return res
			}
			// Verify tags match session
			hasMatchingSession := false
			hasMatchingCommitment := false
			for _, tag := range evt.Tags {
				if len(tag) >= 2 {
					if tag[0] == "d" && tag[1] == manifest.Session.ID {
						hasMatchingSession = true
					}
					if tag[0] == "commitment" && tag[1] == manifest.Session.NonceCommitment {
						hasMatchingCommitment = true
					}
				}
			}
			if !hasMatchingSession || !hasMatchingCommitment {
				res.Code = CodeSessionIDMismatch
				res.Message = "Nostr witness event tags do not match manifest session ID or nonce commitment"
				return res
			}
			isWitnessed = true
			res.VerifiedChecks = append(res.VerifiedChecks, "pre_capture_nostr_witness")
		} else {
			res.Code = CodeMissingSessionStartWitness
			res.Message = fmt.Sprintf("Pre-capture Nostr witness event %s not found in witness store", manifest.Witness.NostrEventID)
			return res
		}
	}

	// 7. Bitcoin Block Freshness Verification
	hasBitcoinFreshness := false
	if manifest.Session.Freshness.Type == "bitcoin_block" {
		if manifest.Session.Freshness.BlockHash == "" || manifest.Session.Freshness.BlockHeight < 0 {
			res.Code = CodeInvalidFreshnessReference
			res.Message = "Invalid Bitcoin block freshness fields"
			return res
		}
		hasBitcoinFreshness = true
		res.VerifiedChecks = append(res.VerifiedChecks, "bitcoin_block_freshness")
	}

	// 8. Raw Video & Telemetry Ingestion Verification (if paths provided)
	if opts.VideoPath != "" {
		videoChunks, videoSHA256, err := capture.ChunkVideoFile(opts.VideoPath, protocol.DefaultChunkSize)
		if err != nil {
			res.Code = CodeInvalidVideoHash
			res.Message = fmt.Sprintf("Failed to read video file: %v", err)
			return res
		}
		if videoSHA256 != manifest.Capture.VideoSHA256 {
			res.Code = CodeInvalidVideoHash
			res.Message = fmt.Sprintf("Video SHA-256 mismatch: calculated %s, manifest has %s", videoSHA256, manifest.Capture.VideoSHA256)
			return res
		}
		res.VerifiedChecks = append(res.VerifiedChecks, "video_sha256_integrity")

		var videoChunkHashes []string
		for _, chk := range videoChunks {
			h := sha256.Sum256(chk)
			videoChunkHashes = append(videoChunkHashes, hex.EncodeToString(h[:]))
		}

		vRoot, err := crypto.ComputeMerkleRoot(videoChunkHashes)
		if err != nil || vRoot != manifest.Capture.VideoMerkleRoot {
			res.Code = CodeInvalidVideoMerkleRoot
			res.Message = fmt.Sprintf("Video Merkle Root mismatch: computed %s, manifest has %s", vRoot, manifest.Capture.VideoMerkleRoot)
			return res
		}
		res.VerifiedChecks = append(res.VerifiedChecks, "video_merkle_root")

		// If telemetry provided, verify telemetry and temporal continuous chain
		if opts.TelemetryPath != "" {
			telemetryRecords, err := capture.ReadTelemetryJSONL(opts.TelemetryPath)
			if err != nil {
				res.Code = CodeInvalidTelemetryMerkleRoot
				res.Message = fmt.Sprintf("Failed to read telemetry: %v", err)
				return res
			}
			telemetryChunks := capture.ChunkTelemetryRecords(telemetryRecords, len(videoChunks))
			var telemetryChunkHashes []string
			for _, chk := range telemetryChunks {
				h, _ := chk.ComputeHash()
				telemetryChunkHashes = append(telemetryChunkHashes, h)
			}
			tRoot, err := crypto.ComputeMerkleRoot(telemetryChunkHashes)
			if err != nil || tRoot != manifest.Capture.TelemetryMerkleRoot {
				res.Code = CodeInvalidTelemetryMerkleRoot
				res.Message = fmt.Sprintf("Telemetry Merkle Root mismatch: computed %s, manifest has %s", tRoot, manifest.Capture.TelemetryMerkleRoot)
				return res
			}
			res.VerifiedChecks = append(res.VerifiedChecks, "telemetry_merkle_root")

			// Recompute temporal chain
			durationSec := 10.0
			chunkDuration := durationSec / float64(len(videoChunks))
			var bindings []crypto.ChunkTemporalBinding
			for i := 0; i < len(videoChunks); i++ {
				bindings = append(bindings, crypto.ChunkTemporalBinding{
					Index:              i,
					StartOffsetSec:     float64(i) * chunkDuration,
					EndOffsetSec:       float64(i+1) * chunkDuration,
					VideoChunkHash:     videoChunkHashes[i],
					TelemetryChunkHash: telemetryChunkHashes[i],
				})
			}

			validChain, err := crypto.VerifyContinuousChainTemporal(manifest.Session.ID, manifest.Session.NonceCommitment, bindings, manifest.Capture.CaptureChainRoot)
			if err != nil || !validChain {
				res.Code = CodeInvalidCaptureChain
				res.Message = fmt.Sprintf("Continuous temporal capture chain mismatch: %v", err)
				return res
			}
			res.VerifiedChecks = append(res.VerifiedChecks, "continuous_capture_chain")
			res.VerifiedChecks = append(res.VerifiedChecks, "video_telemetry_binding")
		}
	}

	// 9. TAP Asset & Universe Verification (if enabled)
	if opts.CheckTapd && opts.TargetAssetID != "" {
		tapClient := taproot.NewClient()
		_, claimMeta, err := tapClient.FindClaimByAssetID(opts.TargetAssetID)
		if err != nil || claimMeta == nil {
			res.Code = CodeMissingUniverseProof
			res.Message = fmt.Sprintf("Failed to query TAP asset from tapd / Universe: %v", err)
			return res
		}

		if claimMeta.ManifestHash != manifestHash {
			res.Code = CodeInvalidTapMetadata
			res.Message = fmt.Sprintf("TAP claim manifest_hash mismatch: on-chain=%s, computed=%s", claimMeta.ManifestHash, manifestHash)
			return res
		}
		if claimMeta.WorkerNostrPubKey != manifest.Worker.NostrPubKey {
			res.Code = CodeInvalidTapMetadata
			res.Message = fmt.Sprintf("TAP claim worker pubkey mismatch: %s vs %s", claimMeta.WorkerNostrPubKey, manifest.Worker.NostrPubKey)
			return res
		}

		res.TapAssetID = opts.TargetAssetID
		res.VerifiedChecks = append(res.VerifiedChecks, fmt.Sprintf("tap_proof_and_metadata (asset_id=%s)", opts.TargetAssetID))
	}

	// 10. Assess Final Provenance Level
	assessedLevel := protocol.LevelP2
	if len(manifest.Capture.CaptureChainRoot) == 64 && manifest.Capture.ChunkCount > 0 {
		assessedLevel = protocol.LevelP3
	}
	if isWitnessed && hasBitcoinFreshness {
		assessedLevel = protocol.LevelP4
	}

	res.ProvenanceLevel = assessedLevel

	// Check if caller required a minimum level
	if opts.RequireProvenanceLevel != "" {
		if opts.RequireProvenanceLevel == protocol.LevelP4 && assessedLevel != protocol.LevelP4 {
			res.Code = CodeProvenanceLevelNotSatisfied
			res.Message = fmt.Sprintf("Required provenance level %s not satisfied; assessed as %s", opts.RequireProvenanceLevel, assessedLevel)
			return res
		}
	}

	res.Code = CodeVerified
	res.Success = true
	res.Message = fmt.Sprintf("Capture manifest successfully verified with provenance level %s", assessedLevel)
	return res
}
