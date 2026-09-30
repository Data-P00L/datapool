package capture

import (
	"encoding/json"
	"os/exec"
	"strings"

	"github.com/dattapool/mvp/pkg/protocol"
)

// QueryBitcoinFreshness attempts to query the local bitcoind regtest stack for the latest block.
func QueryBitcoinFreshness() protocol.FreshnessReference {
	// Try docker exec first
	cmd := exec.Command("docker", "compose", "exec", "-T", "bitcoind", "bitcoin-cli", "-regtest", "-rpcuser=admin", "-rpcpassword=password", "getblockchaininfo")
	out, err := cmd.Output()
	if err != nil {
		// Try direct bitcoin-cli
		cmd2 := exec.Command("bitcoin-cli", "-regtest", "-rpcuser=admin", "-rpcpassword=password", "getblockchaininfo")
		out, err = cmd2.Output()
	}

	if err == nil {
		var info struct {
			Blocks        int64  `json:"blocks"`
			BestBlockHash string `json:"bestblockhash"`
		}
		if err := json.Unmarshal(out, &info); err == nil && info.BestBlockHash != "" {
			return protocol.FreshnessReference{
				Type:        "bitcoin_block",
				BlockHeight: info.Blocks,
				BlockHash:   strings.TrimSpace(info.BestBlockHash),
			}
		}
	}

	// Fallback for offline captures
	return protocol.FreshnessReference{
		Type: "local_only",
	}
}
