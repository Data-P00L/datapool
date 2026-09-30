package protocol

import (
	"errors"
	"fmt"
	"strings"
)

const (
	PublicationType            = "media_publication"
	DatasetAvailabilityType    = "dataset_availability"
	PublicationPlatformYouTube = "youtube"
	DattaPoolBlockStartMarker  = "--- DATTAPOOL ---"
	DattaPoolBlockEndMarker    = "--- END DATTAPOOL ---"
)

// MediaInfo represents external media platform identifiers.
type MediaInfo struct {
	Platform   string `json:"platform"`
	VideoID    string `json:"video_id"`
	Visibility string `json:"visibility"`
}

// MediaPublication represents the association between a DattaPool capture and a media platform publication.
type MediaPublication struct {
	Protocol                 string `json:"protocol"`
	Type                     string `json:"type"`
	SchemaVersion            string `json:"schema_version"`
	SessionID                string `json:"session_id"`
	ManifestHash             string `json:"manifest_hash"`
	CaptureClaimAssetID      string `json:"capture_claim_asset_id,omitempty"`
	Platform                 string `json:"platform"`
	VideoID                  string `json:"video_id"`
	RequestedVisibility      string `json:"requested_visibility"`
	ActualVisibility         string `json:"actual_visibility"`
	WatchURL                 string `json:"watch_url,omitempty"`
	SourceVideoSHA256        string `json:"source_video_sha256"`
	PublishedByNostrPubKey   string `json:"published_by_nostr_pubkey"`
	PublishedAt              int64  `json:"published_at"`
	SourceBundleURI          string `json:"source_bundle_uri,omitempty"`
	NostrAvailabilityEventID string `json:"nostr_availability_event_id,omitempty"`
}

// DatasetAvailabilityPayload is the canonical Nostr event content announcing dataset availability.
type DatasetAvailabilityPayload struct {
	Protocol            string    `json:"protocol"`
	Type                string    `json:"type"`
	SchemaVersion       string    `json:"schema_version"`
	SessionID           string    `json:"session_id"`
	ManifestHash        string    `json:"manifest_hash"`
	Media               MediaInfo `json:"media"`
	CaptureClaimAssetID string    `json:"capture_claim_asset_id,omitempty"`
	SourceBundleURI     string    `json:"source_bundle_uri,omitempty"`
}

// DattaPoolDescriptionMetadata contains parsed key-value pairs from the YouTube description block.
type DattaPoolDescriptionMetadata struct {
	Protocol          string `json:"protocol"`
	Schema            string `json:"schema"`
	Type              string `json:"type"`
	SessionID         string `json:"session_id"`
	WorkerNostrPubKey string `json:"worker_nostr_pubkey"`
	ManifestHash      string `json:"manifest_hash"`
	CaptureChainRoot  string `json:"capture_chain_root"`
	TAPClaimAssetID   string `json:"tap_claim_asset_id,omitempty"`
	ProvenanceLevel   string `json:"provenance_level"`
	SkillTag          string `json:"skill_tag"`
}

// BuildYouTubeDescription generates deterministic YouTube description with human header and DattaPool block.
func BuildYouTubeDescription(m *CaptureManifest, tapClaimAssetID string) (string, error) {
	if m == nil {
		return "", errors.New("manifest cannot be nil")
	}

	manifestHash, err := m.ComputeManifestHash()
	if err != nil {
		return "", fmt.Errorf("failed to compute manifest hash: %w", err)
	}

	skill := m.Task.SkillTag
	if skill == "" {
		skill = DefaultSkillTag
	}
	level := string(m.Provenance.Level)
	if level == "" {
		level = string(LevelP3)
	}

	var sb strings.Builder
	sb.WriteString("DattaPool Robotics Capture\n\n")
	sb.WriteString(fmt.Sprintf("Skill: %s\n", skill))
	sb.WriteString(fmt.Sprintf("Provenance: %s\n", level))
	sb.WriteString(fmt.Sprintf("Session: %s\n\n", m.Session.ID))

	sb.WriteString(DattaPoolBlockStartMarker + "\n")
	sb.WriteString("protocol=dattapool\n")
	sb.WriteString("schema=0.4.0\n")
	sb.WriteString("type=capture_publication\n")
	sb.WriteString(fmt.Sprintf("session_id=%s\n", m.Session.ID))
	sb.WriteString(fmt.Sprintf("worker_nostr_pubkey=%s\n", m.Worker.NostrPubKey))
	sb.WriteString(fmt.Sprintf("manifest_hash=%s\n", manifestHash))
	sb.WriteString(fmt.Sprintf("capture_chain_root=%s\n", m.Capture.CaptureChainRoot))
	if tapClaimAssetID != "" {
		sb.WriteString(fmt.Sprintf("tap_claim_asset_id=%s\n", tapClaimAssetID))
	}
	sb.WriteString(fmt.Sprintf("provenance_level=%s\n", level))
	sb.WriteString(fmt.Sprintf("skill_tag=%s\n", skill))
	sb.WriteString(DattaPoolBlockEndMarker)

	return sb.String(), nil
}

// ParseYouTubeDescription extracts and validates the DattaPool metadata block from a YouTube video description.
func ParseYouTubeDescription(description string) (*DattaPoolDescriptionMetadata, error) {
	startIdx := strings.Index(description, DattaPoolBlockStartMarker)
	if startIdx == -1 {
		return nil, errors.New("missing DattaPool metadata start marker")
	}
	endIdx := strings.Index(description, DattaPoolBlockEndMarker)
	if endIdx == -1 {
		return nil, errors.New("missing DattaPool metadata end marker")
	}
	if startIdx >= endIdx {
		return nil, errors.New("invalid marker ordering in description")
	}

	block := description[startIdx+len(DattaPoolBlockStartMarker) : endIdx]
	lines := strings.Split(block, "\n")
	kv := make(map[string]string)

	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if trimmed == "" || strings.HasPrefix(trimmed, "#") {
			continue
		}
		parts := strings.SplitN(trimmed, "=", 2)
		if len(parts) == 2 {
			kv[strings.TrimSpace(parts[0])] = strings.TrimSpace(parts[1])
		}
	}

	sessionID := kv["session_id"]
	if sessionID == "" {
		return nil, errors.New("missing session_id in DattaPool metadata block")
	}
	manifestHash := kv["manifest_hash"]
	if manifestHash == "" {
		return nil, errors.New("missing manifest_hash in DattaPool metadata block")
	}
	workerPubKey := kv["worker_nostr_pubkey"]
	if workerPubKey == "" {
		return nil, errors.New("missing worker_nostr_pubkey in DattaPool metadata block")
	}
	chainRoot := kv["capture_chain_root"]
	if chainRoot == "" {
		return nil, errors.New("missing capture_chain_root in DattaPool metadata block")
	}

	return &DattaPoolDescriptionMetadata{
		Protocol:          kv["protocol"],
		Schema:            kv["schema"],
		Type:              kv["type"],
		SessionID:         sessionID,
		WorkerNostrPubKey: workerPubKey,
		ManifestHash:      manifestHash,
		CaptureChainRoot:  chainRoot,
		TAPClaimAssetID:   kv["tap_claim_asset_id"],
		ProvenanceLevel:   kv["provenance_level"],
		SkillTag:          kv["skill_tag"],
	}, nil
}
