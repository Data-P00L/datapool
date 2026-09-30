package capture

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"time"

	"github.com/dattapool/mvp/internal/crypto"
	"github.com/dattapool/mvp/internal/nostr"
	"github.com/dattapool/mvp/pkg/protocol"
)

// SessionState tracks the runtime lifecycle of an active capture session.
type SessionState struct {
	SessionID          string                              `json:"session_id"`
	Status             string                              `json:"status"` // "STARTED", "INGESTED", "SEALED"
	WorkerPubKey       string                              `json:"worker_pubkey"`
	DevicePubKey       string                              `json:"device_pubkey"`
	SessionKeyPair     *crypto.KeyPair                     `json:"session_key_pair"`
	SessionNonceHex    string                              `json:"session_nonce_hex"`
	NonceCommitmentHex string                              `json:"nonce_commitment_hex"`
	Freshness          protocol.FreshnessReference         `json:"freshness"`
	Witness            protocol.WitnessInfo                `json:"witness"`
	WorkerDeviceAuth   protocol.WorkerDeviceAuthorization  `json:"worker_device_authorization"`
	DeviceSessionAuth  protocol.DeviceSessionAuthorization `json:"device_session_authorization"`
	SkillTag           string                              `json:"skill_tag"`
	StartedAt          int64                               `json:"started_at"`
	EndedAt            int64                               `json:"ended_at"`
	ProcessedData      *ProcessedCaptureData               `json:"processed_data,omitempty"`
	Manifest           *protocol.CaptureManifest           `json:"manifest,omitempty"`
}

// StartSessionOptions provides configuration for initializing a capture session.
type StartSessionOptions struct {
	WorkerKeyPath   string
	DeviceKeyPath   string
	SkillTag        string
	WitnessMode     string // "nostr", "mock", "offline"
	FreshnessMode   string // "bitcoin", "local"
	SessionTTLHours int
	StoreDir        string
}

// StartSession initializes an authenticated pre-capture session.
func StartSession(opts StartSessionOptions) (*SessionState, error) {
	if opts.SkillTag == "" {
		opts.SkillTag = protocol.DefaultSkillTag
	}
	if opts.SessionTTLHours <= 0 {
		opts.SessionTTLHours = 24
	}

	workerKey, err := crypto.LoadKeyFromFile(opts.WorkerKeyPath)
	if err != nil {
		return nil, fmt.Errorf("failed to load worker key: %w", err)
	}

	deviceKey, err := crypto.LoadKeyFromFile(opts.DeviceKeyPath)
	if err != nil {
		return nil, fmt.Errorf("failed to load device key: %w", err)
	}

	sessionKey, err := crypto.GenerateKeyPair()
	if err != nil {
		return nil, fmt.Errorf("failed to generate session key: %w", err)
	}

	// 1. Generate random 256-bit session nonce and domain-separated commitment
	nonceBytes := make([]byte, 32)
	if _, err := rand.Read(nonceBytes); err != nil {
		return nil, fmt.Errorf("failed to generate random session nonce: %w", err)
	}
	nonceHex := hex.EncodeToString(nonceBytes)

	sessionIDBytes := make([]byte, 16)
	if _, err := rand.Read(sessionIDBytes); err != nil {
		return nil, fmt.Errorf("failed to generate session ID: %w", err)
	}
	sessionID := hex.EncodeToString(sessionIDBytes)

	nonceCommitment := protocol.ComputeNonceCommitment(sessionID, nonceHex)
	now := time.Now().Unix()
	expiresAt := now + int64(opts.SessionTTLHours*3600)

	// 2. Worker -> Device Authorization
	workerDevicePayload := protocol.BuildWorkerDeviceAuthPayload(workerKey.PublicKeyHex, deviceKey.PublicKeyHex, now, expiresAt)
	workerDeviceSig, err := workerKey.SignMessage(workerDevicePayload)
	if err != nil {
		return nil, fmt.Errorf("failed to sign worker-device authorization: %w", err)
	}

	workerDeviceAuth := protocol.WorkerDeviceAuthorization{
		WorkerPubKey:    workerKey.PublicKeyHex,
		DevicePubKey:    deviceKey.PublicKeyHex,
		IssuedAt:        now,
		ExpiresAt:       expiresAt,
		WorkerSignature: workerDeviceSig,
	}

	// 3. Device -> Session Authorization
	deviceSessionPayload := protocol.BuildDeviceSessionAuthPayload(deviceKey.PublicKeyHex, sessionKey.PublicKeyHex, sessionID, nonceCommitment, now, expiresAt)
	deviceSessionSig, err := deviceKey.SignMessage(deviceSessionPayload)
	if err != nil {
		return nil, fmt.Errorf("failed to sign device-session authorization: %w", err)
	}

	deviceSessionAuth := protocol.DeviceSessionAuthorization{
		DevicePubKey:    deviceKey.PublicKeyHex,
		SessionPubKey:   sessionKey.PublicKeyHex,
		SessionID:       sessionID,
		NonceCommitment: nonceCommitment,
		IssuedAt:        now,
		ExpiresAt:       expiresAt,
		DeviceSignature: deviceSessionSig,
	}

	// 4. Freshness reference
	var freshness protocol.FreshnessReference
	if opts.FreshnessMode == "local" {
		freshness = protocol.FreshnessReference{Type: "local_only"}
	} else {
		freshness = QueryBitcoinFreshness()
	}

	// 5. Pre-Capture Nostr Witness Event (if not explicitly offline)
	var witnessInfo protocol.WitnessInfo
	if opts.WitnessMode != "offline" {
		witnessEvent, err := nostr.CreateSessionStartWitnessEvent(
			workerKey,
			sessionID,
			deviceKey.PublicKeyHex,
			sessionKey.PublicKeyHex,
			nonceCommitment,
			freshness,
			opts.SkillTag,
			now,
		)
		if err == nil {
			publisher := nostr.GetDefaultWitnessPublisher(opts.WitnessMode, opts.StoreDir)
			pubRes, err := publisher.PublishSessionStart(context.Background(), witnessEvent)
			if err == nil && pubRes != nil && pubRes.Success {
				witnessInfo = protocol.WitnessInfo{
					NostrEventID: witnessEvent.ID,
					RelayURLs:    pubRes.RelayURLs,
					Published:    true,
				}
			}
		}
	}

	return &SessionState{
		SessionID:          sessionID,
		Status:             "STARTED",
		WorkerPubKey:       workerKey.PublicKeyHex,
		DevicePubKey:       deviceKey.PublicKeyHex,
		SessionKeyPair:     sessionKey,
		SessionNonceHex:    nonceHex,
		NonceCommitmentHex: nonceCommitment,
		Freshness:          freshness,
		Witness:            witnessInfo,
		WorkerDeviceAuth:   workerDeviceAuth,
		DeviceSessionAuth:  deviceSessionAuth,
		SkillTag:           opts.SkillTag,
		StartedAt:          now,
	}, nil
}

// Ingest processes raw video and telemetry files into temporally bound chunk chains.
func (s *SessionState) Ingest(videoPath, telemetryPath string) error {
	if s.Status != "STARTED" {
		return fmt.Errorf("session cannot ingest in status %s", s.Status)
	}

	// 1. Chunk video (deterministic 64KB chunks)
	videoChunks, videoSHA256, err := ChunkVideoFile(videoPath, protocol.DefaultChunkSize)
	if err != nil {
		return fmt.Errorf("video processing failed: %w", err)
	}

	// 2. Parse & chunk telemetry
	telemetryRecords, err := ReadTelemetryJSONL(telemetryPath)
	if err != nil {
		return fmt.Errorf("telemetry parsing failed: %w", err)
	}

	telemetryChunks := ChunkTelemetryRecords(telemetryRecords, len(videoChunks))
	if len(telemetryChunks) != len(videoChunks) {
		return fmt.Errorf("chunk mismatch: %d video chunks vs %d telemetry chunks", len(videoChunks), len(telemetryChunks))
	}

	// 3. Compute Merkle roots
	var videoChunkHashes []string
	for _, chk := range videoChunks {
		h := sha256.Sum256(chk)
		videoChunkHashes = append(videoChunkHashes, hex.EncodeToString(h[:]))
	}

	var telemetryChunkHashes []string
	for _, chk := range telemetryChunks {
		h, err := chk.ComputeHash()
		if err != nil {
			return fmt.Errorf("telemetry chunk hashing failed: %w", err)
		}
		telemetryChunkHashes = append(telemetryChunkHashes, h)
	}

	videoMerkleRoot, err := crypto.ComputeMerkleRoot(videoChunkHashes)
	if err != nil {
		return fmt.Errorf("video merkle root failed: %w", err)
	}

	telemetryMerkleRoot, err := crypto.ComputeMerkleRoot(telemetryChunkHashes)
	if err != nil {
		return fmt.Errorf("telemetry merkle root failed: %w", err)
	}

	// 4. Compute temporal continuous hash chain H0 -> Hn
	chain, err := crypto.NewContinuousHashChain(s.SessionID, s.NonceCommitmentHex)
	if err != nil {
		return fmt.Errorf("failed to init hash chain: %w", err)
	}

	durationSec := 10.0 // Default or estimated capture duration
	chunkDuration := durationSec / float64(len(videoChunks))

	for i := 0; i < len(videoChunks); i++ {
		startOffset := float64(i) * chunkDuration
		endOffset := float64(i+1) * chunkDuration
		binding := crypto.ChunkTemporalBinding{
			Index:              i,
			StartOffsetSec:     startOffset,
			EndOffsetSec:       endOffset,
			VideoChunkHash:     videoChunkHashes[i],
			TelemetryChunkHash: telemetryChunkHashes[i],
		}
		if err := chain.AppendChunkTemporal(binding); err != nil {
			return fmt.Errorf("hash chain step %d failed: %w", i, err)
		}
	}

	s.ProcessedData = &ProcessedCaptureData{
		VideoSHA256:          videoSHA256,
		VideoChunkHashes:     videoChunkHashes,
		TelemetryChunkHashes: telemetryChunkHashes,
		VideoMerkleRoot:      videoMerkleRoot,
		TelemetryMerkleRoot:  telemetryMerkleRoot,
		CaptureChainRoot:     chain.RootHex(),
		ChunkCount:           len(videoChunks),
	}

	s.Status = "INGESTED"
	return nil
}

// StopAndSeal signs the capture summary and constructs the sealed CaptureManifest (0.4.0).
func (s *SessionState) StopAndSeal(deviceKeyPath string) (*protocol.CaptureManifest, error) {
	if s.Status != "INGESTED" || s.ProcessedData == nil {
		return nil, errors.New("cannot seal session before ingestion is complete")
	}

	deviceKey, err := crypto.LoadKeyFromFile(deviceKeyPath)
	if err != nil {
		return nil, fmt.Errorf("failed to load device key: %w", err)
	}

	s.EndedAt = time.Now().Unix()

	// 1. Session Seal Payload & Signature
	sealPayload := protocol.BuildCaptureSealPayload(
		s.SessionID,
		s.ProcessedData.ChunkCount,
		s.ProcessedData.CaptureChainRoot,
		s.ProcessedData.VideoMerkleRoot,
		s.ProcessedData.TelemetryMerkleRoot,
		s.ProcessedData.VideoSHA256,
		s.StartedAt,
		s.EndedAt,
		s.Witness.NostrEventID,
	)

	sessionSealSig, err := s.SessionKeyPair.SignMessage(sealPayload)
	if err != nil {
		return nil, fmt.Errorf("failed to sign session seal: %w", err)
	}

	deviceSealSig, err := deviceKey.SignMessage(sealPayload)
	if err != nil {
		return nil, fmt.Errorf("failed to sign device seal: %w", err)
	}

	captureSeal := protocol.CaptureSeal{
		SessionID:                s.SessionID,
		ChunkCount:               s.ProcessedData.ChunkCount,
		CaptureChainRoot:         s.ProcessedData.CaptureChainRoot,
		VideoMerkleRoot:          s.ProcessedData.VideoMerkleRoot,
		TelemetryMerkleRoot:      s.ProcessedData.TelemetryMerkleRoot,
		VideoSHA256:              s.ProcessedData.VideoSHA256,
		CaptureStartedAt:         s.StartedAt,
		CaptureEndedAt:           s.EndedAt,
		SessionStartNostrEventID: s.Witness.NostrEventID,
		SessionSealSignature:     sessionSealSig,
		DeviceSealSignature:      deviceSealSig,
	}

	// 2. Provenance level determination
	provenanceLevel := protocol.LevelP3
	if s.Witness.Published && s.Witness.NostrEventID != "" && s.Freshness.Type == "bitcoin_block" {
		provenanceLevel = protocol.LevelP4
	}

	// 3. Assemble Canonical CaptureManifest 0.4.0
	manifest := &protocol.CaptureManifest{
		Protocol:      protocol.ProtocolName,
		SchemaVersion: protocol.SchemaVersion,
		Worker: protocol.WorkerIdentity{
			Type:        "human",
			NostrPubKey: s.WorkerPubKey,
		},
		Device: protocol.DeviceIdentity{
			PubKey: s.DevicePubKey,
			Type:   "camera_robot",
		},
		Session: protocol.SessionInfo{
			ID:              s.SessionID,
			PubKey:          s.SessionKeyPair.PublicKeyHex,
			NonceCommitment: s.NonceCommitmentHex,
			StartedAt:       s.StartedAt,
			EndedAt:         s.EndedAt,
			Freshness:       s.Freshness,
		},
		Witness: s.Witness,
		Capture: protocol.CaptureSummary{
			VideoSHA256:         s.ProcessedData.VideoSHA256,
			VideoMerkleRoot:     s.ProcessedData.VideoMerkleRoot,
			TelemetryMerkleRoot: s.ProcessedData.TelemetryMerkleRoot,
			CaptureChainRoot:    s.ProcessedData.CaptureChainRoot,
			ChunkCount:          s.ProcessedData.ChunkCount,
		},
		Task: protocol.TaskInfo{
			SkillTag: s.SkillTag,
		},
		Authorization: protocol.Authorization{
			WorkerDeviceAuth:  s.WorkerDeviceAuth,
			DeviceSessionAuth: s.DeviceSessionAuth,
			// Backward compatibility
			WorkerSignature: s.WorkerDeviceAuth.WorkerSignature,
			ExpiresAt:       s.WorkerDeviceAuth.ExpiresAt,
		},
		Seal: captureSeal,
		Signatures: protocol.Signatures{
			SessionSealSignature: sessionSealSig,
			DeviceSealSignature:  deviceSealSig,
			// Backward compatibility
			SessionSignature: sessionSealSig,
			DeviceSignature:  deviceSealSig,
		},
		Provenance: protocol.ProvenanceMetadata{
			Level: provenanceLevel,
		},
	}

	if err := manifest.Validate(); err != nil {
		return nil, fmt.Errorf("generated manifest failed validation: %w", err)
	}

	s.Manifest = manifest
	s.Status = "SEALED"
	return manifest, nil
}

// SaveSessionState persists the session state to a JSON file.
func (s *SessionState) SaveSessionState(path string) error {
	raw, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return err
	}
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0755); err != nil {
		return err
	}
	return os.WriteFile(path, raw, 0644)
}

// LoadSessionState reads a SessionState from a JSON file.
func LoadSessionState(path string) (*SessionState, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var state SessionState
	if err := json.Unmarshal(data, &state); err != nil {
		return nil, err
	}
	return &state, nil
}
