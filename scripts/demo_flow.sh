#!/bin/bash
set -e

echo "=================================================="
echo "    DattaPool Protocol End-to-End Demo"
echo "    Temporal Provenance & Contribution Accounting"
echo "=================================================="

# 1. Bootstrap Local Infrastructure
echo ""
echo "--- Step 1: Bootstrapping Docker Infrastructure ---"
./scripts/bootstrap.sh

sleep 3

# 2. Build Go Binaries
echo ""
echo "--- Step 2: Compiling DattaPool Binaries (v0.4.0) ---"
rm -rf session_data keys
mkdir -p bin keys session_data
docker run --rm -v "$(pwd):/app" -w /app golang:1.22 sh -c "\
  go build -o bin/dattapool cmd/dattapool/main.go && \
  go build -o bin/contribution cmd/contribution/main.go && \
  go build -o bin/mint_data_token cmd/mint_data_token/main.go && \
  go build -o bin/capture cmd/capture/main.go && \
  go build -o bin/publish_claim cmd/publish_claim/main.go && \
  go build -o bin/verify_claim cmd/verify_claim/main.go"

MINING_ADDR=$(docker compose exec -T bitcoind bitcoin-cli -regtest -rpcuser=admin -rpcpassword=password getnewaddress)
echo "Mining address: $MINING_ADDR"

# 3. Identity Generation
echo ""
echo "--- Step 3: Generating Worker Nostr & Device Keypairs ---"
./bin/capture keygen --out keys/worker.key
./bin/capture keygen --out keys/device.key

# 4. Authenticated Capture Session (With Nostr Witness & Bitcoin Freshness -> P4)
echo ""
echo "--- Step 4: Executing Witnessed Pre-Capture & Ingestion (Target: P4) ---"
./bin/capture quick \
  --worker-key keys/worker.key \
  --device-key keys/device.key \
  --video fixtures/sample-video.mp4 \
  --telemetry fixtures/sample-telemetry.jsonl \
  --skill warehouse_object_pick \
  --witness nostr \
  --freshness bitcoin \
  --out-dir session_data

# 5. Inspect Capture Manifest
echo ""
echo "--- Step 5: Inspecting Machine-Generated Capture Manifest (0.4.0) ---"
./bin/capture inspect session_data/capture-manifest.json

# 6. Publish TAP Claim Asset
echo ""
echo "--- Step 6: Publishing TAP Capture Claim Asset (0.4.0) ---"
./bin/publish_claim --manifest session_data/capture-manifest.json

echo "Mining 1 block to confirm Claim Asset on Bitcoin regtest..."
docker compose exec -T bitcoind bitcoin-cli -regtest -rpcuser=admin -rpcpassword=password generatetoaddress 1 "$MINING_ADDR" > /dev/null
sleep 3

CLAIM_ASSET_ID=$(docker compose exec -T tapd tapcli --network=regtest assets list | python3 -c "import sys, json; data=json.load(sys.stdin); print(next((a['asset_genesis']['asset_id'] for a in data.get('assets', []) if a.get('asset_genesis', {}).get('name') == 'CLAIM'), ''))")
echo "Confirmed CLAIM Asset ID: $CLAIM_ASSET_ID"

# 7. Query Local Universe Proof Discovery
echo ""
echo "--- Step 7: Querying Local Taproot Assets Universe Roots ---"
docker compose exec -T tapd tapcli --network=regtest universe roots

# 8. Independent Multi-Layer Claim Verification (Evaluating P4)
echo ""
echo "--- Step 8: Independent Cryptographic Verification (Target: P4) ---"
./bin/verify_claim \
  --manifest session_data/capture-manifest.json \
  --video fixtures/sample-video.mp4 \
  --telemetry fixtures/sample-telemetry.jsonl \
  --asset_id "$CLAIM_ASSET_ID" \
  --check_tapd \
  --require_level P4

# 9. Contribution Calculation (Objective Duration-Based Base Unit)
echo ""
echo "--- Step 9: Calculating Qualifying ₿DATA (DTTA) Contribution ---"
./bin/dattapool contribution calculate --manifest session_data/capture-manifest.json

# 10. Canonical Contribution Issuance & Market Valuation Separation
echo ""
echo "--- Step 10: Issuing Canonical Contribution Record & Example Valuation ---"
./bin/dattapool contribution issue \
  --manifest session_data/capture-manifest.json \
  --claim-id "$CLAIM_ASSET_ID" \
  --example-sats-rate 75 \
  --out session_data/contribution_issuance.json

# 11. Interactive Proof-of-Possession Challenge
echo ""
echo "--- Step 11: Interactive Proof-of-Possession Challenge ---"
./bin/capture possession-challenge --manifest session_data/capture-manifest.json --count 5 --out session_data/challenge.json

# Extract video chunk hashes
python3 -c "
import json, hashlib
video_bytes = open('fixtures/sample-video.mp4', 'rb').read()
chunk_size = 64 * 1024
chunks = [video_bytes[i:i+chunk_size] for i in range(0, len(video_bytes), chunk_size)]
chunk_hashes = [hashlib.sha256(c).hexdigest() for c in chunks]

def compute_merkle_tree(leaves):
    tree = [leaves]
    curr = leaves
    while len(curr) > 1:
        nxt = []
        for i in range(0, len(curr), 2):
            left = curr[i]
            right = curr[i+1] if i+1 < len(curr) else curr[i]
            combined = bytes.fromhex(left) + bytes.fromhex(right)
            nxt.append(hashlib.sha256(combined).hexdigest())
        tree.append(nxt)
        curr = nxt
    return tree

tree = compute_merkle_tree(chunk_hashes)
ch = json.load(open('session_data/challenge.json'))
proofs = []
for idx in ch['requested_indexes']:
    audit = []
    sides = []
    curr_idx = idx
    for level in range(len(tree) - 1):
        is_right = curr_idx % 2 == 1
        sib_idx = curr_idx - 1 if is_right else curr_idx + 1
        if sib_idx >= len(tree[level]):
            sib_idx = curr_idx
        audit.append(tree[level][sib_idx])
        sides.append('L' if is_right else 'R')
        curr_idx //= 2
    proofs.append({
        'chunk_index': idx,
        'chunk_hash': chunk_hashes[idx],
        'proof': {
            'leaf_index': idx,
            'chunk_hash': chunk_hashes[idx],
            'audit_path': audit,
            'sides': sides
        }
    })

resp = {
    'session_id': ch['session_id'],
    'challenge_nonce': ch['challenge_nonce'],
    'created_at': 1786990000,
    'proofs': proofs
}
json.dump(resp, open('session_data/response.json', 'w'), indent=2)
"

./bin/capture possession-verify \
  --challenge session_data/challenge.json \
  --response session_data/response.json \
  --manifest session_data/capture-manifest.json

# 12. Demonstrate Offline Capture Gracefully Produces P3 and Qualifies for ₿DATA Calculation
echo ""
echo "--- Step 12: Demonstrating Offline Capture (P3) Contribution Qualification ---"
mkdir -p session_data/offline_demo
./bin/capture quick \
  --worker-key keys/worker.key \
  --device-key keys/device.key \
  --video fixtures/sample-video.mp4 \
  --telemetry fixtures/sample-telemetry.jsonl \
  --witness offline \
  --freshness local \
  --out-dir session_data/offline_demo

./bin/verify_claim \
  --manifest session_data/offline_demo/capture-manifest.json \
  --video fixtures/sample-video.mp4 \
  --telemetry fixtures/sample-telemetry.jsonl

./bin/dattapool contribution calculate --manifest session_data/offline_demo/capture-manifest.json

echo ""
echo "=================================================="
echo "    DattaPool Protocol Verified (P4 & P3)!"
echo "    ₿DATA Contribution Accounting Complete!"
echo "=================================================="
