package capture

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"os"

	"github.com/dattapool/mvp/pkg/protocol"
)

// ProcessedCaptureData holds the cryptographic outputs of chunking and hashing.
type ProcessedCaptureData struct {
	VideoSHA256          string
	VideoChunkHashes     []string
	TelemetryChunkHashes []string
	VideoMerkleRoot      string
	TelemetryMerkleRoot  string
	CaptureChainRoot     string
	ChunkCount           int
}

// ChunkVideoFile splits a video file into deterministic chunks of chunkSize bytes.
func ChunkVideoFile(videoPath string, chunkSize int64) ([][]byte, string, error) {
	if chunkSize <= 0 {
		chunkSize = protocol.DefaultChunkSize
	}

	f, err := os.Open(videoPath)
	if err != nil {
		return nil, "", fmt.Errorf("failed to open video file %q: %w", videoPath, err)
	}
	defer f.Close()

	stat, err := f.Stat()
	if err != nil {
		return nil, "", fmt.Errorf("failed to stat video file: %w", err)
	}
	fileSize := stat.Size()
	if fileSize == 0 {
		return nil, "", fmt.Errorf("video file is empty: %q", videoPath)
	}

	fullHasher := sha256.New()
	var chunks [][]byte
	buf := make([]byte, chunkSize)

	for {
		n, err := f.Read(buf)
		if n > 0 {
			chunkCopy := make([]byte, n)
			copy(chunkCopy, buf[:n])
			chunks = append(chunks, chunkCopy)
			fullHasher.Write(chunkCopy)
		}
		if err == io.EOF {
			break
		}
		if err != nil {
			return nil, "", fmt.Errorf("error reading video chunk: %w", err)
		}
	}

	videoSHA256 := hex.EncodeToString(fullHasher.Sum(nil))
	return chunks, videoSHA256, nil
}
