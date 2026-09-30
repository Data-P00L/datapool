package tapclient

import (
	"bytes"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os/exec"
	"strconv"
	"strings"

	"github.com/dattapool/mvp/pkg/metadata"
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
		return "", fmt.Errorf("command %v failed: %w\nStderr: %s", cmdArgs, err, strings.TrimSpace(stderr.String()))
	}

	return stdout.String(), nil
}

// MintAsset submits an asset to the pending mint batch.
func (c *TapClient) MintAsset(assetType, name string, supply uint64, metaBytes []byte) (string, error) {
	args := []string{
		"assets", "mint",
		"--type", assetType,
		"--name", name,
		"--supply", strconv.FormatUint(supply, 10),
	}
	if len(metaBytes) > 0 {
		args = append(args, "--meta_bytes", string(metaBytes))
	}
	return c.execCmd(args...)
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

// FindClaimByVideoHash searches confirmed assets for a CLAIM matching the given video hash.
func (c *TapClient) FindClaimByVideoHash(videoHash string) (*AssetEntry, *metadata.VideoMetadata, error) {
	assets, err := c.ListAssets()
	if err != nil {
		return nil, nil, err
	}

	for _, a := range assets {
		if a.AssetGenesis.Name != "CLAIM" {
			continue
		}

		metaRes, err := c.GetAssetMeta(a.AssetGenesis.AssetID)
		if err != nil {
			continue
		}

		if metaRes.Data == "" {
			continue
		}

		rawBytes, err := hex.DecodeString(metaRes.Data)
		if err != nil {
			continue
		}

		meta, err := metadata.DecodeVideoMetadata(rawBytes)
		if err != nil {
			continue
		}

		if meta.VideoHash == videoHash {
			return &a, meta, nil
		}
	}

	return nil, nil, fmt.Errorf("no claim asset found with video hash %q", videoHash)
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
