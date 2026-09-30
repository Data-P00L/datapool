package verifier

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/dattapool/mvp/internal/capture"
	"github.com/dattapool/mvp/internal/crypto"
	"github.com/dattapool/mvp/pkg/protocol"
)

func TestVerifierFullSuccess040(t *testing.T) {
	tempDir := t.TempDir()
	workerKeyPath := filepath.Join(tempDir, "worker.key")
	deviceKeyPath := filepath.Join(tempDir, "device.key")

	workerKey, _ := crypto.GenerateKeyPair()
	_ = crypto.SaveKeyToFile(workerKey, workerKeyPath)

	deviceKey, _ := crypto.GenerateKeyPair()
	_ = crypto.SaveKeyToFile(deviceKey, deviceKeyPath)

	session, err := capture.StartSession(capture.StartSessionOptions{
		WorkerKeyPath: workerKeyPath,
		DeviceKeyPath: deviceKeyPath,
		SkillTag:      "pick_cube",
		WitnessMode:   "mock",
		FreshnessMode: "local",
		StoreDir:      tempDir,
	})
	if err != nil {
		t.Fatalf("session start failed: %v", err)
	}

	videoPath := filepath.Join(tempDir, "test-video.mp4")
	telemetryPath := filepath.Join(tempDir, "test-telemetry.jsonl")

	videoData := make([]byte, 128*1024)
	_ = os.WriteFile(videoPath, videoData, 0644)

	telemetryContent := `{"timestamp_sec":1786990000.0,"frame_index":0,"imu":{"linear_accel":[0,0,9.81],"angular_vel":[0,0,0]},"joints":{"positions":[0,0,0,0,0,0,0],"velocities":[0,0,0,0,0,0,0],"efforts":[0,0,0,0,0,0,0]},"gripper":{"width":0.08,"force":0},"mode":"AUTONOMOUS"}
{"timestamp_sec":1786990001.0,"frame_index":1,"imu":{"linear_accel":[0,0,9.81],"angular_vel":[0,0,0]},"joints":{"positions":[0.1,0,0,0,0,0,0],"velocities":[0.1,0,0,0,0,0,0],"efforts":[1.2,0,0,0,0,0,0]},"gripper":{"width":0.08,"force":0},"mode":"AUTONOMOUS"}
`
	_ = os.WriteFile(telemetryPath, []byte(telemetryContent), 0644)

	_ = session.Ingest(videoPath, telemetryPath)
	manifest, err := session.StopAndSeal(deviceKeyPath)
	if err != nil {
		t.Fatalf("stop and seal failed: %v", err)
	}

	// Verify
	res := VerifyManifest(VerificationOptions{
		Manifest:      manifest,
		VideoPath:     videoPath,
		TelemetryPath: telemetryPath,
		NostrStoreDir: tempDir,
	})

	if !res.Success || res.Code != CodeVerified {
		t.Fatalf("verification failed: code=%s, message=%s", res.Code, res.Message)
	}
	if res.ProvenanceLevel != protocol.LevelP3 {
		t.Fatalf("expected P3 level for local freshness, got %s", res.ProvenanceLevel)
	}
}

func TestVerifierTamperedWorkerSignature(t *testing.T) {
	tempDir := t.TempDir()
	workerKeyPath := filepath.Join(tempDir, "worker.key")
	deviceKeyPath := filepath.Join(tempDir, "device.key")

	workerKey, _ := crypto.GenerateKeyPair()
	_ = crypto.SaveKeyToFile(workerKey, workerKeyPath)
	deviceKey, _ := crypto.GenerateKeyPair()
	_ = crypto.SaveKeyToFile(deviceKey, deviceKeyPath)

	session, _ := capture.StartSession(capture.StartSessionOptions{
		WorkerKeyPath: workerKeyPath,
		DeviceKeyPath: deviceKeyPath,
		StoreDir:      tempDir,
	})
	videoPath := filepath.Join(tempDir, "v.mp4")
	telemetryPath := filepath.Join(tempDir, "t.jsonl")
	_ = os.WriteFile(videoPath, make([]byte, 64*1024), 0644)
	_ = os.WriteFile(telemetryPath, []byte(`{"timestamp_sec":1.0,"frame_index":0,"imu":{"linear_accel":[0,0,0],"angular_vel":[0,0,0]},"joints":{"positions":[0,0,0,0,0,0,0],"velocities":[0,0,0,0,0,0,0],"efforts":[0,0,0,0,0,0,0]},"gripper":{"width":0,"force":0},"mode":"A"}`), 0644)
	_ = session.Ingest(videoPath, telemetryPath)
	manifest, _ := session.StopAndSeal(deviceKeyPath)

	// Tamper worker signature
	manifest.Authorization.WorkerDeviceAuth.WorkerSignature = "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"

	res := VerifyManifest(VerificationOptions{
		Manifest:      manifest,
		VideoPath:     videoPath,
		TelemetryPath: telemetryPath,
		NostrStoreDir: tempDir,
	})

	if res.Success || res.Code != CodeInvalidWorkerSignature {
		t.Fatalf("expected code %s, got %s (success=%v)", CodeInvalidWorkerSignature, res.Code, res.Success)
	}
}

func TestVerifierTamperedDeviceAuthorization(t *testing.T) {
	tempDir := t.TempDir()
	workerKeyPath := filepath.Join(tempDir, "worker.key")
	deviceKeyPath := filepath.Join(tempDir, "device.key")

	workerKey, _ := crypto.GenerateKeyPair()
	_ = crypto.SaveKeyToFile(workerKey, workerKeyPath)
	deviceKey, _ := crypto.GenerateKeyPair()
	_ = crypto.SaveKeyToFile(deviceKey, deviceKeyPath)

	session, _ := capture.StartSession(capture.StartSessionOptions{
		WorkerKeyPath: workerKeyPath,
		DeviceKeyPath: deviceKeyPath,
		StoreDir:      tempDir,
	})
	videoPath := filepath.Join(tempDir, "v.mp4")
	telemetryPath := filepath.Join(tempDir, "t.jsonl")
	_ = os.WriteFile(videoPath, make([]byte, 64*1024), 0644)
	_ = os.WriteFile(telemetryPath, []byte(`{"timestamp_sec":1.0,"frame_index":0,"imu":{"linear_accel":[0,0,0],"angular_vel":[0,0,0]},"joints":{"positions":[0,0,0,0,0,0,0],"velocities":[0,0,0,0,0,0,0],"efforts":[0,0,0,0,0,0,0]},"gripper":{"width":0,"force":0},"mode":"A"}`), 0644)
	_ = session.Ingest(videoPath, telemetryPath)
	manifest, _ := session.StopAndSeal(deviceKeyPath)

	// Tamper device authorization signature
	manifest.Authorization.DeviceSessionAuth.DeviceSignature = "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"

	res := VerifyManifest(VerificationOptions{
		Manifest:      manifest,
		VideoPath:     videoPath,
		TelemetryPath: telemetryPath,
		NostrStoreDir: tempDir,
	})

	if res.Success || res.Code != CodeInvalidDeviceAuthorization {
		t.Fatalf("expected code %s, got %s (success=%v)", CodeInvalidDeviceAuthorization, res.Code, res.Success)
	}
}
