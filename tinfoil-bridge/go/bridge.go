// Package tinfoilbridge wraps the tinfoil-go SDK for Android via gomobile.
//
// Build with:
//
//	gomobile bind -target=android -androidapi=35 -o tinfoil-bridge.aar ./go/
//
// The resulting .aar is consumed by the :app module.
package tinfoilbridge

import (
	"bufio"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync"

	tinfoil "github.com/tinfoilsh/tinfoil-go"

	ehbpClient "github.com/tinfoilsh/encrypted-http-body-protocol/client"
	ehbpIdentity "github.com/tinfoilsh/encrypted-http-body-protocol/identity"
	"golang.org/x/crypto/cryptobyte"
)

const (
	enclaveName = "inference.tinfoil.sh"
	repoName    = "tinfoilsh/confidential-model-router"
	apiBase     = "https://inference.tinfoil.sh/v1"
)

// ── Caching ──────────────────────────────────────────────────────────

// cachedClient holds a verified Tinfoil HTTP client that is reused across
// requests. The attestation check happens once during NewClientWithParams;
// the HTTP client returned by HTTPClient() automatically re-verifies the
// pinned TLS certificate on every connection.
var (
	mu         sync.Mutex
	cachedHTTP *http.Client
)

// getVerifiedHTTPClient returns a Tinfoil-verified HTTP client. It caches the
// client so attestation verification only happens once (or when re-init is needed).
func getVerifiedHTTPClient() (*http.Client, error) {
	mu.Lock()
	defer mu.Unlock()

	if cachedHTTP != nil {
		return cachedHTTP, nil
	}

	client, err := tinfoil.NewClientWithParams(enclaveName, repoName)
	if err != nil {
		return nil, fmt.Errorf("tinfoil client init: %w", err)
	}

	cachedHTTP = client.HTTPClient()
	return cachedHTTP, nil
}

// EHBP transport (cached separately from the TLS-pinned client).
var (
	ehbpMu      sync.Mutex
	cachedEHBP  *ehbpClient.Transport
)

// clearEHBPCache invalidates the cached EHBP transport (e.g. on key rotation).
func clearEHBPCache() {
	ehbpMu.Lock()
	defer ehbpMu.Unlock()
	cachedEHBP = nil
}

// buildOHTTPKeyConfig constructs an RFC 9458 key configuration from a raw
// X25519 HPKE public key (hex-encoded, 32 bytes) as returned in the enclave's
// GroundTruth.HPKEPublicKey.
func buildOHTTPKeyConfig(hpkePublicKeyHex string) ([]byte, error) {
	pk, err := hex.DecodeString(hpkePublicKeyHex)
	if err != nil {
		return nil, fmt.Errorf("decode HPKE public key: %w", err)
	}
	if len(pk) != 32 {
		return nil, fmt.Errorf("unexpected HPKE public key size: %d (expected 32)", len(pk))
	}

	b := cryptobyte.NewBuilder(nil)
	b.AddUint8(0)       // key config ID
	b.AddUint16(0x0020) // DHKEM(X25519, HKDF-SHA256)
	b.AddBytes(pk)      // 32-byte X25519 public key
	b.AddUint16LengthPrefixed(func(b *cryptobyte.Builder) {
		b.AddUint16(0x0001) // HKDF-SHA256
		b.AddUint16(0x0002) // AES-256-GCM
	})

	return b.Bytes()
}

// getEHBPTransport returns a cached EHBP transport that has verified the
// Tinfoil enclave attestation and holds the enclave's HPKE public key.
// Requests encrypted with this transport can only be decrypted inside the
// genuine enclave — the proxy server never sees plaintext.
func getEHBPTransport() (*ehbpClient.Transport, error) {
	ehbpMu.Lock()
	defer ehbpMu.Unlock()

	if cachedEHBP != nil {
		return cachedEHBP, nil
	}

	// Verify enclave attestation (fetches signed runtime measurements,
	// validates certificate chain, checks Sigstore transparency log).
	client, err := tinfoil.NewClientWithParams(enclaveName, repoName)
	if err != nil {
		return nil, fmt.Errorf("attestation failed: %w", err)
	}

	gt, err := client.Verify()
	if err != nil {
		return nil, fmt.Errorf("verify failed: %w", err)
	}

	hpkeKey := gt.HPKEPublicKey
	if hpkeKey == "" {
		return nil, fmt.Errorf("enclave did not provide HPKE public key")
	}

	config, err := buildOHTTPKeyConfig(hpkeKey)
	if err != nil {
		return nil, fmt.Errorf("build key config: %w", err)
	}

	transport, err := ehbpClient.NewTransportWithConfig("", config)
	if err != nil {
		return nil, fmt.Errorf("create EHBP transport: %w", err)
	}

	cachedEHBP = transport
	return transport, nil
}

// ── Callbacks ────────────────────────────────────────────────────────

// StreamCallback receives streaming chunks from Tinfoil.
// OnData is called for each SSE data payload (the raw JSON string after "data: ").
// Return true from OnData to abort the stream early.
type StreamCallback interface {
	OnData(data string) bool
	OnError(err string)
}

// ── Direct (TLS-pinned) requests ─────────────────────────────────────

// VerifiedChatCompletion sends a non-streaming chat completion request
// through Tinfoil's TEE-attested endpoint with full client-side attestation
// verification and TLS certificate pinning. requestJson must be a valid
// OpenAI chat completion request body. Returns the full response JSON.
func VerifiedChatCompletion(requestJson, apiKey string) (string, error) {
	httpClient, err := getVerifiedHTTPClient()
	if err != nil {
		return "", err
	}

	req, err := http.NewRequest("POST", apiBase+"/chat/completions", strings.NewReader(requestJson))
	if err != nil {
		return "", fmt.Errorf("build request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+apiKey)

	resp, err := httpClient.Do(req)
	if err != nil {
		return "", fmt.Errorf("request failed: %w", err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return "", fmt.Errorf("read response: %w", err)
	}

	if resp.StatusCode >= 400 {
		return "", fmt.Errorf("HTTP %d: %s", resp.StatusCode, string(body))
	}

	return string(body), nil
}

// VerifiedChatCompletionStream sends a streaming chat completion request
// through Tinfoil's TEE-attested endpoint with full client-side attestation
// verification and TLS certificate pinning. SSE data chunks are delivered
// to the callback. The function blocks until the stream completes.
func VerifiedChatCompletionStream(requestJson, apiKey string, cb StreamCallback) error {
	httpClient, err := getVerifiedHTTPClient()
	if err != nil {
		return err
	}

	req, err := http.NewRequest("POST", apiBase+"/chat/completions", strings.NewReader(requestJson))
	if err != nil {
		return fmt.Errorf("build request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+apiKey)

	resp, err := httpClient.Do(req)
	if err != nil {
		return fmt.Errorf("request failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 400 {
		body, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("HTTP %d: %s", resp.StatusCode, string(body))
	}

	scanner := bufio.NewScanner(resp.Body)
	for scanner.Scan() {
		line := scanner.Text()
		if !strings.HasPrefix(line, "data: ") {
			continue
		}
		data := strings.TrimPrefix(line, "data: ")
		if data == "[DONE]" {
			cb.OnData("[DONE]")
			break
		}
		if abort := cb.OnData(data); abort {
			break
		}
	}

	if err := scanner.Err(); err != nil {
		cb.OnError(err.Error())
		return err
	}

	return nil
}

// ── EHBP-encrypted proxy requests ────────────────────────────────────

// ProxiedChatCompletion sends an EHBP-encrypted non-streaming chat completion
// request through a proxy server. The proxy adds the Tinfoil API key and
// forwards to the enclave. The HTTP body is end-to-end encrypted between
// this client and the enclave — the proxy sees only metadata headers.
//
// The proxyURL must point to the proxy's chat completions endpoint
// (e.g. "https://api.example.com/api/premium-llm-tinfoil").
func ProxiedChatCompletion(requestJson, proxyURL, userId, signature string) (string, error) {
	for attempt := 0; attempt < 2; attempt++ {
		result, err := doProxiedCompletion(requestJson, proxyURL, userId, signature)
		if err != nil {
			var keyErr *ehbpIdentity.KeyConfigError
			if errors.As(err, &keyErr) && attempt == 0 {
				clearEHBPCache()
				continue
			}
			return "", err
		}
		return result, nil
	}
	return "", fmt.Errorf("max retries exceeded")
}

func doProxiedCompletion(requestJson, proxyURL, userId, signature string) (string, error) {
	transport, err := getEHBPTransport()
	if err != nil {
		return "", err
	}

	httpClient := &http.Client{Transport: transport}

	req, err := http.NewRequest("POST", proxyURL, strings.NewReader(requestJson))
	if err != nil {
		return "", fmt.Errorf("build request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Tinfoil-Enclave-Url", "https://"+enclaveName)
	req.Header.Set("X-User-Id", userId)
	req.Header.Set("X-Signature", signature)
	if model := extractModel(requestJson); model != "" {
		req.Header.Set("X-Model", model)
	}

	resp, err := httpClient.Do(req)
	if err != nil {
		return "", fmt.Errorf("request failed: %w", err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return "", fmt.Errorf("read response: %w", err)
	}

	if resp.StatusCode >= 400 {
		return "", fmt.Errorf("HTTP %d: %s", resp.StatusCode, string(body))
	}

	return string(body), nil
}

// ProxiedChatCompletionStream sends an EHBP-encrypted streaming chat
// completion request through a proxy server. The body is end-to-end encrypted;
// the proxy never sees plaintext prompts or completions. SSE data chunks
// are delivered to the callback after client-side EHBP decryption.
func ProxiedChatCompletionStream(requestJson, proxyURL, userId, signature string, cb StreamCallback) error {
	for attempt := 0; attempt < 2; attempt++ {
		err := doProxiedStream(requestJson, proxyURL, userId, signature, cb)
		if err != nil {
			var keyErr *ehbpIdentity.KeyConfigError
			if errors.As(err, &keyErr) && attempt == 0 {
				clearEHBPCache()
				continue
			}
			return err
		}
		return nil
	}
	return fmt.Errorf("max retries exceeded")
}

func doProxiedStream(requestJson, proxyURL, userId, signature string, cb StreamCallback) error {
	transport, err := getEHBPTransport()
	if err != nil {
		return err
	}

	httpClient := &http.Client{Transport: transport}

	req, err := http.NewRequest("POST", proxyURL, strings.NewReader(requestJson))
	if err != nil {
		return fmt.Errorf("build request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Tinfoil-Enclave-Url", "https://"+enclaveName)
	req.Header.Set("X-User-Id", userId)
	req.Header.Set("X-Signature", signature)
	if model := extractModel(requestJson); model != "" {
		req.Header.Set("X-Model", model)
	}

	resp, err := httpClient.Do(req)
	if err != nil {
		return fmt.Errorf("request failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 400 {
		body, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("HTTP %d: %s", resp.StatusCode, string(body))
	}

	// The EHBP transport has already decrypted the response body.
	// We read plaintext SSE data from it.
	scanner := bufio.NewScanner(resp.Body)
	for scanner.Scan() {
		line := scanner.Text()
		if !strings.HasPrefix(line, "data: ") {
			continue
		}
		data := strings.TrimPrefix(line, "data: ")
		if data == "[DONE]" {
			cb.OnData("[DONE]")
			break
		}
		if abort := cb.OnData(data); abort {
			break
		}
	}

	if err := scanner.Err(); err != nil {
		cb.OnError(err.Error())
		return err
	}

	return nil
}

// extractModel does a best-effort extraction of the "model" field from an
// OpenAI-format JSON request. The model name is sent as a plaintext header
// (X-Model) so the proxy can determine billing rates without decrypting the body.
func extractModel(requestJson string) string {
	idx := strings.Index(requestJson, `"model"`)
	if idx < 0 {
		return ""
	}
	rest := requestJson[idx+7:] // skip past `"model"`
	colonIdx := strings.Index(rest, ":")
	if colonIdx < 0 {
		return ""
	}
	rest = strings.TrimSpace(rest[colonIdx+1:])
	if len(rest) == 0 || rest[0] != '"' {
		return ""
	}
	rest = rest[1:]
	endIdx := strings.Index(rest, `"`)
	if endIdx < 0 {
		return ""
	}
	return rest[:endIdx]
}
