package protocol

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"regexp"
)

var (
	hex64Regex = regexp.MustCompile(`^[a-fA-F0-9]{64}$`)
)

// CaptureManifest is the comprehensive machine-generated provenance artifact for a capture session (0.4.0).
type CaptureManifest struct {
	Protocol      string             `json:"protocol"`
	SchemaVersion string             `json:"schema_version"`
	Worker        WorkerIdentity     `json:"worker"`
	Device        DeviceIdentity     `json:"device"`
	Session       SessionInfo        `json:"session"`
	Witness       WitnessInfo        `json:"witness,omitempty"`
	Capture       CaptureSummary     `json:"capture"`
	Task          TaskInfo           `json:"task"`
	Authorization Authorization      `json:"authorization"`
	Seal          CaptureSeal        `json:"seal,omitempty"`
	Signatures    Signatures         `json:"signatures"`
	Provenance    ProvenanceMetadata `json:"provenance"`
}

// ComputeNonceCommitment creates a domain-separated commitment: SHA256(DomainNonceCommitment || session_id || nonce).
func ComputeNonceCommitment(sessionID, nonceHex string) string {
	h := sha256.New()
	h.Write([]byte(DomainNonceCommitment))
	h.Write([]byte(sessionID))
	h.Write([]byte(nonceHex))
	return hex.EncodeToString(h.Sum(nil))
}

// BuildWorkerDeviceAuthPayload formats the message Worker signs to authorize a Device.
func BuildWorkerDeviceAuthPayload(workerPubKey, devicePubKey string, issuedAt, expiresAt int64) []byte {
	msg := fmt.Sprintf(
		"%s\nworker_nostr_pubkey:%s\ndevice_pubkey:%s\nissued_at:%d\nexpires_at:%d",
		DomainWorkerDeviceAuth,
		workerPubKey,
		devicePubKey,
		issuedAt,
		expiresAt,
	)
	return []byte(msg)
}

// BuildDeviceSessionAuthPayload formats the message Device signs to authorize an Ephemeral Session.
func BuildDeviceSessionAuthPayload(devicePubKey, sessionPubKey, sessionID, nonceCommitment string, issuedAt, expiresAt int64) []byte {
	msg := fmt.Sprintf(
		"%s\ndevice_pubkey:%s\nsession_pubkey:%s\nsession_id:%s\nsession_nonce_commitment:%s\nissued_at:%d\nexpires_at:%d",
		DomainDeviceSessionAuth,
		devicePubKey,
		sessionPubKey,
		sessionID,
		nonceCommitment,
		issuedAt,
		expiresAt,
	)
	return []byte(msg)
}

// BuildSessionStartCommitmentPayload formats the canonical content embedded in the Nostr pre-capture witness event.
func BuildSessionStartCommitmentPayload(sessionID, devicePubKey, sessionPubKey, nonceCommitment string, freshness FreshnessReference, skillTag string, createdAt int64) []byte {
	msg := fmt.Sprintf(
		"%s\nsession_id:%s\ndevice_pubkey:%s\nsession_pubkey:%s\nnonce_commitment:%s\nfreshness_type:%s\nblock_height:%d\nblock_hash:%s\nskill_tag:%s\ncreated_at:%d",
		DomainSessionStartCommitment,
		sessionID,
		devicePubKey,
		sessionPubKey,
		nonceCommitment,
		freshness.Type,
		freshness.BlockHeight,
		freshness.BlockHash,
		skillTag,
		createdAt,
	)
	return []byte(msg)
}

// BuildCaptureSealPayload formats the message signed by Session and Device keys on capture completion.
func BuildCaptureSealPayload(sessionID string, chunkCount int, chainRoot, videoMerkleRoot, telemetryMerkleRoot, videoSHA256 string, startedAt, endedAt int64, nostrEventID string) []byte {
	msg := fmt.Sprintf(
		"%s\nsession_id:%s\nchunk_count:%d\ncapture_chain_root:%s\nvideo_merkle_root:%s\ntelemetry_merkle_root:%s\nvideo_sha256:%s\nstarted_at:%d\nended_at:%d\nnostr_event_id:%s",
		DomainCaptureSeal,
		sessionID,
		chunkCount,
		chainRoot,
		videoMerkleRoot,
		telemetryMerkleRoot,
		videoSHA256,
		startedAt,
		endedAt,
		nostrEventID,
	)
	return []byte(msg)
}

// CanonicalJSON marshals the manifest deterministically into canonical JSON bytes.
func (m *CaptureManifest) CanonicalJSON() ([]byte, error) {
	return CanonicalizeJSON(m)
}

// ComputeManifestHash computes the SHA-256 hash over the canonical JSON of the manifest.
func (m *CaptureManifest) ComputeManifestHash() (string, error) {
	raw, err := m.CanonicalJSON()
	if err != nil {
		return "", fmt.Errorf("failed to encode canonical json: %w", err)
	}
	h := sha256.Sum256(raw)
	return hex.EncodeToString(h[:]), nil
}

// Validate checks structural integrity and required fields of the manifest.
func (m *CaptureManifest) Validate() error {
	if m.Protocol != ProtocolName {
		return fmt.Errorf("invalid protocol name: got %q, expected %q", m.Protocol, ProtocolName)
	}
	if m.SchemaVersion != SchemaVersion && m.SchemaVersion != SchemaVersionV3 {
		return fmt.Errorf("unsupported schema version: got %q, expected %q or %q", m.SchemaVersion, SchemaVersion, SchemaVersionV3)
	}
	if !hex64Regex.MatchString(m.Worker.NostrPubKey) {
		return fmt.Errorf("invalid worker nostr_pubkey: %q", m.Worker.NostrPubKey)
	}
	if !hex64Regex.MatchString(m.Device.PubKey) {
		return fmt.Errorf("invalid device pubkey: %q", m.Device.PubKey)
	}
	if !hex64Regex.MatchString(m.Session.PubKey) {
		return fmt.Errorf("invalid session pubkey: %q", m.Session.PubKey)
	}
	if m.Session.ID == "" {
		return errors.New("missing session id")
	}
	if !hex64Regex.MatchString(m.Session.NonceCommitment) {
		return fmt.Errorf("invalid session nonce_commitment: %q", m.Session.NonceCommitment)
	}
	if !hex64Regex.MatchString(m.Capture.VideoSHA256) {
		return fmt.Errorf("invalid video_sha256: %q", m.Capture.VideoSHA256)
	}
	if !hex64Regex.MatchString(m.Capture.VideoMerkleRoot) {
		return fmt.Errorf("invalid video_merkle_root: %q", m.Capture.VideoMerkleRoot)
	}
	if !hex64Regex.MatchString(m.Capture.TelemetryMerkleRoot) {
		return fmt.Errorf("invalid telemetry_merkle_root: %q", m.Capture.TelemetryMerkleRoot)
	}
	if !hex64Regex.MatchString(m.Capture.CaptureChainRoot) {
		return fmt.Errorf("invalid capture_chain_root: %q", m.Capture.CaptureChainRoot)
	}
	if m.Capture.ChunkCount <= 0 {
		return fmt.Errorf("chunk_count must be > 0, got %d", m.Capture.ChunkCount)
	}

	// 0.4.0 validations
	if m.SchemaVersion == SchemaVersion {
		if m.Authorization.WorkerDeviceAuth.WorkerSignature == "" && m.Authorization.WorkerSignature == "" {
			return errors.New("missing worker authorization signature")
		}
		if m.Authorization.DeviceSessionAuth.DeviceSignature == "" && m.Signatures.DeviceSignature == "" {
			return errors.New("missing device authorization signature")
		}
	}
	return nil
}

// SaveToFile writes the manifest to a file in canonical format.
func (m *CaptureManifest) SaveToFile(path string) error {
	raw, err := CanonicalJSONIndent(m)
	if err != nil {
		return err
	}
	return os.WriteFile(path, raw, 0644)
}

// LoadManifestFromFile reads and parses a CaptureManifest from a JSON file.
func LoadManifestFromFile(path string) (*CaptureManifest, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("failed to read manifest file %q: %w", path, err)
	}
	var manifest CaptureManifest
	if err := json.Unmarshal(data, &manifest); err != nil {
		return nil, fmt.Errorf("failed to unmarshal manifest json: %w", err)
	}
	if err := manifest.Validate(); err != nil {
		return nil, fmt.Errorf("manifest validation failed: %w", err)
	}
	return &manifest, nil
}
