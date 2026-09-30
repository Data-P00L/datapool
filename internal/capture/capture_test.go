package capture

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/dattapool/mvp/internal/crypto"
	"github.com/dattapool/mvp/pkg/protocol"
)

func TestCaptureSessionWorkflow040(t *testing.T) {
	tempDir := t.TempDir()
	workerKeyPath := filepath.Join(tempDir, "worker.key")
	deviceKeyPath := filepath.Join(tempDir, "device.key")

	workerKey, err := crypto.GenerateKeyPair()
	if err != nil {
		t.Fatalf("failed to generate worker key: %v", err)
	}
	if err := crypto.SaveKeyToFile(workerKey, workerKeyPath); err != nil {
		t.Fatalf("failed to save worker key: %v", err)
	}

	deviceKey, err := crypto.GenerateKeyPair()
	if err != nil {
		t.Fatalf("failed to generate device key: %v", err)
	}
	if err := crypto.SaveKeyToFile(deviceKey, deviceKeyPath); err != nil {
		t.Fatalf("failed to save device key: %v", err)
	}

	// 1. Start Session
	session, err := StartSession(StartSessionOptions{
		WorkerKeyPath: workerKeyPath,
		DeviceKeyPath: deviceKeyPath,
		SkillTag:      "object_sort",
		WitnessMode:   "mock",
		FreshnessMode: "local",
		StoreDir:      tempDir,
	})
	if err != nil {
		t.Fatalf("failed to start capture session: %v", err)
	}

	if session.Status != "STARTED" {
		t.Fatalf("expected status STARTED, got %s", session.Status)
	}
	if session.WorkerDeviceAuth.WorkerSignature == "" {
		t.Fatal("missing worker device auth signature")
	}
	if session.DeviceSessionAuth.DeviceSignature == "" {
		t.Fatal("missing device session auth signature")
	}

	// 2. Ingest
	videoPath := filepath.Join(tempDir, "test-video.mp4")
	telemetryPath := filepath.Join(tempDir, "test-telemetry.jsonl")

	videoData := make([]byte, 128*1024) // 128KB -> 2 chunks of 64KB
	for i := range videoData {
		videoData[i] = byte(i % 256)
	}
	if err := os.WriteFile(videoPath, videoData, 0644); err != nil {
		t.Fatalf("failed to write test video: %v", err)
	}

	telemetryContent := `{"timestamp_sec":1786990000.0,"frame_index":0,"imu":{"linear_accel":[0,0,9.81],"angular_vel":[0,0,0]},"joints":{"positions":[0,0,0,0,0,0,0],"velocities":[0,0,0,0,0,0,0],"efforts":[0,0,0,0,0,0,0]},"gripper":{"width":0.08,"force":0},"mode":"AUTONOMOUS"}
{"timestamp_sec":1786990001.0,"frame_index":1,"imu":{"linear_accel":[0,0,9.81],"angular_vel":[0,0,0]},"joints":{"positions":[0.1,0,0,0,0,0,0],"velocities":[0.1,0,0,0,0,0,0],"efforts":[1.2,0,0,0,0,0,0]},"gripper":{"width":0.08,"force":0},"mode":"AUTONOMOUS"}
`
	if err := os.WriteFile(telemetryPath, []byte(telemetryContent), 0644); err != nil {
		t.Fatalf("failed to write test telemetry: %v", err)
	}

	if err := session.Ingest(videoPath, telemetryPath); err != nil {
		t.Fatalf("ingest failed: %v", err)
	}

	if session.Status != "INGESTED" {
		t.Fatalf("expected status INGESTED, got %s", session.Status)
	}

	// 3. Stop and Seal
	manifest, err := session.StopAndSeal(deviceKeyPath)
	if err != nil {
		t.Fatalf("stop and seal failed: %v", err)
	}

	if manifest.Protocol != protocol.ProtocolName {
		t.Fatalf("protocol name mismatch: %s", manifest.Protocol)
	}
	if manifest.SchemaVersion != protocol.SchemaVersion {
		t.Fatalf("schema version mismatch: %s", manifest.SchemaVersion)
	}
	if manifest.Seal.SessionSealSignature == "" {
		t.Fatal("manifest missing session seal signature")
	}
}
