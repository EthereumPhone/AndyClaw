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
| `:AndyClaw` | The reusable engine. Abstractions and cross-cutting types: `ExecutionEngine/*`, `agent/AgentRunner`, `heartbeat/*`, `skills/` interfaces (`AndyClawSkill`, `SkillManifest`, `ToolDefinition`, `ToolEffect`, `Tier`), `memory/`, `sessions/`, `extensions/`. No concrete skills, no Android UI. |
| `:app` | The wiring. `NodeApp` (the object graph), `NodeRuntime`, the binder services, `agent/AgentLoop`, `llm/*` transports, ~50 `skills/builtin/*` skills, `safety/*`, Compose UI. |
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
| `ILauncherService` | `app/src/main/aidl/…/ILauncherService.aidl` (49 methods) | Three copies: this one, the launcher's (longer — it has a `clawHub*` block at 50–60 this app lacks), SystemUI's (the first 6 only). New methods go **after** the launcher's `clawHub*` block. See the comment at `ILauncherService.aidl:160`. |
| `IAgentDisplayService` | `app/src/main/aidl/android/os/…` (47 methods) | AIDL-generated ordinals, mirrored by the framework's own copy. Append at 47. |
| On-disk state under `filesDir` | see §4 | Must survive an OTA from an arbitrary older build **and a rollback**. New fields are defaulted and trailing; new files are ignored by older code. |

A payload-format change to an existing method fails *silently* against an older counterpart.
Prefer a new method over a versioned payload.

## 4. Storage: `filesDir` and nothing else

The APK's writable state is its own sandbox — `HEARTBEAT.md`, `pending_approvals.json`,
`telegram_chats.json`, heartbeat logs, skills, memory DBs.

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

- `ExecutionEngineFactory.create` is called **once per tool call** inside the hot loop
  (`AgentLoop.kt:547`, `:763`) and again per sub-agent batch. Anything it does per call is in
  the latency path — the tool-definition map is memoized per engine for exactly this reason.
- `AgentLoop.runSubagent` runs on the same `AgentLoop` instance, so a sub-agent inherits
  provenance, tier and skill set. It must never widen them.
- The messenger package is `org.ethereumhpone.messenger` (transposed "hp"); the agent is
  `org.ethereumphone.andyclaw`. Both spellings are load-bearing. Do not "fix" either.
- `AGENTDISPLAYDEBUGKEY` in logcat dumps the assembled system prompt, the tool list and every
  tool result — the fastest way to see what the model actually saw.
  `adb shell am broadcast -a com.android.server.andyclaw.HEARTBEAT_NOW` forces a heartbeat.
- Heartbeat cadence and LLM spend come out of `user_funds.usd_balance`, which is **shared with
  sponsored gas**. A chatty background loop costs the user transactions, not just tokens.
- `HeartbeatPrompt.isContentEffectivelyEmpty` treats a header-only `HEARTBEAT.md` as "nothing to
  do", so seeding the file and setting an interval are two halves of one change — one without
  the other leaves the proactive agent silently doing nothing.
