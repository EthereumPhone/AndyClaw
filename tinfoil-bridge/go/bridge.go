// Package tinfoilbridge wraps the tinfoil-go SDK for Android via gomobile.
//
// Build with:
//   gomobile bind -target=android -androidapi=35 -o tinfoil-bridge.aar ./go/
//
// The resulting .aar is consumed by the :app module.
package tinfoilbridge

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"

	"github.com/tinfoilsh/tinfoil-go"
)

const (
	tinfoilAPIBase = "https://api.tinfoil.sh/v1"
	defaultModel   = "tinfoil/kimi-k2.5"
)

// StreamCallback receives streaming chunks from Tinfoil.
// OnData is called for each SSE data payload (the raw JSON string after "data: ").
// Return true from OnData to abort the stream early.
type StreamCallback interface {
	OnData(data string) bool
	OnError(err string)
}

// VerifiedChatCompletion sends a non-streaming chat completion request
// through Tinfoil's TEE-attested endpoint. requestJson must be a valid
// OpenAI chat completion request body. Returns the full response JSON.
func VerifiedChatCompletion(requestJson, apiKey string) (string, error) {
	client, err := tinfoil.NewClient(tinfoilAPIBase, apiKey)
	if err != nil {
		return "", fmt.Errorf("tinfoil client init: %w", err)
	}

	req, err := http.NewRequest("POST", tinfoilAPIBase+"/chat/completions", strings.NewReader(requestJson))
	if err != nil {
		return "", fmt.Errorf("build request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+apiKey)

	resp, err := client.Do(req)
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
// through Tinfoil's TEE-attested endpoint. SSE data chunks are delivered
// to the callback. The function blocks until the stream completes.
func VerifiedChatCompletionStream(requestJson, apiKey string, cb StreamCallback) error {
	// Ensure stream=true in the request
	var reqMap map[string]interface{}
	if err := json.Unmarshal([]byte(requestJson), &reqMap); err != nil {
		return fmt.Errorf("parse request: %w", err)
	}
	reqMap["stream"] = true
	modifiedReq, _ := json.Marshal(reqMap)

	client, err := tinfoil.NewClient(tinfoilAPIBase, apiKey)
	if err != nil {
		return fmt.Errorf("tinfoil client init: %w", err)
	}

	ctx := context.Background()
	req, err := http.NewRequestWithContext(ctx, "POST", tinfoilAPIBase+"/chat/completions", strings.NewReader(string(modifiedReq)))
	if err != nil {
		return fmt.Errorf("build request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+apiKey)

	resp, err := client.Do(req)
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
