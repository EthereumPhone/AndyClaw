# AndyClaw Extension Development Guide

This document explains how to build an Android app that acts as an **AndyClaw extension** — a standalone APK that exposes functions the AndyClaw agent can discover and invoke at runtime.

The `ExtensionExample` module in this repository is a minimal, working reference implementation using the bound service bridge.

---

## How It Works

```
┌──────────────────────┐         ┌──────────────────────────┐
│      AndyClaw         │         │   Your Extension APK     │
│                       │         │                          │
│  1. ApkExtensionScan  │────────▶│  <meta-data> in manifest │
│     ner reads all     │         │  + @raw/extension_mani-  │
│     installed pkgs    │         │    fest.json              │
│                       │         │                          │
│  2. ExtensionEngine   │         │                          │
│     registers the     │         │                          │
│     extension         │         │                          │
│                       │         │                          │
│  3. ApkExtensionExe-  │◀───────▶│  IPC bridge (service,    │
│     cutor invokes     │  IPC    │  provider, receiver, or  │
│     functions via     │         │  activity intent)        │
│     detected bridge   │         │                          │
└──────────────────────┘         └──────────────────────────┘
```

**Discovery** happens by scanning every installed package's `<application>` metadata for the key `org.ethereumphone.andyclaw.EXTENSION`. When found, AndyClaw reads the extension's identity, its function manifest, and detects what IPC bridge it exposes.

**Execution** routes through the highest-fidelity bridge available. The priority order is: bound service > content provider > broadcast receiver > explicit intent.

---

## Step-by-Step: Creating an Extension

### 1. Create an Android Application Module

An extension is a regular Android app. Create a new `com.android.application` module (not a library). It can have its own UI, or it can be headless — the only requirement is the manifest metadata and at least one IPC bridge component.

### 2. Declare Discovery Metadata

Add four `<meta-data>` entries inside the `<application>` tag of your `AndroidManifest.xml`:

```xml
<application ...>

    <!-- Required: marks this APK as an AndyClaw extension -->
    <meta-data
        android:name="org.ethereumphone.andyclaw.EXTENSION"
        android:value="true" />

    <!-- Required: unique identifier for your extension -->
    <meta-data
        android:name="org.ethereumphone.andyclaw.EXTENSION_ID"
        android:value="my_extension" />

    <!-- Required: human-readable name shown in the UI -->
    <meta-data
        android:name="org.ethereumphone.andyclaw.EXTENSION_NAME"
        android:value="My Extension" />

    <!-- Required: reference to a raw JSON resource listing your functions -->
    <meta-data
        android:name="org.ethereumphone.andyclaw.EXTENSION_MANIFEST"
        android:resource="@raw/extension_manifest" />

    ...
</application>
```

| Key | Type | Description |
|-----|------|-------------|
| `EXTENSION` | `"true"` | Boolean flag that marks the APK for discovery. |
| `EXTENSION_ID` | string | Globally unique ID. Convention: `my_extension` or `com.example.myext`. |
| `EXTENSION_NAME` | string | Display name shown in AndyClaw's Extensions settings. |
| `EXTENSION_MANIFEST` | `@raw/...` | Resource reference to the function manifest JSON. |

### 3. Write the Function Manifest

Create `res/raw/extension_manifest.json`. This is a JSON array where each element describes one function your extension exposes:

```json
[
  {
    "name": "greet",
    "description": "Returns a friendly greeting for the given name.",
    "inputSchema": {
      "type": "object",
      "properties": {
        "name": {
          "type": "string",
          "description": "The name to greet"
        }
      },
      "required": ["name"]
    }
  }
]
```

Each function object supports these fields:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `name` | string | yes | Function name the agent will use to invoke it. Must be unique across all extensions. |
| `description` | string | yes | What the function does — the agent reads this to decide when to call it. |
| `inputSchema` | object | yes | JSON Schema describing the parameters. |
| `requiresApproval` | boolean | no | If `true`, the user must explicitly approve each invocation. Default: `false`. |
| `requiredPermissions` | string[] | no | Android permissions the host app must hold. Default: `[]`. |

### 4. Implement a Bridge

You need at least one IPC bridge so AndyClaw can actually call your functions. Choose from the four options below. If your extension exposes multiple bridge types, AndyClaw will pick the highest-fidelity one automatically.

| Priority | Bridge Type | Best For |
|----------|------------|----------|
| 1 (best) | **Bound Service** | Bidirectional, typed communication. Recommended for most extensions. |
| 2 | **Content Provider** | Synchronous request/response. Good if you already have a provider. |
| 3 | **Broadcast Receiver** | Asynchronous single-shot. Simple but limited. |
| 4 | **Explicit Intent** | Fire-and-forget. No structured return value. |

---

#### Option A: Bound Service (recommended)

Declare a service with the extension intent-filter:

```xml
<service
    android:name=".MyExtensionService"
    android:exported="true">
    <intent-filter>
        <action android:name="org.ethereumphone.andyclaw.EXTENSION_SERVICE" />
    </intent-filter>
</service>
```

Implement a `Service` with a custom `Binder` that speaks AndyClaw's wire protocol:

```kotlin
class MyExtensionService : Service() {

    companion object {
        private const val DESCRIPTOR = "org.ethereumphone.andyclaw.IExtension"
        private const val TRANSACTION_GET_MANIFEST = 1
        private const val TRANSACTION_EXECUTE = 2
    }

    private val binder = object : Binder() {
        override fun onTransact(
            code: Int, data: Parcel, reply: Parcel?, flags: Int
        ): Boolean {
            return when (code) {
                TRANSACTION_GET_MANIFEST -> {
                    data.enforceInterface(DESCRIPTOR)
                    reply?.writeNoException()
                    reply?.writeString(getManifestJson())
                    true
                }
                TRANSACTION_EXECUTE -> {
                    data.enforceInterface(DESCRIPTOR)
                    val function = data.readString() ?: ""
                    val paramsJson = data.readString() ?: "{}"
                    reply?.writeNoException()
                    reply?.writeString(execute(function, paramsJson))
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun getManifestJson(): String {
        // Return the same manifest as your raw resource, or build it dynamically
        ...
    }

    private fun execute(function: String, paramsJson: String): String {
        // Parse paramsJson, run your logic, return a JSON result string
        ...
    }
}
```

**Wire protocol reference:**

| Transaction Code | Request | Response |
|-----------------|---------|----------|
| `1` (GET_MANIFEST) | Interface token only | `writeNoException()` + `writeString(manifestJson)` |
| `2` (EXECUTE) | Interface token + `readString()` (function name) + `readString()` (params JSON) | `writeNoException()` + `writeString(resultJson)` |

The interface descriptor **must** be `"org.ethereumphone.andyclaw.IExtension"`. The result from EXECUTE is a free-form JSON string — AndyClaw passes it through to the agent as-is.

---

#### Option B: Content Provider

Declare a provider whose authority ends with `.andyclaw.extension`:

```xml
<provider
    android:name=".MyExtensionProvider"
    android:authorities="${applicationId}.andyclaw.extension"
    android:exported="true" />
```

Implement `call()` to handle function invocations:

```kotlin
class MyExtensionProvider : ContentProvider() {

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val function = extras?.getString("function") ?: ""
        val params = extras?.getString("params") ?: "{}"

        val result = Bundle()
        try {
            result.putString("result", execute(function, params))
        } catch (e: Exception) {
            result.putString("error", e.message)
        }
        return result
    }

    private fun execute(function: String, params: String): String {
        // Your logic here — return a JSON result string
        ...
    }

    // Required overrides (can be no-ops)
    override fun onCreate() = true
    override fun query(...) = null
    override fun insert(...) = null
    override fun update(...) = 0
    override fun delete(...) = 0
    override fun getType(...) = null
}
```

AndyClaw calls `ContentResolver.call()` with the method `"execute"` and a bundle containing `"function"` and `"params"`. Return a bundle with either `"result"` (success) or `"error"` (failure).

---

#### Option C: Broadcast Receiver

Declare a receiver with the extension intent-filter:

```xml
<receiver
    android:name=".MyExtensionReceiver"
    android:exported="true">
    <intent-filter>
        <action android:name="org.ethereumphone.andyclaw.EXTENSION_BROADCAST" />
    </intent-filter>
</receiver>
```

Handle the broadcast and send a response back:

```kotlin
class MyExtensionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val function = intent.getStringExtra("function") ?: return
        val params = intent.getStringExtra("params") ?: "{}"
        val responseAction = intent.getStringExtra("response_action") ?: return

        val result = execute(function, params)

        val response = Intent(responseAction).apply {
            putExtra("result", result)
        }
        context.sendBroadcast(response)
    }

    private fun execute(function: String, params: String): String {
        // Your logic here — return a JSON result string
        ...
    }
}
```

AndyClaw sends a broadcast with extras `"function"`, `"params"`, and `"response_action"`. Your receiver processes the request and broadcasts back a response intent on the `response_action` with either `"result"` or `"error"`.

---

#### Option D: Explicit Intent (fire-and-forget)

Declare an activity with the extension intent-filter:

```xml
<activity
    android:name=".MyExtensionActivity"
    android:exported="true">
    <intent-filter>
        <action android:name="org.ethereumphone.andyclaw.EXTENSION_ACTION" />
    </intent-filter>
</activity>
```

The activity receives `"function"` and `"params"` as intent extras. This bridge has no structured return value — AndyClaw reports success as soon as the activity launches. Use this for actions where a result isn't needed (e.g., opening a specific screen).

---

### 5. Build, Install, and Rescan

1. Build and install your extension APK on the device.
2. Open AndyClaw, go to **Settings > Extensions**, and tap **Rescan**.
3. Your extension should appear in the list with its name and function count.
4. The agent can now invoke your functions by name during conversations.

---

## Security Model

AndyClaw enforces three security checks on extensions **by default**:

| Check | What It Does |
|-------|-------------|
| **Signature Validation** | Verifies the extension APK has valid signing certificates. If the extension descriptor includes a `signingCertHash`, the certificate's SHA-256 digest must match exactly. |
| **UID Isolation** | Ensures the extension runs under a different Linux UID than AndyClaw (no `android:sharedUserId` overlap). |
| **Permission Checks** | Verifies that Android runtime permissions declared in `requiredPermissions` are granted to the host app. |

Extensions can be marked as **trusted** by the user in the host app, which bypasses all checks. There is also a **developer mode** that disables all security checks globally — intended only for development.

---

## Tips

- **Function names must be globally unique** across all installed extensions. Use a prefix if you're worried about collisions (e.g., `myext_greet` instead of `greet`).
- **Write clear descriptions** — the agent uses them to decide when to call your function. Be specific about what the function does, what it returns, and when it should be used.
- **Keep execution fast** — the default timeout is 30 seconds. Long-running work should be offloaded to a background thread.
- **Return structured JSON** — while AndyClaw doesn't enforce a result schema, returning well-structured JSON helps the agent interpret and relay results to the user.
- **Your extension is a normal app** — it can have its own UI, background services, databases, and anything else a regular Android app can have. The extension bridge is just one component.
