# Building an LLM agent to control Android via a virtual display

An LLM agent needs exactly three primitives to control an Android device: tap at coordinates, swipe between points, and capture what's on screen. **The cleanest implementation runs a Java process as the shell user via `app_process`** (the scrcpy pattern), injecting `MotionEvent` objects through `InputManager.injectInputEvent()` with the virtual display's ID embedded directly in each event. This avoids needing a platform-signed app, avoids per-command process spawning overhead from `adb shell input`, and gives you full programmatic control over event timing and construction. After every action, auto-capture a screenshot via `ImageReader`, compress it to JPEG at quality 80, base64-encode it, and feed it back to the LLM for the next reasoning step.

The remainder of this report walks through the exact AOSP internals, code patterns, and orchestration design for each of the three tools.

## How click(x, y) works at the AOSP level

A tap is two `MotionEvent` objects injected in sequence: `ACTION_DOWN` followed by `ACTION_UP`. The AOSP `input tap` shell command (implemented in `InputShellCommand.java`) uses **the same timestamp for both events with zero delay between them** — the framework handles instant taps correctly. Here is the exact AOSP implementation of `sendTap`:

```java
private void sendTap(int inputSource, float x, float y, int displayId) {
    final long now = SystemClock.uptimeMillis();
    injectMotionEvent(inputSource, MotionEvent.ACTION_DOWN, now, now, x, y, 1.0f, displayId);
    injectMotionEvent(inputSource, MotionEvent.ACTION_UP, now, now, x, y, 0.0f, displayId);
}
```

Each `MotionEvent` is constructed via `MotionEvent.obtain()` with a `PointerProperties` array (setting `id=0`, `toolType=TOOL_TYPE_FINGER`) and a `PointerCoords` array (setting `AXIS_X`, `AXIS_Y`, `AXIS_PRESSURE`, and `size=1.0f`). The **`displayId` is passed directly as a parameter** to `MotionEvent.obtain()` — this is how you target a specific virtual display rather than the default screen. For older API levels where the `displayId` parameter isn't available in `obtain()`, call `event.setDisplayId(virtualDisplayId)` after creation. The injection call uses `INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH`, which blocks until the event is fully consumed by the target window.

**Timing matters for distinguishing tap from long press.** Android's `ViewConfiguration.DEFAULT_LONG_PRESS_TIMEOUT` is **400ms**. If the gap between `ACTION_DOWN` and `ACTION_UP` exceeds this, the framework triggers `onLongPress()` instead of `onClick()`. The `TAP_TIMEOUT` of 100ms is used internally by `GestureDetector` to distinguish taps from scroll initiations, but injected zero-delay taps register correctly. UiAutomator2's `InteractionController` uses a **100ms pause** between down and up for clicks — a reasonable middle ground that ensures the event is registered on all views while staying well below the long-press threshold.

For your agent, implement tap as: `ACTION_DOWN` → optional 50–100ms sleep → `ACTION_UP`, both sharing the same `downTime`. Inject with `WAIT_FOR_FINISH` mode to guarantee delivery before proceeding.

## Swipe injection mirrors AOSP's 120Hz linear interpolation

A swipe is `ACTION_DOWN` → multiple `ACTION_MOVE` events → `ACTION_UP`, all sharing the same `downTime` timestamp. The AOSP `input swipe` command generates intermediate points at **120Hz** (one `ACTION_MOVE` every ~8.33ms) using **linear interpolation** between start and end coordinates. For a default 300ms swipe, this produces approximately **36 intermediate MOVE events**. Here is the core loop from `InputShellCommand.java`:

```java
long now = SystemClock.uptimeMillis();
final long endTime = down + duration;
final float swipeEventPeriodMillis = 1000f / 120;  // ~8.33ms

while (now < endTime) {
    long elapsedTime = now - down;
    long errorMillis = (long) Math.floor(injected * swipeEventPeriodMillis - elapsedTime);
    if (errorMillis > 0) sleep(errorMillis);
    now = SystemClock.uptimeMillis();
    float alpha = (float)(now - down) / duration;
    injectMotionEvent(source, ACTION_MOVE, down, now,
        lerp(x1, x2, alpha), lerp(y1, y2, alpha), 1.0f, displayId);
    injected++;
}
```

The `lerp` function is simple linear interpolation: `(b - a) * alpha + a`. The loop uses error correction — it calculates how far ahead or behind schedule each event is and adjusts sleep time accordingly to maintain the target 120Hz rate.

**Critical consistency rules for multi-event gestures:** The `downTime` must be identical across every event in the gesture (set once at `ACTION_DOWN`, reused for all `ACTION_MOVE` and `ACTION_UP`). The `eventTime` must monotonically increase, using `SystemClock.uptimeMillis()` at each injection. Pressure stays at **1.0f** throughout, dropping to **0.0f** only at `ACTION_UP`. The `source` (`InputDevice.SOURCE_TOUCHSCREEN`) and `toolType` (`TOOL_TYPE_FINGER`) must remain consistent.

**Duration determines whether Android interprets the gesture as a scroll or a fling.** Android's `VelocityTracker` computes gesture velocity at `ACTION_UP` by fitting a quadratic polynomial to the last 20 touch samples within a 100ms window. If velocity exceeds `ViewConfiguration.getScaledMinimumFlingVelocity()` (50 dp/s, roughly **125 px/s at 2.5x density**), scrollable views trigger fling physics. For reliable results:

- **Scroll (no inertia):** 500–1000ms duration — velocity stays below fling threshold
- **Fling (with inertia):** 100–200ms duration — high velocity triggers momentum scrolling  
- **AOSP default swipe:** 300ms — borderline, may or may not fling depending on distance
- **Long press:** `ACTION_DOWN` → **600ms sleep** (safely above the 400ms threshold) → `ACTION_UP`
- **Drag and drop:** `ACTION_DOWN` → 600ms sleep (triggers long press) → `ACTION_MOVE` events → `ACTION_UP`

For common LLM agent gestures, use these recipes: scroll down = swipe from center-bottom to center-top over 500ms; scroll up = reverse; swipe left/right for page navigation = 300ms horizontal swipe across 60% of screen width; pull-to-refresh = swipe from top quarter down 40% of screen height over 800ms.

## Capturing screenshots from ImageReader for LLM consumption

The screenshot pipeline is `VirtualDisplay` → `ImageReader` → `Bitmap` → JPEG bytes → base64 string. Create the `ImageReader` with `PixelFormat.RGBA_8888` and `maxImages=2` (double-buffered), then pass its `Surface` to `DisplayManager.createVirtualDisplay()`:

```java
ImageReader imageReader = ImageReader.newInstance(720, 1280, PixelFormat.RGBA_8888, 2);
VirtualDisplay vd = displayManager.createVirtualDisplay(
    "llm-agent", 720, 1280, densityDpi,
    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | VIRTUAL_DISPLAY_FLAG_PUBLIC,
    imageReader.getSurface(), null, handler);
```

When the `screenshot()` tool is invoked, call `imageReader.acquireLatestImage()` to get the most recent frame (discarding any older buffered frames). The **#1 implementation bug** is failing to account for row stride padding — the buffer's `rowStride` is often larger than `pixelStride × width`, so you must create an oversized bitmap and crop:

```java
Image image = imageReader.acquireLatestImage();
Image.Plane plane = image.getPlanes()[0];
int rowPadding = plane.getRowStride() - plane.getPixelStride() * width;
Bitmap padded = Bitmap.createBitmap(
    width + rowPadding / plane.getPixelStride(), height, Bitmap.Config.ARGB_8888);
padded.copyPixelsFromBuffer(plane.getBuffer());
Bitmap screenshot = Bitmap.createBitmap(padded, 0, 0, width, height);
padded.recycle();
image.close();  // CRITICAL: always close to return buffer to pool
```

Compress to **JPEG at quality 80** rather than PNG. Both formats cost identical LLM tokens (vision models decode pixels, not file bytes), but JPEG produces **80–150KB** versus PNG's 300–800KB for a typical screenshot — significantly smaller API payloads. Base64-encode the JPEG bytes with `Base64.encodeToString(jpegBytes, Base64.NO_WRAP)`.

**Resolution matters for both accuracy and cost.** Anthropic recommends **1024×768** for desktop Computer Use; for a portrait Android display, the analogous sweet spot is **720×1280**. This yields roughly **1,229 tokens per image** under Claude's formula of `(width × height) / 750`. Creating the `VirtualDisplay` at this reduced resolution is simpler than capturing at native 1080×1920 and downscaling — Android automatically renders content to fit the display dimensions. Do not exceed 1568px on the longest edge, as Claude auto-downscales beyond that, which degrades coordinate accuracy.

## The orchestration loop should auto-screenshot after every action

Two competing patterns exist for when the LLM receives visual feedback. **Anthropic's Computer Use** treats `screenshot` as an explicit action — the model must call the computer tool with `action: "screenshot"` to see the screen, giving it control over when to look. **OpenAI's CUA** automatically returns a screenshot after every single action — the orchestrator always captures and sends back the current state.

**For an Android agent, the automatic pattern is superior.** Mobile UIs are highly unpredictable — popups, notifications, permission dialogs, and animations mean the model needs constant visual grounding. The risk of "blind" multi-action sequences (where the model assumes outcomes without verification) is too high. Anthropic's own documentation recommends prompting the model with: *"After each step, take a screenshot and carefully evaluate if you have achieved the right outcome."*

The recommended loop structure:

```
1. Capture initial screenshot → send to LLM with goal
2. LLM returns tool call (tap/swipe)
3. Execute the action on the virtual display
4. Wait for UI to settle (500–1000ms)
5. Capture screenshot → send back as tool result
6. LLM reasons about result → returns next tool call or completion
7. Repeat from step 3 until done (cap at 15–25 iterations)
```

**Wait time after actions** depends on context. With Android animations disabled (set `window_animation_scale`, `transition_animation_scale`, and `animator_duration_scale` to 0 via `adb shell settings put global`), **300–500ms** suffices for most taps. For swipes, allow **500–1000ms** for content rendering. For app launches, allow **2–3 seconds**. The most robust approach is **screen stability detection**: capture two screenshots 200ms apart, compare them, and only proceed when they're nearly identical (with a 5-second maximum timeout).

For error handling, track the last 3–5 actions in a sliding window. If the model taps the same coordinates three times without screen changes, inject a system message telling it to try a different approach. If consecutive screenshots are identical after an action, report "no visible change" in the tool result. Cap total iterations and implement stuck-recovery heuristics (try pressing Back, try pressing Home).

## The INJECT_EVENTS permission and the app_process approach

`android.permission.INJECT_EVENTS` is a **signature-level permission** — it cannot be granted via `pm grant` (which only works for runtime/dangerous permissions). The app must be signed with the platform certificate, or it must run as a UID that already holds the permission. The **shell user (UID 2000) has INJECT_EVENTS by default**, which is why `adb shell input tap` works.

The cleanest architecture for an LLM agent follows scrcpy's approach: push a JAR/DEX file to the device and launch it via `app_process`, which executes as the shell user:

```bash
adb push agent-server.jar /data/local/tmp/
adb shell CLASSPATH=/data/local/tmp/agent-server.jar \
    app_process / com.example.agent.Server
```

This process inherits shell-level permissions including `INJECT_EVENTS`, can access `InputManager` via reflection (since it's a hidden API), and can create `ImageReader`-backed `VirtualDisplay` instances. It communicates with the host-side LLM orchestrator over a socket (USB-forwarded or TCP). scrcpy uses exactly this pattern — its server JAR runs via `app_process`, gaining input injection and screen capture capabilities without any installed app or user consent dialog.

For a custom AOSP build, you have additional options: sign your agent app with the platform key and declare `<uses-permission android:name="android.permission.INJECT_EVENTS"/>` in the manifest, or add your app to the privileged permission allowlist in `/etc/permissions/`. But the `app_process` approach works on any device with ADB access and requires zero AOSP modifications.

## Reference patterns from Anthropic, OpenAI, and testing frameworks

**Anthropic's Computer Use** defines a single `computer` tool with an `action` parameter that dispatches to `screenshot`, `left_click`, `type`, `key`, `scroll`, `left_click_drag`, and others. The tool is declared with `display_width_px` and `display_height_px` matching the virtual display resolution. Screenshots come back as base64 PNG in a `tool_result` content block with `type: "image"`. The agent loop is: send messages → receive `tool_use` blocks → execute → return `tool_result` → repeat until the model responds with text only (no tool calls). The reference implementation lives in `anthropic-quickstarts/computer-use-demo` on GitHub.

**OpenAI's CUA** uses the Responses API with a `computer_use_preview` tool type. It always returns a screenshot as `computer_call_output` with `type: "input_image"` after every action. It uses `previous_response_id` to chain conversation state rather than managing full message history client-side. It includes built-in safety checks for malicious content in screenshots.

**UiAutomator2** (`InteractionController.java`) uses `UiAutomation.injectInputEvent()` with 100ms click delays and 5ms-per-step swipe interpolation. **Appium** layers W3C Actions and mobile gesture extensions on top of UiAutomator2, using density-aware default speeds (5000×density px/s for swipe, 7500×density for fling). **scrcpy** accesses `InputManager` via reflection from a shell-privilege `app_process`, setting `displayId` on each event for virtual display targeting.

For your three-tool Android agent, the cleanest design combines scrcpy's permission model (shell process via `app_process`), AOSP's event construction patterns (from `InputShellCommand.java`), ImageReader-based capture at 720×1280 with JPEG compression, and OpenAI's auto-screenshot-after-every-action orchestration pattern. Define your tool schema as:

- **`click(x, y)`** → inject DOWN+UP at coordinates, wait 500ms, auto-return screenshot
- **`swipe(startX, startY, endX, endY, durationMs)`** → inject DOWN+MOVEs+UP at 120Hz, wait 800ms, auto-return screenshot  
- **`screenshot()`** → capture current frame via ImageReader, return base64 JPEG

## Conclusion

The implementation rests on three well-understood AOSP mechanisms: `MotionEvent` injection via `InputManager` for input, `ImageReader` surface rendering for capture, and the `app_process` shell-user pattern for permissions. The critical details that determine reliability are: keeping `downTime` consistent across gesture events, accounting for `rowStride` padding in `ImageReader` buffers, choosing duration carefully to control scroll-vs-fling behavior, and always giving the model fresh visual feedback after every action. The **120Hz linear interpolation** pattern from AOSP's own `InputShellCommand` is production-tested and should be used directly rather than invented from scratch. Auto-screenshotting after every action (rather than letting the model decide when to look) prevents the blind-action failures that plague less grounded agent loops — the marginal token cost is worth the reliability gain on unpredictable mobile UIs.