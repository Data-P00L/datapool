package metadata

import (
	"strings"
	"testing"
	"time"
)

func TestVideoMetadata_Validate_Success(t *testing.T) {
	validHash := strings.Repeat("a", 64)
	validDataAssetID := strings.Repeat("b", 64)

	m := &VideoMetadata{
		WorkerPubKey: "02abcd1234",
		VideoHash:    validHash,
		DataAssetID:  validDataAssetID,
		RewardAmount: 100,
		SkillTag:     "manipulation",
	}

	if err := m.Validate(); err != nil {
		t.Fatalf("expected validation to succeed, got: %v", err)
	}

	if m.Timestamp <= 0 {
		t.Errorf("expected timestamp to be auto-populated, got %d", m.Timestamp)
	}

	if m.SchemaVersion != "1.0" {
		t.Errorf("expected SchemaVersion to default to '1.0', got %q", m.SchemaVersion)
	}
}

func TestVideoMetadata_Validate_Failures(t *testing.T) {
	validHash := strings.Repeat("a", 64)
	validDataAssetID := strings.Repeat("b", 64)

	tests := []struct {
		name    string
		meta    VideoMetadata
		wantErr string
	}{
		{
			name: "missing video hash",
			meta: VideoMetadata{
				DataAssetID: validDataAssetID,
			},
			wantErr: "video_hash is required",
		},
		{
			name: "invalid video hash length",
			meta: VideoMetadata{
				VideoHash:   "abc123",
				DataAssetID: validDataAssetID,
			},
			wantErr: "must be a 64-character hex string",
		},
		{
			name: "invalid video hash characters",
			meta: VideoMetadata{
				VideoHash:   strings.Repeat("z", 64),
				DataAssetID: validDataAssetID,
			},
			wantErr: "must be a 64-character hex string",
		},
		{
			name: "missing data asset ID",
			meta: VideoMetadata{
				VideoHash: validHash,
			},
			wantErr: "data_asset_id is required",
		},
		{
			name: "negative reward amount",
			meta: VideoMetadata{
				VideoHash:    validHash,
				DataAssetID:  validDataAssetID,
				RewardAmount: -10,
			},
			wantErr: "reward_amount cannot be negative",
		},
		{
			name: "invalid worker pubkey hex",
			meta: VideoMetadata{
				WorkerPubKey: "invalid-pubkey-!",
				VideoHash:    validHash,
				DataAssetID:  validDataAssetID,
			},
			wantErr: "worker_pubkey must be a hex string",
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			err := tc.meta.Validate()
			if err == nil {
				t.Fatalf("expected error containing %q, got nil", tc.wantErr)
			}
			if !strings.Contains(err.Error(), tc.wantErr) {
				t.Errorf("expected error %q to contain %q", err.Error(), tc.wantErr)
			}
		})
	}
}

func TestVideoMetadata_EncodeDecode(t *testing.T) {
	validHash := strings.Repeat("a", 64)
	validDataAssetID := strings.Repeat("b", 64)

	original := &VideoMetadata{
		WorkerPubKey:  "03deadbeef",
		VideoHash:     validHash,
		Timestamp:     time.Now().Unix(),
		SkillTag:      "grasping",
		DataAssetID:   validDataAssetID,
		RewardAmount:  250,
		SchemaVersion: "1.0",
	}

	encoded, err := original.EncodeJSON()
	if err != nil {
		t.Fatalf("failed to encode json: %v", err)
	}

	decoded, err := DecodeVideoMetadata(encoded)
	if err != nil {
		t.Fatalf("failed to decode json: %v", err)
	}

	if decoded.VideoHash != original.VideoHash || decoded.DataAssetID != original.DataAssetID || decoded.RewardAmount != original.RewardAmount {
		t.Errorf("decoded struct does not match original: %+v vs %+v", decoded, original)
	}
}

func TestTokenMetadata_Default(t *testing.T) {
	m := DefaultTokenMetadata()
	if m.Symbol != "DTTA" || m.DisplaySymbol != "₿DATA" || m.Project != "DattaPool" {
		t.Errorf("unexpected default token metadata: %+v", m)
	}
	if m.MonetaryUnit != "BTC_sats" || m.BTCRedemptionGuarantee != false {
		t.Errorf("unexpected monetary unit or redemption guarantee: %+v", m)
	}
	bytes, err := m.EncodeJSON()
	if err != nil {
		t.Fatalf("failed to encode token metadata: %v", err)
	}
	if !strings.Contains(string(bytes), "robotics_data_contribution_accounting") {
		t.Errorf("expected token metadata json to contain purpose string, got: %s", string(bytes))
	}
}
