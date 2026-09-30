package crypto

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"

	"github.com/dattapool/mvp/pkg/protocol"
)

// ChunkTemporalBinding contains timing and index parameters for a single chunk.
type ChunkTemporalBinding struct {
	Index              int     `json:"index"`
	StartOffsetSec     float64 `json:"start_offset_sec"`
	EndOffsetSec       float64 `json:"end_offset_sec"`
	VideoChunkHash     string  `json:"video_chunk_hash"`
	TelemetryChunkHash string  `json:"telemetry_chunk_hash"`
}

// ContinuousHashChain tracks the rolling hash chain across capture chunks with temporal binding.
type ContinuousHashChain struct {
	SessionID   string
	CurrentHash [32]byte
	ChunkIndex  int
	History     []string // Hex hashes of H0, H1, ... Hn
}

// NewContinuousHashChain initializes a new hash chain using the pre-capture session commitment.
// H0 = SHA256(DOMAIN || session_id || nonce_commitment)
func NewContinuousHashChain(sessionID, nonceCommitmentHex string) (*ContinuousHashChain, error) {
	if sessionID == "" || nonceCommitmentHex == "" {
		return nil, errors.New("empty session id or nonce commitment")
	}

	h := sha256.New()
	h.Write([]byte(protocol.DomainCaptureChunk))
	h.Write([]byte(sessionID))
	h.Write([]byte(nonceCommitmentHex))
	var h0 [32]byte
	copy(h0[:], h.Sum(nil))

	return &ContinuousHashChain{
		SessionID:   sessionID,
		CurrentHash: h0,
		ChunkIndex:  0,
		History:     []string{hex.EncodeToString(h0[:])},
	}, nil
}

// AppendChunkTemporal advances the chain with temporal offsets:
// H_k = SHA256(DOMAIN || H_{k-1} || session_id || index || start_offset || end_offset || video_hash || telemetry_hash)
func (c *ContinuousHashChain) AppendChunkTemporal(chunk ChunkTemporalBinding) error {
	vBytes, err := hex.DecodeString(chunk.VideoChunkHash)
	if err != nil || len(vBytes) != 32 {
		return fmt.Errorf("invalid video chunk hash: %q", chunk.VideoChunkHash)
	}
	tBytes, err := hex.DecodeString(chunk.TelemetryChunkHash)
	if err != nil || len(tBytes) != 32 {
		return fmt.Errorf("invalid telemetry chunk hash: %q", chunk.TelemetryChunkHash)
	}
	if chunk.EndOffsetSec < chunk.StartOffsetSec {
		return fmt.Errorf("invalid non-monotonic chunk time range: start=%f, end=%f", chunk.StartOffsetSec, chunk.EndOffsetSec)
	}

	h := sha256.New()
	h.Write([]byte(protocol.DomainCaptureChunk))
	h.Write(c.CurrentHash[:])
	h.Write([]byte(c.SessionID))
	h.Write([]byte(fmt.Sprintf("%d", chunk.Index)))
	h.Write([]byte(fmt.Sprintf("%.3f", chunk.StartOffsetSec)))
	h.Write([]byte(fmt.Sprintf("%.3f", chunk.EndOffsetSec)))
	h.Write(vBytes)
	h.Write(tBytes)

	var nextHash [32]byte
	copy(nextHash[:], h.Sum(nil))

	c.CurrentHash = nextHash
	c.ChunkIndex++
	c.History = append(c.History, hex.EncodeToString(nextHash[:]))
	return nil
}

// RootHex returns the current chain root (H_n) as a 64-character hex string.
func (c *ContinuousHashChain) RootHex() string {
	return hex.EncodeToString(c.CurrentHash[:])
}

// VerifyContinuousChainTemporal recomputes the entire chain with temporal offsets and checks monotonic ordering.
func VerifyContinuousChainTemporal(sessionID, nonceCommitment string, chunks []ChunkTemporalBinding, expectedRootHex string) (bool, error) {
	if len(chunks) == 0 {
		return false, errors.New("empty chunk list")
	}

	chain, err := NewContinuousHashChain(sessionID, nonceCommitment)
	if err != nil {
		return false, err
	}

	var lastEnd float64
	for i, chk := range chunks {
		if chk.Index != i {
			return false, fmt.Errorf("chunk index mismatch: expected %d, got %d", i, chk.Index)
		}
		if i > 0 && chk.StartOffsetSec < lastEnd-0.001 {
			return false, fmt.Errorf("chunk %d start time %f is before previous end time %f", i, chk.StartOffsetSec, lastEnd)
		}
		lastEnd = chk.EndOffsetSec

		if err := chain.AppendChunkTemporal(chk); err != nil {
			return false, fmt.Errorf("temporal chain step %d failed: %w", i, err)
		}
	}

	return chain.RootHex() == expectedRootHex, nil
}
