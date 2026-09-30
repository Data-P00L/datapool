package main

import (
	"flag"
	"fmt"
	"log"
	"os"
	"time"

	"github.com/dattapool/mvp/pkg/metadata"
	"github.com/dattapool/mvp/pkg/tapclient"
)

func main() {
	videoHash := flag.String("video_hash", "", "SHA-256 hash of the video to verify")
	videoFile := flag.String("video_file", "", "Path to the video file (computes SHA-256 automatically)")
	flag.Parse()

	targetHash := *videoHash
	if targetHash == "" && *videoFile != "" {
		computedHash, err := metadata.HashFile(*videoFile)
		if err != nil {
			log.Fatalf("Failed to compute hash for file %s: %v", *videoFile, err)
		}
		targetHash = computedHash
		log.Printf("Computed SHA-256 for %s: %s", *videoFile, targetHash)
	}

	if targetHash == "" {
		log.Fatal("Either -video_hash or -video_file must be provided")
	}

	log.Printf("Searching for claim asset associated with video hash: %s...", targetHash)

	client := tapclient.NewClient()

	asset, meta, err := client.FindClaimByVideoHash(targetHash)
	if err != nil {
		fmt.Printf("❌ FAILED: Could not verify claim for video hash %s: %v\n", targetHash, err)
		os.Exit(1)
	}

	fmt.Printf("\n✅ VERIFIED: Found Claim Asset ID %s\n", asset.AssetGenesis.AssetID)
	fmt.Printf("Meta Hash: %s\n", asset.AssetGenesis.MetaHash)
	fmt.Printf("Worker PubKey: %s\n", meta.WorkerPubKey)
	fmt.Printf("Skill Tag: %s\n", meta.SkillTag)
	fmt.Printf("Contribution Units: %d ₿DATA (DTTA)\n", meta.RewardAmount)
	fmt.Printf("Data Asset ID reference: %s\n", meta.DataAssetID)
	fmt.Printf("Timestamp: %s (Unix: %d)\n", time.Unix(meta.Timestamp, 0).UTC().Format(time.RFC3339), meta.Timestamp)
	fmt.Printf("Genesis Point: %s\n", asset.AssetGenesis.GenesisPoint)
}
