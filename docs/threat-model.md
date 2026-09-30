# DattaPool Security & Threat Model (v0.4.0)

This document outlines the threat landscape, security boundaries, cryptographic defenses, and mitigations implemented in **DattaPool** (Schema Version `0.4.0`).

---

## 1. Core Threat Analysis & Defenses

### 1.1 Claim Jumping (Post-Facto Forgery)
* **Threat**: An attacker copies a completed video MP4 and manufactures a fraudulent claim claiming historical authorship.
* **Defense (Milestone 2)**:
  - **Pre-Capture Nostr Witness**: Before capture begins, the genuine creator publishes a signed Nostr event containing `nonce_commitment = SHA256(DOMAIN || session_id || nonce)`.
  - **Bitcoin Block Freshness**: The session binds the recent Bitcoin block hash/height observed prior to capture start.
  - An attacker who obtains the completed video cannot forge a pre-existing Nostr witness event from the past or change historical Bitcoin blockchain state.

### 1.2 Multi-Sensor Stream Swapping
* **Threat**: An attacker combines a video stream from Session A with high-quality telemetry recorded during Session B.
* **Defense**:
  - The rolling hash chain binds both sensors synchronously at every step:

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

  - Swapping either stream causes `VIDEO_TELEMETRY_BINDING_INVALID` and invalidates `CaptureChainRoot`.

### 1.3 Chunk Reordering & Deletion
* **Threat**: An attacker deletes dropped frames or rearranges chunks.
* **Defense**:
  - Each chunk commitment includes its 0-indexed sequence number $k$ and monotonic relative timestamps $[t_{\text{start}}, t_{\text{end}}]$.
  - The verifier validates $t_{\text{start}} \ge t_{\text{end,prev}}$ and rejects gaps or duplicates with `CHUNK_ORDER_INVALID`.

### 1.4 Interactive Proof-of-Possession Attacks
* **Threat**: An attacker claims authorship of large datasets without actually storing or serving the underlying raw data chunks.
* **Defense**:
  - The verifier issues random interactive challenges with a fresh `challenge_nonce`.
  - The claimant must return raw chunk hashes and Merkle audit paths to the committed `VideoMerkleRoot`.

### 1.5 Compromised Delegation Keys
* **Threat**: An ephemeral session key is leaked.
* **Defense**:
  - Two-tier authorization isolates the root Worker Nostr key from the physical Device key and Ephemeral Session key.
  - Authorizations enforce strict expiration timestamps (`expires_at`).

### 1.6 Double Contribution Issuance & Unverified Minting
* **Threat**: An attacker attempts to produce multiple ₿DATA accounting issuances from a single capture, or mint ₿DATA from unverified/sub-P3 claims.
* **Defense**:
  - **Verification Gate**: Contribution issuance requires positive cryptographic verification meeting or exceeding minimum provenance level `P3` (or `P4`).
  - **Deterministic Issuance ID**: Each issuance derives a unique, deterministic identity:

```
\text{IssuanceID} = \operatorname{SHA256}\left(
\text{DOMAIN}
\parallel
\text{claim\_id}
\parallel
\text{contributor\_pubkey}
\parallel
\text{policy\_id}
\parallel
\text{policy\_version}
\parallel
\text{range}
\right)
```

  - Replay attempts against the same contribution identity fail with `CONTRIBUTION_ALREADY_ISSUED`.
  - **No Peg / Redemption Risk**: Because ₿DATA carries no protocol-guaranteed redemption in BTC sats, an attacker cannot drain a protocol treasury or force automated economic liquidations.

---

## 2. Provenance Summary

| Provenance Level | External Pre-Commitment | Multi-Sensor Binding | Bitcoin Taproot Anchor | Primary Defense |
|---|---|---|---|---|
| **P0** | None | None | Optional | Raw data integrity |
| **P1** | None | None | Optional | Worker non-repudiation |
| **P2** | None | None | Optional | Device authorization |
| **P3** | Local only | Frame-by-frame | Yes | Offline sequence integrity |
| **P4** | **Nostr Event + Bitcoin Block** | **Frame-by-frame** | **Yes** | **Anti-claim-jumping** |
