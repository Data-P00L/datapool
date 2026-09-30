// SPDX-License-Identifier: 0BSD

package protocol

const (
	// ProtocolName identifies the DattaPool protocol.
	ProtocolName = "dattapool"

	// SchemaVersion is the current protocol schema version for Milestone 2.
	SchemaVersion = "0.4.0"

	// SchemaVersionV3 is the legacy protocol schema version.
	SchemaVersionV3 = "0.3.0"

	// ClaimType specifies the TAP asset claim type.
	ClaimType = "capture_claim"

	// DefaultSkillTag is the fallback task skill tag.
	DefaultSkillTag = "robotics_manipulation"

	// DefaultChunkSize is the standard video chunking size (64KB).
	DefaultChunkSize = 64 * 1024

	// NostrKindDattaPoolSessionStart is the experimental Nostr event kind for DattaPool pre-capture witness.
	NostrKindDattaPoolSessionStart = 30078
)

// Cryptographic Domain Separation Constants
const (
	DomainSessionAuth            = "DATTA_SESSION_AUTHORIZATION_V1"
	DomainWorkerDeviceAuth       = "DATTA_WORKER_DEVICE_AUTHORIZATION_V1"
	DomainDeviceSessionAuth      = "DATTA_DEVICE_SESSION_AUTHORIZATION_V1"
	DomainSessionStartCommitment = "DATTA_SESSION_START_COMMITMENT_V1"
	DomainCaptureChunk           = "DATTA_CAPTURE_CHUNK_V1"
	DomainCaptureSeal            = "DATTA_CAPTURE_SEAL_V1"
	DomainProofOfPossession      = "DATTA_PROOF_OF_POSSESSION_V1"
	DomainNonceCommitment        = "DATTA_NONCE_COMMITMENT_V1"
)

// ProvenanceLevel defines the strength of cryptographic and temporal evidence.
type ProvenanceLevel string

const (
	LevelP0 ProvenanceLevel = "P0" // File integrity only (SHA-256)
	LevelP1 ProvenanceLevel = "P1" // Worker-signed claim
	LevelP2 ProvenanceLevel = "P2" // Authenticated Worker -> Device -> Session
	LevelP3 ProvenanceLevel = "P3" // Continuous multi-sensor chain & capture seal (Offline)
	LevelP4 ProvenanceLevel = "P4" // Externally witnessed pre-capture Nostr event + Bitcoin freshness
	LevelP5 ProvenanceLevel = "P5" // Hardware-attested capture (Reserved)
)

// FreshnessReference binds the session start to an external temporal anchor.
type FreshnessReference struct {
	Type        string `json:"type"`                   // "bitcoin_block" or "local_only"
	BlockHeight int64  `json:"block_height,omitempty"` // Bitcoin regtest/mainnet block height
	BlockHash   string `json:"block_hash,omitempty"`   // 64-character hex block hash
}

// WitnessInfo records the external Nostr pre-capture publication state.
type WitnessInfo struct {
	NostrEventID string   `json:"nostr_event_id"`       // 64-character hex Nostr event ID
	RelayURLs    []string `json:"relay_urls,omitempty"` // Relay endpoints where event was broadcast
	Published    bool     `json:"published"`            // True if verified published to relay/mock
}

// WorkerIdentity represents the worker identity (human or robot) identified by a Nostr pubkey.
type WorkerIdentity struct {
	Type        string `json:"type"`         // "human" or "robot"
	NostrPubKey string `json:"nostr_pubkey"` // 64-character hex Nostr public key
}

// DeviceIdentity represents the hardware or software device that performed the recording.
type DeviceIdentity struct {
	PubKey string `json:"pubkey"` // 64-character hex device public key
	Type   string `json:"type"`   // e.g., "camera_robot", "controller", "ego_camera"
}

// SessionInfo contains session-level commitments, timestamps, and freshness.
type SessionInfo struct {
	ID              string             `json:"id"`               // Unique UUID/hex session ID
	PubKey          string             `json:"pubkey"`           // 64-character hex session public key
	NonceCommitment string             `json:"nonce_commitment"` // SHA-256(DOMAIN || session_id || nonce) in hex
	StartedAt       int64              `json:"started_at"`       // Unix timestamp (seconds)
	EndedAt         int64              `json:"ended_at"`         // Unix timestamp (seconds)
	Freshness       FreshnessReference `json:"freshness"`        // External block or local reference
}

// CaptureSummary holds cryptographic commitments of the ingested streams.
type CaptureSummary struct {
	VideoSHA256         string `json:"video_sha256"`          // SHA-256 hash of the entire video file
	VideoMerkleRoot     string `json:"video_merkle_root"`     // Merkle root of video chunks
	TelemetryMerkleRoot string `json:"telemetry_merkle_root"` // Merkle root of telemetry chunks
	CaptureChainRoot    string `json:"capture_chain_root"`    // Continuous hash chain root H_n
	ChunkCount          int    `json:"chunk_count"`           // Number of chunks processed
}

// TaskInfo describes semantic details of the robotic task.
type TaskInfo struct {
	SkillTag        string `json:"skill_tag"`                  // e.g. "warehouse_object_pick"
	Environment     string `json:"environment,omitempty"`      // e.g. "simulated", "real_warehouse"
	RobotModel      string `json:"robot_model,omitempty"`      // e.g. "franka_emika_panda", "ur5e"
	TaskDescription string `json:"task_description,omitempty"` // Brief human-readable description
}

// WorkerDeviceAuthorization contains Worker Nostr signature delegating authority to a Device.
type WorkerDeviceAuthorization struct {
	WorkerPubKey    string `json:"worker_nostr_pubkey"`
	DevicePubKey    string `json:"device_pubkey"`
	IssuedAt        int64  `json:"issued_at"`
	ExpiresAt       int64  `json:"expires_at"`
	WorkerSignature string `json:"worker_signature"` // Signed by Worker Nostr key
}

// DeviceSessionAuthorization contains Device signature delegating authority to an Ephemeral Session.
type DeviceSessionAuthorization struct {
	DevicePubKey    string `json:"device_pubkey"`
	SessionPubKey   string `json:"session_pubkey"`
	SessionID       string `json:"session_id"`
	NonceCommitment string `json:"nonce_commitment"`
	IssuedAt        int64  `json:"issued_at"`
	ExpiresAt       int64  `json:"expires_at"`
	DeviceSignature string `json:"device_signature"` // Signed by Device key
}

// Authorization holds the complete two-tier delegation hierarchy.
type Authorization struct {
	WorkerDeviceAuth  WorkerDeviceAuthorization  `json:"worker_device_authorization"`
	DeviceSessionAuth DeviceSessionAuthorization `json:"device_session_authorization"`

	// Legacy 0.3.0 backward compatibility fields
	WorkerSignature string `json:"worker_signature,omitempty"`
	ExpiresAt       int64  `json:"expires_at,omitempty"`
}

// CaptureSeal contains final session commitments signed by the Session and Device keys.
type CaptureSeal struct {
	SessionID                string `json:"session_id"`
	ChunkCount               int    `json:"chunk_count"`
	CaptureChainRoot         string `json:"capture_chain_root"`
	VideoMerkleRoot          string `json:"video_merkle_root"`
	TelemetryMerkleRoot      string `json:"telemetry_merkle_root"`
	VideoSHA256              string `json:"video_sha256"`
	CaptureStartedAt         int64  `json:"capture_started_at"`
	CaptureEndedAt           int64  `json:"capture_ended_at"`
	SessionStartNostrEventID string `json:"session_start_nostr_event_id,omitempty"`
	SessionSealSignature     string `json:"session_seal_signature"` // Signed by session key
	DeviceSealSignature      string `json:"device_seal_signature"`  // Signed by device key
}

// ProvenanceMetadata stores the assessed provenance level and evaluation flags.
type ProvenanceMetadata struct {
	Level ProvenanceLevel `json:"level"` // e.g. "P4", "P3"
}

// Signatures contains cryptographic attestations on the manifest.
type Signatures struct {
	SessionSealSignature string `json:"session_seal_signature"`
	DeviceSealSignature  string `json:"device_seal_signature"`

	// Legacy 0.3.0 fields
	SessionSignature string `json:"session_signature,omitempty"`
	DeviceSignature  string `json:"device_signature,omitempty"`
}
