package main

import (
	"flag"
	"fmt"
	"log"

	"github.com/dattapool/mvp/internal/taproot"
	"github.com/dattapool/mvp/internal/verifier"
	"github.com/dattapool/mvp/pkg/protocol"
)

func main() {
	tokenName := flag.String("name", protocol.ContributionUnit, "Asset machine symbol / ticker")
	displaySymbol := flag.String("display_symbol", protocol.DisplayContributionUnit, "Human-facing display symbol")
	units := flag.Uint64("units", 1000, "Number of ₿DATA contribution units to issue")
	manifestPath := flag.String("manifest", "", "Optional path to verified capture manifest to derive contribution units")
	project := flag.String("project", "DattaPool", "Project name")
	purpose := flag.String("purpose", "robotics_data_contribution_accounting", "Purpose of the contribution unit")
	version := flag.String("version", "0.2.0", "Token schema version")

	flag.Parse()

	issueUnits := *units
	if *manifestPath != "" {
		manifest, err := protocol.LoadManifestFromFile(*manifestPath)
		if err != nil {
			log.Fatalf("Failed to load manifest: %v", err)
		}
		res := verifier.VerifyManifest(verifier.VerificationOptions{Manifest: manifest})
		if !res.Success {
			log.Fatalf("Cannot derive contribution units from unverified manifest: [%s] %s", res.Code, res.Message)
		}
		accounting, _, err := protocol.CalculateContribution(manifest, res.ProvenanceLevel, nil)
		if err != nil {
			log.Fatalf("Failed to calculate contribution: %v", err)
		}
		issueUnits = accounting.Units
		log.Printf("Derived %d ₿DATA contribution units from verified manifest (Provenance: %s)", issueUnits, res.ProvenanceLevel)
	}

	log.Printf("Issuing %s (%s) contribution asset units (Units: %d)...", *displaySymbol, *tokenName, issueUnits)
	log.Printf("Note: %s is an accounting unit for verified robotics-data contribution; not monetary account.", *displaySymbol)

	meta := protocol.TokenMetadata{
		Project:                *project,
		Symbol:                 *tokenName,
		DisplaySymbol:          *displaySymbol,
		Type:                   "verified_robotics_data_contribution_unit",
		Purpose:                *purpose,
		MonetaryUnit:           protocol.MonetaryUnitSats,
		BTCRedemptionGuarantee: false,
		Version:                *version,
	}

	metaBytes, err := meta.EncodeJSON()
	if err != nil {
		log.Fatalf("Failed to encode token metadata: %v", err)
	}

	client := taproot.NewClient()

	mintOut, err := client.MintAsset("normal", *tokenName, issueUnits, metaBytes)
	if err != nil {
		log.Fatalf("Failed to mint %s asset: %v", *tokenName, err)
	}

	log.Printf("Successfully created %s contribution asset batch.", *tokenName)
	fmt.Printf("Mint Batch Output:\n%s\n", mintOut)

	log.Println("Finalizing batch...")
	finOut, err := client.FinalizeBatch()
	if err != nil {
		log.Fatalf("Failed to finalize batch: %v", err)
	}

	fmt.Printf("Finalize Output:\n%s\n", finOut)
	log.Printf("Batch finalized and broadcast. Mine 1 Bitcoin block to confirm and generate asset ID.")
}
