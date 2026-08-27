# AndyClaw — the on-device agent for ethOS / dGEN1

`org.ethereumphone.andyclaw`. One APK, two modes: **privileged** on ethOS (wallet, device
control, OS-driven heartbeat, agent display) and **open** on stock Android (local Qwen or a
BYO API key). `README.md` has the user-facing feature split; this file is how to work in the
code.

**This repo is not where the APK ships from.** The build output is dropped into the ethOS tree
at `packages/apps/AndyClaw/app-release.apk`, which re-signs it with `LOCAL_CERTIFICATE :=
platform` and installs it as a privileged system app. Roughly **6,000 dgen1 phones** run it,
and the only way an update reaches them is a signed OTA of the whole system image. The ethOS
tree lives at `~/dgen1/v0mp1_test/git-V0MP1`; read its `CLAUDE.md` §0 before assuming anything
about release cadence, and `ANDYCLAW.md` there for every codepath from this app through
`system_server` to the backend.

```bash
# which commit a shipped prebuilt was built from
unzip -p packages/apps/AndyClaw/app-release.apk META-INF/version-control-info.textproto
```

---

## 1. Modules

| Module | What belongs in it |
|---|---|
| `:AndyClaw` | The reusable engine. Abstractions and cross-cutting types: `ExecutionEngine/*`, `agent/AgentRunner`, `heartbeat/*`, `flows/*` (the Flow IR, validator, store and interpreter — §8), `ledger/*` and `frames/*` (the record of what the agent did — §9), `ambient/*` (what is about to matter — §9), `skills/` interfaces (`AndyClawSkill`, `SkillManifest`, `ToolDefinition`, `ToolEffect`, `Tier`), `memory/`, `sessions/`, `extensions/`. No concrete skills, no Android UI. |
| `:app` | The wiring. `NodeApp` (the object graph), `NodeRuntime`, the binder services, `agent/AgentLoop`, `llm/*` transports, ~50 `skills/builtin/*` skills, `ingest/*` (mail and calendar parsers and their sources — §9), `safety/*`, Compose UI. |
| `:ExtensionExample` | A sample out-of-process extension. Reference, not shipped. |

**A new cross-cutting interface goes in `:AndyClaw`; its implementation goes in `:app`.**
`:app` depends on `:AndyClaw`, never the other way round. A type that both a skill and the
engine need (`ToolEffect`, `Provenance`) belongs in `:AndyClaw`; a table of concrete tool names
(`safety/ToolEffects`, `safety/ToolAttenuation`) belongs in `:app`.

## 2. Build and test

```bash
./gradlew :AndyClaw:test :app:test     # unit tests — `unitTests.isReturnDefaultValues = true`,
                                       # so android.util.Log is a no-op stub, no Robolectric
./gradlew :app:assembleRelease         # the artefact to drop into the ethOS tree
./gradlew :app:compileDebugKotlin      # fastest syntax/type check while iterating
```

`:AndyClaw` has `kotlinx-coroutines-test` (`runTest`); `:app` does **not** — use `runBlocking`
there. `local.properties` carries the API keys (`ALCHEMY_API`, `BUNDLER_API`, `ZEROX_API_KEY`,
`BANKR_API`, `VENICE_API`) and the release signing config; it is not in git.

`OpenRouterModelRegistryTest` (27 tests) and one `SmartRouterTest` case fail on `main` and have
for a while — they are not yours. Diff the failure list, don't count it.

## 3. The frozen surfaces

Every one of these is shared with a binary that updates on a different schedule. **Append only.
Never insert, never renumber, never rename.** `ANDYCLAW.md` §3 in the ethOS tree is the
authority; the short version:

| Surface | Where | Rule |
|---|---|---|
| `org.ethereumphone.andyclaw.ipc.IHeartbeatService` | `app/src/main/aidl/…` **and** hand-written `Parcel.transact` in `AndyClawHeartbeatService.java` | OS → app, ordinals `FIRST+0…+5` are the contract on both sides. Authentication is `Binder.getCallingUid() == SYSTEM_UID` (`HeartbeatBindingService.enforceSystemCaller`) — the service is exported with no permission because `system_server` must be able to bind it before first unlock. |
| `com.android.server.IAndyClawHeartbeat` | raw `transact` on the `andyclawheartbeat` binder | app → OS, `FIRST+0…+3`. Gated OS-side by a package check on the calling UID. |
| `ILauncherService` | `app/src/main/aidl/…/ILauncherService.aidl` (69 methods) | Three copies: this one, the launcher's, SystemUI's (the first 6 only). This copy and the launcher's now agree at **every** ordinal 1–69: the `clawHub*` block the launcher has always had sits at 50–60 and is declared here as stubs purely to hold those slots, and the ambient/approval/ledger methods are appended at 61–69. Anything new goes at 70+, in both copies, in the same release. |
| `IAgentDisplayService` | `app/src/main/aidl/android/os/…` (47 methods) | AIDL-generated ordinals, mirrored by the framework's own copy. Append at 47. |
| On-disk state under `filesDir` | see §4 | Must survive an OTA from an arbitrary older build **and a rollback**. New fields are defaulted and trailing; new files are ignored by older code. |

A payload-format change to an existing method fails *silently* against an older counterpart.
Prefer a new method over a versioned payload.

## 4. Storage: `filesDir` and nothing else

The APK's writable state is its own sandbox — `HEARTBEAT.md`, `pending_approvals.json`,
`telegram_chats.json`, heartbeat logs, skills, memory DBs, `flows/` (§8), the ledger and
predicted-context databases, and `session_frames/` (§9).

`/data/andyclaw_files/` is **not** available to this app. It is `0771 system system`, labelled
`andyclaw_data_file`, and sepolicy grants it to `system_server` only; the APK has no rule and no
`sharedUserId`. Writing there needs a sepolicy change *and* a new binder method — that is an
OTA, and it turns a repo-1-only change into a three-repo release. If a feature seems to need it,
it almost certainly doesn't.

## 5. Tier: OPEN vs PRIVILEGED

`Tier` (`:AndyClaw` `skills/Tier.kt`) is a **device-capability** axis, resolved by
`OsCapabilities.currentTier()`. A skill exposes `baseManifest` (always) and an optional
`privilegedManifest` (ethOS only) — that is how a tool becomes unavailable on stock Android.

Tier is not a safety axis. What a tool *does* is `ToolEffect`
(`READ | REVERSIBLE | IRREVERSIBLE | SENSITIVE`), and where a request *came from* is
`Provenance` (`USER | TRUSTED | UNTRUSTED`). Keep the three separate; overloading one for
another is how a capability check quietly becomes the only security check.

## 6. The trust boundary

The agent reads content written by strangers — XMTP bodies, Telegram bodies, a 50-deep
notification buffer, OCR of arbitrary screens — and holds authority that asks for no
confirmation: the **agent sub-account** signs without a prompt, shell runs, messages go out.
That is the lethal trifecta, and it is the central design problem, not a hardening pass.

- `safety/ProvenanceGate` is the mechanism. It is registered **first** in
  `ExecutionEngineFactory.create`, before a rate-limit slot is spent or an approval prompt
  raised.
- `safety/ToolEffects` resolves a tool name to its effect: an explicit
  `ToolDefinition.effect` wins, then the seed table, then `ToolAttenuation.READ_ONLY_TOOLS`,
  then **`IRREVERSIBLE`**. Adding a builtin tool without classifying it makes it need approval —
  noisy, never unsafe. Keep it that way.
- **A gate is a `PreflightCheck`, never a prompt instruction.** Two reasons, both load-bearing:
  `execute_code`'s `ToolBridge` invokes any tool by name straight off `NativeSkillRegistry`,
  and in ToolSearch mode the model discovers tools mid-run. Anything that only lives in the
  system prompt is advice.
- The **user's** wallet path (`propose_*`, `send_native_token`, …) is protected by the SystemUI
  confirmation on the device's terminal screen and needs nothing from this app. Do not add a
  second dialog in front of it.
- Never log, persist, or transmit a private key, a mnemonic, or signing material.

New background trigger? It states its `Provenance` explicitly. The defaults are closed
(`AgentRunner.run` defaults to `UNTRUSTED`) so forgetting is safe, not silent.

## 7. Things that will bite you

- **A new skill is not enabled by adding it.** `agent.enabledSkills` is written once, at
  onboarding, from the ids registered at that moment, and `skillEnabledCheck` blocks anything
  outside it. Every device already in the field therefore has a set that will never contain a
  skill you add today — ship a one-shot seed alongside it (`NodeApp.seedFlowSkillEnabled` is the
  pattern) or the tools are dead on arrival.
- `ExecutionEngineFactory.create` is called **once per tool call** inside the hot loop
  (`AgentLoop.kt:547`, `:763`) and again per sub-agent batch. Anything it does per call is in
  the latency path — the tool-definition map is memoized per engine for exactly this reason.
- `AgentLoop.runSubagent` runs on the same `AgentLoop` instance, so a sub-agent inherits
  provenance, tier and skill set. It must never widen them.
- The messenger package is `org.ethereumhpone.messenger` (transposed "hp"); the agent is
  `org.ethereumphone.andyclaw`. Both spellings are load-bearing. Do not "fix" either.
- **A new pref is not a new feature.** Three switches ship default-off or need receivers
  started (`agent.ambientIngest`); a pref that nothing reads at runtime and no screen can
  reach is the same dead-on-arrival shape as an unseeded skill. Wire it into
  `SettingsScreen`, `SettingsViewModel` **and** `LauncherBindingService.getSettings` /
  `setSetting` — that last pair is a JSON blob and a string switch, so adding a key there
  costs no binder ordinal.
- `AGENTDISPLAYDEBUGKEY` in logcat dumps the assembled system prompt, the tool list and every
  tool result — the fastest way to see what the model actually saw.
  `adb shell am broadcast -a com.android.server.andyclaw.HEARTBEAT_NOW` forces a heartbeat.
- Heartbeat cadence and LLM spend come out of `user_funds.usd_balance`, which is **shared with
  sponsored gas**. A chatty background loop costs the user transactions, not just tokens.
- **The ambient paths have a floor; chat deliberately does not.**
  `NodeApp.getHeartbeatLlmClient()` — the executive summary and the heartbeat — is wrapped in
  `ZeroBalanceFallbackClient`, which re-runs a request on the bundled GGUF when the gateway
  refuses for lack of funds, so an empty balance degrades the ambient surface instead of
  stopping it. `getLlmClient()` is left unwrapped on purpose: a user who typed a question
  should be told their balance is empty, not handed a 1.5B answer and left to wonder. Only a
  403 whose body says `Insufficient balance` falls back, and only a stream that has not yet
  emitted a token; widening either is the bug this class exists to avoid.
- **The ledger records provenance, rung, outcome and duration — never a tool's input or its
  output.** `LauncherBindingService`'s ledger methods hand that store to the launcher, and to
  an export the user can take off the device. Anything added to a row is added to both.
- `HeartbeatPrompt.isContentEffectivelyEmpty` treats a header-only `HEARTBEAT.md` as "nothing to
  do", so seeding the file and setting an interval are two halves of one change — one without
  the other leaves the proactive agent silently doing nothing.

## 8. The execution ladder and compiled flows

`agent-os-design.md` §3 (in the ethOS tree): every intent resolves down a ladder, and
**never skips a rung to reach a lower one**.

| Rung | Here |
|---|---|
| 0 — native API | `WalletSkill`, `MessengerSkill`, `GmailSkill`, `GoogleCalendarSkill`, `TelegramSkill` |
| 1 — intents / AppFunctions | absent; `agent_display_launch_intent` is the nearest thing |
| 2 — notification RemoteInput | `NotificationSkill.reply_to_notification` |
| 3 — compiled flow | `flows/` + `FlowSkill` |
| 4 — VLM discovery | `AgentDisplaySkill` |

A tool says where it sits with `ToolDefinition.rung` and `targetPackages`; `skills/ToolRoutes`
seeds both for the built-ins that predate the ladder, the way `safety/ToolEffects` seeds
effects. `ExecutionEngineFactory.routeGateCheck` blocks a rung-4 call that names a package a
lower rung already covers, and **the block message names the better tool** — a tool result,
not a prompt line, for the same reason the provenance gate is a `PreflightCheck` (§6).

### Flows

A flow is a recorded UI task replayed with **no model in the loop**: `filesDir/flows/`,
content-addressed by sha256 of the canonical IR, HMAC'd with an AndroidKeyStore key
(`KeystoreFlowSigner`). It is executable code carrying the user's authority, so nothing is
trusted on sight — a flow whose hash or MAC does not verify is ignored, not replayed.

- **`FlowValidator` is a gate, not a linter.** Selectors are `view_id` only (text breaks on a
  locale change, coordinates on everything); pre- and postconditions are required; a
  `checkpoint:` must precede any irreversible step; a step touching payment or authentication
  makes the flow uncompilable outright. The IR can *express* the invalid forms so a recorder
  can record what really happened — refusal happens here, not by pretending.
- **`FlowInterpreter` never guesses.** Version pin, per-step perceptual checksum, bounded
  waits, postconditions, a wall-clock budget. Any mismatch aborts and the VLM path takes over.
- **Two effect questions, deliberately different.** `FlowStepEffects` classifies a *step* from
  its target, and only decides where the checkpoint must sit (a declaration can raise it, never
  lower it). `FlowToolEffect` classifies the *tool*: anything that actuates is `IRREVERSIBLE`
  and needs the user's approval before the replay starts. No heuristic guards the gate.
- **Staleness.** `PACKAGE_REPLACED` marks a package's flows stale; a stale flow keeps its tool
  so it can revalidate on next use but drops its `targetPackages`, so it stops standing in front
  of the display. Three consecutive aborts retire it and discovery recompiles.
- **Discovery pays for itself.** `AgentLoop.runSubagent` compiles the session it just drove
  (`RecordingDisplaySkill` → `FlowRecorder` → one model call → `FlowValidator` → install), so
  the expensive path runs once. Only under `USER`/`TRUSTED` provenance.

`tools/measure_warm_path.sh` reads the `AgentRunMetrics` line every run logs — turn latency and
model-call count — which is how the "< 1.5 s, zero model calls" criterion is checked rather
than argued about.

## 9. The ledger, the frames, and anticipatory context

Three subsystems that exist for one claim: the device can say what it did on your behalf, and
know what is about to matter to you, without a model being the source of either.

### The ledger — `:AndyClaw` `ledger/`

`agent-os-design.md` §6: append-only, hash-chained, on-device. One row per **turn** (what was
asked, what it cost) and one per **tool** (which tool, which rung, how it ended). Both in one
chain: `hash = sha256(prev_hash || canonical(row))`, so editing any field of any row breaks
its own hash and every hash after it.

- **`LedgerChain` is pure and separately tested.** The canonical form is length-prefixed
  rather than delimited — under a bare separator `a|b` and `ab|` hash the same, which would
  let a forged row pass. Field order is part of the format: appending is safe, reordering or
  removing invalidates every chain already on a device.
- **`LedgerRepository` is the only writer**, under a `Mutex`. Agent runs overlap routinely
  here (a heartbeat fires mid-chat), and two appends reading the same tail produce two rows
  claiming one position — indistinguishable from tampering later.
- **`LedgerRecorder` is the non-suspending front door**, one writer coroutine behind a bounded
  channel: the hot path never touches disk, and rows keep the order they were handed in
  because the order *is* the chain. Overflow is counted and written down, never swallowed.
- **Rows carry no tool input and no tool output.** Provenance, rung, outcome, duration, cost —
  and nothing that could be a message body. This is a store the user is invited to export.
- **Cost is nullable and null means unknown.** Most models this device runs are not in the
  OpenRouter price registry; rendering that as free would be a lie in the one screen whose job
  is being trustworthy.
- Fed by a `PostProcessor` in `ExecutionEngineFactory` (executions) **and** by
  `ExecutionCallbacks.onToolBlocked` (blocks, which never reach a post-processor). Exactly one
  row per call, whichever way the call ends — the ledger processor runs last, and a chain that
  a previous processor blocked never reaches it.
- Retention drops **prefixes only**. What is left still verifies; a hole in the middle would
  not, and that is the property the store exists to have.

### Frames — `:AndyClaw` `frames/`

`LauncherBindingService` was already pulling a JPEG a second off the agent display and
throwing it away. `SessionFrameStore` keeps them, and `ledger/SessionReplay` joins them to the
ledger rows on the session id, which is the whole of "watch exactly what I did as you".

The retention cap is the precondition, not a follow-up: 20 sessions, 64 MB, 600 frames per
session, evicted **whole sessions** oldest-first — half a recording replays as a jump cut and
misrepresents what happened. `SessionReplay` reports frames the ledger names but storage has
evicted, rather than quietly playing a shorter version.

### Anticipatory context — `:app` `ingest/`, `:AndyClaw` `ambient/`

**HARD RULE: never LLM-extract what is already structured.** The model decides *when to
surface*; it never decides what the gate number is.

`ingest/` is split so that rule is a property of the code rather than a promise:

- **Parsers are pure, non-suspend, dependency-free** — `JsonLdReservationParser` (schema.org
  in mail bodies), `BcbpParser` (IATA Resolution 792 in a boarding-pass barcode),
  `PkPassParser`, `PdfTextExtractor` (streams and string literals, *not* a renderer),
  `ICalParser`, `GoogleCalendarEventParser`. `IngestNoModelCallTest` scans their compiled
  bytes — synthetic lambda classes included — for any reference to an LLM client or an HTTP
  stack, and fails if one appears.
- **Sources fetch and decide nothing** — `GmailIngestSource`, `CalendarIngestSource`. They
  suspend, they hold OkHttp, and they are deliberately outside the scanned set.
- Decode base64 with **`java.util.Base64`**. `android.util.Base64` is a no-op stub under this
  project's unit tests and would make every ingestion test pass against nothing.
- Everything ingested is `UNTRUSTED` and is stored as **typed data**. It is never turned back
  into prose and fed to a model: mail bodies are the injection channel §6 is about.

`ambient/PredictedContext` is the store the Phase 4 card stack reads. Deduplication is on the
real-world thing (`flight:LH400:2026-09-01`), never on the message, so a confirmation, a
schedule change and the boarding pass converge on one card. `PredictedContextScorer` is a pure
per-kind relevance curve — SQL narrows the window, Kotlin ranks — so a flight two hours out
outranks a standup in twenty minutes, which is the intended answer and not an accident.

### The trigger inversion

The notification listener is event-driven and already existed; mail and calendar joined it via
`AmbientIngestManager`, and the 5–60 minute heartbeat became a **backstop**:
`HeartbeatConfig.backstopQuietMs` makes a scheduled tick within ten minutes of an event-driven
run skip with `HeartbeatSkipReason.RECENT_EVENT_TRIGGER`.

- Only paths that actually **ran the agent** open that window (`requestHeartbeatNow(eventDriven
  = true)`, `requestNowWithContext`, `NodeRuntime.noteAmbientActivity` from the reminder, cron,
  Telegram and XMTP paths). Ingesting updates what the device knows; it is not thinking, and it
  must not stand the schedule down.
- A user pressing the heartbeat button is **not** event-driven. Otherwise the manual control
  would quietly turn the schedule off.
- Zero disables the window, which is the right state for a device where the clock genuinely is
  the only thing that wakes the agent.
- `AmbientTriggerPolicy` decides how often a signal becomes a round trip, and the cooldown
  counts *any* ingest — an unlock two seconds after a mail notification has nothing to fetch.
  It looks only at which app posted a notification, never at its text.
