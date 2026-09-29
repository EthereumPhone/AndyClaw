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

Both suites are green. `:app` tests get the real `org.json` (`testImplementation`), because the
`android.jar` stub returns null under `isReturnDefaultValues` — a test that parses JSON against the
stub fails with an NPE that looks like a product bug.

## 3. The frozen surfaces

Every one of these is shared with a binary that updates on a different schedule. **Append only.
Never insert, never renumber, never rename.** `ANDYCLAW.md` §3 in the ethOS tree is the
authority; the short version:

| Surface | Where | Rule |
|---|---|---|
| `org.ethereumphone.andyclaw.ipc.IHeartbeatService` | `app/src/main/aidl/…` **and** hand-written `Parcel.transact` in `AndyClawHeartbeatService.java` | OS → app, ordinals `FIRST+0…+5` are the contract on both sides. Authentication is `Binder.getCallingUid() == SYSTEM_UID` (`HeartbeatBindingService.enforceSystemCaller`) — the service is exported with no permission because `system_server` must be able to bind it before first unlock. |
| `com.android.server.IAndyClawHeartbeat` | raw `transact` on the `andyclawheartbeat` binder | app → OS, `FIRST+0…+3`. Gated OS-side by a package check on the calling UID. |
| `ILauncherService` | `app/src/main/aidl/…/ILauncherService.aidl` (71 methods) | Three copies: this one, the launcher's, SystemUI's (the first 6 only). This copy and the launcher's agree at **every** ordinal 1–71: the `clawHub*` block the launcher has always had sits at 50–60 and is declared here as stubs purely to hold those slots, the ambient/approval/ledger methods are at 61–69, `stopAgent` at 70, `resolvePendingApprovalWithResult` at 71. Anything new goes at 72+, in both copies, in the same release. `resolvePendingApproval` (64) with `approved = true` only acknowledges now — it never runs anything, so no older caller can double-act. |
| `ILauncherCallback` | `app/src/main/aidl/…/ILauncherCallback.aidl` (10 methods, oneway) | Launcher's copy must match. `onAgentStep(json)` is 10; an older launcher ignores it. Its JSON gained `outcome` and `message` (§8); `kind` stays `FAILED` for a hand-over so an older launcher still finishes its card. |
| `IAgentDisplayService` | `app/src/main/aidl/android/os/…` (59 methods) | AIDL-generated ordinals, mirrored by the framework's own copy (byte-identical order). 48–59 are the v2 autopilot block (`@hide` in the framework). Append at 60. Check `getAgentApiVersion() >= 2` before any v2 call — an older OS answers them with 0/null. `>= 3` (`AgentDisplayCapabilities.hasV3`) adds the HUD's `HANDOFF` phase and `HOLD` action, and node actions and launches that honour the STOP latch. Also `IAgentDisplayListener`/`IAgentHudListener`. |
| On-disk state under `filesDir` | see §4 | Must survive an OTA from an arbitrary older build **and a rollback**. New fields are defaulted and trailing; new files are ignored by older code. |

A payload-format change to an existing method fails *silently* against an older counterpart.
Prefer a new method over a versioned payload.

## 4. Storage: `filesDir` and nothing else

The APK's writable state is its own sandbox — `HEARTBEAT.md`, `pending_approvals.json` and
`approval_outcomes.json` (§6), `trigger_provenance.json` (§6), `telegram_chats.json`, heartbeat
logs, skills, memory DBs, `flows/` (§8), the ledger and predicted-context databases, and
`session_frames/` (§9).

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

- **A stranger's run reads nothing private.** `ProvenanceGate.privacyVerdict`: the clipboard
  readers (`ToolEffects.CLIPBOARD_READS`) are closed to every `UNTRUSTED` run; the private-data
  tools (`PRIVATE_DATA_TOOLS` — messages, contacts, mail, memory, location, files, holdings, …)
  are closed to a run whose reply goes to someone other than the owner (`ReplyAudience`, set by
  the Telegram and XMTP runners); and an untrusted run that has read private data may not
  reach the web afterwards (`AgentRunToken.readPrivateData`). Non-owner Telegram runs are built
  without memory or the user story. `ToolBridge` applies the same gates.
- **A run cannot schedule one with more authority than it has.** `create_cronjob`,
  `cancel_cronjob`, `create_reminder` and `cancel_reminder` are `IRREVERSIBLE`, and a fired job
  runs with the provenance of the run that created it (`TriggerProvenanceStore`,
  `trigger_provenance.json`) — the owner's own as `TRUSTED`, a background task like the
  heartbeat. **A job with no entry runs as `UNTRUSTED`**: it predates the file, when a stranger's
  message could create one, or its entry was lost, and nobody can vouch for it. Before this, one
  message from a stranger could create a job that paid out from the agent wallet, forever.
- **A trusted run nobody watches loses its authority to what it reads.** The heartbeat reads
  notifications as part of its task, and a notification is a stranger's text: one saying "send
  0.05 ETH to 0x…" reached the promptless agent wallet that way. Once a `TRUSTED` run has had a
  result that can carry another person's words — every tool but `ToolEffects.NO_THIRD_PARTY_TEXT`,
  so a new tool fails closed — `AgentRunToken.readThirdPartyContent` is set, an `IRREVERSIBLE`
  call needs approval (`ProvenanceGate.taintedTrustedRunNeedsApproval`), and the headless runner
  queues it as a card rather than approving it. Messages to the owner stay open. `USER` runs are
  not affected: somebody is watching them.
- **The Telegram owner is the code-verified chat id and nothing else** (`TelegramOwner.isOwner`,
  `securePrefs.telegramOwnerChatId`; 0 means nobody). `TelegramChatStore`'s first chat used to be
  the owner, so a stranger who wrote first got the owner's audience, memory and Approve buttons.
  Phones set up before the verified id existed are strangers until they redo setup — by design.
- **Standing instructions are written only by an untainted owner.** `HEARTBEAT.md`, `soul.md` and
  `user_story.md` are fed to every later TRUSTED run, so `write_file` to them needs a `USER` run
  that has read no third-party text, and `update_soul` is blocked in a tainted run
  (`ProvenanceGate.standingInstructionVerdict`) — a block, not an approval, because YOLO approves
  everything.
- **The agent's file tools don't touch AndyClaw's own state** (`FileSystemSkill.PROTECTED`: the
  job provenance store, the approval queue and outcomes, `telegram_chats.json`, `flows/`,
  `session_frames/`). Rewriting any of them was a way to hand a stranger the owner's authority.
- **APPROVE runs the exact call.** A call a background run was refused is stored whole in
  `pending_approvals.json` (v2: canonical input ≤ 16 KiB, HMAC under the keystore alias
  `andyclaw_approval_hmac`, 24 h, at most 3 per conversation and 10 in all). The launcher's
  APPROVE (ordinal 71) hands it to `PendingApprovalExecutor`, which runs that one tool with that
  one input — no model — under the call's **original** provenance, with `ExactCallCallbacks`
  approving nothing else, and records it in the ledger session that was refused. `claim()` is
  once only; a request found mid-execution after a process death becomes `UNKNOWN` and is never
  re-run. Finished requests move to `approval_outcomes.json`, so a rollback cannot resurrect
  them. Input `LeakDetector` flags, an entry whose MAC does not verify, an entry from an
  older build and a transaction with calldata (hex nobody can review) are decline-only. An
  executable card shows every parameter whole — what APPROVE runs is exactly what was shown.
  The display tools answer "busy" as their own result; the executor puts such a request back to
  PENDING. Outside senders share one trigger budget across identities, and the owner's own
  requests make room in a queue strangers filled.
- Headless heartbeats queue `SENSITIVE` tools as pending approvals instead of approving them.
- **The home screen asks too** (`services/LauncherApprovalPolicy`). The launcher cannot be asked
  mid-turn (no approval callback on `ILauncherCallback`, and adding one is an ordinal), so its
  turns used to approve everything. Now a launcher turn queues the exact call — source
  `launcher`, provenance `USER`, `conversationId` = the launcher's session id, the turn's ledger
  session — when the tool is `SENSITIVE`, or the run has read someone else's words
  (`AgentRunToken.readThirdPartyContent`), or it is not `USER`; the launcher shows it inline in
  that conversation with APPROVE → device credential → run once. The model is told it is waiting
  (`ExecutionCallbacks.notApprovedMessage`, `LauncherApprovalPolicy.QUEUED_FOR_MODEL`), and the
  launcher gets an `onToolResult` whose summary starts with exactly `Waiting for your approval`,
  its cue to refresh the cards mid-turn. A plain untainted request of the user's own still runs;
  YOLO approves everything as in AndyClaw's own chat. A lock-screen turn (SystemUI's
  `sendLockscreenPrompt`) queues every such call, YOLO or not. `LauncherApprovalPolicyTest`
  fails if an `onApprovalNeeded` in `LauncherBindingService` goes back to `return true`.
- **A launcher caller is its name and its key** (`services/CallerPolicy`): the launcher or
  SystemUI by package name *and* the system uid or AndyClaw's signing key (the ethOS build signs
  all three with the platform key). The name alone let any app called
  `org.ethosmobile.ethoslauncher` read every key off a phone that is not a dgen1.
  `sendLockscreenPrompt` is SystemUI's only.

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
- **Every run carries an `AgentRunToken`** (a coroutine-context element; sub-agents share it):
  the display lease, STOP and the ledger session all hang off it. Tools run on `Dispatchers.IO`
  as children of the run, so cancelling a turn reaches the tool that is running. A tool's
  generic `catch (e: Exception)` must `rethrowIfCancelled(e)` first, or a cancel turns into an
  ordinary error and the run carries on. `execute_code`'s `ToolBridge` passes the context
  through to the tools it calls.
- **Anything that moves money is not half-cancellable.** The agent wallet's submit and parse run
  under `NonCancellable`: a cancel after submission used to report failure and skip the history
  row, which invites a second send.
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
  the other leaves the proactive agent silently doing nothing. Onboarding seeds the starter list;
  for phones onboarded before it did, `HeartbeatBindingService` seeds it once, the first time it
  finds the heartbeat switched on with the list still empty (`heartbeat.seededDefaults`).

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
  waits, postconditions, a wall-clock budget. Any mismatch aborts and the VLM path takes over —
  unless the flow has **committed**: once it has acted past its checkpoint, performed an
  irreversible step, or sent out its **last action** (a flow ending on "Save" has done its task
  there), an abort is reported as "performed X but could not confirm — do not repeat it" and
  nothing falls back (`FlowRunAccounting.mayFallBack`). An action counts only once it may have
  happened (`FlowDispatch`: a certain "never reached the app" is not a commit, an unclear answer
  is). Postconditions are polled for up to 3 s, because a slow app's "sent" bubble arriving late
  used to send the message twice.
- **The right row, never a payment, never blind.** A target not on the screen yet is waited for
  (1.5 s, stop-aware) and never clicked blind; the checks run on the very tree the action acts
  on. A target that matches more than one live node must be followed by a passing identity
  assert — the value as a whole word, polled — before the checkpoint, or the replay aborts
  `AMBIGUOUS_TARGET`. A live target that reads as payment, login or a password field, or a
  private app on screen, aborts `SENSITIVE_TARGET`, which never falls back.
- **STOP aborts a replay** (`STOPPED`, checked before every step and inside every wait). It is
  never counted against the flow, never marks it stale and never falls back. Neither do
  `MISSING_PARAM`, `CHECKPOINT_REFUSED`, `DISPLAY_UNAVAILABLE` or `APP_NOT_INSTALLED`: only
  faults retire a flow.
- **A checkpoint needs a real approval.** `FlowCheckpointPolicy.mayCross` wants the
  `UserApproval` witness `ParallelExecutionEngine` sets for an approved call, unless
  `noConfirm` is on for a `USER`/`TRUSTED` run; `noConfirm` is part of the manifest's memo key.
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

### The autopilot (JevPilot) — `:AndyClaw` `autopilot/`, `:app` `autopilot/`

`agent_display_autopilot` takes a plan (sub-goals + every literal to type in `values`) and
drives the app itself: per step one Jev call (`StepPromptBuilder`) answers all questions at
once, `StepPolicy` acts only above calibrated thresholds (higher for commit-like actions),
`LoopGuard` catches loops and no-ops, and anything unresolved goes to a small
`LlmAutopilotPlanner` call or back to the main loop as `needs_planner`. It never taps
payment/auth elements — the same hard rule as `FlowValidator`. Jev is reached through the
backend's `/api/jev` with the wallet sign-in (`JevHttpClient`).

- Always on for PRIVILEGED via `ToolSearchService.DGEN1_BUILTIN_ALWAYS_ON`; exempt from the
  rung-3 route gate (it runs flows itself); `IRREVERSIBLE`, so UNTRUSTED cannot reach it.
- A successful run whose steps all hit unique view ids compiles to a flow mechanically
  (`AutopilotFlowCompiler`, no model call) with the plan stored as `Flow.intent`; a flow that
  aborts on a changed UI re-runs the autopilot from that intent.
- `agent.autopilot.noConfirm` (default on) runs irreversible flows without the approval card
  for USER/TRUSTED only.
- Screen reads are in-process (`AgentDisplayAccessibilityService.snapshot`, all windows);
  settling is event-driven (`ScreenSettler`) plus the OS frame-quiet check.
- `AgentLoop` ends a turn with the autopilot's `say` (no extra model call) and elides old UI
  trees from history (`pruneOldUiTrees`). A compiled flow for the same task is replayed first
  (`FlowFirst.select`) — matched on the app, the stored goal and exactly the plan's value keys,
  **never on the id**, which is a 40-character slug that collided ("turn Wi-Fi on"/"off", every
  non-Latin goal). New ids carry a hash of the task; a recompile replaces the older flow.
- **One run owns the display.** `AgentDisplayLease.claim` on the first display tool call; only
  the owner puts the display away, when its run ends (`AgentDisplaySkill.onRunFinished`); while
  a live owner holds it, other runs' display tools answer "busy". Ownership follows the owner's
  `Job`, so a cancelled or finished run never blocks the next. `cleanupAll()` no longer parks:
  it used to, from the end of *any* run, under an autopilot still driving.
- **STOP stops.** The rear hold/swipe, the launcher and the live view all stop the run that held
  the display when STOP was pressed (`AgentDisplayLease.noteStop` → `AgentRunToken.stopRequested`).
  The executor checks it before every action and after every Jev or planner reply, and abandons
  a call in flight; `AgentLoop` then ends the turn with one line and no model call. After it no
  tool of that run starts at all — a pre-flight check, and `ExecutionCallbacks.vetoStart` as the
  tool starts, since an approval dialog can outlast a STOP — and sub-agents stop before their
  next model call and never compile the stopped session. A STOP aimed at an earlier run cannot
  stop this one, and a new run clears the OS latch by re-creating the display.
- **A cancelled call still leaves its row.** A tool the turn's cancel interrupted may have
  finished underneath (the agent wallet's send runs under `NonCancellable`);
  `ExecutionCallbacks.onToolInterrupted` writes its ledger row as "it may have run".
- **Every run ends, and a hand-over is not a failure.** Events and results carry `outcome`
  (`success|handoff|failed|stopped|cancelled`) and a human `message` (`AutopilotOutcome`);
  planner malfunctions hand over rather than fail; a throw or a cancel still emits a terminal
  event, so no UI is left on "running".
- **Private apps stay private** (`SensitiveApps`): refused by both launch tools, every node
  action, `get_node_info` and every tree read (any application window, not just the top one),
  and their frames are dropped from replays and recordings. A display with no windows reads as
  empty — never as the main screen's windows.
- UX: `ui/autopilot/` live view (mirror, focus ring, ticker, STOP, share replay), rear HUD via
  `setHudState`, launcher `onAgentStep`. Replays black out editable/password fields.
- **Pre-execution** (`agent/JevToolPrefetch`, `agent.jevPrefetch`, default on): before the first
  model call of a `USER` turn, one Jev call may pick one READ, argument-free, no-egress tool from
  the turn's own tool list; the loop runs it through the ordinary engine and puts the call and its
  result in history, so "what's my battery?" is one model call instead of two. Only when the
  request shares a word with the tool, above 0.85, within 700 ms — a miss costs nothing. Never
  with a Tinfoil client (the request would leave the enclave) or a local model. `AgentRunMetrics`
  logs `preExecuted=`.
- Measure with AndyBench (Agent Display developer screen) and `adb logcat -s AutopilotStep AgentRunMetrics`.

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
- **The `intent` column is never somebody else's text.** Background runners pass a label
  (`BackgroundIntent`: "Heartbeat tasks", "Reminder: <label>", "XMTP message from 0x12…cd"),
  the user's own requests are kept with pasted keys redacted, and sub-agent rows never carry the
  delegated task.
- **What the launcher shows is derived, never stored.** `LedgerDigest` adds `displayIntent`,
  `trigger`, a TURN's `summary{toolsRun, toolsBlocked, toolErrors, sideEffects,
  sideEffectTools, frames}` and `actedOnBehalf` to the binder JSON only. A step belongs to the
  first TURN after it in its session, so a run gives back the display, and writes its
  recording, before its own TURN row.
- **The database is frozen at schema 1** (pinned by a test): new data goes in a sibling
  database. A ledger from a newer build, met after a rollback, is left untouched and this build
  records into memory, so the chain survives the roll-forward.
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

Recording follows the display lease (`AgentDisplayRecording`), not the launcher, so a heartbeat,
Telegram or cron run that drives the display is recorded too. `DisplayRecorder` keeps a frame
at most once a second and only when the screen changed, never a private app's (those are
counted), and caps each recording rather than the conversation.

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

- Only a **successful event-driven run of HEARTBEAT.md** opens that window
  (`HeartbeatRunner.runOnce(eventDriven = true)`). Reminders, cron jobs, Telegram and XMTP
  messages and context runs never do: they did not work through the user's list, and when they
  could, a stranger writing every nine minutes suppressed the user's own tasks for good. An
  event-driven run is never stopped by the window. Ingesting updates what the device knows; it
  is not thinking, and it must not stand the schedule down.
- `NodeRuntime` builds the runner with its configuration and reaches the agent runner through a
  delegate. Built bare, the runner that actually ran on every dGEN1 had a window of zero. The OS
  tick re-reads the settings and awaits `runNow`, which is single-flight with one coalesced
  trailing run.
- `NotificationTriggerPolicy` decides which notifications wake the agent: new and alerting only,
  not from an app the agent just acted in, and no sooner than max(window, 5 min) after the last.
  `TriggerBudget` gives each outside sender (XMTP, a Telegram chat that is not the owner's)
  three runs in a row, then one per ten minutes.
- A user pressing the heartbeat button is **not** event-driven. Otherwise the manual control
  would quietly turn the schedule off.
- Zero disables the window, which is the right state for a device where the clock genuinely is
  the only thing that wakes the agent.
- `AmbientTriggerPolicy` decides how often a signal becomes a round trip, and the cooldown
  counts *any* ingest — an unlock two seconds after a mail notification has nothing to fetch.
  It looks only at which app posted a notification, never at its text.
