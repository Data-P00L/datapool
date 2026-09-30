# DattaPool Contribution Accounting Specification (v0.2.0)

This document specifies the design, data structures, accounting policy, and verification invariants for **₿DATA (DTTA)** contribution units in the DattaPool protocol.

---

## 1. Core Definitions

### ₿DATA (DTTA)
> **₿DATA is DattaPool's standardized accounting unit for independently verified robotics-data contribution.**
> 
> * **Human-facing display symbol:** `₿DATA`
> * **Machine-readable ASCII ticker:** `DTTA`
> * **Nature:** A non-monetary measurement of eligible, cryptographically verified robotics and sensor capture contributions.

### BTC / sats
> **Bitcoin (sats) is the monetary unit used for pricing, licensing, compensation, rewards, and economic settlement.**

---

## 2. Invariant & Separation of Concerns

```text
₿DATA answers:
"How much qualifying verified robotics-data contribution occurred?"

BTC / sats answer:
"What is that contribution economically worth in the market?"
```

The protocol strictly maintains the following separation:

```text
┌─────────────────────────────────────────────────────────────┐
│                 CONTRIBUTION ACCOUNTING                     │
│                     (₿DATA / DTTA)                          │
│  - Standardized physical/temporal measurement               │
│  - Objective, deterministic, cryptographically verifiable   │
│  - Based on proven capture duration & provenance level      │
│  - Supply derived purely from qualifying verified captures │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│                    ECONOMIC VALUATION                       │
│                        (BTC / sats)                         │
│  - Market pricing set externally by dataset buyers/labs     │
│  - No protocol-guaranteed peg or redemption value           │
│  - No treasury backing, reserves, or algorithmic pegs       │
│  - Pure integer satoshi settlement                          │
└─────────────────────────────────────────────────────────────┘
```

### Protocol Guarantees vs. Market Decisions
* **No BTC Peg:** There is **NO** protocol assumption such as `1 ₿DATA = 1 sat` or `1 ₿DATA = X sats`.
* **Zero Protocol-Guaranteed Redemption:** ₿DATA does not entitle the holder to any fixed or guaranteed redemption in Bitcoin from the protocol or a central treasury.
* **External Market Pricing:** A dataset buyer, AI laboratory, or marketplace may offer `75 sats / DTTA` (or any other rate), but that remains an external economic transaction completely decoupled from protocol consensus and provenance verification.

---

## 3. Contribution Lifecycle

Contribution units arise exclusively through the full cryptographic verification pipeline:

```text
          1. REAL-WORLD CAPTURE
                     │
                     ▼
          2. CAPTURE SEAL & MANIFEST (0.4.0)
                     │
                     ▼
          3. TAP CLAIM ASSET PUBLICATION
                     │
                     ▼
          4. INDEPENDENT PROVENANCE VERIFICATION
                     │
                     ▼
          5. CONTRIBUTION QUALIFICATION (P3+)
                     │
                     ▼
          6. DETERMINISTIC ₿DATA ISSUANCE
                     │
                     │  (External Market Valuation)
                     ▼
          7. BTC / SATS SETTLEMENT
```

A raw recording, unsealed file, or self-claimed unverified upload **never** creates valid contribution units.

---

## 4. POC Reference Accounting Policy (`dattapool-base-duration/0.1.0`)

To maintain objectivity and eliminate subjective bias (such as speculative quality scores or AI model suitability), the initial reference accounting policy is strictly duration-based:

* **Policy ID:** `dattapool-base-duration`
* **Policy Version:** `0.1.0`
* **Base Conversion Rate:** `1 ₿DATA = 1 eligible verified second`
* **Minimum Provenance Qualification:** `P3` (Continuous temporal multi-sensor chain & capture seal) or `P4` (Pre-capture Nostr witness + Bitcoin freshness).

### Eligibility Matrix

| Provenance Level | Description | Contribution Eligibility |
|---|---|---|
| **P0** | File SHA-256 integrity only | **0 DTTA (Ineligible)** |
| **P1** | Worker-signed claim | **0 DTTA (Ineligible)** |
| **P2** | Authenticated session delegation | **0 DTTA (Ineligible)** |
| **P3** | Continuous temporal multi-sensor chain (Offline) | **Eligible (1 DTTA / verified second)** |
| **P4** | Externally witnessed pre-capture + Bitcoin anchor | **Eligible (1 DTTA / verified second)** |
| **P5** | Hardware TPM/TEE attested | **Eligible (1 DTTA / verified second)** |

---

## 5. Canonical Contribution Issuance Record

When a capture qualifies, a canonical, auditable `contribution_issuance` record is generated:

```json
{
  "protocol": "dattapool",
  "type": "contribution_issuance",
  "schema_version": "0.4.0",
  "issuance_id": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
  "capture_claim_id": "f5d025e1a498b...01",
  "manifest_hash": "52028d171c1a0c034f01ef3013ee2333a08cc2516ab304186a2fd87d46134c46",
  "contributor_nostr_pubkey": "npub1...",
  "accounting_policy": {
    "id": "dattapool-base-duration",
    "version": "0.1.0"
  },
  "eligible_range": {
    "start_offset_ms": 0,
    "end_offset_ms": 47000
  },
  "verification": {
    "provenance_level": "P4"
  },
  "contribution": {
    "units": 47,
    "unit": "DTTA"
  },
  "issued_at": 1786990000
}
```

---

## 6. Anti-Double-Issuance Protection

To prevent the same verified capture or time range from generating ₿DATA accounting units multiple times:

1. **Deterministic Issuance ID:**
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
2. **Registry Enforcement:**
   Any second issuance attempt against the same contribution identity is rejected immediately with the protocol error:
   ```text
   CONTRIBUTION_ALREADY_ISSUED
   ```

---

## 7. Multi-Party and Partial Attribution Support

The accounting structure natively supports partial contribution ranges via `eligible_range`:
```json
{
  "eligible_range": {
    "start_offset_ms": 0,
    "end_offset_ms": 20000
  }
}
```
This enables future collaborative capture attribution where multiple operators or devices contribute distinct segments to a single robotic session.
