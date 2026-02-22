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
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync"

	tinfoil "github.com/tinfoilsh/tinfoil-go"
)

const (
	enclaveName = "inference.tinfoil.sh"
	repoName    = "tinfoilsh/confidential-model-router"
	apiBase     = "https://inference.tinfoil.sh/v1"
)

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

// StreamCallback receives streaming chunks from Tinfoil.
// OnData is called for each SSE data payload (the raw JSON string after "data: ").
// Return true from OnData to abort the stream early.
type StreamCallback interface {
	OnData(data string) bool
	OnError(err string)
}

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
