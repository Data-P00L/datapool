package capture

import (
	"bufio"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"

	"github.com/dattapool/mvp/pkg/protocol"
)

// IMUReading represents 3-axis accelerometer and gyroscope data.
type IMUReading struct {
	LinearAccel [3]float64 `json:"linear_accel"` // m/s^2 (x, y, z)
	AngularVel  [3]float64 `json:"angular_vel"`  // rad/s (roll, pitch, yaw)
}

// JointState represents joint angles and velocities for a robotic manipulator.
type JointState struct {
	Positions  []float64 `json:"positions"`  // radians
	Velocities []float64 `json:"velocities"` // rad/s
	Efforts    []float64 `json:"efforts"`    // Nm
}

// GripperState represents the end-effector gripper state.
type GripperState struct {
	Width float64 `json:"width"` // meters
	Force float64 `json:"force"` // Newtons
}

// TelemetryRecord represents a single synchronized sensor frame.
type TelemetryRecord struct {
	TimestampSec float64      `json:"timestamp_sec"`
	FrameIndex   int          `json:"frame_index"`
	IMU          IMUReading   `json:"imu"`
	Joints       JointState   `json:"joints"`
	Gripper      GripperState `json:"gripper"`
	Mode         string       `json:"mode"`
}

// TelemetryChunk represents a batch of telemetry records corresponding to a video chunk.
type TelemetryChunk struct {
	Index   int               `json:"index"`
	Records []TelemetryRecord `json:"records"`
}

// HashChunk computes the SHA-256 hex hash of a telemetry chunk (canonical JSON).
func (tc *TelemetryChunk) HashChunk() (string, error) {
	raw, err := protocol.CanonicalizeJSON(tc)
	if err != nil {
		return "", err
	}
	h := sha256.Sum256(raw)
	return hex.EncodeToString(h[:]), nil
}

// ComputeHash is an alias for HashChunk.
func (tc *TelemetryChunk) ComputeHash() (string, error) {
	return tc.HashChunk()
}

// ReadTelemetryFile loads telemetry records from a JSONL file.
func ReadTelemetryFile(filePath string) ([]TelemetryRecord, error) {
	f, err := os.Open(filePath)
	if err != nil {
		return nil, fmt.Errorf("failed to open telemetry file %q: %w", filePath, err)
	}
	defer f.Close()

	var records []TelemetryRecord
	scanner := bufio.NewScanner(f)
	lineNum := 0
	for scanner.Scan() {
		lineNum++
		line := scanner.Bytes()
		if len(line) == 0 {
			continue
		}
		var rec TelemetryRecord
		if err := json.Unmarshal(line, &rec); err != nil {
			return nil, fmt.Errorf("failed to parse telemetry line %d: %w", lineNum, err)
		}
		records = append(records, rec)
	}

	if err := scanner.Err(); err != nil {
		return nil, fmt.Errorf("error reading telemetry file: %w", err)
	}

	return records, nil
}

// ReadTelemetryJSONL is an alias for ReadTelemetryFile.
func ReadTelemetryJSONL(filePath string) ([]TelemetryRecord, error) {
	return ReadTelemetryFile(filePath)
}

// ChunkTelemetry splits telemetry records evenly into numChunks.
func ChunkTelemetry(records []TelemetryRecord, numChunks int) []TelemetryChunk {
	if numChunks <= 0 {
		numChunks = 1
	}
	if len(records) == 0 {
		return []TelemetryChunk{{Index: 0, Records: []TelemetryRecord{}}}
	}

	chunks := make([]TelemetryChunk, numChunks)
	total := len(records)
	chunkSize := (total + numChunks - 1) / numChunks

	for i := 0; i < numChunks; i++ {
		start := i * chunkSize
		if start > total {
			start = total
		}
		end := start + chunkSize
		if end > total {
			end = total
		}
		chunks[i] = TelemetryChunk{
			Index:   i,
			Records: records[start:end],
		}
	}

	return chunks
}

// ChunkTelemetryRecords is an alias for ChunkTelemetry.
func ChunkTelemetryRecords(records []TelemetryRecord, numChunks int) []TelemetryChunk {
	return ChunkTelemetry(records, numChunks)
}
