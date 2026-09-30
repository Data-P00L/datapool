package nostr

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"time"

	"github.com/dattapool/mvp/internal/crypto"
	"github.com/dattapool/mvp/pkg/protocol"
)

// Event represents a standard Nostr event (NIP-01 compliant).
type Event struct {
	ID        string     `json:"id"`
	PubKey    string     `json:"pubkey"`
	CreatedAt int64      `json:"created_at"`
	Kind      int        `json:"kind"`
	Tags      [][]string `json:"tags"`
	Content   string     `json:"content"`
	Sig       string     `json:"sig"`
}

// SerializeEventForID produces the canonical NIP-01 serialized JSON: [0, pubkey, created_at, kind, tags, content]
func (e *Event) SerializeEventForID() ([]byte, error) {
	tags := e.Tags
	if tags == nil {
		tags = [][]string{}
	}
	arr := []interface{}{
		0,
		e.PubKey,
		e.CreatedAt,
		e.Kind,
		tags,
		e.Content,
	}
	return json.Marshal(arr)
}

// ComputeID calculates the 32-byte hex SHA-256 hash of the canonical NIP-01 serialized event.
func (e *Event) ComputeID() (string, error) {
	raw, err := e.SerializeEventForID()
	if err != nil {
		return "", err
	}
	h := sha256.Sum256(raw)
	return hex.EncodeToString(h[:]), nil
}

// Sign signs the event with the given worker Nostr keypair and sets e.ID and e.Sig.
func (e *Event) Sign(keyPair *crypto.KeyPair) error {
	if keyPair == nil {
		return errors.New("cannot sign with nil keypair")
	}
	e.PubKey = keyPair.PublicKeyHex
	if e.CreatedAt <= 0 {
		e.CreatedAt = time.Now().Unix()
	}

	id, err := e.ComputeID()
	if err != nil {
		return fmt.Errorf("failed to compute event ID: %w", err)
	}
	e.ID = id

	sig, err := keyPair.SignMessage([]byte(e.ID))
	if err != nil {
		return fmt.Errorf("failed to sign event ID: %w", err)
	}
	e.Sig = sig
	return nil
}

// Verify verifies the Nostr event ID hash and signature.
func (e *Event) Verify() bool {
	if len(e.ID) != 64 || len(e.PubKey) != 64 || len(e.Sig) != 128 {
		return false
	}

	expectedID, err := e.ComputeID()
	if err != nil || expectedID != e.ID {
		return false
	}

	return crypto.VerifySignature(e.PubKey, []byte(e.ID), e.Sig)
}

// CreateSessionStartWitnessEvent constructs and signs a DattaPool pre-capture witness event.
func CreateSessionStartWitnessEvent(
	workerKey *crypto.KeyPair,
	sessionID, devicePubKey, sessionPubKey, nonceCommitment string,
	freshness protocol.FreshnessReference,
	skillTag string,
	createdAt int64,
) (*Event, error) {
	if createdAt <= 0 {
		createdAt = time.Now().Unix()
	}

	contentObj := map[string]interface{}{
		"protocol":         protocol.ProtocolName,
		"schema_version":   protocol.SchemaVersion,
		"event_type":       "capture_session_start",
		"session_id":       sessionID,
		"device_pubkey":    devicePubKey,
		"session_pubkey":   sessionPubKey,
		"nonce_commitment": nonceCommitment,
		"freshness":        freshness,
		"skill_tag":        skillTag,
	}

	contentJSON, err := protocol.CanonicalizeJSON(contentObj)
	if err != nil {
		return nil, fmt.Errorf("failed to encode event content: %w", err)
	}

	tags := [][]string{
		{"d", sessionID},
		{"protocol", protocol.ProtocolName},
		{"version", protocol.SchemaVersion},
		{"device", devicePubKey},
		{"session", sessionPubKey},
		{"commitment", nonceCommitment},
		{"freshness_type", freshness.Type},
		{"block_height", strconv.FormatInt(freshness.BlockHeight, 10)},
		{"block_hash", freshness.BlockHash},
		{"skill", skillTag},
	}

	evt := &Event{
		Kind:      protocol.NostrKindDattaPoolSessionStart,
		CreatedAt: createdAt,
		Tags:      tags,
		Content:   string(contentJSON),
	}

	if err := evt.Sign(workerKey); err != nil {
		return nil, fmt.Errorf("failed to sign witness event: %w", err)
	}

	return evt, nil
}
