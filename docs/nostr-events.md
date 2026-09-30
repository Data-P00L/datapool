# DattaPool Nostr Witness Event Specification

DattaPool uses Nostr (NIP-01) as a decentralized, censorship-resistant coordination and pre-capture witnessing layer.

---

## 1. Event Definitions

### Event Kind
* **Kind**: `30078` (Parameterized Replaceable / Application Specific Data - Experimental)

### Event Structure (NIP-01)
```json
{
  "id": "<64-hex SHA256 of serialized event>",
  "pubkey": "<64-hex worker nostr public key>",
  "created_at": 1786990000,
  "kind": 30078,
  "tags": [
    ["d", "<session_id>"],
    ["protocol", "dattapool"],
    ["version", "0.4.0"],
    ["device", "<device_pubkey>"],
    ["session", "<session_pubkey>"],
    ["commitment", "<nonce_commitment>"],
    ["freshness_type", "bitcoin_block"],
    ["block_height", "152"],
    ["block_hash", "<bitcoin_block_hash>"],
    ["skill", "warehouse_object_pick"]
  ],
  "content": "{\"device_pubkey\":\"...\",\"event_type\":\"capture_session_start\",\"freshness\":{\"block_hash\":\"...\",\"block_height\":152,\"type\":\"bitcoin_block\"},\"nonce_commitment\":\"...\",\"protocol\":\"dattapool\",\"schema_version\":\"0.4.0\",\"session_id\":\"...\",\"session_pubkey\":\"...\",\"skill_tag\":\"warehouse_object_pick\"}",
  "sig": "<128-hex signature from worker nostr key>"
}
```

---

## 2. Field Semantics

| Field | Description |
|---|---|
| `id` | SHA-256 hash over canonical NIP-01 serialization `[0, pubkey, created_at, kind, tags, content]`. |
| `pubkey` | 32-byte hex Nostr identity of the authorizing worker. |
| `tags["d"]` | Parameterized identifier set to the unique `session_id`. |
| `tags["commitment"]` | Domain-separated commitment $\operatorname{SHA256}(\text{DATTA\_NONCE\_COMMITMENT\_V1} \parallel \text{session\_id} \parallel \text{nonce})$. |
| `tags["freshness_type"]` | `"bitcoin_block"` or `"local_only"`. |
| `content` | Canonical JSON string containing non-sensitive session metadata. **No raw video or telemetry.** |
| `sig` | 64-byte signature ($R \parallel S$) proving worker authorization. |

---

## 3. Privacy & Security Considerations

1. **No Data Leakage**: The pre-capture witness event contains only mathematical commitments (`nonce_commitment`, session public keys, freshness block). It never exposes raw video frames, telemetry trajectories, or the uncommitted raw capture nonce.
2. **Relay Tolerance**: If external Nostr relays are unreachable during capture start, the Capture Client stores the event locally and completes the capture in **Offline Mode (`P3`)**.
3. **Replay Defenses**: The `d` tag binds the unique `session_id`. Modifying any tag or content payload breaks the event `id` and `sig`.
