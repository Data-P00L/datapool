package protocol

import (
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
)

// TAPClaimMetadata defines the compact metadata committed into the Taproot Asset claim (0.4.0).
type TAPClaimMetadata struct {
	Protocol                 string          `json:"protocol"`
	Type                     string          `json:"type"`
	SchemaVersion            string          `json:"schema_version"`
	ManifestHash             string          `json:"manifest_hash"`
	WorkerNostrPubKey        string          `json:"worker_nostr_pubkey"`
	DevicePubKey             string          `json:"device_pubkey"`
	SessionID                string          `json:"session_id"`
	SessionStartNostrEventID string          `json:"session_start_nostr_event_id,omitempty"`
	VideoMerkleRoot          string          `json:"video_merkle_root"`
	TelemetryMerkleRoot      string          `json:"telemetry_merkle_root"`
	CaptureChainRoot         string          `json:"capture_chain_root"`
	ProvenanceLevel          ProvenanceLevel `json:"provenance_level"`
}

// TokenMetadata defines project-level metadata for the DTTA / ₿DATA contribution unit asset.
type TokenMetadata struct {
	Project                string `json:"project"`
	Symbol                 string `json:"symbol"`                   // "DTTA"
	DisplaySymbol          string `json:"display_symbol"`           // "₿DATA"
	Type                   string `json:"type"`                     // "verified_robotics_data_contribution_unit"
	Purpose                string `json:"purpose"`                  // "robotics_data_contribution_accounting"
	MonetaryUnit           string `json:"monetary_unit"`            // "BTC_sats"
	BTCRedemptionGuarantee bool   `json:"btc_redemption_guarantee"` // false
	Version                string `json:"version"`                  // "0.2.0"
}

// BuildClaimMetadataFromManifest creates compact TAPClaimMetadata from a sealed CaptureManifest.
func BuildClaimMetadataFromManifest(m *CaptureManifest) (*TAPClaimMetadata, error) {
	if err := m.Validate(); err != nil {
		return nil, fmt.Errorf("cannot build claim metadata from invalid manifest: %w", err)
	}

	manifestHash, err := m.ComputeManifestHash()
	if err != nil {
		return nil, fmt.Errorf("failed to compute manifest hash: %w", err)
	}

	level := m.Provenance.Level
	if level == "" {
		level = LevelP3
	}

	claim := &TAPClaimMetadata{
		Protocol:                 ProtocolName,
		Type:                     ClaimType,
		SchemaVersion:            m.SchemaVersion,
		ManifestHash:             manifestHash,
		WorkerNostrPubKey:        m.Worker.NostrPubKey,
		DevicePubKey:             m.Device.PubKey,
		SessionID:                m.Session.ID,
		SessionStartNostrEventID: m.Witness.NostrEventID,
		VideoMerkleRoot:          m.Capture.VideoMerkleRoot,
		TelemetryMerkleRoot:      m.Capture.TelemetryMerkleRoot,
		CaptureChainRoot:         m.Capture.CaptureChainRoot,
		ProvenanceLevel:          level,
	}

	if err := claim.Validate(); err != nil {
		return nil, err
	}

	return claim, nil
}

// Validate checks the fields of TAPClaimMetadata.
func (c *TAPClaimMetadata) Validate() error {
	if c.Protocol != ProtocolName {
		return fmt.Errorf("invalid protocol: %q", c.Protocol)
	}
	if c.Type != ClaimType {
		return fmt.Errorf("invalid claim type: %q", c.Type)
	}
	if c.SchemaVersion != SchemaVersion && c.SchemaVersion != SchemaVersionV3 {
		return fmt.Errorf("invalid schema version: %q", c.SchemaVersion)
	}
	if !hex64Regex.MatchString(c.ManifestHash) {
		return fmt.Errorf("invalid manifest_hash: %q", c.ManifestHash)
	}
	if !hex64Regex.MatchString(c.WorkerNostrPubKey) {
		return fmt.Errorf("invalid worker_nostr_pubkey: %q", c.WorkerNostrPubKey)
	}
	if !hex64Regex.MatchString(c.DevicePubKey) {
		return fmt.Errorf("invalid device_pubkey: %q", c.DevicePubKey)
	}
	if c.SessionID == "" {
		return errors.New("missing session_id")
	}
	if !hex64Regex.MatchString(c.VideoMerkleRoot) {
		return fmt.Errorf("invalid video_merkle_root: %q", c.VideoMerkleRoot)
	}
	if !hex64Regex.MatchString(c.TelemetryMerkleRoot) {
		return fmt.Errorf("invalid telemetry_merkle_root: %q", c.TelemetryMerkleRoot)
	}
	if !hex64Regex.MatchString(c.CaptureChainRoot) {
		return fmt.Errorf("invalid capture_chain_root: %q", c.CaptureChainRoot)
	}
	return nil
}

// EncodeJSON serializes the claim metadata to canonical JSON.
func (c *TAPClaimMetadata) EncodeJSON() ([]byte, error) {
	if err := c.Validate(); err != nil {
		return nil, err
	}
	return CanonicalizeJSON(c)
}

// DecodeClaimMetadata parses JSON bytes into TAPClaimMetadata.
func DecodeClaimMetadata(data []byte) (*TAPClaimMetadata, error) {
	var claim TAPClaimMetadata
	if err := json.Unmarshal(data, &claim); err != nil {
		return nil, fmt.Errorf("failed to decode claim metadata: %w", err)
	}
	if err := claim.Validate(); err != nil {
		return nil, fmt.Errorf("invalid claim metadata: %w", err)
	}
	return &claim, nil
}

// DecodeHexClaimMetadata parses a hex string into TAPClaimMetadata.
func DecodeHexClaimMetadata(hexStr string) (*TAPClaimMetadata, error) {
	raw, err := hex.DecodeString(hexStr)
	if err != nil {
		return nil, fmt.Errorf("failed to decode hex string: %w", err)
	}
	return DecodeClaimMetadata(raw)
}

// DefaultDataTokenMetadata returns the canonical metadata for DTTA / ₿DATA contribution unit asset.
func DefaultDataTokenMetadata() *TokenMetadata {
	return &TokenMetadata{
		Project:                "DattaPool",
		Symbol:                 "DTTA",
		DisplaySymbol:          "₿DATA",
		Type:                   "verified_robotics_data_contribution_unit",
		Purpose:                "robotics_data_contribution_accounting",
		MonetaryUnit:           "BTC_sats",
		BTCRedemptionGuarantee: false,
		Version:                "0.2.0",
	}
}

// EncodeJSON serializes the token metadata.
func (t *TokenMetadata) EncodeJSON() ([]byte, error) {
	return CanonicalizeJSON(t)
}
