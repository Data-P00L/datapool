package crypto

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"math/big"
	"os"
	"path/filepath"
	"strings"
)

// KeyPair holds private and public key representations (64-char hex).
type KeyPair struct {
	PrivateKeyHex string            `json:"private_key"`
	PublicKeyHex  string            `json:"public_key"`
	PrivateKey    *ecdsa.PrivateKey `json:"-"`
}

// GenerateKeyPair generates a new cryptographic keypair using standard curve.
func GenerateKeyPair() (*KeyPair, error) {
	curve := elliptic.P256()
	priv, err := ecdsa.GenerateKey(curve, rand.Reader)
	if err != nil {
		return nil, fmt.Errorf("failed to generate key: %w", err)
	}

	privBytes := make([]byte, 32)
	priv.D.FillBytes(privBytes)

	pubBytes := make([]byte, 32)
	priv.PublicKey.X.FillBytes(pubBytes)

	return &KeyPair{
		PrivateKeyHex: hex.EncodeToString(privBytes),
		PublicKeyHex:  hex.EncodeToString(pubBytes),
		PrivateKey:    priv,
	}, nil
}

// KeyPairFromHex loads a keypair from a 64-character hex private key.
func KeyPairFromHex(privHex string) (*KeyPair, error) {
	privHex = strings.TrimSpace(privHex)
	privBytes, err := hex.DecodeString(privHex)
	if err != nil {
		return nil, fmt.Errorf("invalid private key hex: %w", err)
	}
	if len(privBytes) != 32 {
		return nil, fmt.Errorf("expected 32-byte private key, got %d bytes", len(privBytes))
	}

	curve := elliptic.P256()
	d := new(big.Int).SetBytes(privBytes)
	x, y := curve.ScalarBaseMult(privBytes)

	priv := &ecdsa.PrivateKey{
		PublicKey: ecdsa.PublicKey{
			Curve: curve,
			X:     x,
			Y:     y,
		},
		D: d,
	}

	pubBytes := make([]byte, 32)
	x.FillBytes(pubBytes)

	return &KeyPair{
		PrivateKeyHex: hex.EncodeToString(privBytes),
		PublicKeyHex:  hex.EncodeToString(pubBytes),
		PrivateKey:    priv,
	}, nil
}

// SignMessage signs an arbitrary message, returning a 128-char hex signature (64-byte r || s).
func (kp *KeyPair) SignMessage(msg []byte) (string, error) {
	if kp.PrivateKey == nil {
		loaded, err := KeyPairFromHex(kp.PrivateKeyHex)
		if err != nil {
			return "", err
		}
		kp.PrivateKey = loaded.PrivateKey
	}

	h := sha256.Sum256(msg)
	r, s, err := ecdsa.Sign(rand.Reader, kp.PrivateKey, h[:])
	if err != nil {
		return "", fmt.Errorf("signing failed: %w", err)
	}

	sigBytes := make([]byte, 64)
	r.FillBytes(sigBytes[:32])
	s.FillBytes(sigBytes[32:])

	return hex.EncodeToString(sigBytes), nil
}

// VerifySignature verifies a 128-char hex signature against a 64-char hex public key and raw message.
func VerifySignature(pubKeyHex string, msg []byte, sigHex string) bool {
	pubBytes, err := hex.DecodeString(strings.TrimSpace(pubKeyHex))
	if err != nil || len(pubBytes) != 32 {
		return false
	}

	sigBytes, err := hex.DecodeString(strings.TrimSpace(sigHex))
	if err != nil || len(sigBytes) != 64 {
		return false
	}

	curve := elliptic.P256()
	px := new(big.Int).SetBytes(pubBytes)

	// Recover Y coordinate on curve: y^2 = x^3 - 3x + b mod p
	// y^2 = x^3 - 3x + b
	x3 := new(big.Int).Mul(px, px)
	x3.Mul(x3, px)
	threeX := new(big.Int).Mul(big.NewInt(3), px)
	x3.Sub(x3, threeX)
	x3.Add(x3, curve.Params().B)
	x3.Mod(x3, curve.Params().P)

	// Square root modulo P
	py := new(big.Int).ModSqrt(x3, curve.Params().P)
	if py == nil {
		return false
	}

	r := new(big.Int).SetBytes(sigBytes[:32])
	s := new(big.Int).SetBytes(sigBytes[32:])

	h := sha256.Sum256(msg)

	// Try positive y
	pub := ecdsa.PublicKey{Curve: curve, X: px, Y: py}
	if ecdsa.Verify(&pub, h[:], r, s) {
		return true
	}

	// Try negated y (P - y)
	negY := new(big.Int).Sub(curve.Params().P, py)
	pubNeg := ecdsa.PublicKey{Curve: curve, X: px, Y: negY}
	return ecdsa.Verify(&pubNeg, h[:], r, s)
}

// SaveKeyToFile saves a private key hex to a file.
func SaveKeyToFile(kp *KeyPair, filePath string) error {
	dir := filepath.Dir(filePath)
	if err := os.MkdirAll(dir, 0700); err != nil {
		return err
	}
	return os.WriteFile(filePath, []byte(kp.PrivateKeyHex+"\n"), 0600)
}

// LoadKeyFromFile loads a KeyPair from a file containing a private key hex string.
func LoadKeyFromFile(filePath string) (*KeyPair, error) {
	data, err := os.ReadFile(filePath)
	if err != nil {
		return nil, fmt.Errorf("failed to read key file %q: %w", filePath, err)
	}
	return KeyPairFromHex(string(data))
}

// LoadOrGenerateKey loads a key from file or creates a new one.
func LoadOrGenerateKey(filePath string) (*KeyPair, error) {
	if _, err := os.Stat(filePath); err == nil {
		return LoadKeyFromFile(filePath)
	}
	kp, err := GenerateKeyPair()
	if err != nil {
		return nil, err
	}
	if err := SaveKeyToFile(kp, filePath); err != nil {
		return nil, err
	}
	return kp, nil
}
