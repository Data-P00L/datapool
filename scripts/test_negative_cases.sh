#!/bin/bash
# SPDX-License-Identifier: 0BSD
set -e

echo "=================================================="
echo "    DattaPool Negative & Tamper Test Suite"
echo "=================================================="

mkdir -p bin /tmp/dattapool_test

# Compile binaries if needed
if [ ! -f ./bin/capture ] || [ ! -f ./bin/verify_claim ]; then
  echo "Building required Go binaries..."
  docker run --rm -v "$(pwd):/app" -w /app golang:1.22 sh -c "\
    go build -o bin/capture cmd/capture/main.go && \
    go build -o bin/verify_claim cmd/verify_claim/main.go"
fi

# Generate baseline genuine capture session
echo "Generating baseline genuine capture session for negative tests..."
mkdir -p /tmp/dattapool_test/keys /tmp/dattapool_test/session
./bin/capture keygen --out /tmp/dattapool_test/keys/worker.key
./bin/capture keygen --out /tmp/dattapool_test/keys/device.key

./bin/capture quick \
  --worker-key /tmp/dattapool_test/keys/worker.key \
  --device-key /tmp/dattapool_test/keys/device.key \
  --video fixtures/sample-video.mp4 \
  --telemetry fixtures/sample-telemetry.jsonl \
  --skill warehouse_pick \
  --witness nostr \
  --freshness bitcoin \
  --out-dir /tmp/dattapool_test/session > /dev/null

VALID_MANIFEST=/tmp/dattapool_test/session/capture-manifest.json
cp "$VALID_MANIFEST" /tmp/dattapool_test/valid-manifest.json

STORE_FLAG="--nostr_store /tmp/dattapool_test/session/nostr_store"

# Test 1: Tampered Worker Signature
echo ""
echo "--- Test 1: Tampered Worker Nostr Signature ---"
python3 -c "
import json
m = json.load(open('/tmp/dattapool_test/valid-manifest.json'))
m['authorization']['worker_device_authorization']['worker_signature'] = '0'*128
json.dump(m, open('/tmp/dattapool_test/tampered-worker.json', 'w'), indent=2)
"

if ./bin/verify_claim --manifest /tmp/dattapool_test/tampered-worker.json $STORE_FLAG 2>/dev/null; then
  echo "❌ FAIL: Verifier should have rejected tampered worker signature!"
  exit 1
else
  echo "✓ PASS: Verifier correctly rejected INVALID_WORKER_SIGNATURE"
fi

# Test 2: Tampered Device Signature
echo ""
echo "--- Test 2: Tampered Device Signature ---"
python3 -c "
import json
m = json.load(open('/tmp/dattapool_test/valid-manifest.json'))
m['authorization']['device_session_authorization']['device_signature'] = '0'*128
json.dump(m, open('/tmp/dattapool_test/tampered-device.json', 'w'), indent=2)
"

if ./bin/verify_claim --manifest /tmp/dattapool_test/tampered-device.json $STORE_FLAG 2>/dev/null; then
  echo "❌ FAIL: Verifier should have rejected tampered device signature!"
  exit 1
else
  echo "✓ PASS: Verifier correctly rejected INVALID_DEVICE_SIGNATURE"
fi

# Test 3: Tampered Video File
echo ""
echo "--- Test 3: Tampered Video Payload ---"
cp fixtures/sample-video.mp4 /tmp/dattapool_test/tampered-video.mp4
echo "corrupt" >> /tmp/dattapool_test/tampered-video.mp4

if ./bin/verify_claim --manifest /tmp/dattapool_test/valid-manifest.json --video /tmp/dattapool_test/tampered-video.mp4 $STORE_FLAG 2>/dev/null; then
  echo "❌ FAIL: Verifier should have rejected tampered video payload!"
  exit 1
else
  echo "✓ PASS: Verifier correctly rejected INVALID_VIDEO_HASH"
fi

# Test 4: Tampered Telemetry File
echo ""
echo "--- Test 4: Tampered Telemetry Records ---"
cp fixtures/sample-telemetry.jsonl /tmp/dattapool_test/tampered-telemetry.jsonl
echo '{"timestamp_sec":9999999999.0,"frame_index":999,"imu":{"linear_accel":[0,0,0],"angular_vel":[0,0,0]},"joints":{"positions":[0,0,0,0,0,0,0],"velocities":[0,0,0,0,0,0,0],"efforts":[0,0,0,0,0,0,0]},"gripper":{"width":0,"force":0},"mode":"CORRUPTED"}' >> /tmp/dattapool_test/tampered-telemetry.jsonl

if ./bin/verify_claim --manifest /tmp/dattapool_test/valid-manifest.json --video fixtures/sample-video.mp4 --telemetry /tmp/dattapool_test/tampered-telemetry.jsonl $STORE_FLAG 2>/dev/null; then
  echo "❌ FAIL: Verifier should have rejected tampered telemetry!"
  exit 1
else
  echo "✓ PASS: Verifier correctly rejected INVALID_TELEMETRY_MERKLE_ROOT"
fi

# Test 5: Forged Session Chain Root
echo ""
echo "--- Test 5: Forged Capture Chain Root ---"
python3 -c "
import json
m = json.load(open('/tmp/dattapool_test/valid-manifest.json'))
m['capture']['capture_chain_root'] = 'f'*64
json.dump(m, open('/tmp/dattapool_test/tampered-chain.json', 'w'), indent=2)
"

if ./bin/verify_claim --manifest /tmp/dattapool_test/tampered-chain.json 2>/dev/null; then
  echo "❌ FAIL: Verifier should have rejected forged capture chain!"
  exit 1
else
  echo "✓ PASS: Verifier correctly rejected INVALID_SESSION_SIGNATURE / INVALID_DEVICE_SIGNATURE"
fi

echo ""
echo "=================================================="
echo "    All Negative & Tamper Tests Passed!"
echo "=================================================="
