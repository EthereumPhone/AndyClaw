package org.ethereumphone.andyclaw.autopilot

/**
 * What a scrollable page holds beyond the part on screen, learned by scrolling through it once.
 *
 * Jev decides from the state text alone, and the state used to hold only the visible rows. When
 * the row a sub-goal needs was further down, every option was a guess — scroll, or tap something
 * that might lead there — and Jev said so with a low confidence, which cost a planner call per
 * screen. Knowing the whole page turns that into a plain choice: the row is there, bring it into
 * view. Nothing here knows any app; it is lists and rows, whatever the app draws in them.
 *
 * [rows] are keyed by [ScreenElement.signature] in page order, each with the number of forward
 * scrolls from where the scan began at which it first showed. Where a row lies relative to the
 * screen now is read from the rows that are visible now, so a page scrolled since the scan still
 * says correctly which rows are further up and which further down.
 */
class PageScan(
    /** Identity of the page: app, title and the list that was scrolled. */
    val key: String,
    /** The scrolled list's [ScreenElement.signature], to find it again on a later snapshot. */
    val listSignature: String,
    val rows: LinkedHashMap<String, Row>,
) {
    /**
     * [index] is the scroll at which the row first showed. [sticky] means it showed at every
     * scroll: a toolbar or search bar that does not move with the list. It says nothing about
     * where the list is, so it is left out of that reckoning and never offered as off-screen.
     */
    data class Row(val element: ScreenElement, val index: Int, val sticky: Boolean = false)

    /**
     * The rows of this page that [screen] does not show, nearest first, numbered from
     * [OFFSCREEN_ID_BASE] so they never collide with an on-screen element's id.
     */
    fun offscreen(screen: ScreenSnapshot): List<OffscreenRow> {
        val visible = screen.elements.map { it.signature }.toSet()
        // A search bar that stays at the top while the list scrolls looked like the page's first
        // row still being on screen, which put every row above the view "further down".
        val here = rows.values.filter { !it.sticky && it.element.signature in visible }.map { it.index }
        // Nothing of the page is on screen (a dialog over it, a different page after all).
        if (here.isEmpty()) return emptyList()
        val lo = here.min()
        val hi = here.max()
        // A row first seen at the same scroll as a visible one sits beside it, just out of view:
        // below if it is in the later half of that range.
        fun below(index: Int) = index > hi || (index >= lo && index * 2 >= lo + hi && index != lo)
        fun distance(index: Int) = if (below(index)) index - hi else lo - index
        return rows.values
            .filter { !it.sticky && it.element.signature !in visible && it.element.isRow }
            .sortedBy { distance(it.index) }
            .mapIndexed { i, row -> OffscreenRow(OFFSCREEN_ID_BASE + i, row.element, below(row.index)) }
    }

    companion object {
        const val OFFSCREEN_ID_BASE = 1000

        fun key(screen: ScreenSnapshot, list: ScreenElement) =
            "${screen.packageName}|${screen.title.orEmpty()}|${list.signature}"

        /**
         * The list a page scan scrolls: the largest element on screen that can still scroll
         * forward. Pages scroll vertically far more often than anything else, and the largest
         * list is the page rather than a carousel inside it.
         */
        fun primaryList(screen: ScreenSnapshot): ScreenElement? =
            screen.elements
                .filter { "scroll_forward" in it.actions }
                .maxByOrNull { e ->
                    val w = (e.right ?: 0) - (e.left ?: 0)
                    val h = (e.bottom ?: 0) - (e.top ?: 0)
                    w.toLong() * h
                }

        /** Rows worth reaching: something with a name to act on or read, not the list itself. */
        internal val ScreenElement.isRow: Boolean
            get() = !scrollable && !name.isNullOrBlank()
    }
}

/** A row of the current page that is scrolled out of view. */
data class OffscreenRow(val id: Int, val element: ScreenElement, val below: Boolean)
