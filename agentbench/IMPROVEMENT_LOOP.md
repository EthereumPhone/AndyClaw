# The agentbench improvement loop

This is the exact process used to make AndyClaw better with agentbench: measure what the agent
does on a real Android system, find the root cause of every failure, fix the general cause, and
prove the fix with the same measurement. `README.md` explains how the harness works; this file
explains how to use it to improve the agent.

The first iteration (2026-09-28) is the worked example throughout: 12/14 → 14/14, and two of the
baseline "passes" turned out to be hollow.

---

## 0. Ground rules

1. **The device's state is the truth. The agent's reply isn't.** "Done! The phone is now named Andy
   Bench" was a lie in the baseline. Every task checks settings, app data or the contacts DB, or the
   service's own launch log (`launched`), and only uses `reply_regex` when the answer *is* the
   deliverable.
2. **Fix causes, not tasks.** A fix has to help requests the suite doesn't contain. "The model
   guessed a package name" is a cause. "The calculator task fails" is a symptom. If a change would
   only make one prompt pass (a special case, a prompt line naming the app), don't make it.
3. **Prefer a fix at the tool boundary over a prompt instruction.** This matches AndyClaw's own rule
   (CLAUDE.md §6). A tool that refuses a wrong input and names the right one works for every model and
   every phrasing; a sentence in the system prompt is advice.
4. **Suspect the harness first.** In iteration 1, half the alarming signals were harness or emulator
   artefacts: a daemon killed by an ethOS-only framework method, a 32-bit shell overflowing a
   timestamp, apps that crash without ethOS classes, and Chrome state left over from an earlier run.
   Before blaming the agent, prove the environment was sound (§3.2).
5. **One run is an anecdote.** Timing and model-call counts vary from run to run. Confirm with
   `--repeat 3` before believing a gain, and never claim a regression or improvement from n=1.

---

## 1. Measure

```bash
agentbench/agentbench up                 # boot + provision; idempotent
agentbench/agentbench selftest           # the OS side alone: must pass before anything else
agentbench/agentbench suite --name baseline
```

`suites/<ts>_baseline/report.md` gives the pass rate plus, per task: median wall time, model calls,
tool calls (and how many were tool *searches*), prompt tokens, cache-hit ratio, and output tokens.

Also read the **passes**. A pass that took 17 model calls (`dnd_on`), or one whose autopilot
ended in `failed` or `handoff`, is a finding too. In iteration 1 `calculator_multiply` passed without
the calculator ever being opened: the model did the multiplication itself. That led to the
`launched` check.

---

## 2. Triage every failure and every expensive pass

For each run, open these in order:

| Look at | For |
|---|---|
| `transcript.md` → *Model tool calls* | What the model *decided*, with full inputs. Most root causes are visible here: a guessed package, the wrong namespace, a reminder instead of an alarm. |
| `transcript.md` → *Timeline* | What each tool *returned* (summary + detail), autopilot steps, errors, timing. |
| `contact.jpg` | What was actually on the agent display, frame by frame with timestamps. |
| `logcat.txt` | Everything else, grepped by tag (below). |

Useful logcat tags:

| Tag | Tells you |
|---|---|
| `AGENTDISPLAYDEBUGKEY` | The full system prompt, tool list, every `TOOL_CALL` input and every tool `RESULT` |
| `AgentRunMetrics`, `AgentLoop` (`TokenStats`, `SUMMARY`) | Model calls, iterations, tokens and cache use per call |
| `ToolSearchService` | What each `search_available_tools` query returned, and whether siblings were auto-loaded |
| `JevTurnRouter` | Jev's pick of `needsUi` and app for the request |
| `AutopilotStep`, `AutopilotTool`, `AutopilotPlanner` | Every autopilot step, confidence, escalation reason, and how the run ended |
| `ExecEngine`, `ExecEngineFactory` | Pre-flight gates (route gate, provenance) and blocks |
| `BudgetConfig` | `truncateToolResult: N -> 3000`, i.e. the model saw only part of a result |
| `AgentDisplayService`, `AgentDisplayDaemon` | The OS side: launches (`Launched <component> on display N`), refusals, crashes |
| `AndroidRuntime` | Crashes. The harness separates AndyClaw/daemon crashes from other apps' |

Then put the failure in exactly one bucket:

| Bucket | Question | Iteration-1 examples |
|---|---|---|
| **Harness / environment** | Would this happen on a dgen1? | daemon killed by `NoSuchMethodError`; ethOS apps crashing; a date check overflowing |
| **Capability gap** | Is there *no tool* that can do this? | no alarm tool; no way to write a global setting |
| **Tool contract** | Did a tool accept a bad input silently, or return something unusable? | `write_secure_setting("device_name")` "succeeded"; a 27 kB app list cut to 3 kB |
| **Information flow** | Did the system know the right answer and fail to hand it on? | the router picked `com.dgen.dgencalculator`, and the autopilot got the model's guess |
| **Discovery** | Did search surface the wrong tool, or cost extra round trips? | the reminder hint said "alarm timer"; `select:` needed to load a tool just found |
| **Perception / acting** | Did the autopilot misread the screen or miss a tap? | the Wi-Fi page's spinner read as "still loading" |
| **OS policy** | Does the OS deny what the tool needs? | DND access is grantable only by system or shell |
| **Model judgement** | Right tools, bad decision? | false success claims (the tool contract usually fixes these) |

---

## 3. Find the root cause

### 3.1 Trace the decision back to its input

Ask *why the model did X* and follow the answer into the code. For example:

- The model used `create_reminder` for an alarm → what did the search return? `ToolSearchService` log:
  `set_volume, cancel_reminder, create_reminder, …`, with no alarm tool anywhere →
  `grep -rn ACTION_SET_ALARM` → no tool exists → and `SearchHints` described `create_reminder` as
  *"alarm timer schedule alert"*.
- The model never saw the Notes app → `BudgetConfig: truncateToolResult: 26691 -> 3000` → `listApps()`
  returned all 248 packages in PackageManager order.
- The autopilot got `com.google.android.calculator` → yet `JevTurnRouter: route … app=com.dgen.dgencalculator conf=0.99`
  → `JevTurnRouter` used the pick only for prelaunch, gated at `needsUi ≥ 0.85`, and the value was 0.84.

Stop when you reach code that should have behaved differently. That's where the fix goes.

### 3.2 Rule out the environment

Before fixing agent code for a failure, check:

- `agentbench status`: is `agentdisplay` up? A dead daemon shows up as `AgentDisplayService not available`.
- Did an app crash? The transcript lists other apps' crashes separately.
- Does the check itself work? Run its command by hand against a known-good state.
- Is there state left over from an earlier run? Every task that touches an app's data resets it in
  `setup` (`pm clear …`).

If the cause is environmental, fix the **harness**, not AndyClaw, and write it down in README's
"Known gaps" or in memory so nobody chases it again.

### 3.3 Check whether it's a device bug

Some emulator findings are real on the phone. `set_dnd_mode` fails on the emulator *and* on every
dgen1, because `config_defaultDndAccessPackages` in the tree lists only the camera and
`setNotificationPolicyAccessGranted` requires a system or shell caller. Grep the ethOS tree to
decide. Anything that needs an OTA is reported to the owner, not changed from this loop.

---

## 4. Fix

Where to put the fix, in order of preference:

1. **The tool contract.** Refuse bad input and name the right input (the wrong-namespace settings
   write); add the missing tool (`set_alarm`, `write_global_setting`); make results fit the
   budget (`list_installed_apps`).
2. **Information flow.** Pass what the system already knows to where it's needed (the router's
   app pick → autopilot).
3. **Discovery metadata.** `SearchHints`, tool descriptions, including correcting ones that
   mislead.
4. **Autopilot policy** (`StepPolicy`, thresholds). Only with traces showing the policy is the
   cause, and with the `AutopilotStep` log before and after.
5. **The system prompt.** Last resort, and never as the only protection for anything safety-related.

AndyClaw rules that every fix must respect (from AndyClaw's CLAUDE.md):

- **Add a tool to an existing skill; don't create a new skill.** `enabledSkills` is written once at
  onboarding, so a new skill is dead on arrival for the ~6,000 phones already set up.
- **Classify every new tool**: `ToolEffects` (READ / REVERSIBLE / IRREVERSIBLE / SENSITIVE), plus
  `ToolAttenuation`, `ApprovalSummaries`, `SearchHints`, and `NO_THIRD_PARTY_TEXT` where they apply. An
  unclassified tool is treated as IRREVERSIBLE.
- **Frozen surfaces are append-only**: `ILauncherService`, `ILauncherCallback`,
  `IAgentDisplayService`, the heartbeat binder codes, and on-disk formats.
- **A new permission in the manifest** must be *normal*, or be added to the ethOS tree's privapp
  allowlist in the same release. A privileged permission missing from the allowlist bootloops the
  phone.
- **Tests for behaviour the harness found.** A bug the harness exposed gets a unit test when it can
  be expressed as one (`ToolExecutionCallbackTest`: each call announced exactly once).

---

## 5. Verify

```bash
cd ~/AndroidStudioProjects/AndyClaw
./gradlew :AndyClaw:test :app:testDebugUnitTest      # then diff the failures:
#   exactly OpenRouterModelRegistryTest ×27 + SmartRouterTest ×1 fail on main; anything else is yours
./gradlew :app:compileReleaseKotlin                  # debug-only code must not leak into release

agentbench/agentbench build andyclaw                 # build + install over the system stub
agentbench/agentbench suite --name fixN-targeted --only <tasks the fix is for> --repeat 2 \
    --baseline suites/<baseline>
agentbench/agentbench suite --name iterN --baseline suites/<baseline>    # no regressions elsewhere
```

Read the targeted runs' transcripts, not only the pass mark. In iteration 1 the first targeted run
went **0/6**. The routing fix was working: the right apps opened. It was the apps that crashed, the
daemon that died, and two checks that were wrong. Only the transcripts showed that.

If a harness bug is fixed after a suite has run, `agentbench reanalyze suites/<dir>` recomputes the
metrics from the saved logs.

---

## 6. Record

- **Commit** each fix on its own, with the trace that motivated it in the message.
- **Keep** the suite directories: `results.json` + `report.md` are the before/after.
- **Add a task** when a fix is for something the suite didn't cover, and make it safe on a real phone
  (nothing sends, posts or buys).
- **Report**, not change, anything that needs an OTA.

---

## 7. Keep the suite hard

Once the suite is 100%, it no longer tells better from worse. Add tasks just past the current
ability: multi-app flows, reading something several screens deep, apps with first-run obstacles,
ambiguous phrasing ("make the screen stay on longer"). Keep `tasks/device-only.json` for anything
that needs the dgen1's own apps.

---

## Iteration 1, in full

| Finding | Evidence | Root cause | Fix | Result |
|---|---|---|---|---|
| Alarm became a reminder | `TOOL_CALL create_reminder`; search listed no alarm tool | no alarm tool; the reminder's search hint said "alarm timer" | `set_alarm` (ACTION_SET_ALARM, handler resolved, SKIP_UI); hints corrected | ✅ 6 s, 3 calls |
| Model guessed package names | autopilot `app_not_installed`; router log had the right app | the router's pick went only to prelaunch, gated on `needsUi ≥ 0.85` | the autopilot falls back to the router's pick; the refusal lists installed apps | ✅ correct apps open |
| Notes app never found | `truncateToolResult: 26691 -> 3000` | `list_installed_apps` returned all 248 packages | launchable apps only, sorted, `query`, `include_all` | ✅ fits the budget |
| "Phone renamed", wasn't | `write_secure_setting device_name` → `success` | a put creates any key; no global writer | wrong namespace refused, and names the right one; `write_global_setting` | ✅ 7 s, verified |
| Launcher saw every tool twice | two `tool_start` per call | the streaming executor and the engine both announced | removed the executor's duplicate; `ToolExecutionCallbackTest` | ✅ |
| DND takes 45–68 s | `Notification policy access denied` | the OS grants DND access only to system/shell callers | **reported**: needs system_server to grant it (OTA) | open |
| Daemon died mid-suite | `FATAL EXCEPTION IN SYSTEM PROCESS … NoSuchMethodError` | an ethOS-only `IActivityTaskManager` method on a stock image | `StockImageGuard` in the daemon; the self-test covers it | harness |
| ethOS apps crash | `ClassNotFoundException: android.os.FreemeProxy` | they need ethOS framework classes | taken off the emulator; tasks moved to `device-only.json` | harness |

Open for iteration 2:
- The autopilot hands over on every Settings task.
- Jev locks itself out for the rest of a run after one error.
- `select:` costs a round trip for a tool that was just found.
- The prompt cache is rewritten whenever the tool list grows.
- The suite needs harder tasks.
