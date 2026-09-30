package metadata

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"regexp"
	"time"
)

var (
	hexRegex = regexp.MustCompile(`^[a-fA-F0-9]+$`)
)

// VideoMetadata defines the structured metadata embedded in a collectible claim asset.
type VideoMetadata struct {
	WorkerPubKey  string `json:"worker_pubkey"`
	VideoHash     string `json:"video_hash"`
	Timestamp     int64  `json:"timestamp"`
	SkillTag      string `json:"skill_tag"`
	DataAssetID   string `json:"data_asset_id"`
	RewardAmount  int    `json:"reward_amount"`
	SchemaVersion string `json:"schema_version"`
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

// HashFile computes the SHA-256 hex hash of a local file.
func HashFile(filePath string) (string, error) {
	f, err := os.Open(filePath)
	if err != nil {
		return "", fmt.Errorf("failed to open file %q: %w", filePath, err)
	}
	defer f.Close()

	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", fmt.Errorf("failed to calculate hash: %w", err)
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

// Validate checks whether the video claim metadata satisfies required constraints.
func (m *VideoMetadata) Validate() error {
	if m.VideoHash == "" {
		return errors.New("video_hash is required")
	}
	if len(m.VideoHash) != 64 || !hexRegex.MatchString(m.VideoHash) {
		return fmt.Errorf("video_hash must be a 64-character hex string (SHA-256), got: %q", m.VideoHash)
	}

	if m.DataAssetID == "" {
		return errors.New("data_asset_id is required")
	}
	if len(m.DataAssetID) != 64 || !hexRegex.MatchString(m.DataAssetID) {
		return fmt.Errorf("data_asset_id must be a 64-character hex string, got: %q", m.DataAssetID)
	}

	if m.WorkerPubKey != "" && !hexRegex.MatchString(m.WorkerPubKey) {
		return fmt.Errorf("worker_pubkey must be a hex string if provided, got: %q", m.WorkerPubKey)
	}

	if m.RewardAmount < 0 {
		return fmt.Errorf("reward_amount cannot be negative, got: %d", m.RewardAmount)
	}

	if m.Timestamp <= 0 {
		m.Timestamp = time.Now().Unix()
	}

	if m.SchemaVersion == "" {
		m.SchemaVersion = "1.0"
	}

	return nil
}

// EncodeJSON serializes the metadata into bytes.
func (m *VideoMetadata) EncodeJSON() ([]byte, error) {
	if err := m.Validate(); err != nil {
		return nil, err
	}
	return json.Marshal(m)
}

// DecodeVideoMetadata decodes JSON bytes into a VideoMetadata object.
func DecodeVideoMetadata(data []byte) (*VideoMetadata, error) {
	var meta VideoMetadata
	if err := json.Unmarshal(data, &meta); err != nil {
		return nil, fmt.Errorf("failed to unmarshal video metadata: %w", err)
	}
	return &meta, nil
}

// DecodeHexMetadata decodes a hex-encoded JSON string into a VideoMetadata object.
func DecodeHexMetadata(hexStr string) (*VideoMetadata, error) {
	rawBytes, err := hex.DecodeString(hexStr)
	if err != nil {
		return nil, fmt.Errorf("invalid hex string: %w", err)
	}
	return DecodeVideoMetadata(rawBytes)
}

// DefaultTokenMetadata returns default metadata for DTTA / ₿DATA contribution unit asset.
func DefaultTokenMetadata() *TokenMetadata {
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

// EncodeJSON serializes the token metadata into bytes.
func (m *TokenMetadata) EncodeJSON() ([]byte, error) {
	return json.Marshal(m)
}
