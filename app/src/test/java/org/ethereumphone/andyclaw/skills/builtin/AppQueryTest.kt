package org.ethereumphone.andyclaw.skills.builtin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppQueryTest {

    @Test
    fun `every word may come from the label or the package`() {
        // agentbench app_installed_question: "YouTube Music" is labelled "YT Music".
        assertTrue(AppQuery.matches("youtube music", "com.google.android.apps.youtube.music", "YT Music"))
        assertTrue(AppQuery.matches("Maps", "com.google.android.apps.maps", "Maps"))
        assertTrue(AppQuery.matches("calc", "com.dgen.dgencalculator", "Calculator"))
    }

    @Test
    fun `a word found nowhere is no match`() {
        assertFalse(AppQuery.matches("youtube kids", "com.google.android.apps.youtube.music", "YT Music"))
        assertFalse(AppQuery.matches("signal", "com.google.android.apps.maps", "Maps"))
    }
}
