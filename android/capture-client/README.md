# DattaPool Android Field Capture Client (APK)

This directory contains the native Android client application (`org.dattapool.capture`) built for physical device capture, temporal provenance generation, and anti-claim-jumping.

---

## 1. Technical Stack

* **Language**: Kotlin 1.9.22
* **UI**: Jetpack Compose (Material3 dark theme)
* **Camera**: CameraX (1080p MP4 recording)
* **Sensors**: Android `SensorManager` (`Sensor.TYPE_ACCELEROMETER`, `Sensor.TYPE_GYROSCOPE` at ~50Hz)
* **Cryptography**: `SHA256withECDSA` curve keypair generation, signing, and verification matching Go
* **Serialization**: Strict RFC-8785 canonical JSON serializer in Kotlin
* **Nostr Witnessing**: Pre-capture NIP-01 Kind 30078 event creation, signing, and publishing
* **Minimum Android SDK**: 26 (Android 8.0)
* **Target Android SDK**: 33 (Android 13)
* **Compile Android SDK**: 34

---

## 2. Tested Physical Device

* **Device Model**: Samsung Galaxy S20 FE 5G (`SM-G781U`) / Android 13+ reference device
* **Android Version**: 13 (API 33) / 14 (API 34)
* **ADB Device Serial**: `<YOUR_DEVICE_SERIAL>` (retrieve via `adb devices`)

---

## 3. Build & Install Instructions

### Building the APK
```bash
cd android/capture-client
export ANDROID_HOME=$HOME/Android/Sdk # Or your local Android SDK path
./gradlew assembleDebug
```
Artifact output:
`app/build/outputs/apk/debug/app-debug.apk`

### Installing on Connected Android Device
```bash
adb -s <DEVICE_SERIAL> install -r app/build/outputs/apk/debug/app-debug.apk
```

### Granting Permissions & Reversing Freshness Port
```bash
adb -s <DEVICE_SERIAL> shell pm grant org.dattapool.capture android.permission.CAMERA
adb -s <DEVICE_SERIAL> reverse tcp:8090 tcp:8090
```

---

## 4. End-to-End Verification Pipeline

1. **Start WSL Freshness & Witness Bridge**:
   ```bash
   ./bin/freshness_service --port 8090 --store_dir ./session_data/nostr_store
   ```
2. **Launch App on Phone**:
   ```bash
   adb -s <DEVICE_SERIAL> shell am start -n org.dattapool.capture/.MainActivity
   ```
3. **Record Capture**:
   - Tap **`START CAPTURE`**.
   - Perform egocentric motion / task recording.
   - Tap **`STOP & SEAL SESSION`**.
4. **Pull Session Bundle**:
   ```bash
   adb -s <DEVICE_SERIAL> pull /sdcard/Android/data/org.dattapool.capture/files/<SESSION_DIR> session_data/
   ```
5. **Verify with Independent Go Verifier**:
   ```bash
   ./bin/verify_claim \
     --manifest session_data/<SESSION_DIR>/capture-manifest.json \
     --video session_data/<SESSION_DIR>/capture.mp4 \
     --telemetry session_data/<SESSION_DIR>/telemetry.jsonl \
     --nostr_store ./session_data/nostr_store \
     --require_level P4
   ```
   **Output**: `RESULT: VERIFIED`, `PROVENANCE LEVEL: P4`.
6. **Publish TAP Claim Asset to Bitcoin Regtest**:
   ```bash
   ./bin/publish_claim --manifest session_data/<SESSION_DIR>/capture-manifest.json
   ```

---

## 5. YouTube Media Publication & Discovery

The client optionally uploads sealed captures to YouTube as an external discovery and inspection layer:

1. **Sealed Capture Screen**: Tap **`UPLOAD TO YOUTUBE`**.
2. **Visibility Selection**: Default is **`Unlisted`** for development/testing (user can select `Public` or `Private`).
3. **Metadata Embedding**: Automatically embeds the machine-readable `--- DATTAPOOL ---` block in the YouTube description.
4. **Exact MP4 Upload**: Uploads the unaltered sealed `capture.mp4` file.
5. **Nostr Availability Announcement**: Signs and broadcasts a Kind `30078` event with the returned YouTube `video_id`.
6. **Independent Agent Discovery**: Autonomous agents parse the description block and verify provenance against TAP/Universe without trusting YouTube.

See [docs/youtube-publication.md](../../docs/youtube-publication.md) for full protocol and OAuth configuration details.
