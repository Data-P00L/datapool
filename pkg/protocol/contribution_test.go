package protocol

import (
	"errors"
	"testing"
)

// Helper to create a valid mock manifest with specified timestamps and chunk count.
func makeMockManifest(startedAt, endedAt int64, chunks int) *CaptureManifest {
	return &CaptureManifest{
		Protocol:      ProtocolName,
		SchemaVersion: SchemaVersion,
		Worker: WorkerIdentity{
			Type:        "human",
			NostrPubKey: "1111111111111111111111111111111111111111111111111111111111111111",
		},
		Device: DeviceIdentity{
			PubKey: "2222222222222222222222222222222222222222222222222222222222222222",
			Type:   "camera_robot",
		},
		Session: SessionInfo{
			ID:              "session-test-uuid-1234",
			PubKey:          "3333333333333333333333333333333333333333333333333333333333333333",
			NonceCommitment: "4444444444444444444444444444444444444444444444444444444444444444",
			StartedAt:       startedAt,
			EndedAt:         endedAt,
			Freshness: FreshnessReference{
				Type:        "bitcoin_block",
				BlockHeight: 120,
				BlockHash:   "0000000000000000000102030405060708090a0b0c0d0e0f1011121314151617",
			},
		},
		Capture: CaptureSummary{
			VideoSHA256:         "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
			VideoMerkleRoot:     "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
			TelemetryMerkleRoot: "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
			CaptureChainRoot:    "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
			ChunkCount:          chunks,
		},
		Authorization: Authorization{
			WorkerDeviceAuth: WorkerDeviceAuthorization{
				WorkerPubKey:    "1111111111111111111111111111111111111111111111111111111111111111",
				DevicePubKey:    "2222222222222222222222222222222222222222222222222222222222222222",
				IssuedAt:        startedAt,
				ExpiresAt:       endedAt + 3600,
				WorkerSignature: "ee" + string(make([]byte, 126)),
			},
			DeviceSessionAuth: DeviceSessionAuthorization{
				DevicePubKey:    "2222222222222222222222222222222222222222222222222222222222222222",
				SessionPubKey:   "3333333333333333333333333333333333333333333333333333333333333333",
				SessionID:       "session-test-uuid-1234",
				NonceCommitment: "4444444444444444444444444444444444444444444444444444444444444444",
				IssuedAt:        startedAt,
				ExpiresAt:       endedAt + 3600,
				DeviceSignature: "ff" + string(make([]byte, 126)),
			},
		},
		Seal: CaptureSeal{
			SessionID:            "session-test-uuid-1234",
			ChunkCount:           chunks,
			CaptureChainRoot:     "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
			VideoMerkleRoot:      "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
			TelemetryMerkleRoot:  "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
			VideoSHA256:          "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
			CaptureStartedAt:     startedAt,
			CaptureEndedAt:       endedAt,
			SessionSealSignature: "11" + string(make([]byte, 126)),
			DeviceSealSignature:  "22" + string(make([]byte, 126)),
		},
		Provenance: ProvenanceMetadata{
			Level: LevelP4,
		},
	}
}

// 1. Test P4 10-second capture -> 10 ₿DATA under default policy
func TestCalculateContribution_P4_10Seconds(t *testing.T) {
	manifest := makeMockManifest(1700000000, 1700000010, 10)
	accounting, r, err := CalculateContribution(manifest, LevelP4, nil)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if accounting.Units != 10 {
		t.Errorf("expected 10 ₿DATA units, got %d", accounting.Units)
	}
	if accounting.Unit != ContributionUnit {
		t.Errorf("expected unit %q, got %q", ContributionUnit, accounting.Unit)
	}
	if r.EndOffsetMs != 10000 {
		t.Errorf("expected end offset 10000ms, got %d", r.EndOffsetMs)
	}
}

// 2. Test P3 60-second capture -> 60 ₿DATA
func TestCalculateContribution_P3_60Seconds(t *testing.T) {
	manifest := makeMockManifest(1700000000, 1700000060, 60)
	manifest.Provenance.Level = LevelP3
	accounting, r, err := CalculateContribution(manifest, LevelP3, nil)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if accounting.Units != 60 {
		t.Errorf("expected 60 ₿DATA units, got %d", accounting.Units)
	}
	if accounting.Unit != "DTTA" {
		t.Errorf("expected unit DTTA, got %s", accounting.Unit)
	}
	if r.EndOffsetMs != 60000 {
		t.Errorf("expected end offset 60000ms, got %d", r.EndOffsetMs)
	}
}

// 3. Test P2 capture -> not eligible under default P3 minimum
func TestCalculateContribution_P2_Ineligible(t *testing.T) {
	manifest := makeMockManifest(1700000000, 1700000060, 60)
	manifest.Provenance.Level = LevelP2
	_, _, err := CalculateContribution(manifest, LevelP2, nil)
	if err == nil {
		t.Fatal("expected error for P2 capture, got nil")
	}
	if !errors.Is(err, ErrContributionNotEligible) {
		t.Errorf("expected ErrContributionNotEligible, got: %v", err)
	}
}

// 4. Test P1 and P0 captures -> not eligible
func TestCalculateContribution_P0_P1_Ineligible(t *testing.T) {
	manifest := makeMockManifest(1700000000, 1700000030, 30)

	levels := []ProvenanceLevel{LevelP0, LevelP1}
	for _, lvl := range levels {
		manifest.Provenance.Level = lvl
		_, _, err := CalculateContribution(manifest, lvl, nil)
		if err == nil {
			t.Fatalf("expected error for %s capture, got nil", lvl)
		}
		if !errors.Is(err, ErrContributionNotEligible) {
			t.Errorf("expected ErrContributionNotEligible for %s, got: %v", lvl, err)
		}
	}
}

// 5. Test same verified contribution issued twice -> rejected (CONTRIBUTION_ALREADY_ISSUED)
func TestIssueContributionRecord_DoubleIssuancePrevention(t *testing.T) {
	manifest := makeMockManifest(1700000000, 1700000047, 47)
	registry := NewMemoryContributionRegistry()

	// First issuance -> success
	rec1, err := IssueContributionRecord(manifest, LevelP4, "claim-asset-12345", nil, nil, nil, registry)
	if err != nil {
		t.Fatalf("first issuance failed: %v", err)
	}
	if rec1.Contribution.Units != 47 {
		t.Errorf("expected 47 units, got %d", rec1.Contribution.Units)
	}

	// Second issuance with same contribution -> must be rejected
	_, err2 := IssueContributionRecord(manifest, LevelP4, "claim-asset-12345", nil, nil, nil, registry)
	if err2 == nil {
		t.Fatal("expected double issuance to fail, but got success")
	}
	if !errors.Is(err2, ErrContributionAlreadyIssued) {
		t.Errorf("expected ErrContributionAlreadyIssued, got: %v", err2)
	}
}

// 6. Test tampered claim / invalid manifest -> no contribution issuance
func TestIssueContributionRecord_InvalidManifest(t *testing.T) {
	manifest := makeMockManifest(1700000000, 1700000047, 47)
	manifest.Capture.VideoSHA256 = "invalid-non-hex" // corrupt
	registry := NewMemoryContributionRegistry()

	_, err := IssueContributionRecord(manifest, LevelP4, "claim-asset-1", nil, nil, nil, registry)
	if err == nil {
		t.Fatal("expected error on invalid manifest, got nil")
	}
	if !errors.Is(err, ErrContributionClaimInvalid) {
		t.Errorf("expected ErrContributionClaimInvalid, got: %v", err)
	}
}

// 7. Test monetary sats value absent -> contribution issuance still valid
func TestIssueContributionRecord_AbsentSatsValuation(t *testing.T) {
	manifest := makeMockManifest(1700000000, 1700000025, 25)
	registry := NewMemoryContributionRegistry()

	rec, err := IssueContributionRecord(manifest, LevelP4, "claim-asset-sats-absent", nil, nil, nil, registry)
	if err != nil {
		t.Fatalf("issuance without sats valuation failed: %v", err)
	}

	if rec.EconomicValuation != nil {
		t.Errorf("expected nil EconomicValuation, got %+v", rec.EconomicValuation)
	}
	if rec.Contribution.Units != 25 {
		t.Errorf("expected 25 units, got %d", rec.Contribution.Units)
	}
}

// 8. Test changing sats market valuation does NOT alter existing ₿DATA units
func TestIssueContributionRecord_ChangingSatsMarketValuation(t *testing.T) {
	manifest := makeMockManifest(1700000000, 1700000050, 50)

	// Valuation 1: 50 sats / unit
	val1 := &EconomicValuation{AmountSats: 2500}
	rec1, err := IssueContributionRecord(manifest, LevelP4, "claim-asset-v1", nil, nil, val1, nil)
	if err != nil {
		t.Fatalf("issuance 1 failed: %v", err)
	}

	// Valuation 2: 150 sats / unit
	val2 := &EconomicValuation{AmountSats: 7500}
	rec2, err := IssueContributionRecord(manifest, LevelP4, "claim-asset-v2", nil, nil, val2, nil)
	if err != nil {
		t.Fatalf("issuance 2 failed: %v", err)
	}

	// Both must have identical 50 ₿DATA (DTTA) units
	if rec1.Contribution.Units != 50 || rec2.Contribution.Units != 50 {
		t.Errorf("expected both to have 50 units, got rec1=%d rec2=%d", rec1.Contribution.Units, rec2.Contribution.Units)
	}

	if rec1.EconomicValuation.AmountSats != 2500 {
		t.Errorf("expected 2500 sats, got %d", rec1.EconomicValuation.AmountSats)
	}
	if rec2.EconomicValuation.AmountSats != 7500 {
		t.Errorf("expected 7500 sats, got %d", rec2.EconomicValuation.AmountSats)
	}
}

// 9. Test partial eligible range support
func TestIssueContributionRecord_PartialRange(t *testing.T) {
	manifest := makeMockManifest(1700000000, 1700000100, 100)
	registry := NewMemoryContributionRegistry()

	// Alice: 0 to 40 seconds
	rangeAlice := &EligibleRange{StartOffsetMs: 0, EndOffsetMs: 40000}
	recAlice, err := IssueContributionRecord(manifest, LevelP4, "claim-multicontrib", nil, rangeAlice, nil, registry)
	if err != nil {
		t.Fatalf("Alice issuance failed: %v", err)
	}

	// Bob: 40 to 100 seconds
	rangeBob := &EligibleRange{StartOffsetMs: 40000, EndOffsetMs: 100000}
	recBob, err := IssueContributionRecord(manifest, LevelP4, "claim-multicontrib", nil, rangeBob, nil, registry)
	if err != nil {
		t.Fatalf("Bob issuance failed: %v", err)
	}

	if recAlice.IssuanceID == recBob.IssuanceID {
		t.Errorf("Alice and Bob should have distinct deterministic issuance IDs for different ranges")
	}
}
