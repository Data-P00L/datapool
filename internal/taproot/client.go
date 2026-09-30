package taproot

import (
	"bytes"
	"context"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os/exec"
	"strconv"
	"strings"

	"github.com/dattapool/mvp/pkg/protocol"
)

// AssetGenesis represents asset genesis info from tapcli assets list.
type AssetGenesis struct {
	GenesisPoint string `json:"genesis_point"`
	Name         string `json:"name"`
	MetaHash     string `json:"meta_hash"`
	AssetID      string `json:"asset_id"`
	AssetType    string `json:"asset_type"`
	OutputIndex  int    `json:"output_index"`
}

// AssetEntry represents an asset item in tapcli assets list.
type AssetEntry struct {
	Version      string       `json:"version"`
	AssetGenesis AssetGenesis `json:"asset_genesis"`
	Amount       string       `json:"amount"`
	ScriptKey    string       `json:"script_key"`
	LockTime     int64        `json:"lock_time"`
}

// AssetMetaResult represents the response from tapcli assets meta.
type AssetMetaResult struct {
	Data     string `json:"data"`
	Type     string `json:"type"`
	MetaHash string `json:"meta_hash"`
}

// AddrResponse represents a newly created Taproot Assets receive address.
type AddrResponse struct {
	Encoded          string `json:"encoded"`
	AssetID          string `json:"asset_id"`
	AssetType        string `json:"asset_type"`
	Amount           string `json:"amount"`
	GroupKey         string `json:"group_key"`
	ScriptKey        string `json:"script_key"`
	InternalKey      string `json:"internal_key"`
	TapscriptSibling string `json:"tapscript_sibling"`
}

// TapClient provides an interface for interacting with the Taproot Assets daemon.
type TapClient struct {
	Network   string
	UseDocker bool
}

// NewClient initializes a default TapClient configured for regtest.
func NewClient() *TapClient {
	return &TapClient{
		Network:   "regtest",
		UseDocker: true,
	}
}

func (c *TapClient) execCmd(args ...string) (string, error) {
	var cmdArgs []string
	if c.UseDocker {
		cmdArgs = append([]string{"compose", "exec", "-T", "tapd", "tapcli", fmt.Sprintf("--network=%s", c.Network)}, args...)
	} else {
		cmdArgs = append([]string{"tapcli", fmt.Sprintf("--network=%s", c.Network)}, args...)
	}

	cmdName := "docker"
	if !c.UseDocker {
		cmdName = "tapcli"
		cmdArgs = append([]string{fmt.Sprintf("--network=%s", c.Network)}, args...)
	}

	cmd := exec.Command(cmdName, cmdArgs...)
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr

	if err := cmd.Run(); err != nil {
		return "", fmt.Errorf("tapcli command failed: %v: %w\nStderr: %s", cmdArgs, err, strings.TrimSpace(stderr.String()))
	}

	return stdout.String(), nil
}

// MintAsset submits an asset to the pending mint batch.
func (c *TapClient) MintAsset(assetType, name string, supply uint64, metaBytes []byte) (string, error) {
	args := []string{
		"assets", "mint",
		"--type", strings.ToLower(assetType),
		"--name", name,
		"--supply", strconv.FormatUint(supply, 10),
	}
	if len(metaBytes) > 0 {
		args = append(args, "--meta_bytes", string(metaBytes))
	}
	return c.execCmd(args...)
}

// MintDataToken mints the protocol-wide ₿DATA fungible asset (21M supply).
func (c *TapClient) MintDataToken(ctx context.Context, supply uint64, meta []byte) (string, error) {
	_, err := c.MintAsset("NORMAL", "DATA", supply, meta)
	if err != nil {
		return "", err
	}
	return c.FinalizeBatch()
}

// MintClaimAsset mints a unique COLLECTIBLE claim asset with embedded metadata bytes.
func (c *TapClient) MintClaimAsset(ctx context.Context, metaBytes []byte) (string, error) {
	_, err := c.MintAsset("COLLECTIBLE", "CLAIM", 1, metaBytes)
	if err != nil {
		return "", err
	}
	return c.FinalizeBatch()
}

// FinalizeBatch finalizes and broadcasts the pending asset mint batch.
func (c *TapClient) FinalizeBatch() (string, error) {
	return c.execCmd("assets", "mint", "finalize")
}

// ListAssets queries all confirmed assets currently held by the daemon.
func (c *TapClient) ListAssets() ([]AssetEntry, error) {
	out, err := c.execCmd("assets", "list")
	if err != nil {
		return nil, err
	}

	var res struct {
		Assets []AssetEntry `json:"assets"`
	}
	if err := json.Unmarshal([]byte(out), &res); err != nil {
		return nil, fmt.Errorf("failed to parse assets list json: %w\nOutput: %s", err, out)
	}

	return res.Assets, nil
}

// GetAssetMeta retrieves decoded metadata for a given asset ID.
func (c *TapClient) GetAssetMeta(assetID string) (*AssetMetaResult, error) {
	out, err := c.execCmd("assets", "meta", "--asset_id", assetID)
	if err != nil {
		return nil, err
	}

	var res AssetMetaResult
	if err := json.Unmarshal([]byte(out), &res); err != nil {
		return nil, fmt.Errorf("failed to parse asset meta json: %w\nOutput: %s", err, out)
	}

	return &res, nil
}

// FindClaimByManifestHash searches confirmed assets for a CLAIM matching the given manifest hash.
func (c *TapClient) FindClaimByManifestHash(manifestHash string) (*AssetEntry, *protocol.TAPClaimMetadata, error) {
	assets, err := c.ListAssets()
	if err != nil {
		return nil, nil, err
	}

	for _, a := range assets {
		if a.AssetGenesis.Name != "CLAIM" {
			continue
		}

		metaRes, err := c.GetAssetMeta(a.AssetGenesis.AssetID)
		if err != nil || metaRes.Data == "" {
			continue
		}

		rawBytes, err := hex.DecodeString(metaRes.Data)
		if err != nil {
			continue
		}

		meta, err := protocol.DecodeClaimMetadata(rawBytes)
		if err != nil {
			continue
		}

		if meta.ManifestHash == manifestHash {
			return &a, meta, nil
		}
	}

	return nil, nil, fmt.Errorf("no claim asset found with manifest hash %q", manifestHash)
}

// FindClaimByAssetID retrieves and validates a claim asset by its 64-char hex asset ID.
func (c *TapClient) FindClaimByAssetID(assetID string) (*AssetEntry, *protocol.TAPClaimMetadata, error) {
	assets, err := c.ListAssets()
	if err != nil {
		return nil, nil, err
	}

	for _, a := range assets {
		if a.AssetGenesis.AssetID == assetID {
			metaRes, err := c.GetAssetMeta(assetID)
			if err != nil || metaRes.Data == "" {
				return &a, nil, errors.New("asset found but metadata is empty or inaccessible")
			}
			rawBytes, err := hex.DecodeString(metaRes.Data)
			if err != nil {
				return &a, nil, fmt.Errorf("invalid metadata hex: %w", err)
			}
			meta, err := protocol.DecodeClaimMetadata(rawBytes)
			if err != nil {
				return &a, nil, fmt.Errorf("failed to decode claim metadata: %w", err)
			}
			return &a, meta, nil
		}
	}

	return nil, nil, fmt.Errorf("asset ID %q not found in tapd", assetID)
}

// QueryUniverseRoots queries the Universe server for asset roots.
func (c *TapClient) QueryUniverseRoots() (string, error) {
	return c.execCmd("universe", "roots")
}

// QueryUniverseProof queries the Universe server for proof files.
func (c *TapClient) QueryUniverseProof(assetID, scriptKey string) (string, error) {
	return c.execCmd("universe", "proofs", "query", "--asset_id", assetID, "--script_key", scriptKey)
}

// NewAddress creates a new TAP address for receiving the specified asset ID and amount.
func (c *TapClient) NewAddress(assetID string, amount uint64) (*AddrResponse, error) {
	out, err := c.execCmd("addrs", "new", "--asset_id", assetID, "--amt", strconv.FormatUint(amount, 10))
	if err != nil {
		return nil, err
	}

	var res AddrResponse
	if err := json.Unmarshal([]byte(out), &res); err != nil {
		return nil, fmt.Errorf("failed to parse addr json: %w\nOutput: %s", err, out)
	}

	return &res, nil
}

// SendAsset sends assets to a Taproot Assets address.
func (c *TapClient) SendAsset(addr string) (string, error) {
	return c.execCmd("assets", "send", "--addr", addr)
}
