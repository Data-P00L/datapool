# Security Policy

## Experimental Status

DattaPool is currently a proof-of-concept (POC) and minimum viable product (MVP) exploring cryptographically attributable robotics data, Nostr identity, Bitcoin, and Taproot Assets.

Components relating to Taproot Assets, Lightning daemon integration, and Bitcoin anchoring are experimental. Do not deploy this code in production financial environments or with real mainnet funds without independent review and hardening.

---

## Reporting a Vulnerability

We appreciate responsible disclosure of security vulnerabilities.

Please **do not** open a public issue for security bugs.

Instead:
1. Open a **GitHub Security Advisory** directly on this repository via the **Security** tab (`Report a vulnerability`).
2. Include a detailed description of the vulnerability, affected components, steps to reproduce, and a proof of concept (PoC) if available.
3. If GitHub Private Vulnerability Reporting is unavailable for any reason, reach out securely to the repository maintainers via an authorized contact method specified in the GitHub repository profile.

---

## Scope

Security reviews are particularly focused on:
- Cryptographic verification bypasses in `internal/verifier/` and `android/capture-client/`
- Manifest tampering, stream swapping, and replay vulnerabilities
- Proof-of-possession challenge evasion
- Double contribution issuance prevention in the contribution engine
- Secret leakage or inadvertent key exposure in clients
