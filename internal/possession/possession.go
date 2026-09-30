package possession

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"math/big"
	"os"
	"sort"
	"time"

	"github.com/dattapool/mvp/internal/crypto"
	"github.com/dattapool/mvp/pkg/protocol"
)

// PossessionChallenge contains the parameters for an interactive proof-of-possession challenge.
type PossessionChallenge struct {
	SessionID        string `json:"session_id"`
	ChallengeNonce   string `json:"challenge_nonce"`
	RequestedIndexes []int  `json:"requested_indexes"`
	CreatedAt        int64  `json:"created_at"`
}

// ChunkProof contains the Merkle audit path proving inclusion of a requested chunk.
type ChunkProof struct {
	ChunkIndex int                 `json:"chunk_index"`
	ChunkHash  string              `json:"chunk_hash"`
	Proof      *crypto.MerkleProof `json:"proof"`
}

// PossessionResponse bundles the claimant's Merkle audit proofs bound to the challenge nonce.
type PossessionResponse struct {
	SessionID      string       `json:"session_id"`
	ChallengeNonce string       `json:"challenge_nonce"`
	Proofs         []ChunkProof `json:"proofs"`
	CreatedAt      int64        `json:"created_at"`
}

// GenerateChallenge selects sampleCount unique random chunk indexes from totalChunks.
func GenerateChallenge(sessionID string, totalChunks int, sampleCount int) (*PossessionChallenge, error) {
	if sessionID == "" {
		return nil, errors.New("empty session id")
	}
	if totalChunks <= 0 {
		return nil, fmt.Errorf("invalid total chunks: %d", totalChunks)
	}
	if sampleCount <= 0 {
		sampleCount = 5
	}
	if sampleCount > totalChunks {
		sampleCount = totalChunks
	}

	nonceBytes := make([]byte, 32)
	if _, err := rand.Read(nonceBytes); err != nil {
		return nil, fmt.Errorf("failed to generate random challenge nonce: %w", err)
	}

	indexMap := make(map[int]bool)
	for len(indexMap) < sampleCount {
		n, err := rand.Int(rand.Reader, big.NewInt(int64(totalChunks)))
		if err != nil {
			return nil, err
		}
		indexMap[int(n.Int64())] = true
	}

	var requested []int
	for idx := range indexMap {
		requested = append(requested, idx)
	}
	sort.Ints(requested)

	return &PossessionChallenge{
		SessionID:        sessionID,
		ChallengeNonce:   hex.EncodeToString(nonceBytes),
		RequestedIndexes: requested,
		CreatedAt:        time.Now().Unix(),
	}, nil
}

// GenerateResponse generates the Merkle inclusion proofs for the requested chunk indexes.
func GenerateResponse(challenge *PossessionChallenge, videoChunkHashes []string) (*PossessionResponse, error) {
	if challenge == nil {
		return nil, errors.New("cannot generate response for nil challenge")
	}
	if len(videoChunkHashes) == 0 {
		return nil, errors.New("empty video chunk hashes")
	}

	var proofs []ChunkProof
	for _, idx := range challenge.RequestedIndexes {
		if idx < 0 || idx >= len(videoChunkHashes) {
			return nil, fmt.Errorf("requested index %d is out of bounds (total %d)", idx, len(videoChunkHashes))
		}
		proofPath, err := crypto.GenerateMerkleProof(videoChunkHashes, idx)
		if err != nil {
			return nil, fmt.Errorf("failed to generate proof for index %d: %w", idx, err)
		}
		proofs = append(proofs, ChunkProof{
			ChunkIndex: idx,
			ChunkHash:  videoChunkHashes[idx],
			Proof:      proofPath,
		})
	}

	return &PossessionResponse{
		SessionID:      challenge.SessionID,
		ChallengeNonce: challenge.ChallengeNonce,
		Proofs:         proofs,
		CreatedAt:      time.Now().Unix(),
	}, nil
}

// VerifyResponse independently validates that all proofs belong to the expected Merkle root and challenge.
func VerifyResponse(challenge *PossessionChallenge, response *PossessionResponse, expectedVideoMerkleRoot string) (bool, error) {
	if challenge == nil || response == nil {
		return false, errors.New("nil challenge or response")
	}
	if challenge.SessionID != response.SessionID {
		return false, fmt.Errorf("session ID mismatch: challenge=%q, response=%q", challenge.SessionID, response.SessionID)
	}
	if challenge.ChallengeNonce != response.ChallengeNonce {
		return false, fmt.Errorf("challenge nonce mismatch: challenge=%q, response=%q", challenge.ChallengeNonce, response.ChallengeNonce)
	}
	if len(challenge.RequestedIndexes) != len(response.Proofs) {
		return false, fmt.Errorf("proof count mismatch: requested %d, got %d", len(challenge.RequestedIndexes), len(response.Proofs))
	}

	for i, idx := range challenge.RequestedIndexes {
		proof := response.Proofs[i]
		if proof.ChunkIndex != idx {
			return false, fmt.Errorf("proof index mismatch at position %d: expected %d, got %d", i, idx, proof.ChunkIndex)
		}
		if !crypto.VerifyMerkleProof(expectedVideoMerkleRoot, proof.ChunkHash, proof.Proof) {
			return false, fmt.Errorf("invalid Merkle audit path for chunk index %d", idx)
		}
	}

	return true, nil
}

// SaveChallenge saves a challenge to a JSON file.
func SaveChallenge(c *PossessionChallenge, path string) error {
	raw, err := protocol.CanonicalJSONIndent(c)
	if err != nil {
		return err
	}
	return os.WriteFile(path, raw, 0644)
}

// SaveResponse saves a response to a JSON file.
func SaveResponse(r *PossessionResponse, path string) error {
	raw, err := protocol.CanonicalJSONIndent(r)
	if err != nil {
		return err
	}
	return os.WriteFile(path, raw, 0644)
}
