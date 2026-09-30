package protocol

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"sync"
	"time"
)

const (
	// ContributionType is the canonical JSON type for contribution issuance records.
	ContributionType = "contribution_issuance"

	// ContributionUnit is the machine-readable ASCII ticker for ₿DATA contribution units.
	ContributionUnit = "DTTA"

	// DisplayContributionUnit is the human-facing display symbol for DTTA.
	DisplayContributionUnit = "₿DATA"

	// MonetaryUnitSats identifies the monetary unit used for pricing and settlement.
	MonetaryUnitSats = "BTC_sats"

	// DefaultAccountingPolicyID is the identifier for the reference duration-based accounting policy.
	DefaultAccountingPolicyID = "dattapool-base-duration"

	// DefaultAccountingPolicyVersion is the current version of the reference accounting policy.
	DefaultAccountingPolicyVersion = "0.1.0"

	// DefaultContributionUnitDuration defines the base unit conversion: 1 second = 1 ₿DATA.
	DefaultContributionUnitDuration = time.Second

	// DefaultMinimumContributionProvenance is the minimum provenance level required for ₿DATA issuance.
	DefaultMinimumContributionProvenance = LevelP3
)

// Standard Contribution Error Constants
var (
	ErrContributionAlreadyIssued = errors.New("CONTRIBUTION_ALREADY_ISSUED")
	ErrContributionNotEligible   = errors.New("CONTRIBUTION_NOT_ELIGIBLE")
	ErrContributionClaimInvalid  = errors.New("CONTRIBUTION_CLAIM_INVALID")
)

// ContributionAccounting represents the standardized contribution quantity.
// It reflects how much independently verified robotics-data contribution occurred.
type ContributionAccounting struct {
	Units uint64 `json:"units"`
	Unit  string `json:"unit"` // "DTTA"
}

// EconomicValuation represents the economic valuation in Bitcoin sats.
// It is strictly separated from protocol contribution accounting and has no protocol-guaranteed peg.
type EconomicValuation struct {
	AmountSats uint64 `json:"amount_sats"`
}

// AccountingPolicy describes the versioned policy used to calculate contribution units.
type AccountingPolicy struct {
	ID      string `json:"id"`      // e.g. "dattapool-base-duration"
	Version string `json:"version"` // e.g. "0.1.0"
}

// EligibleRange specifies the start and end monotonic time offsets for partial or multi-party contributions.
type EligibleRange struct {
	StartOffsetMs int64 `json:"start_offset_ms"`
	EndOffsetMs   int64 `json:"end_offset_ms"`
}

// VerificationProof binds the verified provenance level to the contribution record.
type VerificationProof struct {
	ProvenanceLevel ProvenanceLevel `json:"provenance_level"`
}

// ContributionIssuanceRecord is the canonical, auditable record proving why ₿DATA units were accounted.
type ContributionIssuanceRecord struct {
	Protocol               string                 `json:"protocol"`
	Type                   string                 `json:"type"`
	SchemaVersion          string                 `json:"schema_version"`
	IssuanceID             string                 `json:"issuance_id"`
	CaptureClaimID         string                 `json:"capture_claim_id"`
	ManifestHash           string                 `json:"manifest_hash"`
	ContributorNostrPubKey string                 `json:"contributor_nostr_pubkey"`
	AccountingPolicy       AccountingPolicy       `json:"accounting_policy"`
	EligibleRange          *EligibleRange         `json:"eligible_range,omitempty"`
	Verification           VerificationProof      `json:"verification"`
	Contribution           ContributionAccounting `json:"contribution"`
	EconomicValuation      *EconomicValuation     `json:"economic_valuation,omitempty"`
	IssuedAt               int64                  `json:"issued_at"`
}

// IsProvenanceEligible checks if an assessed provenance level meets or exceeds the minimum required level.
func IsProvenanceEligible(level ProvenanceLevel, minLevel ProvenanceLevel) bool {
	rank := map[ProvenanceLevel]int{
		LevelP0: 0,
		LevelP1: 1,
		LevelP2: 2,
		LevelP3: 3,
		LevelP4: 4,
		LevelP5: 5,
	}

	r, ok := rank[level]
	if !ok {
		return false
	}
	minR, ok := rank[minLevel]
	if !ok {
		minR = rank[DefaultMinimumContributionProvenance]
	}
	return r >= minR
}

// CalculateContribution computes qualifying ₿DATA (DTTA) units from a manifest and verified provenance level.
func CalculateContribution(manifest *CaptureManifest, verifiedLevel ProvenanceLevel, policy *AccountingPolicy) (*ContributionAccounting, *EligibleRange, error) {
	if manifest == nil {
		return nil, nil, errors.New("manifest is nil")
	}

	if policy == nil {
		policy = &AccountingPolicy{
			ID:      DefaultAccountingPolicyID,
			Version: DefaultAccountingPolicyVersion,
		}
	}

	// Verify minimum qualification (P3 or higher)
	if !IsProvenanceEligible(verifiedLevel, DefaultMinimumContributionProvenance) {
		return nil, nil, fmt.Errorf("%w: provenance level %s does not meet minimum %s",
			ErrContributionNotEligible, verifiedLevel, DefaultMinimumContributionProvenance)
	}

	var durationSec float64
	if manifest.Session.StartedAt > 0 && manifest.Session.EndedAt >= manifest.Session.StartedAt {
		durationSec = float64(manifest.Session.EndedAt - manifest.Session.StartedAt)
	} else if manifest.Seal.CaptureStartedAt > 0 && manifest.Seal.CaptureEndedAt >= manifest.Seal.CaptureStartedAt {
		durationSec = float64(manifest.Seal.CaptureEndedAt - manifest.Seal.CaptureStartedAt)
	}

	// Fallback to chunk count approximation if timestamps are zero or identical
	if durationSec <= 0 && manifest.Capture.ChunkCount > 0 {
		durationSec = float64(manifest.Capture.ChunkCount)
	}

	if durationSec < 1.0 {
		durationSec = 1.0
	}

	units := uint64(durationSec)
	eligibleRange := &EligibleRange{
		StartOffsetMs: 0,
		EndOffsetMs:   int64(durationSec * 1000),
	}

	return &ContributionAccounting{
		Units: units,
		Unit:  ContributionUnit,
	}, eligibleRange, nil
}

// ComputeIssuanceID derives a deterministic SHA-256 hash preventing duplicate contribution issuance.
func ComputeIssuanceID(captureClaimID string, contributorPubKey string, policy AccountingPolicy, r *EligibleRange) string {
	var startMs, endMs int64
	if r != nil {
		startMs = r.StartOffsetMs
		endMs = r.EndOffsetMs
	}
	payload := fmt.Sprintf("DATTA_CONTRIBUTION_ISSUANCE_V1\nclaim:%s\npubkey:%s\npolicy_id:%s\npolicy_ver:%s\nstart_ms:%d\nend_ms:%d",
		captureClaimID, contributorPubKey, policy.ID, policy.Version, startMs, endMs)
	h := sha256.Sum256([]byte(payload))
	return hex.EncodeToString(h[:])
}

// ContributionRegistry interface defines state tracking to prevent double issuance.
type ContributionRegistry interface {
	HasIssued(issuanceID string) bool
	RecordIssuance(record *ContributionIssuanceRecord) error
}

// MemoryContributionRegistry is an in-memory thread-safe registry for anti-double-issuance tracking.
type MemoryContributionRegistry struct {
	mu      sync.RWMutex
	records map[string]*ContributionIssuanceRecord
}

// NewMemoryContributionRegistry creates a new in-memory contribution registry.
func NewMemoryContributionRegistry() *MemoryContributionRegistry {
	return &MemoryContributionRegistry{
		records: make(map[string]*ContributionIssuanceRecord),
	}
}

// HasIssued checks whether an issuance ID has already been recorded.
func (r *MemoryContributionRegistry) HasIssued(issuanceID string) bool {
	r.mu.RLock()
	defer r.mu.RUnlock()
	_, exists := r.records[issuanceID]
	return exists
}

// RecordIssuance records a new contribution issuance, rejecting duplicates.
func (r *MemoryContributionRegistry) RecordIssuance(record *ContributionIssuanceRecord) error {
	r.mu.Lock()
	defer r.mu.Unlock()

	if _, exists := r.records[record.IssuanceID]; exists {
		return fmt.Errorf("%w: issuance ID %s already exists", ErrContributionAlreadyIssued, record.IssuanceID)
	}

	r.records[record.IssuanceID] = record
	return nil
}

// FileContributionRegistry persists issuance records to a JSON ledger file.
type FileContributionRegistry struct {
	mu       sync.Mutex
	filePath string
	records  map[string]*ContributionIssuanceRecord
}

// NewFileContributionRegistry loads or creates a persistent contribution registry file.
func NewFileContributionRegistry(filePath string) (*FileContributionRegistry, error) {
	reg := &FileContributionRegistry{
		filePath: filePath,
		records:  make(map[string]*ContributionIssuanceRecord),
	}

	if data, err := os.ReadFile(filePath); err == nil {
		_ = json.Unmarshal(data, &reg.records)
	}

	return reg, nil
}

// HasIssued checks whether an issuance ID is present in the ledger.
func (f *FileContributionRegistry) HasIssued(issuanceID string) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	_, exists := f.records[issuanceID]
	return exists
}

// RecordIssuance appends and persists an issuance record to the ledger file.
func (f *FileContributionRegistry) RecordIssuance(record *ContributionIssuanceRecord) error {
	f.mu.Lock()
	defer f.mu.Unlock()

	if _, exists := f.records[record.IssuanceID]; exists {
		return fmt.Errorf("%w: issuance ID %s already exists in ledger %s", ErrContributionAlreadyIssued, record.IssuanceID, f.filePath)
	}

	f.records[record.IssuanceID] = record
	data, err := json.MarshalIndent(f.records, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(f.filePath, data, 0644)
}

// IssueContributionRecord generates a verified ContributionIssuanceRecord, enforcing policy and anti-double-issuance.
func IssueContributionRecord(
	manifest *CaptureManifest,
	verifiedLevel ProvenanceLevel,
	claimID string,
	policy *AccountingPolicy,
	customRange *EligibleRange,
	valuation *EconomicValuation,
	registry ContributionRegistry,
) (*ContributionIssuanceRecord, error) {
	if manifest == nil {
		return nil, errors.New("cannot issue contribution for nil manifest")
	}

	if err := manifest.Validate(); err != nil {
		return nil, fmt.Errorf("%w: invalid manifest: %v", ErrContributionClaimInvalid, err)
	}

	manifestHash, err := manifest.ComputeManifestHash()
	if err != nil {
		return nil, fmt.Errorf("failed to compute manifest hash: %w", err)
	}

	if policy == nil {
		policy = &AccountingPolicy{
			ID:      DefaultAccountingPolicyID,
			Version: DefaultAccountingPolicyVersion,
		}
	}

	accounting, eligibleRange, err := CalculateContribution(manifest, verifiedLevel, policy)
	if err != nil {
		return nil, err
	}

	if customRange != nil {
		eligibleRange = customRange
	}

	if claimID == "" {
		claimID = manifestHash
	}

	contributorPubKey := manifest.Worker.NostrPubKey
	if contributorPubKey == "" {
		return nil, fmt.Errorf("%w: missing contributor Nostr pubkey", ErrContributionClaimInvalid)
	}

	issuanceID := ComputeIssuanceID(claimID, contributorPubKey, *policy, eligibleRange)

	if registry != nil && registry.HasIssued(issuanceID) {
		return nil, fmt.Errorf("%w: contribution %s for claim %s already issued",
			ErrContributionAlreadyIssued, issuanceID, claimID)
	}

	record := &ContributionIssuanceRecord{
		Protocol:               ProtocolName,
		Type:                   ContributionType,
		SchemaVersion:          manifest.SchemaVersion,
		IssuanceID:             issuanceID,
		CaptureClaimID:         claimID,
		ManifestHash:           manifestHash,
		ContributorNostrPubKey: contributorPubKey,
		AccountingPolicy:       *policy,
		EligibleRange:          eligibleRange,
		Verification: VerificationProof{
			ProvenanceLevel: verifiedLevel,
		},
		Contribution:      *accounting,
		EconomicValuation: valuation,
		IssuedAt:          time.Now().Unix(),
	}

	if registry != nil {
		if err := registry.RecordIssuance(record); err != nil {
			return nil, err
		}
	}

	return record, nil
}

// EncodeJSON serializes the contribution record into canonical JSON format.
func (c *ContributionIssuanceRecord) EncodeJSON() ([]byte, error) {
	return CanonicalizeJSON(c)
}
