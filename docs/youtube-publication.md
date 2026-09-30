# DattaPool YouTube Media Publication & Discovery Architecture

## 1. Overview and Core Architectural Principle

DattaPool uses YouTube strictly as an **external distribution, preview, and discovery layer**, NOT as DattaPool's canonical cryptographic storage layer.

### Source Artifact vs. Media Publication

```text
SOURCE ARTIFACT (Canonical & Immutable)
├── capture.mp4               (Raw egocentric video committed in SHA-256)
├── telemetry.jsonl           (Sensor streams committed in Merkle tree)
├── capture-manifest.json     (Cryptographic manifest & signatures)
├── compensation.json         (TAP contribution remuneration receipt)
├── capture seal              (Session & device key attestations)
└── proof material            (Temporal hash chain & Merkle roots)

        versus

MEDIA PUBLICATION (Discovery & Distribution Layer)
├── YouTube Video             (Transcoded representation for streaming)
├── Title & Tags              (Semantic search indexing & classification)
├── Description Block         (Deterministic machine-readable metadata)
├── Video ID & Watch URL      (Public locator)
├── Visibility State          (Unlisted / Public / Private)
└── Nostr Availability Event  (Signed public announcement)
```

### Critical Verification Invariant
> **Important**: YouTube transcodes all uploaded videos.
> Downloading a video stream from YouTube will **NOT** reproduce the original `video_sha256` or 64KB video Merkle tree root.
> 
> YouTube is used for human preview, visual inspection, automated agent classification, and discovery.
> Complete P3/P4 provenance verification requires the original sealed DattaPool bundle.

---

## 2. YouTube Authentication & Google Cloud Setup

YouTube uploads utilize Google OAuth 2.0 with minimal required permissions.

### Required OAuth Scope
```text
https://www.googleapis.com/auth/youtube.upload
```

### Google Cloud Project Configuration
1. Navigate to the [Google Cloud Console](https://console.cloud.google.com/).
2. Create or select a project (e.g. `dattapool-capture-client`).
3. Enable the **YouTube Data API v3** under **APIs & Services > Library**.
4. Configure the **OAuth Consent Screen**:
   - User Type: External
   - Add Test Users: Include authorized Google accounts for testing.
   - Scopes: Add `https://www.googleapis.com/auth/youtube.upload`.
5. Create Credentials:
   - Type: **OAuth 2.0 Client ID**
   - Application Type: **Android** (or Web / Desktop for standalone relay tools)
   - Specify the Package Name: `org.dattapool.capture`
   - Provide the **SHA-1 signing certificate fingerprint** (see below).

#### How to Obtain the SHA-1 Fingerprint:

**Method 1 (Gradle - Recommended):**
Run the signing report inside `android/capture-client`:
```bash
cd android/capture-client
./gradlew signingReport
```
Look for the `SHA1` entry under `Variant: debug` (or `release` for production):
```text
Variant: debug
Store: ~/.android/debug.keystore
SHA1: 26:F8:7F:EC:AB:D3:7E:37:BE:2C:3C:3D:5F:CA:67:58:B8:27:D4:BD
```

**Method 2 (JDK Keytool):**
For the debug keystore directly:
```bash
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
```
For a custom release keystore:
```bash
keytool -list -v -keystore /path/to/release.keystore -alias <your_alias>
```

### Security Guarantees
- **No confidential client secrets** are stored in the Android APK.
- **No OAuth tokens** are stored in `capture-manifest.json` or exported session bundles.
- Authentication state is isolated from the cryptographic capture engine.

---

## 3. Upload Flow and Resumable Protocol

### Upload State Machine

```text
       NOT_PUBLISHED
             ↓
      AUTH_REQUIRED ──(OAuth)──> AUTHORIZING
                                      ↓
      [ CAPTURE SEALED ] ───────>   READY
                                      ↓
                                  UPLOADING  (0% -> 100% chunked)
                                      ↓
                              YOUTUBE_PROCESSING
                                      ↓
                                  PUBLISHED
                                      ↓
                        PUBLISHING_NOSTR_AVAILABILITY
                                      ↓
                                  AVAILABLE
```

If an upload fails at any stage:
- The state transitions to `FAILED` with a descriptive, recoverable error.
- The DattaPool capture remains **`CAPTURE SEALED`** and unmodified.
- Retrying does not re-upload if a valid `video_id` is already assigned to the session.

### Exact Byte Upload
The client uploads the exact sealed `capture.mp4` file used when computing:
- `video_sha256`
- `video_merkle_root`
- `capture_chain_root`

The video is not transcoded, re-encoded, or mutated prior to upload.

---

## 4. Visibility Semantics & API Project Restrictions

The client defaults to **Unlisted** visibility for all development and testing:

```text
● Unlisted  (Default)
○ Public    (Requires explicit user selection)
○ Private
```

### Handling Unverified API Project Restrictions
Google Cloud YouTube API projects in testing status may force uploaded videos to remain `private` regardless of the requested status.

The client inspects the returned video resource and records both:
- `requested_visibility`: The status selected by the user (e.g., `unlisted`).
- `actual_visibility`: The status enforced by YouTube (e.g., `private`).

The UI clearly displays both states truthfully to the operator.

---

## 5. YouTube Video Metadata Specification

### Title Format
```text
DattaPool Capture | <skill_tag> | <provenance_level> | <short_session_id>
```
*Example:* `DattaPool Capture | general_manipulation | P4 | 833ab02a`

### Standard Discovery Tags
```text
dattapool
robotics
robotics-data
egocentric
robot-training-data
embodied-ai
P4
<skill_tag>
```

### Description Format
The YouTube description consists of a human-readable header followed by a deterministic, delimited machine-readable block:

```text
DattaPool Robotics Capture

Skill: general_manipulation
Provenance: P4
Session: 833ab02adca3ac6680542d616e77bf5f

--- DATTAPOOL ---
protocol=dattapool
schema=0.4.0
type=capture_publication
session_id=833ab02adca3ac6680542d616e77bf5f
worker_nostr_pubkey=0ead498f89be73a48abd4253ab2a95675a20f867d0a038ebaf11d6900d4d05d4
manifest_hash=51b8803ea5e955e039f2df35bcc9198ae3bb6bee81ef0d5e76fac11d1e9f4ed2
capture_chain_root=f566881b48364607b1c125c812ca281c5765561c5c4264c9d4d01bb11fb040a1
tap_claim_asset_id=60dfeafdde09a8343ac58ae7332565e463469e80245ace60a981a5c07985bc88
provenance_level=P4
skill_tag=general_manipulation
--- END DATTAPOOL ---
```

---

## 6. Media Publication Record (`publication.json`)

Stored in the session directory and persisted locally:

```json
{
  "protocol": "dattapool",
  "type": "media_publication",
  "schema_version": "0.1.0",
  "session_id": "833ab02adca3ac6680542d616e77bf5f",
  "manifest_hash": "51b8803ea5e955e039f2df35bcc9198ae3bb6bee81ef0d5e76fac11d1e9f4ed2",
  "capture_claim_asset_id": "60dfeafdde09a8343ac58ae7332565e463469e80245ace60a981a5c07985bc88",
  "platform": "youtube",
  "video_id": "abc123xyz",
  "requested_visibility": "unlisted",
  "actual_visibility": "unlisted",
  "watch_url": "https://youtu.be/abc123xyz",
  "source_video_sha256": "4444444444444444444444444444444444444444444444444444444444444444",
  "published_by_nostr_pubkey": "0ead498f89be73a48abd4253ab2a95675a20f867d0a038ebaf11d6900d4d05d4",
  "published_at": 1700000100,
  "source_bundle_uri": null,
  "nostr_availability_event_id": "ea12bc..."
}
```

---

## 7. Nostr Dataset Availability Event (Kind `30078`)

Because unlisted YouTube videos are not indexed in public YouTube search, the client broadcasts a signed Nostr availability announcement upon upload completion:

```json
{
  "id": "ea12bc...",
  "pubkey": "0ead498f89be73a48abd4253ab2a95675a20f867d0a038ebaf11d6900d4d05d4",
  "created_at": 1700000100,
  "kind": 30078,
  "tags": [
    ["d", "833ab02adca3ac6680542d616e77bf5f"],
    ["protocol", "dattapool"],
    ["type", "dataset_availability"],
    ["platform", "youtube"],
    ["video_id", "abc123xyz"],
    ["visibility", "unlisted"],
    ["manifest_hash", "51b8803ea5e955e039f2df35bcc9198ae3bb6bee81ef0d5e76fac11d1e9f4ed2"],
    ["claim_asset_id", "60dfeafdde09a8343ac58ae7332565e463469e80245ace60a981a5c07985bc88"]
  ],
  "content": "{\"protocol\":\"dattapool\",\"type\":\"dataset_availability\",\"schema_version\":\"0.1.0\",\"session_id\":\"833ab02adca3ac6680542d616e77bf5f\",\"manifest_hash\":\"51b8803ea5e955e039f2df35bcc9198ae3bb6bee81ef0d5e76fac11d1e9f4ed2\",\"media\":{\"platform\":\"youtube\",\"video_id\":\"abc123xyz\",\"visibility\":\"unlisted\"},\"capture_claim_asset_id\":\"60dfeafdde09a8343ac58ae7332565e463469e80245ace60a981a5c07985bc88\"}",
  "sig": "..."
}
```

---

## 8. Third-Party Agent Discovery & Verification Flow

An autonomous robotics data agent discovers and indexes captures via the following steps:

```text
1. Agent listens to Nostr relays for kind:30078 events with type="dataset_availability".
2. Agent extracts YouTube video_id, manifest_hash, and session_id.
3. Agent queries YouTube Data API or fetches video page metadata.
4. Agent parses the delimited "--- DATTAPOOL ---" block in the description.
5. Agent extracts:
   ├── session_id
   ├── manifest_hash
   ├── worker_nostr_pubkey
   ├── capture_chain_root
   ├── tap_claim_asset_id
   ├── provenance_level
   └── skill_tag
6. Agent queries DattaPool / TAP Universe using tap_claim_asset_id / manifest_hash.
7. Agent verifies the cryptographic claim independently.
8. Agent indexes video metadata in its robotics training catalog.
9. (Optional) When high-fidelity training data is required, agent requests the canonical sealed bundle.
```

No trust in YouTube's infrastructure is required for cryptographic provenance verification.
