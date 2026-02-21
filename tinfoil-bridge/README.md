# Tinfoil Bridge

Go bridge wrapping `tinfoil-go` for TEE-attested LLM inference on Android.

## Building the AAR

Prerequisites:
- Go 1.22+
- gomobile (`go install golang.org/x/mobile/cmd/gomobile@latest && gomobile init`)

```bash
cd go/
go mod tidy
gomobile bind -target=android -androidapi=35 -o ../tinfoil-bridge.aar .
```

The resulting `tinfoil-bridge.aar` should be placed in this directory and is
consumed by the `:app` module via a `files()` dependency.

For CI, a prebuilt `.aar` is committed to this directory.
