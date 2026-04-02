package org.ethereumphone.andyclaw.analyzer

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.ethereumphone.andyclaw.analyzer.model.SemanticType

object NodeClassifier {

    private val NAV_LABELS = setOf(
        "navigate up", "back", "close", "collapse", "go back",
        "navigate back", "up button"
    )

    fun classify(node: AccessibilityNodeInfo): SemanticType {
        val className = node.className?.toString() ?: ""
        val resourceId = node.viewIdResourceName?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString() ?: ""

        // 1. EditText / text input
        if (className.contains("EditText")) {
            return SemanticType.TEXT_FIELD
        }

        // 2. Toggle (Switch, ToggleButton)
        if (node.isCheckable && (className.contains("Switch") || className.contains("Toggle"))) {
            return SemanticType.TOGGLE
        }

        // 3. CheckBox
        if (node.isCheckable && className.contains("CheckBox")) {
            return SemanticType.CHECKBOX
        }

        // 4. RadioButton
        if (node.isCheckable && className.contains("RadioButton")) {
            return SemanticType.RADIO_BUTTON
        }

        // 5. Slider / SeekBar
        if (className.contains("SeekBar") || className.contains("Slider") || node.rangeInfo != null) {
            return SemanticType.SLIDER
        }

        // 6. Spinner
        if (className.contains("Spinner")) {
            return SemanticType.SPINNER
        }

        // 7. Tab
        if (className.contains("Tab") && node.isClickable) {
            return SemanticType.TAB
        }

        // 8. Navigation button (back, close, navigate up)
        if (contentDesc in NAV_LABELS || NAV_LABELS.any { contentDesc.startsWith(it) }) {
            return SemanticType.NAV_BUTTON
        }

        // 9. ImageButton or clickable ImageView
        if (className.contains("ImageButton")) {
            return SemanticType.ICON_BUTTON
        }
        if (className.contains("ImageView") && node.isClickable) {
            return SemanticType.ICON_BUTTON
        }

        // 10. Search bar (by resource id pattern)
        if (resourceId.contains("search") && (node.isClickable || hasClickableChild(node))) {
            return SemanticType.SEARCH_BAR
        }

        // 11. Explicit Button class
        if (className.contains("Button") && !className.contains("Image")) {
            return SemanticType.BUTTON
        }

        // 12. Clickable layout that aggregates children -> MENU_ITEM
        // (detected by ScreenAnalyzer during aggregation, not here)

        // 13. Clickable generic View/ViewGroup with text -> BUTTON
        if (node.isClickable && isGenericViewClass(className) && (text.isNotEmpty() || contentDesc.isNotEmpty())) {
            return SemanticType.BUTTON
        }

        // 14. Compose node detection: generic View with actions/state
        if (className == "android.view.View") {
            return classifyComposeNode(node)
        }

        // 15. Non-clickable TextView -> TEXT or HEADER
        if (className.contains("TextView")) {
            return if (looksLikeHeader(node)) SemanticType.HEADER else SemanticType.TEXT
        }

        // 16. Non-clickable ImageView with content description
        if (className.contains("ImageView") && contentDesc.isNotEmpty()) {
            return SemanticType.IMAGE
        }

        return SemanticType.UNKNOWN
    }

    /**
     * Determines if a node is a layout container (not a leaf widget).
     */
    fun isLayoutContainer(className: String): Boolean {
        return className.contains("Layout") ||
                className.contains("FrameLayout") ||
                className.contains("ViewGroup") ||
                className.contains("RecyclerView") ||
                className.contains("ListView") ||
                className.contains("ScrollView") ||
                className.contains("CardView") ||
                className.contains("ConstraintLayout") ||
                className.contains("CoordinatorLayout") ||
                className.contains("RelativeLayout")
    }

    /**
     * Like isLayoutContainer, but also includes android.view.View
     * (used by Compose as a generic container). Used for aggregation checks.
     */
    fun isAggregationCandidate(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString() ?: ""
        if (isLayoutContainer(className)) return true
        // Compose: a clickable View with children is an aggregation candidate
        return className == "android.view.View" && node.childCount > 0
    }

    /**
     * Checks if a node is a scrollable container.
     */
    fun isScrollableContainer(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString() ?: ""
        return node.isScrollable || className.contains("RecyclerView") ||
                className.contains("ScrollView") || className.contains("ListView")
    }

    /**
     * Returns true if this node should be treated as structural noise
     * and skipped in favor of its children.
     */
    fun isStructuralNoise(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString() ?: ""
        if (!isLayoutContainer(className)) return false

        // A clickable/checkable container is NOT noise - it's actionable
        if (node.isClickable || node.isCheckable || node.isLongClickable) return false

        // A container with its own text/contentDescription is not noise
        if (!node.text.isNullOrEmpty() || !node.contentDescription.isNullOrEmpty()) return false

        return true
    }

    private fun classifyComposeNode(node: AccessibilityNodeInfo): SemanticType {
        val actions = node.actionList.map { it.id }
        val stateDesc = node.stateDescription?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""

        // Checkable compose toggle
        if (node.isCheckable) return SemanticType.TOGGLE

        // Editable compose text field
        if (actions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT.id)) {
            return SemanticType.TEXT_FIELD
        }

        // Clickable with text -> button
        if (node.isClickable && (node.text?.isNotEmpty() == true || contentDesc.isNotEmpty())) {
            return SemanticType.BUTTON
        }

        // Scrollable -> skip (handled as container)
        if (node.isScrollable) return SemanticType.UNKNOWN

        // Non-interactive with text
        if (node.text?.isNotEmpty() == true || contentDesc.isNotEmpty()) {
            return SemanticType.TEXT
        }

        return SemanticType.UNKNOWN
    }

    private fun looksLikeHeader(node: AccessibilityNodeInfo): Boolean {
        val resourceId = node.viewIdResourceName?.toString() ?: ""
        // Known header resource IDs
        if (resourceId.contains("title") && !resourceId.contains("summary")) {
            // Check if it's a top-level title (not inside a clickable parent)
            val parent = node.parent
            val parentClickable = parent?.isClickable == true
            parent?.recycle()
            if (!parentClickable) return true
        }
        if (resourceId.contains("homepage_title") || resourceId.contains("toolbar_title")) {
            return true
        }
        return false
    }

    private fun isGenericViewClass(className: String): Boolean {
        return className == "android.view.View" ||
                className == "android.view.ViewGroup" ||
                className.contains("Layout") ||
                className.contains("CardView")
    }

    private fun hasClickableChild(node: AccessibilityNodeInfo): Boolean {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val clickable = child.isClickable
            child.recycle()
            if (clickable) return true
        }
        return false
    }
}
