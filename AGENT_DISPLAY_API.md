# AgentDisplayService API Reference

Complete API for LLM-controlled virtual display. All methods are called via `IAgentDisplayService` binder interface. Only the `org.ethereumphone.andyclaw` package is authorized to call these methods.

---

## Display Lifecycle

### `createAgentDisplay(int width, int height, int dpi)`
Creates the virtual display with an ImageReader surface. Flags: PUBLIC, OWN_CONTENT_ONLY, SUPPORTS_TOUCH, TRUSTED, OWN_FOCUS, OWN_DISPLAY_GROUP, ALWAYS_UNLOCKED.

### `destroyAgentDisplay()`
Removes all tasks on the display, releases the VirtualDisplay, closes ImageReader, clears cached frames and touch state.

### `getDisplayId() -> int`
Returns the display ID, or `Display.INVALID_DISPLAY` (-1) if no display is created.

### `resizeAgentDisplay(int width, int height, int dpi)` **NEW**
Hot-resizes the display without destroying it. Recreates the ImageReader with new dimensions and swaps the surface. Clears cached frames.

### `getDisplayInfo() -> String` **NEW**
Returns JSON: `{"displayId":2,"width":1080,"height":1920,"dpi":420}`

---

## App Management

### `launchApp(String packageName)`
Launches the default activity for the given package on the virtual display.

### `launchActivity(String packageName, String activityName)` **NEW**
Launches a specific activity by component name. Example: `launchActivity("com.android.settings", "com.android.settings.Settings")`

### `launchIntentUri(String uri)` **NEW**
Launches an arbitrary intent from a URI string. Parsed via `Intent.parseUri(uri, URI_INTENT_SCHEME)`. Example: `"intent:#Intent;action=android.intent.action.VIEW;data=https://example.com;end"`

### `getCurrentActivity() -> String` **NEW**
Returns the top activity on the virtual display as `"package/activity"` short string, or `null` if nothing is running.

---

## High-Level Touch

### `tap(float x, float y)`
Quick tap with hardcoded 50ms hold. DOWN at `now`, UP at `now+50`.

### `tapPrecise(float x, float y, long holdDurationMs)` **NEW**
Tap with configurable hold duration in milliseconds. Uses `Handler.postDelayed` for the UP event so the target app sees a real held-down state. Use this for anything from a quick 10ms tap to a 2000ms press.

### `longPress(float x, float y, long durationMs)` **NEW**
Convenience for long press. Same as `tapPrecise` but semantically distinct. Typical Android long-press threshold is ~500ms.

### `doubleTap(float x, float y, long intervalMs)` **NEW**
Two taps separated by `intervalMs`. For the system to recognize it as a double-tap, keep `intervalMs` under 300ms. Each individual tap has a 30ms hold.

### `swipe(float x1, float y1, float x2, float y2, int durationMs)`
Linear swipe from (x1,y1) to (x2,y2). Generates ~60fps intermediate MOVE events. Events are injected synchronously with computed timestamps.

### `fling(float x1, float y1, float x2, float y2)` **NEW**
Fast ~50ms swipe with only 2 intermediate points. Generates high velocity for triggering scroll fling/momentum in list views.

### `drag(float startX, float startY, float endX, float endY, long holdBeforeDragMs, int dragDurationMs)` **NEW**
Hold at start position for `holdBeforeDragMs` (to trigger drag mode), then move to end over `dragDurationMs`. Uses real-time delays via Handler. Ideal for drag-and-drop operations.

### `pinch(float centerX, float centerY, float startSpan, float endSpan, int durationMs)` **NEW**
Two-finger pinch gesture centered at (centerX, centerY). Both fingers move horizontally:
- Finger 0: `centerX - span/2`
- Finger 1: `centerX + span/2`

`startSpan > endSpan` = pinch in (zoom out), `startSpan < endSpan` = pinch out (zoom in). Uses real-time scheduled multi-touch events with proper `ACTION_POINTER_DOWN` / `ACTION_POINTER_UP`.

### `gesture(float[] xPoints, float[] yPoints, long[] timestampsMs)` **NEW**
Arbitrary touch path with timed waypoints. All three arrays must have the same length (minimum 2). Timestamps are **relative** — the first timestamp is the base, and deltas between subsequent timestamps determine real-time delays.

Example — draw an "L" shape over 500ms:
```
xPoints:      [100, 100, 100, 300]
yPoints:      [100, 300, 500, 500]
timestampsMs: [0,   150, 300, 500]
```

First point = ACTION_DOWN, intermediate = ACTION_MOVE, last = ACTION_UP.

---

## Raw Touch (Per-Pointer State Machine) **ALL NEW**

Complete low-level control over multi-touch. The service tracks active pointers internally via a `SparseArray`. Use this for any gesture not covered by the high-level methods.

### `touchDown(int pointerId, float x, float y, float pressure)`
Start a touch. First pointer triggers `ACTION_DOWN`; additional pointers trigger `ACTION_POINTER_DOWN`. Pressure range: 0.0 to 1.0.

### `touchMove(int pointerId, float x, float y, float pressure)`
Move an active pointer. Throws if the pointer isn't active. Injects `ACTION_MOVE` with all active pointers.

### `touchUp(int pointerId)`
Release a pointer. Last pointer triggers `ACTION_UP`; otherwise triggers `ACTION_POINTER_UP`. Throws if pointer isn't active.

### `touchCancel()`
Cancel all active touches. Injects `ACTION_CANCEL` and clears all pointer state. Use this to reset if something goes wrong.

### Raw touch example — custom two-finger rotation:
```
touchDown(0, 200, 500, 1.0)   // finger 0 down
touchDown(1, 800, 500, 1.0)   // finger 1 down
touchMove(0, 250, 400, 1.0)   // rotate finger 0
touchMove(1, 750, 600, 1.0)   // rotate finger 1
touchMove(0, 300, 350, 1.0)   // continue rotation
touchMove(1, 700, 650, 1.0)
touchUp(1)                     // finger 1 up
touchUp(0)                     // finger 0 up
```

---

## Key Input

### `pressBack()`
Injects `KEYCODE_BACK` (DOWN + UP).

### `pressHome()`
Injects `KEYCODE_HOME`.

### `pressRecents()` **NEW**
Injects `KEYCODE_APP_SWITCH` to open the recents/overview screen.

### `pressEnter()` **NEW**
Injects `KEYCODE_ENTER`. Useful for submitting text fields, confirming dialogs.

### `pressKey(int keyCode)` **NEW**
Inject any arbitrary key code. See [KeyEvent constants](https://developer.android.com/reference/android/view/KeyEvent) for all codes. Common ones:
| Code | Constant | Use |
|------|----------|-----|
| 4 | KEYCODE_BACK | Back |
| 3 | KEYCODE_HOME | Home |
| 187 | KEYCODE_APP_SWITCH | Recents |
| 66 | KEYCODE_ENTER | Enter/confirm |
| 67 | KEYCODE_DEL | Backspace |
| 112 | KEYCODE_FORWARD_DEL | Delete |
| 61 | KEYCODE_TAB | Tab / next field |
| 111 | KEYCODE_ESCAPE | Escape |
| 24 | KEYCODE_VOLUME_UP | Volume up |
| 25 | KEYCODE_VOLUME_DOWN | Volume down |
| 26 | KEYCODE_POWER | Power |
| 82 | KEYCODE_MENU | Menu |
| 84 | KEYCODE_SEARCH | Search |

### `pressKeyWithDuration(int keyCode, long holdDurationMs)` **NEW**
Press and hold a key for the specified duration. DOWN injected immediately, UP scheduled after `holdDurationMs`. Useful for power button hold, volume hold for rapid adjustment, etc.

### `pressKeyWithMeta(int keyCode, int metaState)` **NEW**
Press a key with modifier keys. `metaState` is a bitmask:
| Flag | Value | Meaning |
|------|-------|---------|
| META_SHIFT_ON | 0x1 | Shift |
| META_ALT_ON | 0x2 | Alt |
| META_CTRL_ON | 0x1000 | Ctrl |
| META_META_ON | 0x10000 | Meta/Windows |

Examples:
- Ctrl+A (select all): `pressKeyWithMeta(29, 0x1000)` — keyCode 29 = KEYCODE_A
- Ctrl+C (copy): `pressKeyWithMeta(31, 0x1000)` — keyCode 31 = KEYCODE_C
- Ctrl+V (paste): `pressKeyWithMeta(50, 0x1000)` — keyCode 50 = KEYCODE_V
- Shift+Tab: `pressKeyWithMeta(61, 0x1)` — keyCode 61 = KEYCODE_TAB

---

## Text Input

### `inputText(String text)`
Types text instantly via `KeyCharacterMap.getEvents()`. All key events injected synchronously.

### `inputTextWithDelay(String text, int delayBetweenKeysMs)` **NEW**
Types text with a delay between each character. Scheduled via Handler. Useful for:
- Human-like typing simulation
- Apps that process input character-by-character
- Search suggestions that trigger on each keystroke

---

## Clipboard **ALL NEW**

### `setClipboard(String text)`
Sets the system clipboard to the given text. Works across the whole device (not display-scoped).

### `getClipboard() -> String`
Returns the current clipboard text, or `null` if empty/non-text.

### Clipboard + paste workflow:
```
setClipboard("complex text that can't be typed")
pressKeyWithMeta(50, 0x1000)  // Ctrl+V to paste
```

---

## Screen Capture

### `captureFrame() -> byte[]`
Returns JPEG at quality 80. Tries to acquire a fresh frame; falls back to cached frame.

### `captureFrameWithQuality(int quality) -> byte[]` **NEW**
Returns JPEG at specified quality (1-100). Lower = smaller/faster, higher = better fidelity.

### `captureFrameRegion(int x, int y, int width, int height, int quality) -> byte[]` **NEW**
Captures a cropped region as JPEG. Coordinates are clamped to display bounds. Useful for focusing on a specific UI element without sending the full screen.

### `captureFrameAsPng() -> byte[]` **NEW**
Returns lossless PNG. Larger than JPEG but pixel-perfect. Useful when you need exact pixel matching.

**Note:** PNG payloads can be several MB for full-screen captures. Be mindful of binder transaction size limits (~1MB). For full-screen lossless, consider using `captureFrameRegion` on smaller areas.

---

## Accessibility

### `getAccessibilityTree() -> String`
Returns JSON tree of the UI hierarchy from the accessibility service. Requires proxy to be connected.

### `clickNode(String viewId)`
Click a node by its view ID (resource ID string).

### `longClickNode(String viewId)` **NEW**
Long-click a node. Delegates to `AccessibilityNodeInfo.performAction(ACTION_LONG_CLICK)`.

### `setNodeText(String viewId, String text)`
Set text on an editable node.

### `scrollNodeForward(String viewId)` **NEW**
Scroll a scrollable node forward (down/right). Delegates to `ACTION_SCROLL_FORWARD`.

### `scrollNodeBackward(String viewId)` **NEW**
Scroll a scrollable node backward (up/left). Delegates to `ACTION_SCROLL_BACKWARD`.

### `focusNode(String viewId)` **NEW**
Set accessibility focus on a node. Delegates to `ACTION_ACCESSIBILITY_FOCUS`.

### `getNodeInfo(String viewId) -> String` **NEW**
Returns detailed JSON info about a specific node (bounds, text, content description, enabled state, etc.).

---

## Proxy Management

### `registerAccessibilityProxy(IAgentAccessibilityProxy proxy)`
Called by the AndyClaw app's AccessibilityService to register itself. The proxy is used for all accessibility tree operations.

---

## IAgentAccessibilityProxy — Methods to Implement in AndyClaw

The AndyClaw app's `AgentDisplayAccessibilityService` must implement these methods:

```java
interface IAgentAccessibilityProxy {
    // Existing
    String getTreeForDisplay(int displayId);
    boolean clickNodeByViewId(int displayId, String viewId);
    boolean setNodeTextByViewId(int displayId, String viewId, String text);

    // NEW — must be added
    boolean longClickNodeByViewId(int displayId, String viewId);
    boolean scrollNodeForwardByViewId(int displayId, String viewId);
    boolean scrollNodeBackwardByViewId(int displayId, String viewId);
    boolean focusNodeByViewId(int displayId, String viewId);
    String getNodeInfoByViewId(int displayId, String viewId);
}
```

### Implementation guide for new proxy methods:

**`longClickNodeByViewId`** — Find node, call `node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)`

**`scrollNodeForwardByViewId`** — Find node, call `node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)`

**`scrollNodeBackwardByViewId`** — Find node, call `node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)`

**`focusNodeByViewId`** — Find node, call `node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)`

**`getNodeInfoByViewId`** — Find node, return JSON with:
```json
{
  "viewId": "com.example:id/button",
  "className": "android.widget.Button",
  "text": "Submit",
  "contentDescription": "Submit form",
  "bounds": {"left": 100, "top": 200, "right": 300, "bottom": 250},
  "enabled": true,
  "clickable": true,
  "scrollable": false,
  "focused": false,
  "checked": false,
  "selected": false,
  "editable": false,
  "childCount": 0
}
```
