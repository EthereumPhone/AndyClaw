package org.ethereumphone.andyclaw.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The ingest's bookkeeping on disk: survives a restart, tolerates anything else it finds. */
class FileIngestStoresTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `state and seen messages survive a restart`() {
        val dir = tmp.newFolder("ambient")
        FileIngestStores(dir).apply {
            save(IngestState(state = IngestState.OFFLINE, lastAttemptMs = 2, lastSuccessMs = 1, lastFailureMs = 2))
            markAll(listOf("m1", "m2"), nowMs = 10)
        }

        val reopened = FileIngestStores(dir)
        assertEquals(IngestState(IngestState.OFFLINE, 2, 1, 2), reopened.load())
        assertTrue(reopened.contains("m1"))
        assertFalse(reopened.contains("m3"))
    }

    @Test
    fun `missing, unreadable or newer files read as empty or as what is understood`() {
        val dir = tmp.newFolder("ambient")
        assertEquals(IngestState(), FileIngestStores(dir).load())

        File(dir, "ingest_state.json").writeText("{ not json")
        assertEquals(IngestState(), FileIngestStores(dir).load())

        File(dir, "ingest_state.json").writeText("""{"v":9,"state":"ok","lastSuccessMs":5,"somethingNew":[1,2]}""")
        assertEquals(5L, FileIngestStores(dir).load().lastSuccessMs)
    }

    @Test
    fun `old seen entries age out`() {
        val dir = tmp.newFolder("ambient")
        val stores = FileIngestStores(dir)
        stores.markAll(listOf("old"), nowMs = 0)
        stores.markAll(listOf("new"), nowMs = 100L * 24 * 60 * 60 * 1000)
        assertFalse(stores.contains("old"))
        assertTrue(stores.contains("new"))
    }

    @Test
    fun `clearing forgets what was read`() {
        val dir = tmp.newFolder("ambient")
        val stores = FileIngestStores(dir)
        stores.markAll(listOf("m1"), nowMs = 1)
        stores.clear()
        assertFalse(FileIngestStores(dir).contains("m1"))
    }
}
