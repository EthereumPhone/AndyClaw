package org.ethereumphone.andyclaw.llm.reflex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReflexActivityTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var clock = 1_000L

    private fun activity(file: File = File(tmp.root, "reflex_activity.json")) = ReflexActivity(file) { clock }

    @Test
    fun `reads and commands are counted, and survive a restart`() {
        val file = File(tmp.root, "a.json")
        val a = activity(file)
        a.noteRead()
        a.noteRead()
        clock = 2_000L
        a.noteHandled("set_alarm", ReflexActivity.DONE, 640)
        a.noteHandled("wifi_off", ReflexActivity.FAILED, 30)

        val s = activity(file).stats()
        assertEquals(2, s.read)
        // A failure went to the AI: it is listed, but it is not a command handled on the phone.
        assertEquals(1, s.handled)
        assertEquals(2, s.recent.size)
        assertEquals("wifi_off", s.recent[0].label)
        assertEquals("set_alarm", s.lastHandled!!.label)
        assertEquals(2_000L, s.lastHandled!!.atMs)
        assertEquals(1_000L, s.lastReadMs)
    }

    @Test
    fun `the recent list is capped, newest first`() {
        val a = activity()
        repeat(ReflexActivity.MAX_RECENT + 5) { i ->
            clock = i.toLong()
            a.noteHandled("launch_app", ReflexActivity.READ, 100)
        }
        val s = a.stats()
        assertEquals(ReflexActivity.MAX_RECENT, s.recent.size)
        assertEquals((ReflexActivity.MAX_RECENT + 4).toLong(), s.recent[0].atMs)
        assertEquals(ReflexActivity.MAX_RECENT + 5, s.handled)
    }

    @Test
    fun `a damaged or newer file reads as empty or as far as it is understood`() {
        val damaged = File(tmp.root, "damaged.json").apply { writeText("{not json") }
        assertEquals(0, activity(damaged).stats().read)
        assertNull(activity(damaged).stats().lastHandled)

        val newer = File(tmp.root, "newer.json").apply {
            writeText("""{"version":2,"read":7,"handled":1,"lastReadMs":5,"future":true,"recent":[{"atMs":5,"label":"set_volume","outcome":"done","ms":400,"extra":1}]}""")
        }
        val s = activity(newer).stats()
        assertEquals(7, s.read)
        assertEquals("set_volume", s.lastHandled!!.label)
    }

    @Test
    fun `listeners hear every change`() {
        val a = activity()
        var heard = 0
        a.onChange = { heard++ }
        a.noteRead()
        a.noteHandled("dnd_on", ReflexActivity.DONE, 50)
        a.clear()
        assertEquals(3, heard)
        assertEquals(0, a.stats().read)
    }

    @Test
    fun `every shipped label has words for Settings`() {
        val labels = ReflexSpec.ACTIONS.keys + ReflexSpec.INTENTS.keys
        for (label in labels) {
            assertTrue("no description for $label", ReflexActivity.describe(label) != "Ran a command")
        }
    }
}
