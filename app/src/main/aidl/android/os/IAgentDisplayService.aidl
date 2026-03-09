package android.os;

import android.os.IAgentAccessibilityProxy;

interface IAgentDisplayService {
    // ── Display Lifecycle ───────────────────────────────────────────────
    void createAgentDisplay(int width, int height, int dpi);
    void destroyAgentDisplay();
    int getDisplayId();
    void resizeAgentDisplay(int width, int height, int dpi);
    String getDisplayInfo();

    // ── App Management ──────────────────────────────────────────────────
    void launchApp(String packageName);
    void launchActivity(String packageName, String activityName);
    void launchIntentUri(String uri);
    String getCurrentActivity();

    // ── High-Level Touch ────────────────────────────────────────────────
    void tap(float x, float y);
    void tapPrecise(float x, float y, long holdDurationMs);
    void longPress(float x, float y, long durationMs);
    void doubleTap(float x, float y, long intervalMs);
    void swipe(float x1, float y1, float x2, float y2, int durationMs);
    void fling(float x1, float y1, float x2, float y2);
    void drag(float startX, float startY, float endX, float endY, long holdBeforeDragMs, int dragDurationMs);
    void pinch(float centerX, float centerY, float startSpan, float endSpan, int durationMs);
    void gesture(in float[] xPoints, in float[] yPoints, in long[] timestampsMs);

    // ── Raw Touch (per-pointer state machine) ───────────────────────────
    void touchDown(int pointerId, float x, float y, float pressure);
    void touchMove(int pointerId, float x, float y, float pressure);
    void touchUp(int pointerId);
    void touchCancel();

    // ── Key Input ───────────────────────────────────────────────────────
    void pressBack();
    void pressHome();
    void pressRecents();
    void pressEnter();
    void pressKey(int keyCode);
    void pressKeyWithDuration(int keyCode, long holdDurationMs);
    void pressKeyWithMeta(int keyCode, int metaState);

    // ── Text Input ──────────────────────────────────────────────────────
    void inputText(String text);
    void inputTextWithDelay(String text, int delayBetweenKeysMs);

    // ── Clipboard ───────────────────────────────────────────────────────
    void setClipboard(String text);
    String getClipboard();

    // ── Screen Capture ──────────────────────────────────────────────────
    byte[] captureFrame();
    byte[] captureFrameWithQuality(int quality);
    byte[] captureFrameRegion(int x, int y, int width, int height, int quality);
    byte[] captureFrameAsPng();

    // ── Accessibility ───────────────────────────────────────────────────
    String getAccessibilityTree();
    void clickNode(String viewId);
    void longClickNode(String viewId);
    void setNodeText(String viewId, String text);
    void scrollNodeForward(String viewId);
    void scrollNodeBackward(String viewId);
    void focusNode(String viewId);
    String getNodeInfo(String viewId);

    // ── Proxy Management ────────────────────────────────────────────────
    void registerAccessibilityProxy(IAgentAccessibilityProxy proxy);
}
