# agentbench

Runs AndyClaw on an emulator the way a dgen1 user reaches it — a prompt typed into the
launcher's dGENT tab — and measures what it did. One command per prompt or per suite; nothing
to tap on the emulator.

```bash
agentbench/agentbench up                          # boot + provision (idempotent, ~2 min cold)
agentbench/agentbench run "Turn on dark mode"     # one prompt, streamed live, saved in runs/
agentbench/agentbench suite                       # tasks/core.json, checked against device state
agentbench/agentbench suite --only calculator_multiply,note_groceries --repeat 3
agentbench/agentbench run --set autopilotEnabled=false "…"   # any launcher setSetting key
agentbench/agentbench cycle                       # rebuild AndyClaw → reinstall → suite → diff vs last suite
agentbench/agentbench compare suites/A suites/B
```

Credentials live outside git in `~/.config/agentbench/secrets.json`:

```json
{ "walletAddress": "0x…", "walletSignature": "0x…" }
```

`walletSignature` is the address's signature of `Signing into AndyClaw` (chainId 8453), which is what
AndyClaw's onboarding stores. It gives the dgen1 defaults: ETHOS_PREMIUM models and Jev. An
`"openrouterApiKey"` works instead, but Jev needs the wallet sign-in, so the autopilot will then
hand every task back to the model.

## What runs where

```
host: agentbench (python)
  │  adb shell am instrument -w -r  (one blocking call per prompt, events streamed back)
  ▼
agentbench-client.apk  ── binds LauncherBindingService, calls sendPrompt(prompt, session, ILauncherCallback)
  │                       exactly as the launcher does; records every callback + display frame
  ▼
AndyClaw (debug build, /system/priv-app stub + update → FLAG_SYSTEM → Tier.PRIVILEGED)
  │  ServiceManager "agentdisplay"
  ▼
agentdisplayd  ── the ethOS tree's AgentDisplayService.java, compiled unmodified, hosted in an
                  app_process at uid 1000 (daemon/), since a stock image has no ethOS system_server
```

- **Client identity.** `LauncherBindingService` admits the launcher and SystemUI. The emulator is
  signed with Google's SDK platform key, so a system-uid client cannot be installed; instead the
  client's package is admitted in **debug builds only** (`BuildConfig.DEBUG`; R8 drops it from
  release). There is still no adb path into a shipped AndyClaw.
- **Provisioning.** `app/src/debug/.../AgentBenchSeedProvider` does what onboarding does (wallet
  sign-in, user story, all skills enabled, heartbeat off) for callers with the shell or root uid.
  Debug source set only.
- **System app.** The 250 MB debug APK doesn't fit the emulator's system overlay, so a slim stub
  (same package, cert and permissions) goes to `/system/priv-app` and the real APK is installed over
  it as an update, which keeps `SYSTEM|PRIVILEGED`. The privapp allowlist lists every requested
  permission, because a missing privileged one bootloops the emulator.
- **App surface.** The dgen1's own Calculator, Notes, Alarm, Timer and Stopwatch are installed from the
  ethOS tree, re-signed. ethOS Contacts is system-uid, so the emulator keeps Google Contacts.
- **AVD.** `agentbench_API_35`, 720×720 at 240 dpi like the dgen1, x86_64 with ARM translation.

## Output

`runs/<ts>_<slug>/` (or `suites/<ts>_<name>/runs/<task>__<rep>/`):

| file | what |
|---|---|
| `transcript.md` | outcome, metrics, checks, each model tool call **with its input**, the callback timeline, the reply |
| `contact.jpg` | up to 30 agent-display frames on one image with timestamps. The quickest way to see what went wrong |
| `summary.json` | the numbers (wall time, model calls, iterations, tokens, tools, autopilot outcomes, crashes) |
| `logcat.txt` | everything logged during the run. `AGENTDISPLAYDEBUGKEY` has the full system prompt and every tool result |
| `device/events.jsonl`, `device/frames/` | raw launcher callbacks and every JPEG frame |

A suite adds `report.md` (pass rate and medians per task, deltas against the previous suite or
`--baseline`) and `results.json`.

## Tasks

`tasks/*.json`. Each task has a `prompt` and `setup`/`teardown` (adb shell as root). `reset_apps`
force-stops apps first. `checks` can be `setting`, `shell_regex`, `reply_regex`, `tool_used` or
`tool_not_used`. A run passes when it completes, every check holds and nothing crashed. Prefer
checks on device state over the reply: the agent saying "done" is the thing under test.
Keep tasks safe on a real phone (nothing that sends, posts or buys).

## Improvement loop

1. `agentbench suite` → read `report.md`, then the failing runs' `transcript.md` and `contact.jpg`.
2. Classify each failure: perception (tree/screenshot missed it), planning (wrong app or path), acting
   (tap missed, text not entered), verification (claimed success without it), infrastructure (timeouts, crashes).
3. Change AndyClaw (prompt, tool descriptions, autopilot policy, a tool), or `AgentDisplayService.java` in the
   ethOS tree (`agentbench build daemon` recompiles it in seconds, with no system build).
4. `agentbench cycle` → compare. Run with `--repeat 3` before believing a change: runs are not deterministic.

## Known gaps vs a dgen1

- No wallet, rear screen, LED matrix or `andyclawheartbeat`. Wallet tasks and the rear HUD can't be tested here.
- AndyClaw is debug-signed, not platform-signed, so signature-only permissions (`DEVICE_POWER`, `REBOOT`, …)
  are not granted and the device-control tools that need them will fail. That includes
  `MANAGE_NOTIFICATIONS`, so `set_dnd_mode` is refused here and works only on a dgen1.
- The emulator has no vibrator: AudioService keeps `mode_ringer` at 2 when VIBRATE is set, so
  `vibrate_mode` lives in `tasks/device-only.json`.
- There is no calendar account, so the calendar provider has no calendars to write to.
- `pm clear com.android.chrome` brings back Chrome's first-run screens every time; a real phone
  shows them once.
- arm64 native code runs under translation. Whisper is skipped on x86 hosts because it SIGILLs there.
  Local LLM and XMTP native paths are untested here.
- SELinux is permissive (needed for the daemon's service registration).
