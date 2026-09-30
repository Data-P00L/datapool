# DattaPool Protocol Specification (v0.4.0)

DattaPool is a decentralized, open-source protocol for verifiable robotics data attribution, temporal provenance, and anti-claim-jumping.

---

## 1. Architectural Model

```text
                     DATTAPOOL

                  NOSTR IDENTITY
                       │
             WorkerDeviceAuthorization
                       │
                       ▼
                DEVICE IDENTITY
                       │
            DeviceSessionAuthorization
                       │
                       ▼
                SESSION IDENTITY
                       │
            Pre-Capture Commitment
             & Nostr Witness Event
                       │
                       ▼
              CAPTURE CLIENT (Offline)
                       │
          ┌────────────┴─────────────┐
          │                          │
        VIDEO                     TELEMETRY
          │                          │
          └────────────┬─────────────┘
                       │
          temporal chunk / hash / chain
                       │
                       ▼
                 CAPTURE SEAL
                       │
                       ▼
               CAPTURE MANIFEST (0.4.0)
                       │
                  manifest hash
                       │
          ┌────────────┴─────────────┐
          │                          │
       NOSTR                     TAP CLAIM
      witness                         │
    verification                  Bitcoin (Regtest)
                                      │
                                   Universe
                                      │
                                      ▼
                              CLAIM VERIFIER
                                      │
                                      ▼
                             RESULT: VERIFIED
                           PROVENANCE LEVEL: P4
```

---

## 2. Cryptographic Domain Separation

Every signed message and commitment enforces a dedicated domain prefix:

| Domain Constant | Target Payload | Signer |
|---|---|---|
| `DATTA_WORKER_DEVICE_AUTHORIZATION_V1` | Worker $\to$ Device delegation | Worker Nostr Key |
| `DATTA_DEVICE_SESSION_AUTHORIZATION_V1` | Device $\to$ Session delegation | Device Key |
| `DATTA_NONCE_COMMITMENT_V1` | Pre-capture nonce commitment | Ephemeral Hash |
| `DATTA_SESSION_START_COMMITMENT_V1` | Nostr pre-capture witness event | Worker Nostr Key |
| `DATTA_CAPTURE_CHUNK_V1` | Temporal rolling chunk chain | Session State |
| `DATTA_CAPTURE_SEAL_V1` | Final session completion seal | Session & Device Keys |
| `DATTA_PROOF_OF_POSSESSION_V1` | Interactive Merkle challenge | Verifier & Claimant |

---

## 3. Capture Workflow (0.4.0)

### Step 1: Pre-Capture Authorization & Witnessing
1. Generate random 256-bit `session_nonce`.
2. Compute `nonce_commitment = SHA256(DATTA_NONCE_COMMITMENT_V1 || session_id || nonce)`.
3. Query recent Bitcoin block hash/height (`freshness_reference`).
4. Worker signs `WorkerDeviceAuthorization`.
5. Device signs `DeviceSessionAuthorization`.
6. Worker signs and broadcasts pre-capture Nostr witness event (Kind 30078).

### Step 2: Continuous Temporal Ingestion & Chaining
For each 64KB video chunk $V_k$ and synchronized telemetry frame batch $T_k$:
* Compute $v_k = \operatorname{SHA256}(V_k)$ and $t_k = \operatorname{SHA256}(T_k)$.
* Advance rolling temporal chain:

```
H_0 = \operatorname{SHA256}\left(
\text{DATTA\_CAPTURE\_CHUNK\_V1}
\parallel
\text{session\_id}
\parallel
\text{nonce\_commitment}
\right)
```

```
H_k = \operatorname{SHA256}\left(
\text{DATTA\_CAPTURE\_CHUNK\_V1}
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

* Compute binary Merkle trees: `VideoMerkleRoot` and `TelemetryMerkleRoot`.

### Step 3: Session Sealing & Manifest Generation
1. Session key signs `DATTA_CAPTURE_SEAL_V1`.
2. Device key signs `DATTA_CAPTURE_SEAL_V1`.
3. Save canonical `capture-manifest.json` (0.4.0).

---

## 4. Verification Pipeline

The independent verifier validates:
1. `manifest_schema_validation`
2. `manifest_canonical_hash`
3. `worker_device_authorization`
4. `device_session_authorization`
5. `capture_seal_signature`
6. `pre_capture_nostr_witness`
7. `bitcoin_block_freshness`
8. `video_sha256_integrity`
9. `video_merkle_root`
10. `telemetry_merkle_root`
11. `continuous_capture_chain`
12. `video_telemetry_binding`
13. `tap_proof_and_metadata`
14. Assesses Provenance Level (`P4` or `P3`).

---

## 5. Contribution Qualification & Economic Settlement

Following independent verification, qualifying captures enter the contribution accounting lifecycle:

```text
              REAL-WORLD CAPTURE
                      │
                      ▼
               Capture Manifest
                      │
                      ▼
                 TAP Claim
                      │
                      ▼
               Verification
                      │
                      ▼
          Contribution Qualification (P3+)
                      │
                      ▼
                ₿DATA / DTTA
       standardized contribution units
                      │
                      │  (External Market Negotiation)
                      ▼
                 BTC / sats
          payment and settlement
```

### Key Invariant
* **₿DATA (DTTA):** Standardized accounting unit for independently verified robotics-data contribution. It is **not** a monetary unit and carries **no protocol-guaranteed BTC redemption value**.
* **BTC / sats:** The monetary unit used for pricing, licensing, compensation, and settlement.
* Under reference policy `dattapool-base-duration/0.1.0`, `1 ₿DATA = 1 eligible verified second` for captures evaluated at provenance level `P3` or higher. Sub-P3 captures (`P0`–`P2`) are ineligible for base contribution units.

