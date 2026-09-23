package android.os;

import android.os.IAgentAccessibilityProxy;
import android.os.IAgentDisplayListener;
import android.os.IAgentHudListener;
import android.view.SurfaceControl;

interface IAgentDisplayService {

    // ---- Display lifecycle ----
    void createAgentDisplay(int width, int height, int dpi);
    void destroyAgentDisplay();
    void destroyAgentDisplayAndPromote();
    int getDisplayId();
    void resizeAgentDisplay(int width, int height, int dpi);
    String getDisplayInfo();

    // ---- App management ----
    void launchApp(String packageName);
    void launchActivity(String packageName, String activityName);
    void launchIntentUri(String uri);
    String getCurrentActivity();

    // ---- High-level touch ----
    void tap(float x, float y);
    void tapPrecise(float x, float y, long holdDurationMs);
    void longPress(float x, float y, long durationMs);
    void doubleTap(float x, float y, long intervalMs);
    void swipe(float x1, float y1, float x2, float y2, int durationMs);
    void fling(float x1, float y1, float x2, float y2);
    void drag(float startX, float startY, float endX, float endY,
              long holdBeforeDragMs, int dragDurationMs);
    void pinch(float centerX, float centerY, float startSpan, float endSpan,
               int durationMs);
    void gesture(in float[] xPoints, in float[] yPoints, in long[] timestampsMs);

    // ---- Raw touch (per-pointer state machine) ----
    void touchDown(int pointerId, float x, float y, float pressure);
    void touchMove(int pointerId, float x, float y, float pressure);
    void touchUp(int pointerId);
    void touchCancel();

    // ---- Key input ----
    void pressBack();
    void pressHome();
    void pressRecents();
    void pressEnter();
    void pressKey(int keyCode);
    void pressKeyWithDuration(int keyCode, long holdDurationMs);
    void pressKeyWithMeta(int keyCode, int metaState);

    // ---- Text input ----
    void inputText(String text);
    void inputTextWithDelay(String text, int delayBetweenKeysMs);

    // ---- Clipboard ----
    void setClipboard(String text);
    String getClipboard();

    // ---- Screen capture ----
    byte[] captureFrame();
    byte[] captureFrameWithQuality(int quality);
    byte[] captureFrameRegion(int x, int y, int width, int height, int quality);
    byte[] captureFrameAsPng();

    // ---- Accessibility ----
    String getAccessibilityTree();
    String clickNode(String viewId);
    String longClickNode(String viewId);
    String setNodeText(String viewId, String text);
    String scrollNodeForward(String viewId);
    String scrollNodeBackward(String viewId);
    String focusNode(String viewId);
    String getNodeInfo(String viewId);

    // ---- Proxy management ----
    void registerAccessibilityProxy(IAgentAccessibilityProxy proxy);

    // ---- v2: autopilot (APPEND ONLY) ----
    // Declaration order is the binder transaction order, and the AndyClaw APK compiles its own
    // copy of this file. Never insert above this line or reorder below it; only append.
    // An older system_server answers these with an empty reply, which reads as 0 / null, so
    // callers check getAgentApiVersion() >= 2 before using any of them.

    /**
     * 2 on a build that implements this block.
     * @hide
     */
    int getAgentApiVersion();
    /**
     * JSON: frame counters and timing, for diagnostics.
     * @hide
     */
    String getFrameStats();
    /**
     * Blocks until the display has shown no meaningful change for quietMs (tiny changes such as a
     * blinking caret are ignored), or timeoutMs passes. JSON result.
     * @hide
     */
    String waitForIdle(long quietMs, long timeoutMs);
    /**
     * Runs a JSON list of input actions in order, optionally waiting for idle after. JSON result.
     * @hide
     */
    String executeActions(String actionsJson);
    /**
     * JPEG of the current frame, downscaled to at most maxWidth.
     * @hide
     */
    byte[] captureFrameScaled(int maxWidth, int quality);
    /**
     * A mirror layer of the agent display for a live preview. Null when there is no display.
     * @hide
     */
    SurfaceControl mirrorAgentDisplay();
    /** @hide */
    void registerDisplayListener(IAgentDisplayListener listener);
    /** @hide */
    void unregisterDisplayListener(IAgentDisplayListener listener);
    /**
     * Progress for the rear-screen HUD. Keys are whitelisted and sanitised by the service.
     * @hide
     */
    void setHudState(String hudJson);
    /**
     * System UID only (the rear-screen HUD).
     * @hide
     */
    void registerHudListener(IAgentHudListener listener);
    /**
     * System UID only.
     * @hide
     */
    void unregisterHudListener(IAgentHudListener listener);
    /**
     * Stops the agent: cancels input and refuses more until the next createAgentDisplay().
     * @hide
     */
    void requestStop(String source);
}
