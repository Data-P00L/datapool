package main

import (
	"flag"
	"fmt"
	"log"

	"github.com/dattapool/mvp/pkg/metadata"
	"github.com/dattapool/mvp/pkg/tapclient"
)

func main() {
	workerPubKey := flag.String("worker_pubkey", "", "Worker's Public Key (hex)")
	videoHash := flag.String("video_hash", "", "SHA-256 hash of the video")
	videoFile := flag.String("video_file", "", "Path to the video file (computes SHA-256 automatically)")
	timestamp := flag.Int64("timestamp", 0, "Unix timestamp (defaults to current time)")
	skillTag := flag.String("skill_tag", "manipulation", "Skill tag describing the video task")
	dataAssetID := flag.String("data_asset_id", "", "ID of the ₿DATA (DTTA) contribution asset")
	rewardAmount := flag.Int("reward_amount", 100, "Amount of ₿DATA (DTTA) contribution units")

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

	meta := metadata.VideoMetadata{
		WorkerPubKey:  *workerPubKey,
		VideoHash:     targetHash,
		Timestamp:     *timestamp,
		SkillTag:      *skillTag,
		DataAssetID:   *dataAssetID,
		RewardAmount:  *rewardAmount,
		SchemaVersion: "1.0",
	}

	if err := meta.Validate(); err != nil {
		log.Fatalf("Validation error: %v", err)
	}

	metaBytes, err := meta.EncodeJSON()
	if err != nil {
		log.Fatalf("Failed to encode metadata: %v", err)
	}

	log.Printf("Minting Collectible Claim for video %s...", targetHash)

	client := tapclient.NewClient()

	mintOut, err := client.MintAsset("collectible", "CLAIM", 1, metaBytes)
	if err != nil {
		log.Fatalf("Failed to mint claim asset: %v", err)
	}

	fmt.Printf("Claim Mint Batch Output:\n%s\n", mintOut)

	log.Println("Finalizing batch...")
	finOut, err := client.FinalizeBatch()
	if err != nil {
		log.Fatalf("Failed to finalize batch: %v", err)
	}

	fmt.Printf("Finalize Output:\n%s\n", finOut)
	log.Printf("Claim batch finalized. Mine 1 Bitcoin block to confirm and publish to Universe.")
}
