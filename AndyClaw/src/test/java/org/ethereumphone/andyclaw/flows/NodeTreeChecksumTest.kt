package org.ethereumphone.andyclaw.flows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The checksum has to be loose enough that a conversation with new messages in it is the
 * same screen, and tight enough that a redesigned screen is a different one. Both
 * directions are tested here, because getting either wrong breaks the rung: too tight and
 * every replay aborts, too loose and a replay taps into a UI it does not recognise.
 */
class NodeTreeChecksumTest {

    private fun tree(vararg elements: String, pkg: String = "org.thoughtcrime.securesms") = """
        {"screen":{"package":"$pkg","title":"Conversations"},
         "elements":[${elements.joinToString(",")}],
         "scrollable":true}
    """.trimIndent()

    private fun el(type: String, viewId: String, label: String) =
        """{"id":1,"type":"$type","label":"$label","viewId":"$viewId","actions":["click"],"center_x":10,"center_y":20}"""

    @Test
    fun `different content in the same layout is the same screen`() {
        val a = tree(el("row", "conversation_item", "Anna: see you at 6"))
        val b = tree(el("row", "conversation_item", "Bruno: on my way"))
        assertEquals(NodeTreeChecksum.of(a), NodeTreeChecksum.of(b))
    }

    @Test
    fun `a new element is a different screen`() {
        val a = tree(el("row", "conversation_item", "x"))
        val b = tree(el("row", "conversation_item", "x"), el("button", "fab_compose", "New"))
        assertNotEquals(NodeTreeChecksum.of(a), NodeTreeChecksum.of(b))
    }

    @Test
    fun `a renamed view id is a different screen`() {
        val a = tree(el("button", "send_button", "Send"))
        val b = tree(el("button", "btn_send", "Send"))
        assertNotEquals(NodeTreeChecksum.of(a), NodeTreeChecksum.of(b))
    }

    @Test
    fun `a different app is a different screen`() {
        val a = tree(el("row", "item", "x"))
        val b = tree(el("row", "item", "x"), pkg = "com.other.app")
        assertNotEquals(NodeTreeChecksum.of(a), NodeTreeChecksum.of(b))
    }

    @Test
    fun `an unreadable tree has no checksum and therefore never matches`() {
        assertEquals(NodeTreeChecksum.UNKNOWN, NodeTreeChecksum.of(null))
        assertEquals(NodeTreeChecksum.UNKNOWN, NodeTreeChecksum.of("not json"))
        assertNotEquals(NodeTreeChecksum.UNKNOWN, NodeTreeChecksum.of(tree(el("a", "b", "c"))))
    }

    @Test
    fun `the legacy tree format is understood too`() {
        val legacy = """
            {"elements":[{"idx":0,"cls":"android.widget.Button","id":"send_button","text":"Send"}]}
        """.trimIndent()
        assertTrue(NodeTreeChecksum.of(legacy).isNotEmpty())
        assertTrue("send_button" in NodeTreeChecksum.viewIdsOf(legacy))
    }

    @Test
    fun `view ids and text can be read back for conditions`() {
        val t = tree(el("row", "toolbar_title", "Anna"))
        assertEquals(listOf("toolbar_title"), NodeTreeChecksum.viewIdsOf(t))
        assertTrue(NodeTreeChecksum.textOf(t, "toolbar_title").contains("Anna"))
        assertTrue(NodeTreeChecksum.textOf(t, "nope").isEmpty())
    }
}
