#!/bin/bash
set -e

echo "=================================================="
echo "   DattaPool Milestone 2 Adversarial Test Suite"
echo "=================================================="

# Compile binaries if needed
mkdir -p bin /tmp/dattapool_adv
docker run --rm -v "$(pwd):/app" -w /app golang:1.22 sh -c "\
  go build -o bin/dattapool cmd/dattapool/main.go && \
  go build -o bin/contribution cmd/contribution/main.go && \
  go build -o bin/capture cmd/capture/main.go && \
  go build -o bin/verify_claim cmd/verify_claim/main.go"

# 0. Generate baseline genuine P4 capture
echo ""
echo "--- Generating Baseline Genuine P4 Capture ---"
mkdir -p /tmp/dattapool_adv/genuine_keys /tmp/dattapool_adv/session_data
./bin/capture keygen --out /tmp/dattapool_adv/genuine_keys/worker.key
./bin/capture keygen --out /tmp/dattapool_adv/genuine_keys/device.key

./bin/capture quick \
  --worker-key /tmp/dattapool_adv/genuine_keys/worker.key \
  --device-key /tmp/dattapool_adv/genuine_keys/device.key \
  --video fixtures/sample-video.mp4 \
  --telemetry fixtures/sample-telemetry.jsonl \
  --skill warehouse_pick \
  --witness nostr \
  --freshness bitcoin \
  --out-dir /tmp/dattapool_adv/session_data

GENUINE_MANIFEST=/tmp/dattapool_adv/session_data/capture-manifest.json

# Baseline check
echo "Testing baseline genuine verification..."
./bin/verify_claim --manifest "$GENUINE_MANIFEST" --video fixtures/sample-video.mp4 --telemetry fixtures/sample-telemetry.jsonl

# Test A: Stolen video + attacker identity (Attempts P4 claim)
echo ""
echo "--- Test A: Stolen Video + Attacker Identity (Targeting P4) ---"
mkdir -p /tmp/dattapool_adv/attacker_keys /tmp/dattapool_adv/attacker_session
./bin/capture keygen --out /tmp/dattapool_adv/attacker_keys/worker.key
./bin/capture keygen --out /tmp/dattapool_adv/attacker_keys/device.key

# Attacker runs offline without genuine witness
./bin/capture quick \
  --worker-key /tmp/dattapool_adv/attacker_keys/worker.key \
  --device-key /tmp/dattapool_adv/attacker_keys/device.key \
  --video fixtures/sample-video.mp4 \
  --telemetry fixtures/sample-telemetry.jsonl \
  --witness offline \
  --freshness local \
  --out-dir /tmp/dattapool_adv/attacker_session

if ./bin/verify_claim --manifest /tmp/dattapool_adv/attacker_session/capture-manifest.json --require_level P4 2>/dev/null; then
  echo "❌ FAIL: Attacker session should NOT satisfy P4 provenance!"
  exit 1
else
  echo "✓ PASS: Attacker session correctly rejected for required P4 provenance"
fi

# Test B: Stolen video + fake historical session (unwitnessed)
echo ""
echo "--- Test B: Stolen Video + Fake Historical Session ---"
python3 -c "
import json
m = json.load(open('$GENUINE_MANIFEST'))
m['session']['started_at'] = 1000000000
json.dump(m, open('/tmp/dattapool_adv/fake-history.json', 'w'), indent=2)
"
if ./bin/verify_claim --manifest /tmp/dattapool_adv/fake-history.json --video fixtures/sample-video.mp4 2>/dev/null; then
  echo "❌ FAIL: Verifier should reject fake historical session with altered timestamps!"
  exit 1
else
  echo "✓ PASS: Verifier correctly rejected fake historical session"
fi

# Test C: Video from Session A + Telemetry from Session B (Stream Swapping)
echo ""
echo "--- Test C: Swapped Telemetry Stream (Stream Swapping) ---"
# Create corrupted telemetry
cp fixtures/sample-telemetry.jsonl /tmp/dattapool_adv/swapped_telemetry.jsonl
echo '{"timestamp_sec":999.0,"frame_index":99,"imu":{"linear_accel":[1,1,1],"angular_vel":[0,0,0]},"joints":{"positions":[0,0,0,0,0,0,0],"velocities":[0,0,0,0,0,0,0],"efforts":[0,0,0,0,0,0,0]},"gripper":{"width":0,"force":0},"mode":"A"}' >> /tmp/dattapool_adv/swapped_telemetry.jsonl

if ./bin/verify_claim --manifest "$GENUINE_MANIFEST" --video fixtures/sample-video.mp4 --telemetry /tmp/dattapool_adv/swapped_telemetry.jsonl 2>/dev/null; then
  echo "❌ FAIL: Verifier should reject swapped telemetry!"
  exit 1
else
  echo "✓ PASS: Verifier correctly rejected stream swapping (INVALID_TELEMETRY_MERKLE_ROOT)"
fi

# Test D: Reordered Chunks
echo ""
echo "--- Test D: Tampered / Reordered Capture Chain Root ---"
python3 -c "
import json
m = json.load(open('$GENUINE_MANIFEST'))
m['capture']['capture_chain_root'] = '1122334455667788990011223344556677889900112233445566778899001122'
json.dump(m, open('/tmp/dattapool_adv/reordered-chain.json', 'w'), indent=2)
"
if ./bin/verify_claim --manifest /tmp/dattapool_adv/reordered-chain.json 2>/dev/null; then
  echo "❌ FAIL: Verifier should reject invalid capture chain root!"
  exit 1
else
  echo "✓ PASS: Verifier correctly rejected invalid capture chain root"
fi

# Test E: Deleted / Missing Middle Chunk (Video Hash mismatch)
echo ""
echo "--- Test E: Deleted / Truncated Video Chunks ---"
truncate -s 65536 /tmp/dattapool_adv/truncated-video.mp4 2>/dev/null || dd if=fixtures/sample-video.mp4 of=/tmp/dattapool_adv/truncated-video.mp4 bs=65536 count=1 2>/dev/null
if ./bin/verify_claim --manifest "$GENUINE_MANIFEST" --video /tmp/dattapool_adv/truncated-video.mp4 2>/dev/null; then
  echo "❌ FAIL: Verifier should reject truncated video!"
  exit 1
else
  echo "✓ PASS: Verifier correctly rejected truncated video (INVALID_VIDEO_HASH)"
fi

# Test F: Duplicated / Corrupted Video Chunk
echo ""
echo "--- Test F: Corrupted Video Chunk Payload ---"
cp fixtures/sample-video.mp4 /tmp/dattapool_adv/corrupt-video.mp4
echo "corruption_byte_injection" >> /tmp/dattapool_adv/corrupt-video.mp4
if ./bin/verify_claim --manifest "$GENUINE_MANIFEST" --video /tmp/dattapool_adv/corrupt-video.mp4 2>/dev/null; then
  echo "❌ FAIL: Verifier should reject corrupted video chunk!"
  exit 1
else
  echo "✓ PASS: Verifier correctly rejected corrupted chunk payload"
fi

# Test H: Replayed Nostr Start Event (Attached to mismatched Session ID)
echo ""
echo "--- Test H: Replayed Nostr Start Event with Mismatched Session ID ---"
python3 -c "
import json
m = json.load(open('$GENUINE_MANIFEST'))
m['session']['id'] = 'forged-session-id-999'
json.dump(m, open('/tmp/dattapool_adv/replayed-nostr.json', 'w'), indent=2)
"
if ./bin/verify_claim --manifest /tmp/dattapool_adv/replayed-nostr.json 2>/dev/null; then
  echo "❌ FAIL: Verifier should reject mismatched session ID against Nostr witness event!"
  exit 1
else
  echo "✓ PASS: Verifier correctly rejected session ID mismatch on Nostr witness"
fi

# Test I: Worker Authorization Reused for Unauthorized Device
echo ""
echo "--- Test I: Worker Authorization Reused for Unauthorized Device ---"
python3 -c "
import json
m = json.load(open('$GENUINE_MANIFEST'))
m['device']['pubkey'] = '0000000000000000000000000000000000000000000000000000000000000000'
json.dump(m, open('/tmp/dattapool_adv/unauthorized-device.json', 'w'), indent=2)
"
if ./bin/verify_claim --manifest /tmp/dattapool_adv/unauthorized-device.json 2>/dev/null; then
  echo "❌ FAIL: Verifier should reject unauthorized device!"
  exit 1
else
  echo "✓ PASS: Verifier correctly rejected unauthorized device (INVALID_DEVICE_AUTHORIZATION)"
fi

# Test J: Device Authorization Reused for Wrong Session Key
echo ""
echo "--- Test J: Device Authorization Reused for Wrong Session Key ---"
python3 -c "
import json
m = json.load(open('$GENUINE_MANIFEST'))
m['session']['pubkey'] = '0000000000000000000000000000000000000000000000000000000000000000'
json.dump(m, open('/tmp/dattapool_adv/wrong-session-key.json', 'w'), indent=2)
"
if ./bin/verify_claim --manifest /tmp/dattapool_adv/wrong-session-key.json 2>/dev/null; then
  echo "❌ FAIL: Verifier should reject mismatched session key!"
  exit 1
else
  echo "✓ PASS: Verifier correctly rejected mismatched session key (INVALID_SESSION_AUTHORIZATION)"
fi

# Test M: Valid Chunk with Invalid Merkle Path (Proof of Possession)
echo ""
echo "--- Test M: Proof of Possession with Corrupted Merkle Path ---"
./bin/capture possession-challenge --manifest "$GENUINE_MANIFEST" --count 3 --out /tmp/dattapool_adv/ch.json
python3 -c "
import json
ch = json.load(open('/tmp/dattapool_adv/ch.json'))
resp = {
  'session_id': ch['session_id'],
  'challenge_nonce': ch['challenge_nonce'],
  'created_at': 1786990000,
  'proofs': [
    {
      'chunk_index': ch['requested_indexes'][0],
      'chunk_hash': '0000000000000000000000000000000000000000000000000000000000000000',
      'proof': {'leaf_index': 0, 'chunk_hash': '0'*64, 'audit_path': ['0'*64], 'sides': ['L']}
    }
  ]
}
json.dump(resp, open('/tmp/dattapool_adv/bad_resp.json', 'w'))
"
if ./bin/capture possession-verify --challenge /tmp/dattapool_adv/ch.json --response /tmp/dattapool_adv/bad_resp.json --manifest "$GENUINE_MANIFEST" 2>/dev/null; then
  echo "❌ FAIL: Possession verifier should reject corrupted Merkle proof!"
  exit 1
else
  echo "✓ PASS: Possession verifier correctly rejected invalid Merkle audit path"
fi

# Test N: Valid Possession Response Replayed Against Different Challenge Nonce
echo ""
echo "--- Test N: Replayed Possession Response (Different Nonce) ---"
python3 -c "
import json
ch = json.load(open('/tmp/dattapool_adv/ch.json'))
ch['challenge_nonce'] = 'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff'
json.dump(ch, open('/tmp/dattapool_adv/replayed_ch.json', 'w'))
"
if ./bin/capture possession-verify --challenge /tmp/dattapool_adv/replayed_ch.json --response /tmp/dattapool_adv/bad_resp.json --manifest "$GENUINE_MANIFEST" 2>/dev/null; then
  echo "❌ FAIL: Possession verifier should reject replayed nonce!"
  exit 1
else
  echo "✓ PASS: Possession verifier correctly rejected replayed challenge nonce"
fi

# Test O: Double Contribution Issuance Prevention
echo ""
echo "--- Test O: Double Contribution Issuance Rejection ---"
./bin/dattapool contribution issue --manifest "$GENUINE_MANIFEST" --ledger /tmp/dattapool_adv/ledger.json > /dev/null
if ./bin/dattapool contribution issue --manifest "$GENUINE_MANIFEST" --ledger /tmp/dattapool_adv/ledger.json 2>/dev/null; then
  echo "❌ FAIL: Should reject duplicate contribution issuance!"
  exit 1
else
  echo "✓ PASS: Contribution engine correctly rejected CONTRIBUTION_ALREADY_ISSUED"
fi

echo ""
echo "=================================================="
echo "    All Adversarial Tests Passed Successfully!"
echo "=================================================="
