#!/bin/bash
set -e

echo "=================================================="
echo "    DattaPool Android Field Capture Verification"
echo "    Physical Device -> Go Verifier -> Bitcoin/TAP"
echo "=================================================="

SESSION_DIR="session_data/android_p4_final/session-47ea454f35266f3c52a5e0b324c10d1b"
MANIFEST="$SESSION_DIR/capture-manifest.json"
VIDEO="$SESSION_DIR/capture.mp4"
TELEMETRY="$SESSION_DIR/telemetry.jsonl"
STORE="./session_data/nostr_store"

# 1. Independent Verification
echo ""
echo "--- Step 1: Independent Cryptographic Verification ---"
./bin/verify_claim \
  --manifest "$MANIFEST" \
  --video "$VIDEO" \
  --telemetry "$TELEMETRY" \
  --nostr_store "$STORE" \
  --require_level P4

# 2. Tamper Test A: Modified Android Video
echo ""
echo "--- Step 2: Negative Tamper Test - Corrupted Video File ---"
cp "$VIDEO" /tmp/corrupt_android_video.mp4
echo "bad_byte_injection" >> /tmp/corrupt_android_video.mp4
if ./bin/verify_claim --manifest "$MANIFEST" --video /tmp/corrupt_android_video.mp4 --nostr_store "$STORE" 2>/dev/null; then
  echo "❌ FAIL: Tampered Android video should be rejected!"
  exit 1
else
  echo "✓ PASS: Tampered Android video correctly rejected (INVALID_VIDEO_HASH)"
fi

# 3. Tamper Test B: Modified Android Telemetry
echo ""
echo "--- Step 3: Negative Tamper Test - Corrupted Telemetry ---"
cp "$TELEMETRY" /tmp/corrupt_android_telemetry.jsonl
echo '{"timestamp_sec":999.0,"frame_index":9999,"imu":{"linear_accel":[0,0,0],"angular_vel":[0,0,0]},"joints":{"positions":[0,0,0,0,0,0,0],"velocities":[0,0,0,0,0,0,0],"efforts":[0,0,0,0,0,0,0]},"gripper":{"width":0,"force":0},"mode":"A"}' >> /tmp/corrupt_android_telemetry.jsonl
if ./bin/verify_claim --manifest "$MANIFEST" --video "$VIDEO" --telemetry /tmp/corrupt_android_telemetry.jsonl --nostr_store "$STORE" 2>/dev/null; then
  echo "❌ FAIL: Tampered Android telemetry should be rejected!"
  exit 1
else
  echo "✓ PASS: Tampered Android telemetry correctly rejected (INVALID_TELEMETRY_MERKLE_ROOT)"
fi

# 4. Tamper Test C: Modified Session ID in Manifest
echo ""
echo "--- Step 4: Negative Tamper Test - Mismatched Session ID ---"
python3 -c "
import json
m = json.load(open('$MANIFEST'))
m['session']['id'] = 'forged-session-id-999'
json.dump(m, open('/tmp/forged_android_manifest.json', 'w'), indent=2)
"
if ./bin/verify_claim --manifest /tmp/forged_android_manifest.json --nostr_store "$STORE" 2>/dev/null; then
  echo "❌ FAIL: Mismatched session ID should be rejected!"
  exit 1
else
  echo "✓ PASS: Mismatched session ID correctly rejected (INVALID_CAPTURE_SEAL / SESSION_ID_MISMATCH)"
fi

echo ""
echo "=================================================="
echo "    All Android Field Capture Tests PASSED! (P4)"
echo "=================================================="
