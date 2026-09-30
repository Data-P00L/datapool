package main

import (
	"context"
	"flag"
	"fmt"
	"log"

	"github.com/dattapool/mvp/internal/taproot"
	"github.com/dattapool/mvp/internal/verifier"
	"github.com/dattapool/mvp/pkg/protocol"
)

func main() {
	manifestPath := flag.String("manifest", "session_data/capture-manifest.json", "Path to capture manifest file")
	skipPreflight := flag.Bool("skip_preflight", false, "Skip local cryptographic pre-flight checks")
	flag.Parse()

	manifest, err := protocol.LoadManifestFromFile(*manifestPath)
	if err != nil {
		log.Fatalf("Failed to load manifest: %v", err)
	}

	// 1. Run local pre-flight verification before on-chain minting
	if !*skipPreflight {
		res := verifier.VerifyManifest(verifier.VerificationOptions{
			Manifest: manifest,
		})
		if !res.Success {
			log.Fatalf("Pre-flight manifest verification failed: [%s] %s", res.Code, res.Message)
		}
		fmt.Printf("✓ Pre-flight verification passed (Provenance Level: %s).\n", res.ProvenanceLevel)
	}

	// 2. Build compact TAP Claim Metadata
	claimMeta, err := protocol.BuildClaimMetadataFromManifest(manifest)
	if err != nil {
		log.Fatalf("Failed to build claim metadata: %v", err)
	}

	claimMetaBytes, err := claimMeta.EncodeJSON()
	if err != nil {
		log.Fatalf("Failed to encode claim metadata: %v", err)
	}

	fmt.Printf("Publishing TAP Claim Asset for Manifest Hash: %s\n", claimMeta.ManifestHash)
	fmt.Printf("Claim Metadata (JSON): %s\n", string(claimMetaBytes))

	// 3. Mint via tapd
	tapClient := taproot.NewClient()
	ctx := context.Background()

	res, err := tapClient.MintClaimAsset(ctx, claimMetaBytes)
	if err != nil {
		log.Fatalf("Failed to mint claim asset: %v", err)
	}

	fmt.Printf("Claim asset batch submitted. Mine 1 Bitcoin block to confirm and generate asset ID.\n")
	_ = res
}
