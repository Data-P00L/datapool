package protocol

import (
	"strings"
	"testing"
)

func TestYouTubeDescriptionRoundTrip(t *testing.T) {
	manifest := &CaptureManifest{
		Protocol:      ProtocolName,
		SchemaVersion: SchemaVersion,
		Worker: WorkerIdentity{
			Type:        "human",
			NostrPubKey: "0ead498f89be73a48abd4253ab2a95675a20f867d0a038ebaf11d6900d4d05d4",
		},
		Device: DeviceIdentity{
			PubKey: "1111111111111111111111111111111111111111111111111111111111111111",
			Type:   "camera_robot",
		},
		Session: SessionInfo{
			ID:              "833ab02adca3ac6680542d616e77bf5f",
			PubKey:          "2222222222222222222222222222222222222222222222222222222222222222",
			NonceCommitment: "3333333333333333333333333333333333333333333333333333333333333333",
			StartedAt:       1700000000,
			EndedAt:         1700000060,
			Freshness:       FreshnessReference{Type: "local_only"},
		},
		Capture: CaptureSummary{
			VideoSHA256:         "4444444444444444444444444444444444444444444444444444444444444444",
			VideoMerkleRoot:     "5555555555555555555555555555555555555555555555555555555555555555",
			TelemetryMerkleRoot: "6666666666666666666666666666666666666666666666666666666666666666",
			CaptureChainRoot:    "f566881b48364607b1c125c812ca281c5765561c5c4264c9d4d01bb11fb040a1",
			ChunkCount:          10,
		},
		Task: TaskInfo{
			SkillTag: "general_manipulation",
		},
		Authorization: Authorization{
			WorkerDeviceAuth: WorkerDeviceAuthorization{
				WorkerPubKey:    "0ead498f89be73a48abd4253ab2a95675a20f867d0a038ebaf11d6900d4d05d4",
				DevicePubKey:    "1111111111111111111111111111111111111111111111111111111111111111",
				IssuedAt:        1700000000,
				ExpiresAt:       1700086400,
				WorkerSignature: "sig1",
			},
			DeviceSessionAuth: DeviceSessionAuthorization{
				DevicePubKey:    "1111111111111111111111111111111111111111111111111111111111111111",
				SessionPubKey:   "2222222222222222222222222222222222222222222222222222222222222222",
				SessionID:       "833ab02adca3ac6680542d616e77bf5f",
				NonceCommitment: "3333333333333333333333333333333333333333333333333333333333333333",
				IssuedAt:        1700000000,
				ExpiresAt:       1700086400,
				DeviceSignature: "sig2",
			},
		},
		Provenance: ProvenanceMetadata{
			Level: LevelP4,
		},
	}

	tapAssetID := "60dfeafdde09a8343ac58ae7332565e463469e80245ace60a981a5c07985bc88"

	desc, err := BuildYouTubeDescription(manifest, tapAssetID)
	if err != nil {
		t.Fatalf("BuildYouTubeDescription failed: %v", err)
	}

	if !strings.Contains(desc, DattaPoolBlockStartMarker) || !strings.Contains(desc, DattaPoolBlockEndMarker) {
		t.Fatalf("Description missing markers: %s", desc)
	}

	parsed, err := ParseYouTubeDescription(desc)
	if err != nil {
		t.Fatalf("ParseYouTubeDescription failed: %v", err)
	}

	if parsed.SessionID != manifest.Session.ID {
		t.Errorf("Expected session_id %s, got %s", manifest.Session.ID, parsed.SessionID)
	}
	if parsed.WorkerNostrPubKey != manifest.Worker.NostrPubKey {
		t.Errorf("Expected worker_nostr_pubkey %s, got %s", manifest.Worker.NostrPubKey, parsed.WorkerNostrPubKey)
	}
	if parsed.CaptureChainRoot != manifest.Capture.CaptureChainRoot {
		t.Errorf("Expected capture_chain_root %s, got %s", manifest.Capture.CaptureChainRoot, parsed.CaptureChainRoot)
	}
	if parsed.TAPClaimAssetID != tapAssetID {
		t.Errorf("Expected tap_claim_asset_id %s, got %s", tapAssetID, parsed.TAPClaimAssetID)
	}
	if parsed.ProvenanceLevel != string(manifest.Provenance.Level) {
		t.Errorf("Expected provenance_level %s, got %s", manifest.Provenance.Level, parsed.ProvenanceLevel)
	}
	if parsed.SkillTag != manifest.Task.SkillTag {
		t.Errorf("Expected skill_tag %s, got %s", manifest.Task.SkillTag, parsed.SkillTag)
	}
}
