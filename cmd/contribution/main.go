package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"os"
	"path/filepath"

	"github.com/dattapool/mvp/internal/verifier"
	"github.com/dattapool/mvp/pkg/protocol"
)

func main() {
	if len(os.Args) < 2 {
		printUsage()
		os.Exit(1)
	}

	subcommand := os.Args[1]
	switch subcommand {
	case "calculate":
		runCalculate(os.Args[2:])
	case "issue":
		runIssue(os.Args[2:])
	default:
		fmt.Fprintf(os.Stderr, "Unknown contribution subcommand: %q\n", subcommand)
		printUsage()
		os.Exit(1)
	}
}

func printUsage() {
	fmt.Println("DattaPool Contribution Accounting CLI")
	fmt.Println("Usage:")
	fmt.Println("  dattapool contribution calculate --manifest <capture-manifest.json>")
	fmt.Println("  dattapool contribution issue --manifest <capture-manifest.json> [flags]")
	fmt.Println("")
	fmt.Println("Flags for issue:")
	fmt.Println("  --manifest <path>           Path to verified capture manifest (required)")
	fmt.Println("  --claim-id <id>             Associated TAP claim asset ID or hash (optional)")
	fmt.Println("  --ledger <path>             Path to persistent issuance ledger JSON (default: session_data/contribution_ledger.json)")
	fmt.Println("  --out <path>                Path to write canonical contribution issuance record JSON")
	fmt.Println("  --example-sats-rate <rate>  Optional example market valuation rate in sats/DTTA (NOT protocol issuance)")
	fmt.Println("  --require-level <level>     Minimum required provenance level (default: P3)")
}

func runCalculate(args []string) {
	fs := flag.NewFlagSet("calculate", flag.ExitOnError)
	manifestPath := fs.String("manifest", "session_data/capture-manifest.json", "Path to capture manifest file")
	requireLevel := fs.String("require-level", "P3", "Minimum required provenance level")
	_ = fs.Parse(args)

	manifest, err := protocol.LoadManifestFromFile(*manifestPath)
	if err != nil {
		log.Fatalf("Failed to load manifest: %v", err)
	}

	// Verify manifest locally to evaluate provenance level
	res := verifier.VerifyManifest(verifier.VerificationOptions{
		Manifest: manifest,
	})

	level := res.ProvenanceLevel
	if !res.Success {
		// Fallback to manifest level if unverified offline
		level = manifest.Provenance.Level
	}

	if *requireLevel != "" && !protocol.IsProvenanceEligible(level, protocol.ProvenanceLevel(*requireLevel)) {
		fmt.Printf("FAILED: assessed provenance %s does not meet required %s\n", level, *requireLevel)
		os.Exit(1)
	}

	policy := &protocol.AccountingPolicy{
		ID:      protocol.DefaultAccountingPolicyID,
		Version: protocol.DefaultAccountingPolicyVersion,
	}

	accounting, eligibleRange, err := protocol.CalculateContribution(manifest, level, policy)
	if err != nil {
		fmt.Printf("FAILED: %v\n", err)
		os.Exit(1)
	}

	durationSec := float64(eligibleRange.EndOffsetMs-eligibleRange.StartOffsetMs) / 1000.0

	fmt.Println("==================================================")
	fmt.Println("   DattaPool Contribution Calculation (DTTA)")
	fmt.Println("==================================================")
	fmt.Printf("Manifest Hash:        %s\n", res.ManifestHash)
	fmt.Printf("Assessed Provenance:  %s\n", level)
	fmt.Printf("Eligible Duration:    %.1f seconds\n", durationSec)
	fmt.Printf("Accounting Policy:    %s (v%s)\n", policy.ID, policy.Version)
	fmt.Printf("₿DATA Units:          %d %s\n", accounting.Units, accounting.Unit)
	fmt.Println("==================================================")
	fmt.Println("Note: ₿DATA (DTTA) is an accounting unit of verified robotics-data contribution.")
	fmt.Println("Economic valuation is denominated in BTC/sats and determined externally by buyers/markets.")
}

func runIssue(args []string) {
	fs := flag.NewFlagSet("issue", flag.ExitOnError)
	manifestPath := fs.String("manifest", "session_data/capture-manifest.json", "Path to capture manifest file")
	claimID := fs.String("claim-id", "", "Associated TAP claim asset ID or hash (optional)")
	ledgerPath := fs.String("ledger", "session_data/contribution_ledger.json", "Path to issuance ledger JSON")
	outPath := fs.String("out", "", "Path to write canonical contribution issuance record")
	exampleSatsRate := fs.Uint64("example-sats-rate", 0, "Optional example market valuation rate in sats/DTTA")
	requireLevel := fs.String("require-level", "P3", "Minimum required provenance level")
	_ = fs.Parse(args)

	manifest, err := protocol.LoadManifestFromFile(*manifestPath)
	if err != nil {
		log.Fatalf("Failed to load manifest: %v", err)
	}

	// Verify cryptographic evidence before issuance
	res := verifier.VerifyManifest(verifier.VerificationOptions{
		Manifest: manifest,
	})

	if !res.Success {
		log.Fatalf("Cannot issue contribution for unverified manifest: [%s] %s", res.Code, res.Message)
	}

	if !protocol.IsProvenanceEligible(res.ProvenanceLevel, protocol.ProvenanceLevel(*requireLevel)) {
		log.Fatalf("Provenance level %s does not satisfy minimum %s required for contribution accounting",
			res.ProvenanceLevel, *requireLevel)
	}

	// Ensure ledger directory exists
	if err := os.MkdirAll(filepath.Dir(*ledgerPath), 0755); err != nil {
		log.Fatalf("Failed to create ledger directory: %v", err)
	}

	ledger, err := protocol.NewFileContributionRegistry(*ledgerPath)
	if err != nil {
		log.Fatalf("Failed to open contribution ledger: %v", err)
	}

	policy := &protocol.AccountingPolicy{
		ID:      protocol.DefaultAccountingPolicyID,
		Version: protocol.DefaultAccountingPolicyVersion,
	}

	var valuation *protocol.EconomicValuation
	if *exampleSatsRate > 0 {
		accounting, _, _ := protocol.CalculateContribution(manifest, res.ProvenanceLevel, policy)
		if accounting != nil {
			valuation = &protocol.EconomicValuation{
				AmountSats: accounting.Units * (*exampleSatsRate),
			}
		}
	}

	record, err := protocol.IssueContributionRecord(manifest, res.ProvenanceLevel, *claimID, policy, nil, valuation, ledger)
	if err != nil {
		log.Fatalf("Contribution issuance failed: %v", err)
	}

	recordBytes, err := json.MarshalIndent(record, "", "  ")
	if err != nil {
		log.Fatalf("Failed to format contribution record: %v", err)
	}

	if *outPath != "" {
		if err := os.MkdirAll(filepath.Dir(*outPath), 0755); err == nil {
			_ = os.WriteFile(*outPath, recordBytes, 0644)
		}
	}

	durationSec := float64(record.EligibleRange.EndOffsetMs-record.EligibleRange.StartOffsetMs) / 1000.0

	fmt.Println("==================================================")
	fmt.Println("RESULT: CONTRIBUTION ISSUED")
	fmt.Printf("PROVENANCE LEVEL: %s\n", record.Verification.ProvenanceLevel)
	fmt.Println("==================================================")
	fmt.Printf("Issuance ID:          %s\n", record.IssuanceID)
	fmt.Printf("Contributor Pubkey:   %s\n", record.ContributorNostrPubKey)
	fmt.Printf("Capture Claim ID:     %s\n", record.CaptureClaimID)
	fmt.Printf("Manifest Hash:        %s\n", record.ManifestHash)
	fmt.Printf("Eligible Duration:    %.1f seconds\n", durationSec)
	fmt.Printf("Accounting Policy:    %s (v%s)\n", record.AccountingPolicy.ID, record.AccountingPolicy.Version)
	fmt.Printf("₿DATA Units:          %d %s\n", record.Contribution.Units, record.Contribution.Unit)
	fmt.Println("")
	fmt.Println("Economic Valuation:")
	if record.EconomicValuation != nil {
		fmt.Printf("  Example Market Rate: %d sats/%s\n", *exampleSatsRate, record.Contribution.Unit)
		fmt.Printf("  Example Settlement:  %d sats\n", record.EconomicValuation.AmountSats)
		fmt.Println("  (Note: Market valuation is external; protocol does not peg or guarantee BTC redemption)")
	} else {
		fmt.Println("  NOT SET BY PROTOCOL (Settlement in BTC/sats determined externally)")
	}
	fmt.Println("==================================================")
}
