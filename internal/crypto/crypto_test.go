package crypto

import (
	"crypto/sha256"
	"encoding/hex"
	"testing"
)

func TestSignAndVerify(t *testing.T) {
	kp, err := GenerateKeyPair()
	if err != nil {
		t.Fatalf("failed to generate keypair: %v", err)
	}

	msg := []byte("DATTA_AUTHENTICATION_CHALLENGE_2026")
	sigHex, err := kp.SignMessage(msg)
	if err != nil {
		t.Fatalf("failed to sign message: %v", err)
	}

	if len(sigHex) != 128 {
		t.Fatalf("expected 128 char hex signature, got %d", len(sigHex))
	}

	// Positive verification
	if !VerifySignature(kp.PublicKeyHex, msg, sigHex) {
		t.Fatal("signature verification failed for valid message and key")
	}

	// Negative: tampered message
	tamperedMsg := []byte("DATTA_AUTHENTICATION_CHALLENGE_CORRUPTED")
	if VerifySignature(kp.PublicKeyHex, tamperedMsg, sigHex) {
		t.Fatal("signature verification passed for tampered message")
	}

	// Negative: wrong key
	otherKp, _ := GenerateKeyPair()
	if VerifySignature(otherKp.PublicKeyHex, msg, sigHex) {
		t.Fatal("signature verification passed for wrong public key")
	}

	// Negative: corrupted signature
	corruptedSig := sigHex[:len(sigHex)-2] + "00"
	if VerifySignature(kp.PublicKeyHex, msg, corruptedSig) {
		t.Fatal("signature verification passed for corrupted signature")
	}
}

func TestMerkleTreeAndProof(t *testing.T) {
	leafData := []string{"chunk0", "chunk1", "chunk2", "chunk3", "chunk4"}
	var leafHashes []string
	for _, d := range leafData {
		h := sha256.Sum256([]byte(d))
		leafHashes = append(leafHashes, hex.EncodeToString(h[:]))
	}

	root, err := ComputeMerkleRoot(leafHashes)
	if err != nil {
		t.Fatalf("failed to compute merkle root: %v", err)
	}

	if len(root) != 64 {
		t.Fatalf("expected 64 char hex root, got %d", len(root))
	}

	// Generate and verify proof for each leaf
	for i, leafHash := range leafHashes {
		proof, err := GenerateMerkleProof(leafHashes, i)
		if err != nil {
			t.Fatalf("failed to generate proof for leaf %d: %v", i, err)
		}

		if !VerifyMerkleProof(root, leafHash, proof) {
			t.Fatalf("merkle proof verification failed for leaf %d", i)
		}

		// Negative: verify with wrong root
		if VerifyMerkleProof("0000000000000000000000000000000000000000000000000000000000000000", leafHash, proof) {
			t.Fatalf("merkle proof passed for invalid root on leaf %d", i)
		}

		// Negative: verify with wrong leaf hash
		wrongH := sha256.Sum256([]byte("wrong"))
		wrongHash := hex.EncodeToString(wrongH[:])
		if VerifyMerkleProof(root, wrongHash, proof) {
			t.Fatalf("merkle proof passed for wrong leaf hash on leaf %d", i)
		}
	}
}

func TestContinuousHashChainTemporal(t *testing.T) {
	sessionID := "session-chain-test-123"
	nonceCommitment := "4aaa6be12ca3c816782b8ea19ecc60c924a3471bedbe323af45d0c5274768a6c"

	chain, err := NewContinuousHashChain(sessionID, nonceCommitment)
	if err != nil {
		t.Fatalf("failed to initialize chain: %v", err)
	}

	hV0 := sha256.Sum256([]byte("v0"))
	hV1 := sha256.Sum256([]byte("v1"))
	hV2 := sha256.Sum256([]byte("v2"))

	hT0 := sha256.Sum256([]byte("t0"))
	hT1 := sha256.Sum256([]byte("t1"))
	hT2 := sha256.Sum256([]byte("t2"))

	bindings := []ChunkTemporalBinding{
		{Index: 0, StartOffsetSec: 0.0, EndOffsetSec: 1.0, VideoChunkHash: hex.EncodeToString(hV0[:]), TelemetryChunkHash: hex.EncodeToString(hT0[:])},
		{Index: 1, StartOffsetSec: 1.0, EndOffsetSec: 2.0, VideoChunkHash: hex.EncodeToString(hV1[:]), TelemetryChunkHash: hex.EncodeToString(hT1[:])},
		{Index: 2, StartOffsetSec: 2.0, EndOffsetSec: 3.0, VideoChunkHash: hex.EncodeToString(hV2[:]), TelemetryChunkHash: hex.EncodeToString(hT2[:])},
	}

	for _, b := range bindings {
		if err := chain.AppendChunkTemporal(b); err != nil {
			t.Fatalf("failed to append chunk %d: %v", b.Index, err)
		}
	}

	root := chain.RootHex()
	if len(root) != 64 {
		t.Fatalf("expected 64 char root hex, got %d", len(root))
	}

	// Verify chain function
	valid, err := VerifyContinuousChainTemporal(sessionID, nonceCommitment, bindings, root)
	if err != nil || !valid {
		t.Fatalf("expected valid chain verification, got valid=%v, err=%v", valid, err)
	}

	// Negative: tampered chunk in sequence
	tamperedBindings := append([]ChunkTemporalBinding{}, bindings...)
	hTampered := sha256.Sum256([]byte("tampered_v1"))
	tamperedBindings[1].VideoChunkHash = hex.EncodeToString(hTampered[:])
	valid, _ = VerifyContinuousChainTemporal(sessionID, nonceCommitment, tamperedBindings, root)
	if valid {
		t.Fatal("chain verification passed for tampered video chunk")
	}

	// Negative: non-monotonic time range
	badTimeBindings := append([]ChunkTemporalBinding{}, bindings...)
	badTimeBindings[1].StartOffsetSec = 0.5 // overlap
	valid, _ = VerifyContinuousChainTemporal(sessionID, nonceCommitment, badTimeBindings, root)
	if valid {
		t.Fatal("chain verification passed for non-monotonic time range")
	}
}
