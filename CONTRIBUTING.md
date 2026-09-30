# Contributing to DattaPool

Thank you for your interest in contributing to DattaPool! DattaPool is an open-source protocol and reference implementation for cryptographically attributable robotics and egocentric training data.

The project is licensed under the permissive **0BSD** license (see [LICENSE](LICENSE)). You are free to fork, modify, experiment, and integrate DattaPool into your projects.

---

## Getting Started

1. **Fork the Repository**:
   Fork the repository on GitHub to your personal or organization account.

2. **Clone your Fork**:
   ```bash
   git clone https://github.com/<your-username>/datapool.git
   cd datapool
   ```

3. **Create a Feature Branch**:
   ```bash
   git checkout -b feature/your-feature-name
   ```

---

## Development & Testing Workflow

### Go Verification Engine & CLI Tools
The reference protocol engine, verifier, and CLI tools are implemented in Go 1.22+.

- **Run Go Unit Tests**:
  ```bash
  # Native:
  go test -v ./...

  # Or via Docker:
  docker run --rm -v "$PWD":/app -w /app golang:1.22 go test -v ./...
  ```

- **Run Negative & Tamper Test Suite**:
  ```bash
  ./scripts/test_negative_cases.sh
  ```

- **Run Adversarial Test Suite**:
  ```bash
  ./scripts/test_adversarial_cases.sh
  ```

- **Code Formatting & Vetting**:
  ```bash
  gofmt -w .
  go vet ./...
  ```

### Android Capture Client
The Android reference capture client is located under `android/capture-client`.

- **Build APK**:
  ```bash
  cd android/capture-client
  ./gradlew assembleDebug
  ```

- **Run Android Tests**:
  ```bash
  cd android/capture-client
  ./gradlew test
  ```

---

## Coding Conventions

- **Clean and Idiomatic**: Follow standard Go (`go fmt`, `effective go`) and Kotlin idioms.
- **No Unsanitized Secrets**: Never commit real API keys, OAuth client secrets, private keys (`nsec`, ECDSA private keys), wallet seed phrases, or machine credentials.
- **Deterministic Serialization**: When adding or modifying protocol structures, ensure RFC-8785 canonical JSON formatting is preserved so hashes remain cross-language deterministic between Go and Kotlin.

---

## Protocol & Schema Change Expectations

DattaPool defines cryptographic provenance and attribution schemas (currently version `0.4.0`).
- **Backward Compatibility**: Any change to `pkg/protocol/` or `ProtocolModels.kt` that alters manifest structure, hash calculation, or seal signatures affects independent verifiers.
- **Proposals & RFCs**: For breaking schema modifications, please open an issue or discussion proposing the change before submitting a pull request.
- **Fixture Updates**: Any schema evolution must include updated canonical test fixtures under `fixtures/protocol-vX.Y.Z/`.

---

## Security-Sensitive Changes

If your contribution modifies cryptographic primitives, signature verification, session key authorization, or token minting logic:
- Always include corresponding adversarial test cases in `scripts/test_adversarial_cases.sh` or unit tests.
- Verify that tamper detection correctly rejects invalid inputs with specific error codes.

---

## Submitting Pull Requests

1. Commit your changes with clear, descriptive commit messages.
2. Push your feature branch to your GitHub fork:
   ```bash
   git push origin feature/your-feature-name
   ```
3. Open a Pull Request against the `main` branch.
4. Provide a clear summary of what your PR changes, why it is needed, and test evidence.

**Note**: DattaPool does not require copyright assignment. All contributions are licensed under the project's 0BSD license.
