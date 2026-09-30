package main

import (
	"flag"
	"fmt"
	"os"
	"path/filepath"

	"github.com/dattapool/mvp/internal/verifier"
	"github.com/dattapool/mvp/pkg/protocol"
)

func main() {
	manifestPath := flag.String("manifest", "session_data/capture-manifest.json", "Path to capture manifest file")
	videoPath := flag.String("video", "", "Path to raw video file for hash/Merkle verification (optional)")
	telemetryPath := flag.String("telemetry", "", "Path to raw telemetry JSONL file (optional)")
	nonceHex := flag.String("nonce", "", "Session nonce hex for chain verification (optional)")
	targetAssetID := flag.String("asset_id", "", "Target TAP claim asset ID to verify on-chain (optional)")
	checkTapd := flag.Bool("check_tapd", false, "Query local tapd daemon for asset metadata & Universe proof")
	requireLevel := flag.String("require_level", "", "Required minimum provenance level (e.g. P4)")
	nostrStore := flag.String("nostr_store", "", "Path to local nostr event store directory")
	flag.Parse()

	manifest, err := protocol.LoadManifestFromFile(*manifestPath)
	if err != nil {
		fmt.Fprintf(os.Stderr, "FAILED: INVALID_MANIFEST_SCHEMA\nDetails: %v\n", err)
		os.Exit(1)
	}

	storeDir := *nostrStore
	if storeDir == "" {
		storeDir = filepath.Join(filepath.Dir(*manifestPath), "nostr_store")
	}

	opts := verifier.VerificationOptions{
		Manifest:               manifest,
		VideoPath:              *videoPath,
		TelemetryPath:          *telemetryPath,
		SessionNonceHex:        *nonceHex,
		TargetAssetID:          *targetAssetID,
		CheckTapd:              *checkTapd,
		RequireProvenanceLevel: protocol.ProvenanceLevel(*requireLevel),
		NostrStoreDir:          storeDir,
	}

	res := verifier.VerifyManifest(opts)

	if !res.Success {
		fmt.Printf("FAILED: %s\n", res.Code)
		fmt.Printf("Details: %s\n", res.Message)
		os.Exit(1)
	}

	fmt.Println("==================================================")
	fmt.Println("RESULT: VERIFIED")
	fmt.Printf("PROVENANCE LEVEL: %s\n", res.ProvenanceLevel)
	fmt.Println("==================================================")
	fmt.Printf("Manifest Hash:        %s\n", res.ManifestHash)
	fmt.Printf("Worker Nostr Pubkey:  %s\n", res.WorkerPubKey)
	fmt.Printf("Device Pubkey:        %s\n", res.DevicePubKey)
	fmt.Printf("Session ID:           %s\n", res.SessionID)
	fmt.Printf("Capture Chain Root:   %s\n", res.ChainRoot)
	if res.TapAssetID != "" {
		fmt.Printf("Taproot Asset ID:     %s\n", res.TapAssetID)
	}
	fmt.Println("Verified Checks:")
	for _, check := range res.VerifiedChecks {
		fmt.Printf("  ✓ %s\n", check)
	}
}
