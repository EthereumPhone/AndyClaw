package org.ethereumphone.andyclaw.autopilot

import kotlin.math.abs

/**
 * Jev cannot count or measure distance, so "the second chat" or "the switch next to Wi-Fi" must
 * already be written into the text it reads. This precomputes those facts geometrically.
 */
object Annotations {

    data class Annotation(
        /** "row 2 of 5" — position among elements of the same column and type, top to bottom. */
        val ordinal: String? = null,
        /** "top bar", "bottom bar" or null for the content area. */
        val region: String? = null,
        /** For an element with no name of its own: the nearest labelled text on the same row. */
        val nearLabel: String? = null,
    )

    private const val SAME_COLUMN_PX = 40
    private const val SAME_ROW_PX = 30

    fun annotate(screen: ScreenSnapshot): Map<Int, Annotation> {
        val result = HashMap<Int, Annotation>(screen.elements.size)
        val topBar = screen.height * 0.12
        val bottomBar = screen.height * 0.88

        // Group elements that look like list rows: same type, same horizontal centre.
        val columns = screen.elements
            .filter { it.clickable }
            .groupBy { it.type to (it.centerX / SAME_COLUMN_PX) }
            .filterValues { it.size >= 2 }
        val ordinals = HashMap<Int, String>()
        for (column in columns.values) {
            val sorted = column.sortedBy { it.centerY }
            sorted.forEachIndexed { i, el -> ordinals[el.id] = "row ${i + 1} of ${sorted.size}" }
        }

        val labelled = screen.elements.filter { !it.label.isNullOrBlank() }

        for (el in screen.elements) {
            val region = when {
                el.centerY < topBar -> "top bar"
                el.centerY > bottomBar -> "bottom bar"
                else -> null
            }
            val near = if (el.name.isNullOrBlank()) {
                labelled
                    .filter { it.id != el.id && abs(it.centerY - el.centerY) <= SAME_ROW_PX }
                    .minByOrNull { abs(it.centerX - el.centerX) }
                    ?.label
            } else {
                null
            }
            result[el.id] = Annotation(ordinals[el.id], region, near)
        }
        return result
    }
}
