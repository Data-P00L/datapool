package nostr

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
)

// PublishResult contains the outcome of a witness event publication.
type PublishResult struct {
	EventID   string   `json:"event_id"`
	RelayURLs []string `json:"relay_urls"`
	Success   bool     `json:"success"`
	Message   string   `json:"message"`
}

// WitnessPublisher defines the interface for publishing and fetching DattaPool witness events.
type WitnessPublisher interface {
	PublishSessionStart(ctx context.Context, event *Event) (*PublishResult, error)
	FetchEvent(ctx context.Context, eventID string) (*Event, error)
}

// MockWitnessPublisher stores witness events in memory and on local disk for offline testing.
type MockWitnessPublisher struct {
	mu      sync.RWMutex
	events  map[string]*Event
	dataDir string
}

// NewMockWitnessPublisher initializes an in-memory & disk-backed mock witness publisher.
func NewMockWitnessPublisher(dataDir string) *MockWitnessPublisher {
	if dataDir == "" {
		dataDir = "./session_data/nostr_store"
	} else if !strings.HasSuffix(dataDir, "nostr_store") {
		dataDir = filepath.Join(dataDir, "nostr_store")
	}
	_ = os.MkdirAll(dataDir, 0755)
	return &MockWitnessPublisher{
		events:  make(map[string]*Event),
		dataDir: dataDir,
	}
}

// PublishSessionStart validates and stores the session-start witness event.
func (m *MockWitnessPublisher) PublishSessionStart(ctx context.Context, event *Event) (*PublishResult, error) {
	if event == nil {
		return nil, errors.New("cannot publish nil event")
	}
	if !event.Verify() {
		return nil, errors.New("event signature verification failed")
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	m.events[event.ID] = event

	// Persist to disk
	filePath := filepath.Join(m.dataDir, fmt.Sprintf("%s.json", event.ID))
	raw, err := json.MarshalIndent(event, "", "  ")
	if err == nil {
		_ = os.WriteFile(filePath, raw, 0644)
	}

	return &PublishResult{
		EventID:   event.ID,
		RelayURLs: []string{"mock://local.nostr.witness"},
		Success:   true,
		Message:   "Witness event accepted by local witness store",
	}, nil
}

// FetchEvent retrieves a previously published event by ID.
func (m *MockWitnessPublisher) FetchEvent(ctx context.Context, eventID string) (*Event, error) {
	m.mu.RLock()
	evt, exists := m.events[eventID]
	m.mu.RUnlock()

	if exists {
		return evt, nil
	}

	// Try reading from disk
	filePath := filepath.Join(m.dataDir, fmt.Sprintf("%s.json", eventID))
	data, err := os.ReadFile(filePath)
	if err == nil {
		var diskEvt Event
		if err := json.Unmarshal(data, &diskEvt); err == nil && diskEvt.ID == eventID {
			m.mu.Lock()
			m.events[eventID] = &diskEvt
			m.mu.Unlock()
			return &diskEvt, nil
		}
	}

	return nil, fmt.Errorf("witness event %q not found", eventID)
}

// GetDefaultWitnessPublisher selects the appropriate publisher based on environment.
func GetDefaultWitnessPublisher(witnessMode string, dataDir string) WitnessPublisher {
	if strings.ToLower(witnessMode) == "nostr" {
		relayEnv := os.Getenv("DATTA_NOSTR_RELAYS")
		if relayEnv != "" {
			// Configurable relay list
			return NewMockWitnessPublisher(dataDir) // For POC fallback
		}
	}
	return NewMockWitnessPublisher(dataDir)
}
