package main

import (
	"flag"
	"fmt"
	"log"

	"github.com/dattapool/mvp/pkg/tapclient"
)

func main() {
	dataAssetID := flag.String("data_asset_id", "", "ID of the ₿DATA (DTTA) contribution asset to transfer")
	amount := flag.Uint64("amount", 100, "Amount of ₿DATA contribution units to transfer")
	workerAddr := flag.String("worker_tap_addr", "", "Worker's Taproot Asset address (if empty, one will be generated for simulation)")

	flag.Parse()

	if *dataAssetID == "" {
		log.Fatal("data_asset_id is required")
	}

	client := tapclient.NewClient()

	targetAddr := *workerAddr
	if targetAddr == "" {
		log.Printf("Generating new Taproot Asset address for receiving %d ₿DATA contribution units...", *amount)
		addrResp, err := client.NewAddress(*dataAssetID, *amount)
		if err != nil {
			log.Fatalf("Failed to create receiver address: %v", err)
		}
		targetAddr = addrResp.Encoded
		log.Printf("Created TAP receive address: %s", targetAddr)
	}

	log.Printf("Sending %d ₿DATA contribution units to worker address...", *amount)
	sendOut, err := client.SendAsset(targetAddr)
	if err != nil {
		log.Fatalf("Failed to send contribution units: %v", err)
	}

	fmt.Printf("\n✅ CONTRIBUTION UNITS SENT: Transferred %d ₿DATA (DTTA) units!\n", *amount)
	fmt.Printf("Receiver Address: %s\n", targetAddr)
	fmt.Printf("Send Response:\n%s\n", sendOut)
	log.Printf("Mine 1 Bitcoin block to confirm the transfer transaction.")
}
