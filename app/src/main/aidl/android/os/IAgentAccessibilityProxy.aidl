package android.os;

/**
 * Callback interface for accessibility tree operations.
 * Implemented by the AndyClaw app's AccessibilityService and registered
 * with AgentDisplayService so the framework can query the UI tree.
 */
interface IAgentAccessibilityProxy {
    String getTreeForDisplay(int displayId);
    boolean clickNodeByViewId(int displayId, String viewId);
    boolean setNodeTextByViewId(int displayId, String viewId, String text);
}
