# AndyClaw — 3-Day Sprint Plan (v3)

## Open Android App + OS-Exclusive Superpowers

> Strategy: Ship an open-source app that works on ANY Android device.
> But on your OS, it's 10x better — and that's the reason people switch.

---

## The Two-Tier Model

```
┌─────────────────────────────────────────────────────────────────┐
│                                                                 │
│   ┌───────────────────────────────────────────────────────┐     │
│   │            PRIVILEGED TIER (Your OS Only)              │     │
│   │                                                       │     │
│   │  System Service · Always-On · Zero Permission Prompts │     │
│   │  Deep OS Hooks · Skill Supercharger API               │     │
│   │  Ambient Listening · App Interception · Screen Read   │     │
│   │  Background Agents · Proactive Suggestions            │     │
│   │  Hardware-Accelerated Local LLM                       │     │
│   │                                                       │     │
│   └───────────────────────┬───────────────────────────────┘     │
│                           │ extends                             │
│   ┌───────────────────────┴───────────────────────────────┐     │
│   │            OPEN TIER (Any Android 9+)                  │     │
│   │                                                       │     │
│   │  Regular App · Play Store / F-Droid / Sideload        │     │
│   │  Standard Permissions · User-Granted Access           │     │
│   │  Chat UI · Skill Engine · External Skill SDK          │     │
│   │  LLM Client · Session Management                     │     │
│   │                                                       │     │
│   └───────────────────────────────────────────────────────┘     │
│                                                                 │
│                        AndyClaw                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Open Tier** = Great AI assistant, works everywhere, open source, anyone can build skills.
**Privileged Tier** = The same app detects it's on your OS and unlocks capabilities that are literally impossible on stock Android.

---

## How the Tier Detection Works

```kotlin
object OsCapabilities {

    /** Check if we're running on the custom OS */
    val isPrivilegedOs: Boolean by lazy {
        // Option A: Check for your OS build property
        Build.MANUFACTURER == "YourOS" ||
        SystemProperties.get("ro.youros.version", "").isNotEmpty()
    }

    /** Check if we have system-level privileges */
    val isSystemApp: Boolean by lazy {
        (appContext.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
    }

    /** Combined: on your OS AND installed as system app */
    val hasPrivilegedAccess: Boolean get() = isPrivilegedOs && isSystemApp

    /**
     * Skill capabilities vary by tier.
     * Skills query this to know what they can do.
     */
    fun hasCapability(cap: Capability): Boolean = when (cap) {
        // Available on any Android
        Capability.SHELL_BASIC        -> true
        Capability.CONTACTS_READ      -> true
        Capability.NOTIFICATIONS_READ -> true
        Capability.FILE_ACCESS        -> true
        Capability.CAMERA             -> true

        // Only on your OS
        Capability.SHELL_ROOT           -> hasPrivilegedAccess
        Capability.SCREEN_READ          -> hasPrivilegedAccess
        Capability.APP_INTERCEPT        -> hasPrivilegedAccess
        Capability.ALWAYS_ON_SERVICE    -> hasPrivilegedAccess
        Capability.AMBIENT_CONTEXT      -> hasPrivilegedAccess
        Capability.SYSTEM_SETTINGS_WRITE -> hasPrivilegedAccess
        Capability.BACKGROUND_AGENTS    -> hasPrivilegedAccess
        Capability.LOCAL_LLM_ACCELERATED -> hasPrivilegedAccess
        Capability.PROACTIVE_TRIGGERS   -> hasPrivilegedAccess
    }
}
```

---

## What Makes Your OS Version Worth Switching For

| Capability | Stock Android (Open Tier) | Your OS (Privileged Tier) |
|---|---|---|
| **Always on** | App can be killed by battery optimizer. User must whitelist manually. | System service. Unkillable. Boots with device. Zero config. |
| **Permissions** | User must grant each permission via dialog. Some are hard to get (accessibility, notification access). | Pre-granted. No prompts. Frictionless. |
| **Screen awareness** | None — can't see what's on screen. | Full screen read via OS hook. Agent sees what you see. "What's this error on my screen?" just works. |
| **App control** | Can only launch apps. | Can interact WITH apps — tap buttons, fill forms, navigate. Full UI automation via privileged AccessibilityService. |
| **Proactive agent** | Reactive only — waits for user to type. | Proactive — monitors context (calendar, location, notifications) and offers help before you ask. "You have a meeting in 10 min, want me to find directions?" |
| **Background tasks** | Limited by Android's background execution limits. | Unlimited background agents. "Monitor Hacker News for posts about my project and summarize daily." |
| **Notification interception** | Read-only (if user grants NotificationListener). | Read, modify, auto-reply, route. "Auto-respond to Slack DMs when I'm in DND with a summary of my status." |
| **Local LLM** | Basic llama.cpp, limited by app sandbox. | Hardware-accelerated inference via OS-level GPU/NPU access. Faster, lower battery, works offline. |
| **Skill depth** | Skills use public Android APIs (good but limited). | Skills use **Supercharger API** — a private OS API surface that exposes deep system internals. |
| **Inter-app data** | Sandboxed. Can only see own data. | Can read app databases, SharedPreferences, internal storage (with user consent toggle in OS settings). |
| **System settings** | Read-only for most. | Full read/write to all system settings. Toggle anything. |
| **Trusted execution** | Every destructive action needs approval dialog. | User sets trust level per skill. "Full auto" mode for trusted skills — no interruptions. |

The pitch: *"AndyClaw works on any Android. But on [YourOS], it's like giving your phone a brain."*

---

## Revised Skill Interface (Tier-Aware)

```kotlin
interface AndyClawSkill {
    val id: String
    val name: String

    /**
     * Skills declare two manifests:
     * - base: works on any Android
     * - privileged: additional tools unlocked on your OS
     *
     * The registry merges them based on detected tier.
     */
    val baseManifest: SkillManifest
    val privilegedManifest: SkillManifest? get() = null  // null = no extras

    suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult
}

enum class Tier { OPEN, PRIVILEGED }
```

### Example: Notifications Skill (Tier-Aware)

```kotlin
class NotificationSkill(private val context: Context) : AndyClawSkill {

    override val id = "notifications"
    override val name = "Notifications"

    // Available on ALL Android devices
    override val baseManifest = SkillManifest(
        description = "Read current notifications",
        tools = listOf(
            ToolDefinition(
                name = "list_notifications",
                description = "List active notifications",
                parameters = jsonSchema {
                    "app_filter" type "string" optional true description "Filter by app name"
                }
            )
        ),
        permissions = listOf("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE")
    )

    // ONLY available on your OS
    override val privilegedManifest = SkillManifest(
        description = "Full notification control",
        tools = listOf(
            ToolDefinition(
                name = "dismiss_notification",
                description = "Dismiss a specific notification",
                parameters = jsonSchema {
                    "notification_id" type "string" required true
                }
            ),
            ToolDefinition(
                name = "reply_to_notification",
                description = "Reply directly to a notification (messaging apps)",
                parameters = jsonSchema {
                    "notification_id" type "string" required true
                    "reply_text" type "string" required true
                },
                requiresApproval = true
            ),
            ToolDefinition(
                name = "auto_triage",
                description = "Set rules to auto-categorize and handle notifications",
                parameters = jsonSchema {
                    "rules" type "array" items jsonSchema {
                        "app" type "string"
                        "action" type "string" enum listOf("mute", "summarize", "auto_reply", "escalate")
                        "condition" type "string" optional true
                    }
                }
            )
        )
    )

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        return when (tool) {
            "list_notifications" -> listNotifications(params)

            // Privileged-only tools
            "dismiss_notification" -> {
                require(tier == Tier.PRIVILEGED) { "Requires YourOS" }
                dismissViaSystemApi(params)
            }
            "reply_to_notification" -> {
                require(tier == Tier.PRIVILEGED) { "Requires YourOS" }
                replyViaRemoteInput(params)
            }
            "auto_triage" -> {
                require(tier == Tier.PRIVILEGED) { "Requires YourOS" }
                setupTriageRules(params)
            }
            else -> SkillResult.Error("Unknown tool: $tool")
        }
    }
}
```

---

## Supercharger API (Your OS Exclusive)

This is the private API surface that only exists on your OS. Skills use it
for things impossible on stock Android.

```kotlin
/**
 * Only available on YourOS. Accessed via:
 *   val supercharger = SuperchargerApi.getInstance(context)
 *
 * On stock Android, getInstance() returns null.
 * Skills check for this and gracefully degrade.
 */
interface SuperchargerApi {

    /** Read what's currently on screen as structured data */
    suspend fun getScreenContent(): ScreenContent

    /** Perform UI actions in any app */
    suspend fun performAction(action: UiAction): ActionResult

    /** Read another app's internal data (user must enable per-app in OS settings) */
    suspend fun readAppData(packageName: String, path: String): ByteArray?

    /** Register a background agent that runs on triggers */
    fun registerBackgroundAgent(agent: BackgroundAgent)

    /** Access hardware-accelerated LLM inference */
    fun getLocalLlmEngine(): LocalLlmEngine?

    /** Intercept and modify notifications before they're shown */
    fun registerNotificationInterceptor(interceptor: NotificationInterceptor)

    /** Get ambient device context (recent apps, current activity, sensors) */
    suspend fun getAmbientContext(): AmbientContext

    /** Write system settings directly */
    suspend fun writeSystemSetting(namespace: String, key: String, value: String)

    companion object {
        fun getInstance(context: Context): SuperchargerApi? {
            if (!OsCapabilities.hasPrivilegedAccess) return null
            return context.getSystemService("supercharger") as? SuperchargerApi
        }
    }
}

data class ScreenContent(
    val currentApp: String,
    val activityName: String,
    val elements: List<UiElement>,  // parsed accessibility tree
    val screenshot: Bitmap?          // optional, for vision LLM
)

data class AmbientContext(
    val currentApp: String,
    val recentApps: List<String>,
    val currentLocation: Location?,
    val nextCalendarEvent: CalendarEvent?,
    val batteryLevel: Int,
    val isCharging: Boolean,
    val connectivity: ConnectivityState,
    val timeOfDay: TimeOfDay,  // MORNING, AFTERNOON, EVENING, NIGHT
    val activeNotificationCount: Int
)
```

---

## Revised 3-Day Sprint

### Day 1 — Core Engine (Works Everywhere)

| Block | Task | Hours |
|-------|------|-------|
| Morning | **Project setup**: Two modules — `:app` and `:skill-sdk`. Kotlin + Compose + Hilt + Room + OkHttp. GitHub repo, Apache 2.0 license. | 1.5h |
| Morning | **LLM Client**: Anthropic Messages API with tool_use. Streaming via SSE. Model selector (Sonnet default, Haiku for speed). API key config screen. | 2.5h |
| Afternoon | **Skill engine**: `AndyClawSkill` interface with `baseManifest` + `privilegedManifest`. `SkillRegistry` with tier detection, dispatch, and merged tool list. `PromptAssembler` that builds system prompt from active skills. | 3h |
| Afternoon | **Agent loop**: Message → assemble prompt → LLM call → tool_use parse → dispatch → result → loop until text. Handle multi-step tool chains. | 2h |
| Evening | **4 base skills** (Open Tier — work on any Android): | 3h |
| | → `DeviceInfoSkill` — battery, storage, network, device model | |
| | → `ClipboardSkill` — read/write clipboard | |
| | → `ShellSkill` — basic shell (non-root on stock, root on your OS) | |
| | → `FileSystemSkill` — scoped storage access | |
| | **Day 1 total** | **~12h** |

---

### Day 2 — UI + Tier-Aware Skills

| Block | Task | Hours |
|-------|------|-------|
| Morning | **Chat UI**: Compose chat screen, markdown rendering, streaming token display, input bar with attachment button. Dark/light theme. | 3h |
| Morning | **Session persistence**: Room DB. Message history. Basic session management. | 1.5h |
| Afternoon | **6 tier-aware skills** (base tools on stock, extra tools on your OS): | 5h |
| | → `ContactsSkill` — read/search (stock) + create/edit/smart merge (privileged) | |
| | → `AppsSkill` — list/launch (stock) + UI automation/interact (privileged) | |
| | → `NotificationSkill` — read (stock) + dismiss/reply/triage (privileged) | |
| | → `SettingsSkill` — read (stock) + write any setting (privileged) | |
| | → `CameraSkill` — take photo (stock) + silent capture/vision analysis (privileged) | |
| | → `SMSSkill` — read (stock) + send/auto-reply (privileged) | |
| Evening | **OS detection + Supercharger stub**: `OsCapabilities` detection. Stub `SuperchargerApi` interface. On your OS, wire to real system service. On stock, returns null → skills degrade gracefully. | 1.5h |
| Evening | **Approval flow**: Compose dialog for `requiresApproval` tools. Trust levels on your OS (always ask / ask once / full auto). | 1h |
| | **Day 2 total** | **~12h** |

---

### Day 3 — External Skills + Polish + Ship

| Block | Task | Hours |
|-------|------|-------|
| Morning | **External skill loading**: APK discovery via PackageManager meta-data tag. AIDL interface for cross-process skill communication. Hot-load on install (BroadcastReceiver for PACKAGE_ADDED). | 3h |
| Morning | **Skill SDK package**: Publishable `:skill-sdk` module with interfaces, AIDL, manifest helpers, and a `SkillBuilder` DSL for quick skill creation. | 1.5h |
| Midday | **Skill template repo**: Standalone GitHub template project. Clone → implement one function → install → works. Example: `WeatherSkill`. README with 5-minute quickstart. | 1h |
| Afternoon | **Privileged-only showcase features** (your OS only): | 3h |
| | → `ScreenSkill` — "What's on my screen?" via SuperchargerApi screen read + vision LLM | |
| | → `ProactiveAgent` — Background service that monitors AmbientContext and surfaces suggestions ("You have a meeting in 15 min — want directions?") | |
| Afternoon | **Open Tier polish**: Permission request flows (runtime permissions with explanations). Settings screen (API key, model selection, skill management, enabled skills toggle). Onboarding flow (3 screens explaining what AndyClaw can do). | 2h |
| Evening | **Testing & packaging**: Test all skills on stock emulator + your OS device. Build release APK for GitHub Releases. Build system image with privileged variant for your OS. Write README with feature comparison table. | 2h |
| | **Day 3 total** | **~12.5h** |

---

## What Ships on Day 3

### For Everyone (Open Source APK)

- Chat UI with streaming LLM responses
- 10 built-in skills using standard Android APIs
- External skill SDK — anyone can build and publish skills
- Works on any Android 9+ device
- Apache 2.0 licensed, GitHub repo

### For Your OS Users (Same APK, More Unlocked)

Everything above, PLUS:
- Persistent always-on system service
- Skills unlock privileged tools automatically (no extra setup)
- Screen awareness — agent can see and understand what's on screen
- App control — agent can interact with any app's UI
- Proactive suggestions from ambient context
- Notification auto-triage and reply
- Trust levels — set skills to full-auto mode
- Direct system settings control
- Root shell access
- Path to local on-device LLM (NPU-accelerated)

### For Skill Developers

- `andyclaw-skill-sdk` library (Maven Central / GitHub Packages)
- Template repo with working example
- AIDL interface for cross-process skills
- `SuperchargerApi` documented for building privileged skill extensions
- Skills automatically work at both tiers — base features on stock, enhanced on your OS

---

## Open Source Strategy

```
github.com/andyclaw/
├── andyclaw-android/          # Main app (Apache 2.0)
│   ├── app/                   # The app itself
│   ├── skill-sdk/             # SDK for third-party skills
│   └── skills-builtin/        # Built-in skills (reference implementations)
│
├── andyclaw-skill-template/   # Clone-to-start template for skill devs
│
├── andyclaw-skills-community/ # Curated community skill directory
│   ├── weather/
│   ├── spotify/
│   ├── home-assistant/
│   └── ...
│
└── docs/                      # Developer docs site
    ├── getting-started.md
    ├── building-skills.md
    ├── supercharger-api.md    # Docs for your OS exclusive APIs
    └── architecture.md
```

**The flywheel**: Open source app → skill developers build on it → more skills make
AndyClaw better → more users → some users want the privileged features →
they switch to your OS.

---

## Architecture: Why This Works as a Platform

```
         THIRD-PARTY SKILL APKS
        ┌──────┐ ┌──────┐ ┌──────┐
        │Spotify│ │HomeAs│ │Custom│  ← Anyone can build
        │Skill  │ │Skill │ │Skill │
        └──┬───┘ └──┬───┘ └──┬───┘
           │        │        │
     ══════╪════════╪════════╪══════════════════════
           │    AIDL IPC     │
     ══════╪════════╪════════╪══════════════════════
           │        │        │
     ┌─────┴────────┴────────┴──────────────────┐
     │           SKILL REGISTRY                  │
     │  discovers · validates · routes · merges  │
     ├──────────────────────────────────────────-┤
     │           BUILT-IN SKILLS                 │
     │  Contacts│Apps│Notif│Settings│Shell│...   │
     │          │    │     │        │     │      │
     │     base tools + privileged tools         │
     ├───────────────────┬──────────────────────-┤
     │   PROMPT ASSEMBLER│     AGENT CORE        │
     │   (merges all     │  (LLM loop, session   │
     │    skill manifests│   mgmt, streaming)     │
     │    into system    │                        │
     │    prompt)        │                        │
     ├───────────────────┴──────────────────────-┤
     │              TIER DETECTOR                 │
     │   Stock Android? → Open Tier               │
     │   YourOS?        → Privileged Tier         │
     ├──────────────────────────────────────────-┤
     │         SUPERCHARGER API                   │
     │   null on stock │ real impl on your OS     │
     │                 │                          │
     │          Screen Read · App Control         │
     │          Background Agents · Local LLM     │
     │          Notification Intercept            │
     └──────────────────────────────────────────-┘
```

The Skill SDK is the moat. Once developers build skills for AndyClaw,
users follow. And once users want the privileged features,
they have a reason to switch to your OS.
