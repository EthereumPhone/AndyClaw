# AndyClaw

An open-source AI assistant for Android that can control your device, manage crypto wallets, run Linux commands, and operate autonomously in the background. Built by [Freedom Factory](https://github.com/EthereumPhone) for the [dGEN1](https://dgen.gg) and ethOS, but runs on any Android device.

## How It Works

AndyClaw is a single APK that operates in two modes depending on what device it's running on:

### On ethOS (dGEN1)

When installed on a device running ethOS, AndyClaw automatically detects the OS wallet service and unlocks **privileged mode**. No API key needed — authentication is handled by signing a message with your ethOS wallet.

**What you get in privileged mode:**
- Wallet integration (send transactions, check balances, manage tokens across Ethereum, Optimism, Polygon, Arbitrum, Base, and more)
- XMTP messaging (send and receive onchain messages)
- Full device control (WiFi, Bluetooth, mobile data, audio, power)
- Phone calls and call management
- Calendar read/write
- App installation and management (install, uninstall, clear data, force stop)
- Device power controls (reboot, shutdown)
- Code execution
- Screen time and usage stats
- OS-managed heartbeat (the OS triggers the AI periodically, no foreground service needed)
- An autonomous sub-account wallet the AI controls for micro-payments and DeFi

### On Stock Android

When installed on a regular Android device, AndyClaw runs in **open mode**. You bring your own [OpenRouter](https://openrouter.ai) API key to power the LLM.

**What you get in open mode:**
- Chat with the AI assistant
- Device info (battery, storage, device details)
- Clipboard read/write
- Contacts read/write
- SMS send/read
- Camera capture
- Location and navigation
- App listing and launching
- Notification reading
- File system operations
- Shell commands
- Web search
- Long-term memory (the AI remembers things across sessions)
- Background heartbeat via foreground service
- Termux integration (if Termux is installed)
- ClawHub skills (install community-made skills)

**Setup:**
1. Install the APK
2. On first launch, enter your OpenRouter API key (get one at [openrouter.ai](https://openrouter.ai))
3. Tell the AI what you want help with
4. Name your AI (or keep the default)
5. Share your values/priorities
6. Choose which skills to enable (or turn on YOLO mode for full access)

## Features

### AI Agent Loop

AndyClaw uses an agentic tool-use loop. The AI can chain multiple tool calls together to accomplish complex tasks — checking your battery, looking up a contact, sending them a message, and logging what it did, all in a single conversation turn. Up to 20 tool iterations per request.

### Heartbeat

The heartbeat is the AI's autonomous background pulse. It wakes up periodically (default: 30 minutes, configurable) and can:
- Check device status (battery, connectivity, storage)
- Review notifications and messages
- Execute pending tasks
- Log what it found and did

On ethOS, the OS triggers the heartbeat directly. On stock Android, a foreground service keeps it alive.

Heartbeat logs are viewable in-app so you can see exactly what the AI did while you weren't looking.

### Skills System

Skills are modular capabilities the AI can use. There are 30 built-in skills covering everything from device info to crypto wallets. Skills are tier-aware — some are available on all Android devices, others require ethOS privileged access.

**ClawHub** lets you install community-created skills written as SKILL.md files. These can be instruction-only (the AI follows written procedures) or Termux-executable (the AI runs scripts in a Linux environment).

### Termux Integration

If [Termux](https://termux.dev) is installed, the AI gets a full Linux environment. It can run bash commands, install packages, execute scripts, and interact with the terminal. ClawHub skills can define Termux entrypoints that get synced and executed automatically.

### Long-Term Memory

The AI has a semantic memory system backed by SQLite FTS4 and vector embeddings. It can store and retrieve memories across sessions — facts you tell it, things it learns, context from conversations. Memories are automatically injected into the system prompt when relevant.

### Sessions

Chat history is persisted. You can resume previous conversations or start fresh ones.

### Extensions

Third-party apps can register as AndyClaw extensions, providing additional skills that get discovered and loaded automatically.

## Requirements

- Android 15+ (API 35)
- An OpenRouter API key (stock Android only — get one at [openrouter.ai](https://openrouter.ai))
- Optional: [Termux](https://termux.dev) for Linux command execution

## Models

AndyClaw routes through [OpenRouter](https://openrouter.ai) (stock Android) or a premium gateway (ethOS). The default model is `minimax/minimax-m2.5`. You can switch models in settings.

## Building From Source

```bash
git clone https://github.com/EthereumPhone/AndyClaw.git
cd AndyClaw
```

Create `local.properties` if it doesn't exist and add any optional keys:

```properties
# Optional — only needed for wallet/crypto features
BUNDLER_API=your_pimlico_bundler_key
ALCHEMY_API=your_alchemy_api_key

# Optional — override the premium LLM gateway URL
PREMIUM_LLM_URL=https://your-gateway.com/api/llm

# Optional — build as system app (for ethOS system image builds)
SYSTEM_APP=true
```

Build the APK:

```bash
./gradlew assembleRelease
```

The APK will be at `app/build/outputs/apk/release/`.

## Permissions

AndyClaw requests a wide range of permissions to support its full skill set. On stock Android, most privileged permissions (device power, package management, system settings) are not usable — they require system-level access that only ethOS provides. Standard permissions (contacts, SMS, camera, location, etc.) are requested at runtime when a skill needs them.

## License

[GPL-3.0](LICENSE)
