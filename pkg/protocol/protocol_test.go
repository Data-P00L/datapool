package protocol

import (
	"os"
	"path/filepath"
	"testing"
)

func TestCanonicalJSON(t *testing.T) {
	input := map[string]interface{}{
		"zeta":  1,
		"alpha": "hello",
		"beta":  true,
		"nested": map[string]interface{}{
			"y": 2,
			"x": 1,
		},
	}

	canonical, err := CanonicalizeJSON(input)
	if err != nil {
		t.Fatalf("failed to canonicalize: %v", err)
	}

	expected := `{"alpha":"hello","beta":true,"nested":{"x":1,"y":2},"zeta":1}`
	if string(canonical) != expected {
		t.Fatalf("expected %s, got %s", expected, string(canonical))
	}
}

func TestGoldenVectors040(t *testing.T) {
	manifestPath := "../../fixtures/protocol-v0.4.0/manifest.json"
	manifest, err := LoadManifestFromFile(manifestPath)
	if err != nil {
		t.Fatalf("failed to load golden manifest: %v", err)
	}

	canonical, err := manifest.CanonicalJSON()
	if err != nil {
		t.Fatalf("failed to compute canonical json: %v", err)
	}

	hash, err := manifest.ComputeManifestHash()
	if err != nil {
		t.Fatalf("failed to compute hash: %v", err)
	}

	dir := filepath.Dir(manifestPath)
	_ = os.WriteFile(filepath.Join(dir, "canonical-manifest.json"), canonical, 0644)
	_ = os.WriteFile(filepath.Join(dir, "manifest.sha256"), []byte(hash+"\n"), 0644)

	t.Logf("Golden Manifest Hash: %s", hash)
}

func TestManifest040ValidationAndClaim(t *testing.T) {
	manifest := &CaptureManifest{
		Protocol:      ProtocolName,
		SchemaVersion: SchemaVersion,
		Worker: WorkerIdentity{
			Type:        "human",
			NostrPubKey: "64547ff96c988e9766980bb5e6488cb010dd8337978021bbb4ee9e26a96ca9f6",
		},
		Device: DeviceIdentity{
			PubKey: "ce21b7e060466b5bd9ccc3d09aa4c730894c8bf179c0cb8aa4d0f7bbf9ffbea0",
			Type:   "camera_robot",
		},
		Session: SessionInfo{
			ID:              "session-test-040",
			PubKey:          "d725f42a1b830f2f61e62f350137b9c2d28391d691d2cda06ca3016f2d0de2e8",
			NonceCommitment: "4aaa6be12ca3c816782b8ea19ecc60c924a3471bedbe323af45d0c5274768a6c",
			StartedAt:       1786990000,
			EndedAt:         1786990010,
			Freshness: FreshnessReference{
				Type:        "bitcoin_block",
				BlockHeight: 152,
				BlockHash:   "0000000000000000000000000000000000000000000000000000000000000001",
			},
		},
		Witness: WitnessInfo{
			NostrEventID: "1111111111111111111111111111111111111111111111111111111111111111",
			Published:    true,
		},
		Capture: CaptureSummary{
			VideoSHA256:         "782e5b00b1597db6ad18e201aac33cec26946b6af916dd2a5e5cde13f67502d1",
			VideoMerkleRoot:     "3078eb4078502302b535e2830ee36042852834b03be22261281231064dec4ae2",
			TelemetryMerkleRoot: "c31e1e74f9948f6d8a139f45769741a9fa8eb9cfee3d66989e8656943dc7ffe4",
			CaptureChainRoot:    "2cb58b9d3d516fa3e985830b7c6fc870d61b5844e9261fc6efa1f1698bab40e6",
			ChunkCount:          192,
		},
		Task: TaskInfo{
			SkillTag: "warehouse_object_pick",
		},
		Authorization: Authorization{
			WorkerDeviceAuth: WorkerDeviceAuthorization{
				WorkerPubKey:    "64547ff96c988e9766980bb5e6488cb010dd8337978021bbb4ee9e26a96ca9f6",
				DevicePubKey:    "ce21b7e060466b5bd9ccc3d09aa4c730894c8bf179c0cb8aa4d0f7bbf9ffbea0",
				WorkerSignature: "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
			},
			DeviceSessionAuth: DeviceSessionAuthorization{
				DevicePubKey:    "ce21b7e060466b5bd9ccc3d09aa4c730894c8bf179c0cb8aa4d0f7bbf9ffbea0",
				SessionPubKey:   "d725f42a1b830f2f61e62f350137b9c2d28391d691d2cda06ca3016f2d0de2e8",
				DeviceSignature: "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890",
			},
		},
		Provenance: ProvenanceMetadata{
			Level: LevelP4,
		},
	}

	if err := manifest.Validate(); err != nil {
		t.Fatalf("manifest 0.4.0 validation failed: %v", err)
	}

	claim, err := BuildClaimMetadataFromManifest(manifest)
	if err != nil {
		t.Fatalf("failed to build claim metadata: %v", err)
	}

	if claim.ProvenanceLevel != LevelP4 {
		t.Fatalf("expected provenance level P4, got %s", claim.ProvenanceLevel)
	}

	claimBytes, err := claim.EncodeJSON()
	if err != nil {
		t.Fatalf("failed to encode claim JSON: %v", err)
	}

	decoded, err := DecodeClaimMetadata(claimBytes)
	if err != nil {
		t.Fatalf("failed to decode claim JSON: %v", err)
	}

	if decoded.ManifestHash != claim.ManifestHash {
		t.Fatalf("manifest hash mismatch after decode")
	}
}
