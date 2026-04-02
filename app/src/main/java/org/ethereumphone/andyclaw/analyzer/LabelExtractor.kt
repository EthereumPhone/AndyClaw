package org.ethereumphone.andyclaw.analyzer

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Extracted label info from a node or its children.
 */
data class LabelInfo(
    val label: String?,
    val summary: String?,
    val hint: String?,
    val value: String?,
    val hasWidget: Boolean = false,
    val widgetCheckable: Boolean = false,
    val widgetChecked: Boolean = false
)

object LabelExtractor {

    /**
     * Extract label directly from a single node (no child traversal).
     */
    fun extractDirect(node: AccessibilityNodeInfo): String? {
        return node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            ?: node.hintText?.toString()?.takeIf { it.isNotBlank() }
    }

    /**
     * Extract full label info from a container node by scanning its children.
     * Used for aggregation parents (clickable layouts with child TextViews).
     */
    fun extractFromContainer(node: AccessibilityNodeInfo): LabelInfo {
        var title: String? = null
        var summary: String? = null
        var hint: String? = null
        var hasWidget = false
        var widgetCheckable = false
        var widgetChecked = false

        // First check the container itself
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let {
            title = it
        }

        // Then scan children recursively (but shallow - max 3 levels)
        scanChildren(node, depth = 0, maxDepth = 3) { child, resourceId ->
            val childClass = child.className?.toString() ?: ""

            // Detect widget_frame children (Switch, CheckBox inside)
            if (resourceId.contains("widget_frame") || resourceId.contains("switchWidget")) {
                hasWidget = true
                widgetCheckable = child.isCheckable
                widgetChecked = child.isChecked
                return@scanChildren
            }

            // Check if THIS child is the checkable widget itself
            if (child.isCheckable && (childClass.contains("Switch") || childClass.contains("CheckBox") || childClass.contains("Toggle"))) {
                hasWidget = true
                widgetCheckable = true
                widgetChecked = child.isChecked
                return@scanChildren
            }

            val childText = child.text?.toString()?.takeIf { it.isNotBlank() }

            if (childText != null) {
                when {
                    // Known title resource IDs
                    resourceId.endsWith(":id/title") || resourceId.endsWith(":id/android:id/title") ||
                    resourceId.contains("title") && !resourceId.contains("summary") -> {
                        if (title == null) title = childText
                    }
                    // Known summary resource IDs
                    resourceId.endsWith(":id/summary") || resourceId.endsWith(":id/android:id/summary") ||
                    resourceId.contains("summary") -> {
                        if (summary == null) summary = childText
                    }
                    // Search bar hint/placeholder
                    resourceId.contains("search") && resourceId.contains("title") -> {
                        if (title == null) title = childText
                    }
                    // Generic text - first becomes title, second becomes summary
                    else -> {
                        if (title == null) title = childText
                        else if (summary == null && childText != title) summary = childText
                    }
                }
            }

            // Also check contentDescription as fallback
            if (title == null) {
                child.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let {
                    title = it
                }
            }
        }

        // Extract hint from the node itself
        hint = node.hintText?.toString()?.takeIf { it.isNotBlank() }

        // Clean up summary if it's just whitespace
        summary = summary?.takeIf { it.isNotBlank() }

        return LabelInfo(
            label = title,
            summary = summary,
            hint = hint,
            value = null,
            hasWidget = hasWidget,
            widgetCheckable = widgetCheckable,
            widgetChecked = widgetChecked
        )
    }

    /**
     * Recursively scan children up to maxDepth, invoking callback for each.
     */
    private fun scanChildren(
        node: AccessibilityNodeInfo,
        depth: Int,
        maxDepth: Int,
        callback: (child: AccessibilityNodeInfo, resourceId: String) -> Unit
    ) {
        if (depth > maxDepth) return

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                val resourceId = child.viewIdResourceName?.toString() ?: ""
                callback(child, resourceId)
                // Recurse into child's children
                if (child.childCount > 0) {
                    scanChildren(child, depth + 1, maxDepth, callback)
                }
            } finally {
                child.recycle()
            }
        }
    }
}
