package possession

import (
	"crypto/sha256"
	"encoding/hex"
	"testing"

	"github.com/dattapool/mvp/internal/crypto"
)

func TestPossessionChallengeResponseVerification(t *testing.T) {
	// Generate mock chunk hashes
	var chunkHashes []string
	for i := 0; i < 20; i++ {
		h := sha256.Sum256([]byte(string(rune('A' + i))))
		chunkHashes = append(chunkHashes, hex.EncodeToString(h[:]))
	}

	merkleRoot, err := crypto.ComputeMerkleRoot(chunkHashes)
	if err != nil {
		t.Fatalf("failed to compute merkle root: %v", err)
	}

	sessionID := "session-possession-test"

	// 1. Generate challenge
	challenge, err := GenerateChallenge(sessionID, len(chunkHashes), 5)
	if err != nil {
		t.Fatalf("failed to generate challenge: %v", err)
	}

	if len(challenge.RequestedIndexes) != 5 {
		t.Fatalf("expected 5 requested indexes, got %d", len(challenge.RequestedIndexes))
	}

	// 2. Generate response
	response, err := GenerateResponse(challenge, chunkHashes)
	if err != nil {
		t.Fatalf("failed to generate response: %v", err)
	}

	// 3. Positive verification
	valid, err := VerifyResponse(challenge, response, merkleRoot)
	if err != nil || !valid {
		t.Fatalf("expected valid response verification, got valid=%v, err=%v", valid, err)
	}

	// 4. Negative: wrong Merkle root
	valid, _ = VerifyResponse(challenge, response, "0000000000000000000000000000000000000000000000000000000000000000")
	if valid {
		t.Fatal("verification should fail for wrong merkle root")
	}

	// 5. Negative: replayed response with different challenge nonce
	tamperedChallenge := *challenge
	tamperedChallenge.ChallengeNonce = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
	valid, _ = VerifyResponse(&tamperedChallenge, response, merkleRoot)
	if valid {
		t.Fatal("verification should fail for replayed challenge nonce")
	}

	// 6. Negative: corrupted chunk in response
	tamperedResponse := *response
	tamperedResponse.Proofs[0].ChunkHash = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
	valid, _ = VerifyResponse(challenge, &tamperedResponse, merkleRoot)
	if valid {
		t.Fatal("verification should fail for corrupted chunk hash")
	}
}
