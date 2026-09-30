# DattaPool Provenance Levels Specification

This document defines the formal hierarchy of provenance levels (`P0` through `P5`) evaluated by the DattaPool independent verification engine.

---

## 1. Provenance Level Matrix

| Level | Name | Evidence Required | Anti-Theft / Temporal Guarantee | Contribution Eligibility |
|---|---|---|---|---|
| **P0** | File Integrity Only | Raw SHA-256 hash matching claim | Proves file content matches hash. **No authorship or timing guarantee.** | **Ineligible (0 DTTA)** |
| **P1** | Worker-Signed Claim | Worker Nostr signature over raw file hash | Proves worker identity signed the file after acquisition. No device binding. | **Ineligible (0 DTTA)** |
| **P2** | Authenticated Multi-Party Session | Two-tier authorization: Worker $\to$ Device $\to$ Ephemeral Session | Proves authorized device and session key signed the capture metadata. | **Ineligible (0 DTTA)** |
| **P3** | Continuous Temporal Provenance (Offline) | P2 + Continuous multi-sensor hash chain ($H_0 \to H_n$) + Merkle roots + Capture Seal | Proves continuous ordering, multi-stream video/telemetry synchronization, and monotonic time ranges during offline recording. | **Eligible (1 DTTA / sec)** |
| **P4** | Externally Witnessed Pre-Capture Commitment | P3 + Pre-capture Nostr witness event published before ingestion + Bitcoin block freshness anchor | **Proves session existed and was publicly committed before video was recorded.** Strong anti-claim-jumping defense. | **Eligible (1 DTTA / sec)** |
| **P5** | Hardware Attested (Reserved) | P4 + TPM 2.0 / Secure Enclave hardware attestation | Proves physical camera/robot hardware execution. | **Eligible (1 DTTA / sec)** |

---

## 2. Detailed Level Requirements

### Level P0: File Integrity
* **Conditions**:
  - `video_sha256` matches the raw file bytes.
* **Scope**: Trivial data integrity check. Anyone who copies the file can satisfy P0.

### Level P1: Worker Attribution
* **Conditions**:
  - Valid `worker_nostr_pubkey`.
  - Signature over manifest or claim metadata.
* **Scope**: Proves identity attribution, but does not prevent post-facto claim jumping.

### Level P2: Authenticated Session Delegation
* **Conditions**:
  - Valid `WorkerDeviceAuthorization` (`worker_signature` over device pubkey and expiration).
  - Valid `DeviceSessionAuthorization` (`device_signature` over session pubkey, ID, and nonce commitment).
  - Valid `SessionSealSignature` over final capture seal.
* **Scope**: Enforces identity separation and prevents unauthorized devices from manufacturing sessions.

### Level P3: Continuous Synchronized Provenance (Offline-First)
* **Conditions**:
  - All P2 requirements.
  - Video stream chunked into deterministic 64KB blocks.
  - Telemetry frames batched and synchronized with video chunks.
  - Binary Merkle trees computed for both streams (`VideoMerkleRoot`, `TelemetryMerkleRoot`).
  - Continuous sequential hash chain:

```
H_0 = \operatorname{SHA256}\left(
\text{DOMAIN}
\parallel
\text{session\_id}
\parallel
\text{nonce\_commitment}
\right)
```

```
H_k = \operatorname{SHA256}\left(
\text{DOMAIN}
\parallel
H_{k-1}
\parallel
\text{session\_id}
\parallel
k
\parallel
t_{\text{start}}
\parallel
t_{\text{end}}
\parallel
v_k
\parallel
t_k
\right)
```

  - Monotonic time ranges ($t_{\text{start}} \ge t_{\text{end,prev}}$).
  - `CaptureSeal` signed by Session key and Device key.
* **Scope**: Ideal for offline camera units and air-gapped robotics rigs. Guarantees sensor synchronization and sequence integrity.

### Level P4: Externally Witnessed Pre-Capture Commitment
* **Conditions**:
  - All P3 requirements.
  - **Freshness Reference**: Recent Bitcoin regtest/mainnet block hash and height bound into the pre-capture session commitment.
  - **Pre-Capture Nostr Witness Event**: Signed by Worker Nostr identity and broadcast to Nostr relays prior to ingestion:
    - Kind: `30078`
    - Content: Canonical JSON binding `session_id`, `device_pubkey`, `session_pubkey`, `nonce_commitment`, `freshness`.
  - **Taproot Asset Anchor**: Collectible `CLAIM` asset minted on Bitcoin regtest committing to `manifest_hash`.
  - **Universe Proof**: Asset proof discoverable in local/remote Taproot Assets Universe.
* **Scope**: Prevents claim jumping. An attacker who steals the completed MP4 cannot forge the pre-capture Nostr witness event or produce a matching pre-capture Bitcoin block anchor.

---

## 3. What P4 Does and Does Not Prove

### What P4 Proves:
1. A specific Nostr worker identity authorized a specific device and ephemeral session before capture started.
2. An external, publicly verifiable cryptographic commitment existed before the video recording was finalized.
3. The video and telemetry streams are temporally bound frame-by-frame and chunk-by-chunk.
4. The capture was sealed and permanently anchored on Bitcoin via Taproot Assets.

### What P4 Does NOT Prove:
1. Proof-of-Personhood (the Nostr pubkey could belong to an automated bot).
2. Physical camera location (requires GPS/hardware attestation).
3. Authenticity of the physical scene (does not detect staged environments or synthetic deepfakes without downstream model verification).

---

## 4. Provenance & Contribution Accounting Relationship

```text
PROVENANCE LEVEL (Evidence Strength)
       ↓
CONTRIBUTION ACCOUNTING (₿DATA / DTTA Units)
       ↓
ECONOMIC VALUATION (BTC / sats Market Settlement)
```

* **P0–P2:** Insufficient temporal and sensor chaining evidence. These levels do not qualify for protocol ₿DATA contribution accounting.
* **P3 & P4:** Satisfy continuous temporal chaining, Merkle commitments, and dual-key capture seals. They qualify for objective duration-based ₿DATA accounting (`1 second = 1 DTTA` under `dattapool-base-duration/0.1.0`).
* **Economic Valuation:** Is independent of provenance level and determined outside the protocol in BTC sats.

