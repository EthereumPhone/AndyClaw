# Tinfoil Encrypted Proxy: Implementation Guide

## Overview

This document describes how to build a proxy server that sits between your end-user clients and the [Tinfoil](https://tinfoil.sh) private inference API. The proxy lets you:

- **Keep your Tinfoil API key server-side** (never expose it to clients)
- **Authenticate your own users** before forwarding requests
- **Count input/output tokens** for billing — without breaking end-to-end encryption
- **Rate-limit and log metadata** as needed

Privacy is preserved through the **Encrypted HTTP Body Protocol (EHBP)**, which encrypts HTTP bodies at the application layer using HPKE (RFC 9180). Your proxy only ever sees encrypted blobs for request/response content, plus plaintext headers for routing and metering.

---

## Architecture

```
┌─────────────┐         ┌──────────────┐         ┌─────────────────────┐
│  End-User   │  HTTPS  │  Your Proxy  │  HTTPS  │  Tinfoil Enclave    │
│  (Browser / │────────>│  (Go server) │────────>│  (Secure Hardware)  │
│   App)      │         │              │         │                     │
│             │<────────│              │<────────│                     │
└─────────────┘         └──────────────┘         └─────────────────────┘
     │                        │                          │
     │  Bodies: ENCRYPTED     │  Bodies: ENCRYPTED       │  Bodies: DECRYPTED
     │  Headers: PLAINTEXT    │  Headers: PLAINTEXT      │  (inside enclave)
```

The client SDK (JavaScript `SecureClient`) performs attestation verification and HPKE encryption/decryption. Your proxy forwards opaque payloads and reads metadata headers.

---

## Prerequisites

- Go 1.21+
- A Tinfoil API key — generate one at [docs.tinfoil.sh/get-api-key](https://docs.tinfoil.sh/get-api-key)
- For clients: the `tinfoil` npm package (`npm install tinfoil`)

---

## Part 1: Proxy Server (Go)

### Project Setup

```bash
mkdir tinfoil-proxy && cd tinfoil-proxy
go mod init your-org/tinfoil-proxy
```

### Required Endpoints

| Path                     | Method | Description                                                  |
| ------------------------ | ------ | ------------------------------------------------------------ |
| `/attestation`           | GET    | Proxy attestation bundles from `https://atc.tinfoil.sh/attestation` |
| `/v1/chat/completions`   | POST   | Forward encrypted inference requests to the enclave          |
| `/v1/responses`          | POST   | Forward encrypted inference requests (responses API)         |

### Required EHBP Headers

These headers coordinate the encryption between client and enclave. **Your proxy MUST forward them unchanged.**

| Direction | Header                    | Purpose                                           |
| --------- | ------------------------- | ------------------------------------------------- |
| Request → | `Ehbp-Encapsulated-Key`   | HPKE encapsulated key (hex, 64 chars)             |
| Request → | `X-Tinfoil-Enclave-Url`   | The verified enclave URL — use as upstream target  |
| ← Response | `Ehbp-Response-Nonce`     | 32-byte nonce for response decryption (hex, 64 chars) |

### Full Implementation

```go
package main

import (
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

// ──────────────────────────────────────────────
// Configuration
// ──────────────────────────────────────────────

var (
	listenAddr = envOrDefault("LISTEN_ADDR", ":8080")
	apiKey     = os.Getenv("TINFOIL_API_KEY")

	ehbpRequestHeaders  = []string{"Ehbp-Encapsulated-Key"}
	ehbpResponseHeaders = []string{"Ehbp-Response-Nonce"}
)

func envOrDefault(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

// ──────────────────────────────────────────────
// Usage tracking (in-memory, replace with DB)
// ──────────────────────────────────────────────

type UsageRecord struct {
	UserID           string
	Timestamp        time.Time
	Model            string
	PromptTokens     int
	CompletionTokens int
	TotalTokens      int
}

type UsageStore struct {
	mu      sync.Mutex
	records []UsageRecord
}

var store = &UsageStore{}

func (s *UsageStore) Add(r UsageRecord) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.records = append(s.records, r)
	log.Printf("[USAGE] user=%s model=%s prompt=%d completion=%d total=%d",
		r.UserID, r.Model, r.PromptTokens, r.CompletionTokens, r.TotalTokens)
}

// parseUsageMetrics parses "prompt=67,completion=42,total=109"
func parseUsageMetrics(raw string) (prompt, completion, total int, err error) {
	parts := strings.Split(raw, ",")
	for _, part := range parts {
		kv := strings.SplitN(strings.TrimSpace(part), "=", 2)
		if len(kv) != 2 {
			continue
		}
		val, parseErr := strconv.Atoi(kv[1])
		if parseErr != nil {
			continue
		}
		switch kv[0] {
		case "prompt":
			prompt = val
		case "completion":
			completion = val
		case "total":
			total = val
		}
	}
	if prompt == 0 && completion == 0 && total == 0 {
		return 0, 0, 0, fmt.Errorf("no usage metrics found in: %s", raw)
	}
	return prompt, completion, total, nil
}

// ──────────────────────────────────────────────
// Handlers
// ──────────────────────────────────────────────

func main() {
	if apiKey == "" {
		log.Fatal("TINFOIL_API_KEY environment variable is required")
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/attestation", attestationHandler)
	mux.HandleFunc("/v1/chat/completions", proxyHandler)
	mux.HandleFunc("/v1/responses", proxyHandler)

	// Optional: health check
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte("ok"))
	})

	log.Printf("Tinfoil proxy listening on %s", listenAddr)
	log.Fatal(http.ListenAndServe(listenAddr, mux))
}

// attestationHandler proxies the attestation bundle so the client
// can verify the enclave without connecting to Tinfoil directly.
func attestationHandler(w http.ResponseWriter, r *http.Request) {
	setCORS(w, "GET, OPTIONS")
	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusNoContent)
		return
	}

	resp, err := http.Get("https://atc.tinfoil.sh/attestation")
	if err != nil {
		http.Error(w, "Failed to fetch attestation bundle", http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()

	if ct := resp.Header.Get("Content-Type"); ct != "" {
		w.Header().Set("Content-Type", ct)
	}
	w.WriteHeader(resp.StatusCode)
	io.Copy(w, resp.Body)
}

// proxyHandler forwards encrypted requests to the Tinfoil enclave,
// injects the API key, requests usage metrics, and records them.
func proxyHandler(w http.ResponseWriter, r *http.Request) {
	setCORS(w, "POST, OPTIONS")
	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusNoContent)
		return
	}

	// ── 1. Authenticate your user ──────────────────────
	// Replace this with your actual auth logic.
	userID := r.Header.Get("X-User-ID")
	if userID == "" {
		userID = "anonymous"
	}

	// ── 2. Determine the upstream enclave URL ──────────
	upstreamBase := r.Header.Get("X-Tinfoil-Enclave-Url")
	if upstreamBase == "" {
		http.Error(w, "X-Tinfoil-Enclave-Url header required", http.StatusBadRequest)
		return
	}
	upstreamURL := upstreamBase + r.URL.Path

	// ── 3. Build the upstream request ──────────────────
	req, err := http.NewRequestWithContext(r.Context(), http.MethodPost, upstreamURL, r.Body)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	req.Header.Set("Content-Type", "application/json")
	if accept := r.Header.Get("Accept"); accept != "" {
		req.Header.Set("Accept", accept)
	}

	// Inject YOUR Tinfoil API key (never exposed to the client)
	req.Header.Set("Authorization", "Bearer "+apiKey)

	// Forward EHBP encryption headers unchanged
	copyHeaders(req.Header, r.Header, ehbpRequestHeaders...)

	// ── 4. Request usage metrics from the enclave ──────
	req.Header.Set("X-Tinfoil-Request-Usage-Metrics", "true")

	// ── 5. Forward the request ─────────────────────────
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()

	// ── 6. Copy EHBP response headers ──────────────────
	copyHeaders(w.Header(), resp.Header, ehbpResponseHeaders...)

	if ct := resp.Header.Get("Content-Type"); ct != "" {
		w.Header().Set("Content-Type", ct)
	}

	// Handle chunked transfer for streaming
	if te := resp.Header.Get("Transfer-Encoding"); te != "" {
		w.Header().Set("Transfer-Encoding", te)
		w.Header().Del("Content-Length")
	}

	// ── 7. Check for non-streaming usage metrics ───────
	if usage := resp.Header.Get("X-Tinfoil-Usage-Metrics"); usage != "" {
		prompt, completion, total, parseErr := parseUsageMetrics(usage)
		if parseErr == nil {
			store.Add(UsageRecord{
				UserID:           userID,
				Timestamp:        time.Now(),
				PromptTokens:     prompt,
				CompletionTokens: completion,
				TotalTokens:      total,
			})
		}
	}

	w.WriteHeader(resp.StatusCode)

	// ── 8. Stream the response body ────────────────────
	if flusher, ok := w.(http.Flusher); ok {
		buf := make([]byte, 1024)
		for {
			n, readErr := resp.Body.Read(buf)
			if n > 0 {
				w.Write(buf[:n])
				flusher.Flush()
			}
			if readErr != nil {
				break
			}
		}
	} else {
		io.Copy(w, resp.Body)
	}

	// ── 9. Check for streaming usage (HTTP trailers) ───
	if usage := resp.Trailer.Get("X-Tinfoil-Usage-Metrics"); usage != "" {
		prompt, completion, total, parseErr := parseUsageMetrics(usage)
		if parseErr == nil {
			store.Add(UsageRecord{
				UserID:           userID,
				Timestamp:        time.Now(),
				PromptTokens:     prompt,
				CompletionTokens: completion,
				TotalTokens:      total,
			})
		}
	}
}

// ──────────────────────────────────────────────
// Helpers
// ──────────────────────────────────────────────

func setCORS(w http.ResponseWriter, methods string) {
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Access-Control-Allow-Methods", methods)
	w.Header().Set("Access-Control-Allow-Headers",
		"Accept, Authorization, Content-Type, Ehbp-Encapsulated-Key, X-Tinfoil-Enclave-Url, X-User-ID")
	w.Header().Set("Access-Control-Expose-Headers", "Ehbp-Response-Nonce")
}

func copyHeaders(dst, src http.Header, keys ...string) {
	for _, key := range keys {
		if value := src.Get(key); value != "" {
			dst.Set(key, value)
		}
	}
}
```

### Running the Proxy

```bash
export TINFOIL_API_KEY="tk_your_key_here"
go run main.go
```

---

## Part 2: Client Setup (JavaScript / TypeScript)

The Tinfoil JavaScript SDK's `SecureClient` handles attestation verification and EHBP encryption automatically.

### Install

```bash
npm install tinfoil
```

### Usage

```typescript
import { SecureClient } from "tinfoil";

// Point at YOUR proxy, not Tinfoil directly
const client = new SecureClient({
  baseURL: "https://your-proxy.example.com/",
  attestationBundleURL: "https://your-proxy.example.com",
});

// Wait for attestation + key exchange
await client.ready();

// Make a request — body is encrypted end-to-end to the enclave
const response = await client.fetch("/v1/chat/completions", {
  method: "POST",
  headers: {
    "Content-Type": "application/json",
    "Accept": "text/event-stream",       // for streaming
    "X-User-ID": "user-123",             // your custom auth header
  },
  body: JSON.stringify({
    model: "llama3-3-70b",
    messages: [{ role: "user", content: "Hello!" }],
    stream: true,
  }),
});
```

The SDK will:
1. Fetch the attestation bundle via your proxy (`GET /attestation`)
2. Verify the enclave is genuine (client-side, using Sigstore + hardware attestation)
3. Encrypt the request body with the enclave's HPKE public key
4. Send the encrypted payload through your proxy
5. Decrypt the response body client-side

Your proxy never sees plaintext content.

---

## Part 3: Token Counting & Billing

### How It Works

Since EHBP encrypts all request/response bodies, your proxy **cannot** read token counts from the JSON payload. Instead, Tinfoil exposes usage metrics as HTTP headers.

### The Flow

```
Your Proxy                                Tinfoil Enclave
    │                                           │
    │  X-Tinfoil-Request-Usage-Metrics: true    │
    │  (encrypted body)                         │
    │──────────────────────────────────────────> │
    │                                           │
    │  X-Tinfoil-Usage-Metrics:                 │
    │    prompt=67,completion=42,total=109       │
    │  (encrypted body)                         │
    │ <──────────────────────────────────────────│
    │                                           │
    │  [parse header, log to billing DB]        │
```

### Non-Streaming Responses

Token counts arrive as a standard HTTP response header:

```go
if usage := resp.Header.Get("X-Tinfoil-Usage-Metrics"); usage != "" {
    // usage = "prompt=67,completion=42,total=109"
    prompt, completion, total, err := parseUsageMetrics(usage)
    // ... store for billing
}
```

### Streaming Responses (SSE)

For streaming responses, the usage header arrives as an **HTTP trailer** — it's sent after the response body completes:

```go
// First, consume the entire response body (stream it to the client)
io.Copy(w, resp.Body)

// THEN read the trailer
if usage := resp.Trailer.Get("X-Tinfoil-Usage-Metrics"); usage != "" {
    prompt, completion, total, err := parseUsageMetrics(usage)
    // ... store for billing
}
```

### Parsing the Metrics

The format is always: `prompt=<int>,completion=<int>,total=<int>`

```go
func parseUsageMetrics(raw string) (prompt, completion, total int, err error) {
    parts := strings.Split(raw, ",")
    for _, part := range parts {
        kv := strings.SplitN(strings.TrimSpace(part), "=", 2)
        if len(kv) != 2 {
            continue
        }
        val, _ := strconv.Atoi(kv[1])
        switch kv[0] {
        case "prompt":
            prompt = val
        case "completion":
            completion = val
        case "total":
            total = val
        }
    }
    return
}
```

---

## Part 4: Production Considerations

### Persisting Usage Data

The in-memory `UsageStore` in the example above is for illustration. In production, write usage records to a database:

```go
// Example: insert into PostgreSQL
func (s *PgStore) Add(r UsageRecord) error {
    _, err := s.db.Exec(
        `INSERT INTO usage_log (user_id, timestamp, model, prompt_tokens, completion_tokens, total_tokens)
         VALUES ($1, $2, $3, $4, $5, $6)`,
        r.UserID, r.Timestamp, r.Model, r.PromptTokens, r.CompletionTokens, r.TotalTokens,
    )
    return err
}
```

### Authentication

Replace the `X-User-ID` header with your real auth mechanism:

```go
// Example: validate a JWT or session token
token := r.Header.Get("Authorization")
userID, err := validateToken(token)
if err != nil {
    http.Error(w, "Unauthorized", http.StatusUnauthorized)
    return
}
```

### Rate Limiting

Your proxy has full visibility into headers, so you can rate-limit per user:

```go
if !rateLimiter.Allow(userID) {
    w.Header().Set("X-Rate-Limit-Remaining", "0")
    http.Error(w, "Rate limit exceeded", http.StatusTooManyRequests)
    return
}
```

### CORS for Browser Clients

The CORS headers must expose the EHBP headers in both directions:

```
Access-Control-Allow-Headers:  Ehbp-Encapsulated-Key, X-Tinfoil-Enclave-Url, ...
Access-Control-Expose-Headers: Ehbp-Response-Nonce
```

### HTTPS in Production

While the request/response **bodies** are always encrypted by EHBP regardless of transport, the **headers** (including your custom auth headers and usage metrics) are only protected by TLS. Always deploy your proxy behind HTTPS.

### Admin API as a Cross-Check

Tinfoil also offers an Admin API for retrieving usage statistics tied to your API key. You can use this as a cross-reference to verify your proxy's header-based counts match Tinfoil's own records. See [docs.tinfoil.sh/admin/admin-api](https://docs.tinfoil.sh/admin/admin-api).

---

## Security Model Summary

| What your proxy CAN see                | What your proxy CANNOT see         |
| --------------------------------------- | ---------------------------------- |
| HTTP headers (routing, auth, CORS)      | Request body (prompts)             |
| `X-Tinfoil-Enclave-Url` (target)       | Response body (completions)        |
| `X-Tinfoil-Usage-Metrics` (token counts)| Decrypted content of any kind      |
| Custom headers (`X-User-ID`, etc.)      | HPKE private keys                  |
| Encrypted blob sizes                    | Model weights or internal state    |

**End-to-end encryption is maintained.** Your proxy is a transparent relay for content, and a metadata-aware gateway for everything else.

---

## Trust Model for Token Counts

| Threat                                  | Mitigated? |
| --------------------------------------- | ---------- |
| User faking lower counts                | Yes — enclave computes counts      |
| MITM between proxy and enclave          | Yes — TLS protects the connection  |
| Proxy operator inflating counts         | No — headers are not signed        |
| Verifying counts independently          | Partial — use Tinfoil Admin API    |

The token counts come from the enclave and are trustworthy for your billing purposes. However, they are not cryptographically signed, so your end users are trusting your proxy to report them faithfully (same trust model as any metered SaaS).

---

## References

- [Tinfoil Proxy Guide](https://docs.tinfoil.sh/guides/proxy-server) — official documentation
- [Encrypted Request Proxy Example](https://github.com/tinfoilsh/encrypted-request-proxy-example) — reference implementation (Go + TypeScript + Swift)
- [EHBP Specification](https://docs.tinfoil.sh/resources/ehbp) — full protocol details
- [EHBP GitHub Repository](https://github.com/tinfoilsh/encrypted-http-body-protocol) — open-source reference
- [Tinfoil Admin API](https://docs.tinfoil.sh/admin/admin-api) — server-side usage tracking
- [Tinfoil JavaScript SDK](https://www.npmjs.com/package/tinfoil) — client-side SDK with `SecureClient`
