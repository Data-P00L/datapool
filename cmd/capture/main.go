package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"path/filepath"

	"github.com/dattapool/mvp/internal/capture"
	"github.com/dattapool/mvp/internal/crypto"
	"github.com/dattapool/mvp/internal/possession"
	"github.com/dattapool/mvp/pkg/protocol"
)

func main() {
	if len(os.Args) < 2 {
		printUsage()
		os.Exit(1)
	}

	subcmd := os.Args[1]
	switch subcmd {
	case "keygen":
		runKeygen(os.Args[2:])
	case "start":
		runStart(os.Args[2:])
	case "ingest":
		runIngest(os.Args[2:])
	case "stop":
		runStop(os.Args[2:])
	case "quick":
		runQuick(os.Args[2:])
	case "inspect":
		runInspect(os.Args[2:])
	case "possession-challenge":
		runPossessionChallenge(os.Args[2:])
	case "possession-verify":
		runPossessionVerify(os.Args[2:])
	default:
		fmt.Fprintf(os.Stderr, "Unknown subcommand: %s\n", subcmd)
		printUsage()
		os.Exit(1)
	}
}

func printUsage() {
	fmt.Println("DattaPool Capture Client (Protocol 0.4.0)")
	fmt.Println("Usage:")
	fmt.Println("  capture keygen [--out <file>]")
	fmt.Println("  capture start --worker-key <file> --device-key <file> [--skill <tag>] [--witness <nostr|mock|offline>] [--out <file>]")
	fmt.Println("  capture ingest --session <state-file> --video <file> --telemetry <file>")
	fmt.Println("  capture stop --session <state-file> --device-key <file> [--out-manifest <file>]")
	fmt.Println("  capture quick --worker-key <file> --device-key <file> --video <file> --telemetry <file> [--out-dir <dir>]")
	fmt.Println("  capture inspect <manifest-file>")
	fmt.Println("  capture possession-challenge --manifest <manifest-file> --count <n> --out <file>")
	fmt.Println("  capture possession-verify --challenge <file> --response <file> --manifest <file>")
}

func runKeygen(args []string) {
	fs := flag.NewFlagSet("keygen", flag.ExitOnError)
	outPath := fs.String("out", "keys/device.key", "Output path for private key")
	_ = fs.Parse(args)

	kp, err := crypto.GenerateKeyPair()
	if err != nil {
		fmt.Fprintf(os.Stderr, "Keygen failed: %v\n", err)
		os.Exit(1)
	}

	if err := crypto.SaveKeyToFile(kp, *outPath); err != nil {
		fmt.Fprintf(os.Stderr, "Failed to save key to %s: %v\n", *outPath, err)
		os.Exit(1)
	}

	fmt.Printf("Generated keypair successfully.\n")
	fmt.Printf("Public Key (hex): %s\n", kp.PublicKeyHex)
	fmt.Printf("Saved to:         %s\n", *outPath)
}

func runStart(args []string) {
	fs := flag.NewFlagSet("start", flag.ExitOnError)
	workerKey := fs.String("worker-key", "keys/worker.key", "Path to worker private key")
	deviceKey := fs.String("device-key", "keys/device.key", "Path to device private key")
	skillTag := fs.String("skill", protocol.DefaultSkillTag, "Task skill tag")
	witnessMode := fs.String("witness", "nostr", "Witness mode: nostr, mock, offline")
	freshnessMode := fs.String("freshness", "bitcoin", "Freshness mode: bitcoin, local")
	outState := fs.String("out", "", "Path to save session state JSON")
	_ = fs.Parse(args)

	session, err := capture.StartSession(capture.StartSessionOptions{
		WorkerKeyPath: *workerKey,
		DeviceKeyPath: *deviceKey,
		SkillTag:      *skillTag,
		WitnessMode:   *witnessMode,
		FreshnessMode: *freshnessMode,
	})
	if err != nil {
		fmt.Fprintf(os.Stderr, "Failed to start capture session: %v\n", err)
		os.Exit(1)
	}

	if *outState == "" {
		*outState = fmt.Sprintf("session_data/session-%s.json", session.SessionID)
	}
	if err := session.SaveSessionState(*outState); err != nil {
		fmt.Fprintf(os.Stderr, "Failed to save session state: %v\n", err)
		os.Exit(1)
	}

	fmt.Println("--- Capture Session Started (0.4.0) ---")
	fmt.Printf("Session ID:           %s\n", session.SessionID)
	fmt.Printf("Session Pubkey:       %s\n", session.SessionKeyPair.PublicKeyHex)
	fmt.Printf("Nonce Commitment:     %s\n", session.NonceCommitmentHex)
	fmt.Printf("Freshness Reference:  %s (Block: %d, Hash: %s)\n", session.Freshness.Type, session.Freshness.BlockHeight, session.Freshness.BlockHash)
	if session.Witness.Published {
		fmt.Printf("Nostr Witness Event:  %s (Published: true)\n", session.Witness.NostrEventID)
	} else {
		fmt.Printf("Nostr Witness Event:  None (Offline/Unwitnessed)\n")
	}
	fmt.Printf("Session state saved:  %s\n", *outState)
}

func runIngest(args []string) {
	fs := flag.NewFlagSet("ingest", flag.ExitOnError)
	sessionPath := fs.String("session", "", "Path to session state JSON")
	videoPath := fs.String("video", "", "Path to video file")
	telemetryPath := fs.String("telemetry", "", "Path to telemetry JSONL file")
	_ = fs.Parse(args)

	if *sessionPath == "" || *videoPath == "" || *telemetryPath == "" {
		fmt.Fprintln(os.Stderr, "Error: --session, --video, and --telemetry are required")
		os.Exit(1)
	}

	session, err := capture.LoadSessionState(*sessionPath)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Failed to load session: %v\n", err)
		os.Exit(1)
	}

	if err := session.Ingest(*videoPath, *telemetryPath); err != nil {
		fmt.Fprintf(os.Stderr, "Ingestion failed: %v\n", err)
		os.Exit(1)
	}

	if err := session.SaveSessionState(*sessionPath); err != nil {
		fmt.Fprintf(os.Stderr, "Failed to save updated session: %v\n", err)
		os.Exit(1)
	}

	fmt.Println("--- Streams Ingested Successfully ---")
	fmt.Printf("Chunks Processed:       %d\n", session.ProcessedData.ChunkCount)
	fmt.Printf("Video SHA-256:          %s\n", session.ProcessedData.VideoSHA256)
	fmt.Printf("Video Merkle Root:      %s\n", session.ProcessedData.VideoMerkleRoot)
	fmt.Printf("Telemetry Merkle Root:  %s\n", session.ProcessedData.TelemetryMerkleRoot)
	fmt.Printf("Capture Chain Root Hn:  %s\n", session.ProcessedData.CaptureChainRoot)
}

func runStop(args []string) {
	fs := flag.NewFlagSet("stop", flag.ExitOnError)
	sessionPath := fs.String("session", "", "Path to session state JSON")
	deviceKey := fs.String("device-key", "keys/device.key", "Path to device private key")
	outManifest := fs.String("out-manifest", "session_data/capture-manifest.json", "Output manifest file path")
	_ = fs.Parse(args)

	if *sessionPath == "" {
		fmt.Fprintln(os.Stderr, "Error: --session is required")
		os.Exit(1)
	}

	session, err := capture.LoadSessionState(*sessionPath)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Failed to load session: %v\n", err)
		os.Exit(1)
	}

	manifest, err := session.StopAndSeal(*deviceKey)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Stop and seal failed: %v\n", err)
		os.Exit(1)
	}

	if err := manifest.SaveToFile(*outManifest); err != nil {
		fmt.Fprintf(os.Stderr, "Failed to save manifest: %v\n", err)
		os.Exit(1)
	}

	manifestHash, _ := manifest.ComputeManifestHash()
	fmt.Println("--- Session Sealed & Manifest Created ---")
	fmt.Printf("Manifest Hash:        %s\n", manifestHash)
	fmt.Printf("Assessed Provenance:  %s\n", manifest.Provenance.Level)
	fmt.Printf("Saved manifest to:    %s\n", *outManifest)
}

func runQuick(args []string) {
	fs := flag.NewFlagSet("quick", flag.ExitOnError)
	workerKey := fs.String("worker-key", "keys/worker.key", "Worker private key")
	deviceKey := fs.String("device-key", "keys/device.key", "Device private key")
	videoPath := fs.String("video", "fixtures/sample-video.mp4", "Video file")
	telemetryPath := fs.String("telemetry", "fixtures/sample-telemetry.jsonl", "Telemetry file")
	skillTag := fs.String("skill", protocol.DefaultSkillTag, "Skill tag")
	witnessMode := fs.String("witness", "nostr", "Witness mode")
	freshnessMode := fs.String("freshness", "bitcoin", "Freshness mode")
	outDir := fs.String("out-dir", "session_data", "Output directory")
	_ = fs.Parse(args)

	_ = os.MkdirAll(*outDir, 0755)

	session, err := capture.StartSession(capture.StartSessionOptions{
		WorkerKeyPath: *workerKey,
		DeviceKeyPath: *deviceKey,
		SkillTag:      *skillTag,
		WitnessMode:   *witnessMode,
		FreshnessMode: *freshnessMode,
		StoreDir:      *outDir,
	})
	if err != nil {
		fmt.Fprintf(os.Stderr, "Quick session start failed: %v\n", err)
		os.Exit(1)
	}

	if err := session.Ingest(*videoPath, *telemetryPath); err != nil {
		fmt.Fprintf(os.Stderr, "Quick ingest failed: %v\n", err)
		os.Exit(1)
	}

	manifest, err := session.StopAndSeal(*deviceKey)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Quick seal failed: %v\n", err)
		os.Exit(1)
	}

	stateFile := filepath.Join(*outDir, fmt.Sprintf("session-%s.json", session.SessionID))
	_ = session.SaveSessionState(stateFile)

	manifestFile := filepath.Join(*outDir, "capture-manifest.json")
	if err := manifest.SaveToFile(manifestFile); err != nil {
		fmt.Fprintf(os.Stderr, "Failed to save manifest: %v\n", err)
		os.Exit(1)
	}

	manifestHash, _ := manifest.ComputeManifestHash()
	fmt.Println("--- Quick Capture Workflow Completed (0.4.0) ---")
	fmt.Printf("Session ID:           %s\n", session.SessionID)
	fmt.Printf("Manifest Hash:        %s\n", manifestHash)
	fmt.Printf("Provenance Level:     %s\n", manifest.Provenance.Level)
	fmt.Printf("Video SHA-256:        %s\n", manifest.Capture.VideoSHA256)
	fmt.Printf("Capture Chain Root:   %s\n", manifest.Capture.CaptureChainRoot)
	fmt.Printf("Manifest Path:        %s\n", manifestFile)
}

func runInspect(args []string) {
	if len(args) < 1 {
		fmt.Fprintln(os.Stderr, "Usage: capture inspect <manifest-file>")
		os.Exit(1)
	}
	manifest, err := protocol.LoadManifestFromFile(args[0])
	if err != nil {
		fmt.Fprintf(os.Stderr, "Failed to load manifest: %v\n", err)
		os.Exit(1)
	}

	manifestHash, _ := manifest.ComputeManifestHash()
	raw, _ := json.MarshalIndent(manifest, "", "  ")
	fmt.Printf("Manifest Hash: %s\n", manifestHash)
	fmt.Println(string(raw))
}

func runPossessionChallenge(args []string) {
	fs := flag.NewFlagSet("possession-challenge", flag.ExitOnError)
	manifestPath := fs.String("manifest", "session_data/capture-manifest.json", "Path to manifest")
	count := fs.Int("count", 5, "Number of chunk indexes to challenge")
	outPath := fs.String("out", "session_data/possession-challenge.json", "Output path for challenge")
	_ = fs.Parse(args)

	manifest, err := protocol.LoadManifestFromFile(*manifestPath)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Failed to load manifest: %v\n", err)
		os.Exit(1)
	}

	ch, err := possession.GenerateChallenge(manifest.Session.ID, manifest.Capture.ChunkCount, *count)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Failed to generate challenge: %v\n", err)
		os.Exit(1)
	}

	if err := possession.SaveChallenge(ch, *outPath); err != nil {
		fmt.Fprintf(os.Stderr, "Failed to save challenge: %v\n", err)
		os.Exit(1)
	}

	fmt.Printf("Generated possession challenge for session %s (indexes: %v)\n", ch.SessionID, ch.RequestedIndexes)
	fmt.Printf("Saved to: %s\n", *outPath)
}

func runPossessionVerify(args []string) {
	fs := flag.NewFlagSet("possession-verify", flag.ExitOnError)
	challengePath := fs.String("challenge", "", "Path to challenge file")
	responsePath := fs.String("response", "", "Path to response file")
	manifestPath := fs.String("manifest", "", "Path to manifest file")
	_ = fs.Parse(args)

	if *challengePath == "" || *responsePath == "" || *manifestPath == "" {
		fmt.Fprintln(os.Stderr, "Error: --challenge, --response, and --manifest are required")
		os.Exit(1)
	}

	manifest, err := protocol.LoadManifestFromFile(*manifestPath)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Failed to load manifest: %v\n", err)
		os.Exit(1)
	}

	var ch possession.PossessionChallenge
	chData, _ := os.ReadFile(*challengePath)
	_ = json.Unmarshal(chData, &ch)

	var resp possession.PossessionResponse
	respData, _ := os.ReadFile(*responsePath)
	_ = json.Unmarshal(respData, &resp)

	valid, err := possession.VerifyResponse(&ch, &resp, manifest.Capture.VideoMerkleRoot)
	if err != nil || !valid {
		fmt.Printf("FAILED: INVALID_POSSESSION_PROOF (%v)\n", err)
		os.Exit(1)
	}

	fmt.Println("==================================================")
	fmt.Println("RESULT: POSSESSION VERIFIED")
	fmt.Println("==================================================")
	fmt.Printf("Session ID:        %s\n", ch.SessionID)
	fmt.Printf("Challenge Nonce:   %s\n", ch.ChallengeNonce)
	fmt.Printf("Verified Chunks:   %v\n", ch.RequestedIndexes)
}
