package android.os;

import android.os.IAgentAccessibilityProxy;

interface IAgentDisplayService {
    void createAgentDisplay(int width, int height, int dpi);
    void destroyAgentDisplay();
    int getDisplayId();
    void launchApp(String packageName);
    void tap(float x, float y);
    void swipe(float x1, float y1, float x2, float y2, int durationMs);
    void inputText(String text);
    void pressBack();
    void pressHome();
    byte[] captureFrame();
    String getAccessibilityTree();
    void clickNode(String viewId);
    void setNodeText(String viewId, String text);
    void registerAccessibilityProxy(IAgentAccessibilityProxy proxy);
}
