# DattaPool

[![License: 0BSD](https://img.shields.io/badge/License-0BSD-blue.svg)](LICENSE)
[![Protocol Version](https://img.shields.io/badge/protocol-v0.4.0-orange.svg)](docs/protocol.md)
[![Go Version](https://img.shields.io/badge/go-1.22+-00ADD8.svg)](go.mod)
[![Android](https://img.shields.io/badge/Android-API%2026%2B-green.svg)](android/capture-client/)

> **DattaPool** is an open protocol and reference implementation for cryptographically attributable robotics and egocentric training data, using Nostr identity, Bitcoin, and Taproot Assets.

---

## What DattaPool Is

DattaPool provides cryptographic provenance, identity attribution, and contribution accounting for real-world robotics and egocentric human-demonstration datasets. By binding video and multi-modal sensor telemetry into temporal cryptographic chains sealed by authenticated hardware and worker identities, DattaPool prevents data theft, retroactive claim manufacturing, stream swapping, and claim jumping.

---

## Architecture

DattaPool coordinates multiple decentralized layers into an integrated provenance pipeline:

| Layer | Component | Function in DattaPool |
|---|---|---|
| **Identity & Coordination** | **Nostr** (NIP-01, NIP-78) | Worker / robot identity, authorization delegations, pre-capture witness events, and dataset availability announcements. |
| **Provenance & Attribution** | **DattaPool Capture Protocol** | Continuous multi-sensor temporal hash chaining, Merkle tree commitments, capture sealing, and independent verification. |
| **Asset & Contribution Layer**| **Taproot Assets (TAP)** | Immutable on-chain claim assets, provenance metadata anchoring, and contribution token accounting. |
| **Anchor & Settlement** | **Bitcoin** | Block freshness anchoring and final economic settlement. |
| **Proof Discovery** | **Taproot Assets Universe** | Public discovery, verification, and distribution of Taproot Asset issuance proofs and metadata. |
| **Contribution Accounting** | **₿DATA (`DTTA`)** | Standardized accounting unit for independently verified robotics-data contribution. |
| **Valuation & Settlement** | **BTC / satoshis** | The monetary unit of account for market valuation and payment settlement. |

### Architectural Invariants & Epistemological Boundaries

* **Capture Claim $\neq$ Proof of Economic Value**: A verified claim proves when, how, and by whom data was captured; it does not dictate its market valuation.
* **Provenance Verification $\neq$ Proof of Unique Human Identity**: Cryptographic verification proves that a session was authorized by a specific Nostr keypair, not the biological uniqueness of the operator.
* **Proof of Possession $\neq$ Proof of Authorship**: Possession challenges prove the prover currently holds raw video/telemetry chunks matching the Merkle root; pre-capture witness events and delegation signatures establish attribution.

---

## Current Status

> [!WARNING]
> **Status: Proof of Concept / Minimum Viable Product (POC / MVP)**
> DattaPool is currently experimental research software. Taproot Assets, Lightning Network daemon (LND), and Bitcoin-related components are designed for local regtest development and testnet exploration. They should not be assumed production-ready or used with real mainnet funds without independent security audits.

---

## Core Components

```text
├── android/capture-client/   Native Android capture client (Kotlin, Jetpack Compose, CameraX)
├── cmd/                      Go CLI utilities and daemons
│   ├── capture/              Capture session manager, keygen, and possession challenge tools
│   ├── contribution/         Contribution duration calculator and issuance engine
│   ├── dattapool/            Unified DattaPool CLI management tool
│   ├── freshness_service/    HTTP freshness and witness bridge for local/field devices
│   ├── mint_data_token/      Taproot Asset minting utility for verified claims
│   ├── publish_claim/        TAP asset publisher and Universe proof synchronizer
│   └── verify_claim/         Multi-layer cryptographic verifier
├── fixtures/                 Deterministic protocol test fixtures and synthetic test media
├── internal/                 Internal Go packages (crypto, capture, nostr, verifier, taproot)
├── pkg/                      Public Go libraries (protocol models, canonical JSON, metadata)
└── scripts/                  Automated test suites and end-to-end demo flows
```

---

## Protocol Model

DattaPool is currently implemented against **Schema Version `0.4.0`**.

### The Two-Tier Delegation Hierarchy

1. **Worker-to-Device Authorization (`WorkerDeviceAuthorization`)**: The contributor signs an authorization binding the hardware device pubkey to the worker Nostr identity.
2. **Device-to-Session Authorization (`DeviceSessionAuthorization`)**: The hardware device authorizes an ephemeral, session-specific keypair and commits to a one-time session nonce.

### Provenance Levels (P0–P5)

| Level | Name | Evidence Required | Guarantees | Contribution Eligibility |
|---|---|---|---|---|
| **P0** | File Hash | SHA-256 hash | File integrity only | Ineligible (0 DTTA) |
| **P1** | Signed Claim | Worker Nostr signature over hash | Attribution to worker identity | Ineligible (0 DTTA) |
| **P2** | Authenticated Session | Two-tier delegation: Worker $\to$ Device $\to$ Session | Hardware binding and delegation | Ineligible (0 DTTA) |
| **P3** | Continuous Temporal | P2 + Continuous multi-sensor hash chain + Merkle roots + Capture Seal | Sequence ordering and sensor binding for **offline captures** | **Eligible (1 DTTA / sec)** |
| **P4** | Witnessed Pre-Capture | P3 + Pre-capture Nostr witness event + Bitcoin block freshness | **Full Anti-Claim-Jumping protection** | **Eligible (1 DTTA / sec)** |
| **P5** | Hardware Attested | P4 + Secure Enclave / TPM hardware attestation | Tamper-proof hardware execution | **Eligible** |

### Continuous Temporal Hash Chaining

Video and sensor telemetry are sliced into synchronized temporal chunks. Each chunk is hashed and bound sequentially using a continuous cryptographic chain:

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

The final hash $H_n$ forms the `capture_chain_root`, which is committed inside the signed `CaptureSeal`.

---

## ₿DATA / DTTA

> **₿DATA is DattaPool's standardized accounting unit for independently verified robotics-data contribution. It is not DattaPool's monetary unit of account and carries no protocol-guaranteed BTC redemption value. Economic valuation and settlement are denominated in Bitcoin sats.**

* **Contribution Accounting (`₿DATA` / `DTTA`)**: Standardized measurement of verified data contribution (under policy `dattapool-base-duration/0.1.0`, 1 ₿DATA = 1 qualifying second of verified P3+ robotics telemetry). Supply is derived dynamically from verified captures, not fixed.
* **Economic Valuation (`BTC` / `sats`)**: Determined by market buyers and contributors. Settled directly in satoshis with zero protocol-guaranteed peg or treasury redemption promises.

---

## Quick Start

### Prerequisites
* **Docker & Docker Compose** (for bitcoind, lnd, and tapd stack)
* **Go 1.22+** (or run via the provided Dockerized tooling)
* **Python 3.10+** (for utility verification scripts)
* **Java 17+ / Android SDK** (for the Android client)

### Clone & Configure
```bash
git clone https://github.com/Data-P00L/datapool.git
cd datapool
cp .env.example .env
```

---

## Docker Development Stack

DattaPool includes a local Docker Compose development stack providing Bitcoin regtest, LND, and Taproot Assets:

```bash
docker compose up -d
```

Services:
* **`bitcoind`**: Bitcoin Core 26.0 in `-regtest` mode (RPC: `18443`, ZMQ: `28332`/`28333`).
* **`lnd`**: Lightning Network Daemon v0.19.0-beta (gRPC: `10009`, REST: `8081`).
* **`tapd`**: Taproot Assets Daemon v0.7.2 (gRPC: `10029`, REST: `8089`).

> [!NOTE]
> All credentials in `docker-compose.yml` (`rpcuser=admin`, `rpcpassword=password`) are **DEVELOPMENT ONLY** deterministic placeholders for regtest.

---

## Android Capture Client

The Android client (`org.dattapool.capture`) turns an Android smartphone or wearable rig into an authenticated egocentric robotics recording station.

### Features
* **CameraX Recording**: Captures 1080p 30fps MP4 video streams.
* **IMU Telemetry Collection**: High-frequency accelerometer and gyroscope logging (~50Hz) aligned with video frame indices.
* **Nostr Identity**: In-app Secp256k1 key generation and pre-capture NIP-01 Kind 30078 event signing.
* **Dual Capture Modes**:
  * **P3 Mode**: Fully offline capture with local session keys and temporal hash chaining.
  * **P4 Mode**: Witnessed mode communicating with the local freshness service bridge (`freshness_service`) over reverse ADB.
* **YouTube Discovery & Publication**: Optional export and upload of sealed captures to YouTube using OAuth 2.0 (`youtube.upload` scope) with automatic `--- DATTAPOOL ---` metadata description injection.

### Building & Installing the Android Client
```bash
cd android/capture-client
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew assembleDebug
```

Install via ADB:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For detailed YouTube OAuth configuration and permissions, see [docs/youtube-publication.md](docs/youtube-publication.md).

---

## Running the Demo

Run the automated end-to-end demo flow:

```bash
./scripts/demo_flow.sh
```

This script:
1. Bootstraps local Bitcoin regtest, LND, and Taproot Assets daemons.
2. Generates Nostr worker and device identities.
3. Performs a witnessed pre-capture session with Bitcoin block freshness anchoring.
4. Processes video and telemetry into an authenticated `capture-manifest.json` (v0.4.0).
5. Mints a `CLAIM` Taproot Asset committed to the manifest hash.
6. Verifies the claim independently $\to$ `RESULT: VERIFIED (PROVENANCE LEVEL: P4)`.
7. Issues canonical ₿DATA contribution accounting records.
8. Demonstrates an interactive proof-of-possession challenge.

---

## Running Tests

### 1. Go Unit Tests
Run the cryptographic, protocol, Nostr, and verifier test suites:

```bash
docker run --rm -v "$PWD":/app -w /app golang:1.22 go test -v ./pkg/... ./internal/...
```

### 2. Negative & Tamper Tests
Verify that the independent verifier rejects tampered worker signatures, forged device authorizations, corrupted video payloads, altered telemetry streams, and forged capture chains:

```bash
./scripts/test_negative_cases.sh
```

### 3. Adversarial Attack Tests
Run the comprehensive adversarial suite verifying resilience against stolen video re-registration, fake timestamps, telemetry stream swapping, chunk truncation, replayed challenge nonces, and double contribution issuance:

```bash
./scripts/test_adversarial_cases.sh
```

### 4. Android Unit Tests
Run unit tests for Canonical JSON, compensation verification, and YouTube metadata builders:

```bash
cd android/capture-client
./gradlew test
```

---

## Security / Threat Model

DattaPool assumes an adversarial model where capture data may be intercepted, copied, or re-submitted by untrusted intermediaries:

* **Claim-Jumping Resistance**: An attacker with access to raw video cannot manufacture a valid P4 claim because they lack the pre-capture Nostr witness event published before recording started.
* **Sensor-Stream Binding**: Swapping telemetry from a different capture session produces a Merkle root mismatch and breaks the continuous temporal hash chain.
* **Zero Treasury Peg**: ₿DATA is strictly an accounting unit; it cannot be redeemed for protocol-managed funds, eliminating treasury-drain attack vectors.

For complete threat analysis and trust boundaries, see [docs/threat-model.md](docs/threat-model.md).

---

## Repository Structure

```text
├── LICENSE                           0BSD Open-Source License
├── README.md                         Project documentation and architectural guide
├── CONTRIBUTING.md                   Contribution and development guidelines
├── SECURITY.md                       Vulnerability reporting policy
├── CODE_OF_CONDUCT.md                Contributor Covenant Code of Conduct
├── .env.example                      Environment configuration template
├── .gitignore                        Security and artifact exclusion rules
├── docker-compose.yml                Local Bitcoin regtest & Taproot Assets stack
├── go.mod                            Go module definition (Go 1.22)
├── android/capture-client/           Native Android capture client
├── cmd/                              CLI utilities and service binaries
├── docs/                             Technical specifications and documentation
│   ├── contribution-accounting.md    ₿DATA accounting model
│   ├── nostr-events.md               Nostr event specifications (Kind 30078)
│   ├── protocol.md                   Core protocol specification (v0.4.0)
│   ├── provenance-levels.md          P0–P5 provenance matrix
│   ├── threat-model.md               Security analysis and attack vectors
│   └── youtube-publication.md        YouTube external discovery layer
├── fixtures/                         Deterministic protocol fixtures & synthetic media
├── internal/                         Cryptographic and verifier core
├── pkg/                              Protocol schemas and public APIs
├── scripts/                          Automated test and demo scripts
└── verify_nostr.py                   Nostr availability verification utility
```

---

## Contributing

We welcome community contributions, bug reports, and pull requests! Please review [CONTRIBUTING.md](CONTRIBUTING.md) for details on code style, testing requirements, and submission processes.

---

## License

This project is licensed under the **Zero-Clause BSD (0BSD)** license. See the [LICENSE](LICENSE) file for details.

```text
SPDX-License-Identifier: 0BSD
```
