package nostr

import (
	"context"
	"testing"
	"time"

	"github.com/dattapool/mvp/internal/crypto"
	"github.com/dattapool/mvp/pkg/protocol"
)

func TestNostrEventSignVerify(t *testing.T) {
	workerKey, err := crypto.GenerateKeyPair()
	if err != nil {
		t.Fatalf("failed to generate worker key: %v", err)
	}

	freshness := protocol.FreshnessReference{
		Type:        "bitcoin_block",
		BlockHeight: 155,
		BlockHash:   "0000000000000000000000000000000000000000000000000000000000000001",
	}

	evt, err := CreateSessionStartWitnessEvent(
		workerKey,
		"session-12345",
		"ce21b7e060466b5bd9ccc3d09aa4c730894c8bf179c0cb8aa4d0f7bbf9ffbea0",
		"d725f42a1b830f2f61e62f350137b9c2d28391d691d2cda06ca3016f2d0de2e8",
		"4aaa6be12ca3c816782b8ea19ecc60c924a3471bedbe323af45d0c5274768a6c",
		freshness,
		"warehouse_pick",
		time.Now().Unix(),
	)
	if err != nil {
		t.Fatalf("failed to create witness event: %v", err)
	}

	if !evt.Verify() {
		t.Fatal("valid Nostr event verification failed")
	}

	// Negative: mutated content
	evtMutated := *evt
	evtMutated.Content += " "
	if evtMutated.Verify() {
		t.Fatal("mutated Nostr event should fail verification")
	}

	// Negative: wrong pubkey
	otherKey, _ := crypto.GenerateKeyPair()
	evtWrongPub := *evt
	evtWrongPub.PubKey = otherKey.PublicKeyHex
	if evtWrongPub.Verify() {
		t.Fatal("wrong pubkey Nostr event should fail verification")
	}

	// Negative: corrupted signature
	evtCorruptSig := *evt
	evtCorruptSig.Sig = evt.Sig[:len(evt.Sig)-2] + "00"
	if evtCorruptSig.Verify() {
		t.Fatal("corrupted signature Nostr event should fail verification")
	}
}

func TestMockWitnessPublisher(t *testing.T) {
	workerKey, _ := crypto.GenerateKeyPair()
	freshness := protocol.FreshnessReference{Type: "local_only"}
	evt, err := CreateSessionStartWitnessEvent(workerKey, "session-test", "dev", "sess", "commit", freshness, "pick", time.Now().Unix())
	if err != nil {
		t.Fatalf("failed to create event: %v", err)
	}

	publisher := NewMockWitnessPublisher(t.TempDir())
	res, err := publisher.PublishSessionStart(context.Background(), evt)
	if err != nil || !res.Success {
		t.Fatalf("failed to publish witness event: %v, res: %+v", err, res)
	}

	fetched, err := publisher.FetchEvent(context.Background(), evt.ID)
	if err != nil || fetched == nil || fetched.ID != evt.ID {
		t.Fatalf("failed to fetch published event: %v", err)
	}
}
