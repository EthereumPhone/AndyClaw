package org.ethereumphone.andyclaw.frames

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Keeping the frames, and — the part that has to work before this can be on by default —
 * not keeping too many of them.
 */
class SessionFrameStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private var now = 1_700_000_000_000L

    @Before
    fun setup() {
        root = temp.newFolder("frames")
        now = 1_700_000_000_000L
    }

    private fun store(retention: FrameRetention = FrameRetention()) =
        SessionFrameStore(root, retention) { now += 1_000; now }

    private fun jpeg(byte: Int, size: Int = 64) = ByteArray(size) { byte.toByte() }

    @Test
    fun `frames come back in the order they were captured`() {
        val s = store()
        val session = s.beginSession("session-a")
        val ids = (1..5).map { session.write(jpeg(it))!! }
        session.close()

        val frames = s.frames("session-a")
        assertEquals(5, frames.size)
        assertEquals(ids, frames.map { it.id })
        assertEquals((0..4).toList(), frames.map { it.index })
        // Ordered by index, not by whatever the filesystem returns.
        assertTrue(frames.zipWithNext().all { (a, b) -> a.index < b.index })
    }

    @Test
    fun `a completed session replays byte for byte`() {
        val s = store()
        val session = s.beginSession("replay-me")
        val written = (1..4).map { jpeg(it, size = 16 + it) }
        written.forEach { session.write(it) }
        session.close()

        val readBack = s.frames("replay-me").map { s.read(it)!! }
        assertEquals(written.size, readBack.size)
        written.zip(readBack).forEach { (a, b) -> assertArrayEquals(a, b) }
    }

    @Test
    fun `reopening a session continues it instead of restarting`() {
        // The launcher can stop and restart a stream inside one turn; two half-recordings
        // under one id would replay as a jump cut.
        val s = store()
        s.beginSession("split").also { it.write(jpeg(1)); it.write(jpeg(2)); it.close() }
        s.beginSession("split").also { it.write(jpeg(3)); it.close() }

        val frames = s.frames("split")
        assertEquals(3, frames.size)
        assertEquals(listOf(0, 1, 2), frames.map { it.index })
    }

    @Test
    fun `a session stops at its frame cap and says so`() {
        val s = store(FrameRetention(maxFramesPerSession = 3))
        val session = s.beginSession("long-one")
        repeat(10) { session.write(jpeg(it)) }

        assertTrue(session.truncated)
        assertEquals(3, session.frameIds.size)
        session.close()
        assertEquals(3, s.frames("long-one").size)
    }

    @Test
    fun `the oldest sessions are evicted once there are too many`() {
        val s = store(FrameRetention(maxSessions = 3))
        for (i in 1..6) {
            s.beginSession("session-$i").also { it.write(jpeg(i)); it.close() }
        }

        val remaining = s.sessions()
        assertEquals(3, remaining.size)
        // Whole sessions go, and the ones that survive are the newest.
        assertTrue(s.frames("session-6").isNotEmpty())
        assertTrue(s.frames("session-1").isEmpty())
    }

    @Test
    fun `the byte cap evicts whole sessions too`() {
        val s = store(FrameRetention(maxSessions = 100, maxBytes = 400))
        for (i in 1..6) {
            s.beginSession("s$i").also { repeat(3) { _ -> it.write(jpeg(i, size = 100)) }; it.close() }
        }

        assertTrue("total bytes ${s.totalBytes()} should be within the cap", s.totalBytes() <= 400)
        // Never half a recording: what is left is whole sessions.
        for (name in s.sessions()) {
            assertEquals(3, (root.resolve(name).listFiles() ?: emptyArray()).size)
        }
    }

    @Test
    fun `an empty session leaves nothing behind`() {
        // Otherwise a turn that created a display and did nothing would evict a real
        // recording to make room for a directory with nothing in it.
        val s = store(FrameRetention(maxSessions = 2))
        s.beginSession("empty").close()
        assertFalse(s.sessions().contains(s.dirNameFor("empty")))
    }

    @Test
    fun `a session id cannot escape the store`() {
        val s = store()
        val session = s.beginSession("../../etc/passwd")
        val id = session.write(jpeg(1))
        session.close()

        // The id came in over binder from the launcher; it is not this app's to trust.
        assertTrue(id!!.startsWith("_"))
        assertFalse(id.contains(".."))
        assertTrue(root.resolve(id).canonicalPath.startsWith(root.canonicalPath))
    }

    @Test
    fun `a frame id that this store did not write reads back as nothing`() {
        val s = store()
        assertNull(s.read("../../../etc/passwd"))
        assertNull(s.read("a/b/c"))
        assertNull(s.read("nope"))
    }

    @Test
    fun `two ids that sanitise the same do not collide`() {
        val s = store()
        val a = s.dirNameFor("chat/1")
        val b = s.dirNameFor("chat:1")
        assertTrue(a.startsWith("chat_1-"))
        assertTrue(b.startsWith("chat_1-"))
        assertFalse("the hash suffix is what keeps them apart", a == b)
    }

    @Test
    fun `a closed recording refuses writes and reports exactly what it kept`() {
        val s = store()
        val session = s.beginSession("closing")
        val a = session.write(jpeg(1))!!
        val b = session.write(jpeg(2))!!
        assertEquals(listOf(a, b), session.close())
        assertNull("a capture loop still running after close writes nothing", session.write(jpeg(3)))
        assertEquals(2, s.frames("closing").size)
    }

    @Test
    fun `the frame cap is per recording, not per conversation`() {
        val s = store(FrameRetention(maxFramesPerSession = 2))
        val first = s.beginSession("chat")
        repeat(3) { first.write(jpeg(it)) }
        first.close()
        val second = s.beginSession("chat")
        assertTrue("a later recording in the same chat still records", second.write(jpeg(9)) != null)
        second.close()
        assertEquals(3, s.frames("chat").size)
    }

    @Test
    fun `a recording being written is never pruned, and closing never evicts itself`() {
        val s = store(FrameRetention(maxSessions = 1))
        val open = s.beginSession("still-writing")
        open.write(jpeg(1))
        val other = s.beginSession("finished")
        other.write(jpeg(2))
        other.close()
        assertTrue("the open recording survived the other one's prune", s.frames("still-writing").isNotEmpty())
        assertTrue("the one just closed survived its own prune", s.frames("finished").isNotEmpty())
        open.close()
    }

    @Test
    fun `the oldest by last activity goes first`() {
        val s = store(FrameRetention(maxSessions = 2))
        val a = s.beginSession("a"); a.write(jpeg(1)); a.close()
        val b = s.beginSession("b"); b.write(jpeg(2)); b.close()
        val a2 = s.beginSession("a"); a2.write(jpeg(3)); a2.close() // a is the most recent now
        val c = s.beginSession("c"); c.write(jpeg(4)); c.close()
        assertTrue(s.frames("a").isNotEmpty())
        assertTrue("b was the least recently written", s.frames("b").isEmpty())
        assertTrue(s.frames("c").isNotEmpty())
    }

    @Test
    fun `one recording cannot outgrow its byte share`() {
        val s = store(FrameRetention(maxBytesPerSession = 100))
        val session = s.beginSession("big")
        assertTrue(session.write(jpeg(1, size = 60)) != null)
        assertNull(session.write(jpeg(2, size = 60)))
        assertTrue(session.truncated)
        session.close()
    }

    @Test
    fun `the byte cap is per recording, so a chat that once hit it still records`() {
        val s = store(FrameRetention(maxBytesPerSession = 100))
        val first = s.beginSession("chat")
        assertTrue(first.write(jpeg(1, size = 90)) != null)
        assertNull(first.write(jpeg(2, size = 90)))
        assertTrue(first.truncated)
        first.close()
        val second = s.beginSession("chat")
        assertTrue("a later recording in the same chat still records", second.write(jpeg(3, size = 90)) != null)
        assertFalse(second.truncated)
        second.close()
        assertEquals(2, s.frames("chat").size)
    }
}
