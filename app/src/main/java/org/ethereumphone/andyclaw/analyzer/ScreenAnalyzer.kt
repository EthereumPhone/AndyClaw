package org.ethereumphone.andyclaw.analyzer

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.ethereumphone.andyclaw.analyzer.model.Bounds
import org.ethereumphone.andyclaw.analyzer.model.ScreenContext
import org.ethereumphone.andyclaw.analyzer.model.ScreenResult
import org.ethereumphone.andyclaw.analyzer.model.SemanticType
import org.ethereumphone.andyclaw.analyzer.model.UiElement

object ScreenAnalyzer {

    private const val MAX_DEPTH = 30

    /**
     * Dumps the raw accessibility tree to a string for debugging.
     */
    fun dumpTree(root: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        dumpNode(root, sb, depth = 0)
        return sb.toString()
    }

    private fun dumpNode(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (depth > 20) return
        val indent = "  ".repeat(depth)
        val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val resId = node.viewIdResourceName?.toString()?.substringAfterLast('/') ?: ""
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val flags = buildList {
            if (node.isClickable) add("click")
            if (node.isCheckable) add("check=${node.isChecked}")
            if (node.isScrollable) add("scroll")
            if (node.isFocusable) add("focus")
        }.joinToString(",")

        sb.append("$indent$cls")
        if (text.isNotEmpty()) sb.append(" t=\"$text\"")
        if (desc.isNotEmpty()) sb.append(" d=\"$desc\"")
        if (resId.isNotEmpty()) sb.append(" #$resId")
        if (flags.isNotEmpty()) sb.append(" [$flags]")
        sb.append(" ${rect.toShortString()}")
        sb.appendLine()

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                dumpNode(child, sb, depth + 1)
            } finally {
                try { child.recycle() } catch (_: Exception) {}
            }
        }
    }

    fun analyze(root: AccessibilityNodeInfo): ScreenResult {
        val screenContext = detectScreenContext(root)
        val isScrollable = detectScrollable(root, depth = 0)
        val rawElements = mutableListOf<UiElement>()

        flattenHierarchy(root, rawElements, depth = 0)

        // Sort by visual position: top-to-bottom, then left-to-right
        rawElements.sortWith(compareBy({ it.bounds.y }, { it.bounds.x }))

        // Post-processing: deduplicate and clean
        val cleaned = deduplicateElements(rawElements)

        // Assign sequential IDs
        val elements = cleaned.mapIndexed { index, element ->
            element.copy(id = index)
        }

        return ScreenResult(
            screen = screenContext,
            elements = elements,
            scrollable = isScrollable
        )
    }

    /**
     * Remove duplicate/redundant elements:
     * 1. If two elements have the same label and overlapping bounds, keep the more specific one
     * 2. Filter out repeated generic labels (e.g., multiple "Vehicle" map markers)
     */
    private fun deduplicateElements(elements: List<UiElement>): List<UiElement> {
        // Count label occurrences -- filter out labels that appear 3+ times with same text
        // (e.g., map markers "Vehicle" x6)
        val labelCounts = mutableMapOf<String, Int>()
        for (e in elements) {
            val label = e.label ?: continue
            labelCounts[label] = (labelCounts[label] ?: 0) + 1
        }

        val result = mutableListOf<UiElement>()
        val used = BooleanArray(elements.size)

        for (i in elements.indices) {
            if (used[i]) continue
            val e = elements[i]

            // Filter off-screen elements (negative y = scrolled above viewport)
            if (e.bounds.y < 0) {
                used[i] = true
                continue
            }

            // Filter noise: labels that are just punctuation or single chars
            val label = e.label
            if (label != null && label.length <= 2 && !label.any { it.isLetterOrDigit() }) {
                used[i] = true
                continue
            }

            // Skip repeated generic labels (3+ occurrences with same label = noise)
            if (label != null && (labelCounts[label] ?: 0) >= 3) {
                used[i] = true
                continue
            }

            // Find duplicates: same label with overlapping bounds
            var best = e
            for (j in i + 1 until elements.size) {
                if (used[j]) continue
                val other = elements[j]
                if (other.label == label && label != null && boundsOverlap(e.bounds, other.bounds)) {
                    used[j] = true
                    // Keep the element with more actions, or smaller bounds (more specific)
                    if (other.actions.size > best.actions.size ||
                        (other.actions.size == best.actions.size && area(other.bounds) < area(best.bounds))) {
                        best = other
                    }
                }
            }

            // Filter child elements whose label is a substring of a parent with overlapping bounds
            // (e.g., "Niederschlag" inside "Niederschlag·10 %")
            var isSubstringChild = false
            for (j in result.indices) {
                val parent = result[j]
                val parentLabel = parent.label ?: continue
                if (best.label != null && best.label != parentLabel &&
                    parentLabel.contains(best.label!!) &&
                    boundsOverlap(parent.bounds, best.bounds)) {
                    isSubstringChild = true
                    break
                }
            }
            if (isSubstringChild) continue

            result.add(best)
        }

        // Post-process: clean up labels
        return result.map { element ->
            var e = element
            // Handle "selected,..." prefix in content descriptions (e.g., Uber ride options)
            val label = e.label
            if (label != null && label.startsWith("selected,")) {
                e = e.copy(
                    label = label.removePrefix("selected,"),
                    selected = true
                )
            }
            // Trim trailing whitespace from labels
            if (e.label != null) {
                e = e.copy(label = e.label!!.trim())
            }
            if (e.summary != null) {
                e = e.copy(summary = e.summary!!.trim())
            }
            e
        }
    }

    private fun boundsOverlap(a: Bounds, b: Bounds): Boolean {
        return a.x < b.x + b.w && a.x + a.w > b.x &&
                a.y < b.y + b.h && a.y + a.h > b.y
    }

    private fun area(b: Bounds): Int = b.w * b.h

    // ---- Screen context detection ----

    private fun detectScreenContext(root: AccessibilityNodeInfo): ScreenContext {
        val packageName = root.packageName?.toString() ?: "unknown"
        var title: String? = null
        var screenClass: String? = null

        // Strategy 1: Find toolbar/collapsing_toolbar with content-desc
        title = findTitleFromToolbar(root, depth = 0)

        // Strategy 2: Find the first prominent header text
        if (title == null) {
            title = findFirstHeader(root, depth = 0)
        }

        // Strategy 3: Find window title from known resource IDs
        if (title == null) {
            title = findTitleByResourceId(root, depth = 0)
        }

        return ScreenContext(
            packageName = packageName,
            title = title,
            screenClass = screenClass
        )
    }

    private fun findTitleFromToolbar(node: AccessibilityNodeInfo, depth: Int): String? {
        if (depth > 10) return null
        val resourceId = node.viewIdResourceName?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }

        // Toolbar or collapsing toolbar with content-desc = screen title
        if ((resourceId.contains("toolbar") || resourceId.contains("collapsing_toolbar") ||
                    resourceId.contains("action_bar")) && contentDesc != null) {
            return contentDesc
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                val result = findTitleFromToolbar(child, depth + 1)
                if (result != null) return result
            } finally {
                child.recycle()
            }
        }
        return null
    }

    private fun findFirstHeader(node: AccessibilityNodeInfo, depth: Int): String? {
        if (depth > 8) return null
        val resourceId = node.viewIdResourceName?.toString() ?: ""
        val className = node.className?.toString() ?: ""

        if (className.contains("TextView") &&
            (resourceId.contains("homepage_title") || resourceId.contains("toolbar_title")) &&
            !node.text.isNullOrBlank()) {
            return node.text.toString()
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                val result = findFirstHeader(child, depth + 1)
                if (result != null) return result
            } finally {
                child.recycle()
            }
        }
        return null
    }

    private fun findTitleByResourceId(node: AccessibilityNodeInfo, depth: Int): String? {
        if (depth > 8) return null
        val resourceId = node.viewIdResourceName?.toString() ?: ""

        // Common title patterns
        if (resourceId.endsWith(":id/title") || resourceId.contains("header_title")) {
            val text = node.text?.toString()?.takeIf { it.isNotBlank() }
            if (text != null) {
                // Only use if this is a standalone title, not inside a clickable parent
                val parent = node.parent
                val parentClickable = parent?.isClickable == true
                parent?.recycle()
                if (!parentClickable) return text
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                val result = findTitleByResourceId(child, depth + 1)
                if (result != null) return result
            } finally {
                child.recycle()
            }
        }
        return null
    }

    // ---- Scrollable detection ----

    private fun detectScrollable(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > 15) return false
        if (NodeClassifier.isScrollableContainer(node)) return true

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (detectScrollable(child, depth + 1)) return true
            } finally {
                child.recycle()
            }
        }
        return false
    }

    // ---- Hierarchy flattening ----

    private fun flattenHierarchy(
        node: AccessibilityNodeInfo,
        elements: MutableList<UiElement>,
        depth: Int
    ) {
        if (depth > MAX_DEPTH) return

        val className = node.className?.toString() ?: ""

        // Skip pure scrollable containers but recurse into them
        if (NodeClassifier.isScrollableContainer(node) && !node.isClickable) {
            recurseChildren(node, elements, depth)
            return
        }

        // Check if this is an aggregation parent:
        // A clickable/focusable layout with text-bearing descendants
        if (isAggregationParent(node, className)) {
            val element = buildAggregatedElement(node)
            if (element != null) {
                elements.add(element)
                // Also emit any nested clickable children that are independently actionable
                emitNestedClickables(node, elements, depth)
                return // Don't recurse further - children are consumed
            }
        }

        // Check if this is structural noise -> skip and recurse
        if (NodeClassifier.isStructuralNoise(node)) {
            recurseChildren(node, elements, depth)
            return
        }

        // Classify this node
        val type = NodeClassifier.classify(node)

        // Skip unknown nodes that have no useful info
        if (type == SemanticType.UNKNOWN) {
            // Still recurse if it has children
            if (node.childCount > 0) {
                recurseChildren(node, elements, depth)
            }
            return
        }

        // Build element from this single node
        val element = buildSingleElement(node, type)
        if (element != null) {
            elements.add(element)
        }

        // For non-container leaf types, don't recurse into children
        // (their content is already captured in the element)
        if (type != SemanticType.UNKNOWN && !NodeClassifier.isLayoutContainer(className)) {
            return
        }

        recurseChildren(node, elements, depth)
    }

    private fun recurseChildren(
        node: AccessibilityNodeInfo,
        elements: MutableList<UiElement>,
        depth: Int
    ) {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                flattenHierarchy(child, elements, depth + 1)
            } catch (_: IllegalStateException) {
                // Node was recycled mid-traversal, skip it
            } finally {
                try { child.recycle() } catch (_: Exception) {}
            }
        }
    }

    /**
     * After aggregating a parent, scan for clickable children that are independently
     * actionable (e.g., a "Learn more" button inside a card, or a "Later" button inside a search bar).
     * These get emitted as separate elements so they're not lost in the aggregation.
     */
    private fun emitNestedClickables(node: AccessibilityNodeInfo, elements: MutableList<UiElement>, depth: Int) {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                // Skip checkable widgets (Switch, CheckBox) -- already part of parent's toggle/checkbox
                val childClass = child.className?.toString() ?: ""
                if (child.isCheckable || childClass.contains("Switch") || childClass.contains("CheckBox")) {
                    continue
                }
                // Skip widget_frame containers
                val resId = child.viewIdResourceName?.toString() ?: ""
                if (resId.contains("widget_frame")) {
                    continue
                }

                if (child.isClickable) {
                    val label = LabelExtractor.extractDirect(child)
                        ?: collectChildText(child)
                    if (label != null) {
                        val bounds = extractBounds(child)
                        if (bounds.w > 0 && bounds.h > 0) {
                            val type = NodeClassifier.classify(child)
                            val effectiveType = if (type == SemanticType.UNKNOWN) SemanticType.BUTTON else type
                            val childViewId = child.viewIdResourceName?.toString()
                            elements.add(UiElement(
                                id = 0,
                                type = effectiveType,
                                label = label,
                                actions = buildActions(child),
                                bounds = bounds,
                                enabled = if (!child.isEnabled) false else null,
                                viewId = childViewId,
                                resourceId = extractShortResourceId(childViewId)
                            ))
                        }
                    }
                }
                // Recurse to find deeper nested clickables
                if (child.childCount > 0) {
                    emitNestedClickables(child, elements, depth + 1)
                }
            } finally {
                try { child.recycle() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Collect text from a node's children (for clickable containers with no direct text).
     */
    private fun collectChildText(node: AccessibilityNodeInfo): String? {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                child.text?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
                child.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
                collectChildText(child)?.let { return it }
            } finally {
                try { child.recycle() } catch (_: Exception) {}
            }
        }
        return null
    }

    // ---- Aggregation logic ----

    private fun isAggregationParent(node: AccessibilityNodeInfo, className: String): Boolean {
        if (!NodeClassifier.isAggregationCandidate(node)) return false
        if (!node.isClickable && !node.isFocusable) return false
        if (node.childCount == 0) return false

        // Check if any descendant has text
        return hasTextBearingDescendant(node, depth = 0)
    }

    private fun hasTextBearingDescendant(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > 4) return false
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (!child.text.isNullOrBlank() || !child.contentDescription.isNullOrBlank()) {
                    return true
                }
                if (child.isCheckable) return true // widget_frame with switch
                if (hasTextBearingDescendant(child, depth + 1)) return true
            } finally {
                child.recycle()
            }
        }
        return false
    }

    private fun buildAggregatedElement(node: AccessibilityNodeInfo): UiElement? {
        val labelInfo = LabelExtractor.extractFromContainer(node)
        val label = labelInfo.label ?: return null // Skip elements with no label at all

        // Determine type
        val type = when {
            labelInfo.hasWidget && labelInfo.widgetCheckable -> SemanticType.TOGGLE
            labelInfo.summary != null -> SemanticType.MENU_ITEM
            node.contentDescription?.toString()?.lowercase()?.let {
                it.contains("navigate") || it.contains("back") || it.contains("close")
            } == true -> SemanticType.NAV_BUTTON
            else -> {
                // Fallback: check resource ID for search pattern
                val resourceId = node.viewIdResourceName?.toString() ?: ""
                if (resourceId.contains("search")) SemanticType.SEARCH_BAR
                else SemanticType.MENU_ITEM // clickable container with text defaults to menu_item
            }
        }

        val bounds = extractBounds(node)

        // Don't emit elements that are zero-sized or off-screen
        if (bounds.w <= 0 || bounds.h <= 0) return null

        val actions = buildActions(node)

        // Get viewId: prefer the node's own, else harvest from first child with one
        val fullViewId = node.viewIdResourceName?.toString() ?: findChildViewId(node)

        return UiElement(
            id = 0, // Will be reassigned
            type = type,
            label = label,
            summary = labelInfo.summary,
            hint = labelInfo.hint,
            value = labelInfo.value,
            checked = if (labelInfo.hasWidget && labelInfo.widgetCheckable) labelInfo.widgetChecked else null,
            enabled = if (!node.isEnabled) false else null,
            selected = if (node.isSelected) true else null,
            actions = actions,
            bounds = bounds,
            viewId = fullViewId,
            resourceId = extractShortResourceId(fullViewId)
        )
    }

    // ---- Single element building ----

    private fun buildSingleElement(node: AccessibilityNodeInfo, type: SemanticType): UiElement? {
        val label = LabelExtractor.extractDirect(node)

        // Skip text/header nodes with no actual text
        if (label == null && type in setOf(SemanticType.TEXT, SemanticType.HEADER, SemanticType.IMAGE)) {
            return null
        }

        val bounds = extractBounds(node)
        val actions = buildActions(node)

        // Don't emit elements that are zero-sized or off-screen
        if (bounds.w <= 0 || bounds.h <= 0) return null

        val fullViewId = node.viewIdResourceName?.toString()

        return UiElement(
            id = 0, // Will be reassigned
            type = type,
            label = label,
            hint = node.hintText?.toString()?.takeIf { it.isNotBlank() },
            value = if (type == SemanticType.TEXT_FIELD) node.text?.toString() else null,
            checked = if (node.isCheckable) node.isChecked else null,
            enabled = if (!node.isEnabled) false else null,
            selected = if (node.isSelected) true else null,
            password = if (node.isPassword) true else null,
            actions = actions,
            bounds = bounds,
            viewId = fullViewId,
            resourceId = extractShortResourceId(fullViewId)
        )
    }

    // ---- Utility ----

    /**
     * Search children (up to 3 levels) for the first usable viewIdResourceName.
     * Used for aggregated container elements that have no viewId themselves.
     */
    private fun findChildViewId(node: AccessibilityNodeInfo, depth: Int = 0): String? {
        if (depth > 3) return null
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                val vid = child.viewIdResourceName?.toString()
                if (vid != null) return vid
                findChildViewId(child, depth + 1)?.let { return it }
            } finally {
                try { child.recycle() } catch (_: Exception) {}
            }
        }
        return null
    }

    private fun extractBounds(node: AccessibilityNodeInfo): Bounds {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return Bounds(
            x = rect.left,
            y = rect.top,
            w = rect.width(),
            h = rect.height()
        )
    }

    private fun buildActions(node: AccessibilityNodeInfo): List<String> {
        val actions = mutableListOf<String>()

        if (node.isClickable) actions.add("click")
        if (node.isLongClickable) actions.add("long_click")

        val nodeActions = node.actionList.map { it.id }

        if (nodeActions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT.id)) {
            actions.add("set_text")
        }
        if (node.isScrollable) {
            if (nodeActions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id)) {
                actions.add("scroll_forward")
            }
            if (nodeActions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.id)) {
                actions.add("scroll_backward")
            }
        }

        return actions
    }

    private fun extractShortResourceId(fullId: String?): String? {
        if (fullId == null) return null
        // "com.android.settings:id/recycler_view" -> "recycler_view"
        val idx = fullId.lastIndexOf("/")
        return if (idx >= 0) fullId.substring(idx + 1).takeIf { it.isNotBlank() } else null
    }
}
