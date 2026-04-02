package android.os;

/**
 * Callback interface for accessibility tree operations.
 * Implemented by the AndyClaw app's AccessibilityService and registered
 * with AgentDisplayService so the framework can query the UI tree.
 */
interface IAgentAccessibilityProxy {
    String getTreeForDisplay(int displayId);
    String clickNodeByViewId(int displayId, String viewId);
    String setNodeTextByViewId(int displayId, String viewId, String text);
    String longClickNodeByViewId(int displayId, String viewId);
    String scrollNodeForwardByViewId(int displayId, String viewId);
    String scrollNodeBackwardByViewId(int displayId, String viewId);
    String focusNodeByViewId(int displayId, String viewId);
    String getNodeInfoByViewId(int displayId, String viewId);
}
