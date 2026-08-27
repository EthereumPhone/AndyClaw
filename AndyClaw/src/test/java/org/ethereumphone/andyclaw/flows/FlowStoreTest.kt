package org.ethereumphone.andyclaw.flows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FlowStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val signer = HmacFlowSigner("test-key".toByteArray())

    private fun store(root: File = temp.root, key: String = "test-key") =
        FlowStore(File(root, "flows"), HmacFlowSigner(key.toByteArray()))

    private fun validFlow(id: String = "signal.send", version: Int = 1) = Flow(
        flow = id,
        version = version,
        app = "org.thoughtcrime.securesms",
        appVersionRange = ">=7.2,<8",
        params = listOf("body"),
        preconditions = listOf(NodeExists(viewId = "conversation_list")),
        steps = listOf(
            TypeStep(target = Selector(viewId = "compose_text"), value = "{{body}}"),
            CheckpointStep("send"),
            TapStep(viewId = "send_button"),
        ),
        postconditions = listOf(NodeExists(viewId = "conversation_item_sent")),
    )

    @Test
    fun `installs and reads back`() {
        val s = store()
        val result = s.install(validFlow())
        assertTrue(result is FlowInstallResult.Installed)
        val stored = s.listAll().single()
        assertEquals("signal.send", stored.flow.flow)
        assertEquals(FlowCodec.contentHash(validFlow()), stored.hash)
    }

    @Test
    fun `a flow that does not validate never reaches the disk`() {
        val s = store()
        val bad = validFlow().copy(postconditions = emptyList())
        val result = s.install(bad)
        assertTrue(result is FlowInstallResult.Rejected)
        assertTrue("no_postconditions" in (result as FlowInstallResult.Rejected).errors.map { it.code })
        assertTrue(s.listAll().isEmpty())
    }

    @Test
    fun `an edited flow is refused, not replayed`() {
        val s = store()
        s.install(validFlow())
        val file = File(temp.root, "flows").listFiles()!!.first { it.name.endsWith(FlowStore.FLOW_SUFFIX) }

        // Swap the target of the send tap — the sort of edit that matters.
        file.writeText(file.readText().replace("send_button", "pay_now_button"))

        assertTrue("a flow whose bytes changed must not load", s.listAll().isEmpty())
    }

    @Test
    fun `a flow signed with another key is refused`() {
        store(key = "attacker-key").install(validFlow())
        // Same bytes, same content address, different HMAC.
        assertTrue(store(key = "test-key").listAll().isEmpty())
    }

    @Test
    fun `a flow with no HMAC at all is refused`() {
        val s = store()
        s.install(validFlow())
        File(temp.root, "flows").listFiles()!!.first { it.name.endsWith(".mac") }.delete()
        assertTrue(s.listAll().isEmpty())
    }

    @Test
    fun `a file from a newer build is ignored rather than half understood`() {
        val dir = File(temp.root, "flows").apply { mkdirs() }
        File(dir, "deadbeef${FlowStore.FLOW_SUFFIX}").writeText(
            """{"flow":"a.b","version":1,"app":"a.b","app_version_range":"*","steps":[{"teleport":{}}]}"""
        )
        assertTrue(store().listAll().isEmpty())
    }

    @Test
    fun `reinstalling a flow id replaces the previous version`() {
        val s = store()
        s.install(validFlow(version = 1))
        s.install(validFlow(version = 2))
        val all = s.listAll()
        assertEquals(1, all.size)
        assertEquals(2, all.single().flow.version)
    }

    @Test
    fun `staleness is per package and does not change the content address`() {
        val s = store()
        val hash = (s.install(validFlow()) as FlowInstallResult.Installed).stored.hash

        assertEquals(1, s.markStaleForPackage("org.thoughtcrime.securesms"))
        assertTrue(s.get(hash)!!.meta.stale)
        assertEquals(hash, s.listAll().single().hash)

        assertEquals(0, s.markStaleForPackage("com.unrelated.app"))
    }

    @Test
    fun `a completed replay clears staleness and resets the abort count`() {
        val s = store()
        val hash = (s.install(validFlow()) as FlowInstallResult.Installed).stored.hash

        s.recordUse(hash, aborted = true)
        s.recordUse(hash, aborted = true)
        assertEquals(2, s.get(hash)!!.meta.aborts)

        s.recordUse(hash, aborted = false, appVersion = "7.3.0")
        val meta = s.get(hash)!!.meta
        assertEquals(0, meta.aborts)
        assertFalse(meta.stale)
        assertEquals("7.3.0", meta.compiledAgainstVersion)
        assertEquals(3, meta.uses)
    }

    @Test
    fun `removing a flow takes its sidecars with it`() {
        val s = store()
        val hash = (s.install(validFlow()) as FlowInstallResult.Installed).stored.hash
        s.remove(hash)
        assertNull(s.get(hash))
        assertEquals(0, File(temp.root, "flows").listFiles()!!.size)
    }

    @Test
    fun `an empty store is not an error`() {
        assertTrue(store().listAll().isEmpty())
        assertNotNull(signer.mac(ByteArray(0)))
    }
}
