package crypto

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
)

// MerkleProof represents an inclusion proof for a chunk at a specific leaf index.
type MerkleProof struct {
	LeafIndex int      `json:"leaf_index"`
	ChunkHash string   `json:"chunk_hash"`
	AuditPath []string `json:"audit_path"` // Sibling hashes from bottom to root
	Sides     []string `json:"sides"`      // "L" or "R" indicating sibling position
}

// ComputeMerkleRoot computes the 32-byte hex Merkle root from a list of leaf chunk hashes (hex strings).
func ComputeMerkleRoot(leafHashes []string) (string, error) {
	if len(leafHashes) == 0 {
		return "", errors.New("empty leaf hashes for merkle root calculation")
	}

	var current [][]byte
	for i, hHex := range leafHashes {
		b, err := hex.DecodeString(hHex)
		if err != nil || len(b) != 32 {
			return "", fmt.Errorf("invalid leaf hash at index %d: %q", i, hHex)
		}
		current = append(current, b)
	}

	for len(current) > 1 {
		var next [][]byte
		for i := 0; i < len(current); i += 2 {
			if i+1 < len(current) {
				combined := append(current[i], current[i+1]...)
				h := sha256.Sum256(combined)
				next = append(next, h[:])
			} else {
				// Duplicate last element if odd
				combined := append(current[i], current[i]...)
				h := sha256.Sum256(combined)
				next = append(next, h[:])
			}
		}
		current = next
	}

	return hex.EncodeToString(current[0]), nil
}

// GenerateMerkleProof constructs an inclusion proof for the leaf at leafIndex.
func GenerateMerkleProof(leafHashes []string, leafIndex int) (*MerkleProof, error) {
	if leafIndex < 0 || leafIndex >= len(leafHashes) {
		return nil, fmt.Errorf("leaf index %d out of bounds (len: %d)", leafIndex, len(leafHashes))
	}

	var current [][]byte
	for i, hHex := range leafHashes {
		b, err := hex.DecodeString(hHex)
		if err != nil || len(b) != 32 {
			return nil, fmt.Errorf("invalid leaf hash at index %d", i)
		}
		current = append(current, b)
	}

	targetIdx := leafIndex
	var auditPath []string
	var sides []string

	for len(current) > 1 {
		var next [][]byte
		for i := 0; i < len(current); i += 2 {
			var left, right []byte
			left = current[i]
			if i+1 < len(current) {
				right = current[i+1]
			} else {
				right = current[i]
			}

			if i == targetIdx || (i+1 == targetIdx && i+1 < len(current)) {
				if targetIdx%2 == 0 {
					// Target is left, sibling is right
					auditPath = append(auditPath, hex.EncodeToString(right))
					sides = append(sides, "R")
				} else {
					// Target is right, sibling is left
					auditPath = append(auditPath, hex.EncodeToString(left))
					sides = append(sides, "L")
				}
			}

			combined := append(left, right...)
			h := sha256.Sum256(combined)
			next = append(next, h[:])
		}
		targetIdx = targetIdx / 2
		current = next
	}

	return &MerkleProof{
		LeafIndex: leafIndex,
		ChunkHash: leafHashes[leafIndex],
		AuditPath: auditPath,
		Sides:     sides,
	}, nil
}

// VerifyMerkleProof verifies whether a chunk hash matches the expected Merkle root using its proof.
func VerifyMerkleProof(expectedRoot string, chunkHash string, proof *MerkleProof) bool {
	if proof == nil || len(proof.AuditPath) != len(proof.Sides) {
		return false
	}

	current, err := hex.DecodeString(chunkHash)
	if err != nil || len(current) != 32 {
		return false
	}

	for i := 0; i < len(proof.AuditPath); i++ {
		sibling, err := hex.DecodeString(proof.AuditPath[i])
		if err != nil || len(sibling) != 32 {
			return false
		}

		var combined []byte
		if proof.Sides[i] == "R" {
			combined = append(current, sibling...)
		} else {
			combined = append(sibling, current...)
		}
		h := sha256.Sum256(combined)
		current = h[:]
	}

	return hex.EncodeToString(current) == expectedRoot
}
