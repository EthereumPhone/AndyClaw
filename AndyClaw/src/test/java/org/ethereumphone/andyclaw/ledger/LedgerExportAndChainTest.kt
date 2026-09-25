package org.ethereumphone.andyclaw.ledger

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.ethereumphone.andyclaw.ledger.db.LedgerDao
import org.ethereumphone.andyclaw.ledger.db.entity.LedgerEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.StringWriter

class LedgerExportAndChainTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class MemoryDao : LedgerDao {
        val rows = mutableListOf<LedgerEntryEntity>()
        override suspend fun insert(entry: LedgerEntryEntity) { rows += entry }
        override suspend fun getAll() = rows.sortedBy { it.seq }
        override suspend fun getRecent(limit: Int) = rows.sortedByDescending { it.seq }.take(limit)
        override fun observeRecent(limit: Int): Flow<List<LedgerEntryEntity>> = flowOf(rows.sortedByDescending { it.seq }.take(limit))
        override suspend fun getBySession(sessionId: String) = rows.filter { it.sessionId == sessionId }.sortedBy { it.seq }
        override suspend fun getLast() = rows.maxByOrNull { it.seq }
        override suspend fun count() = rows.size
        override suspend fun deleteThroughSeq(seq: Long) { rows.removeAll { it.seq <= seq } }
    }

    private fun draft(i: Int) = LedgerDraft(
        sessionId = "s", kind = LedgerKind.TOOL, intent = "step $i", provenance = "USER", outcome = LedgerOutcome.OK,
        actions = listOf(LedgerAction("tool_$i", true, 3, note = "n\"$i")), costUsd = 0.1 + 0.2, frames = listOf("d/$i.jpg"),
    )

    @Test
    fun `an export line carries the stored bytes, so the chain can be re-verified off the phone`() = runTest {
        val dao = MemoryDao()
        val repo = LedgerRepository(dao)
        repeat(3) { repo.append(draft(it)) }

        val out = StringWriter()
        LedgerExporter(repo).write(dao.getAll(), out)
        val lines = out.toString().trim().lines().map { Json.parseToJsonElement(it).jsonObject }

        assertEquals(3, lines.size)
        for ((line, stored) in lines.zip(dao.getAll())) {
            val canonical = line["canonical"]!!.jsonObject
            // Rebuild the row from the export alone and hash it the way the phone did.
            val rebuilt = stored.copy(
                actionsJson = canonical["actionsJson"]!!.jsonPrimitive.content,
                framesJson = canonical["framesJson"]!!.jsonPrimitive.content,
                modelIdsJson = canonical["modelIdsJson"]!!.jsonPrimitive.content,
            )
            assertEquals(line["hash"]!!.jsonPrimitive.content, LedgerChain.hashOf(rebuilt))
            assertEquals(stored.seq, line["seq"]!!.jsonPrimitive.longOrNull)
        }
    }

    @Test
    fun `export waits for queued rows and leaves no file behind on failure`() = runTest {
        val dao = MemoryDao()
        val repo = LedgerRepository(dao)
        var drained = false
        val file = LedgerExporter(repo) { drained = true; true }.export(tmp.root)
        assertTrue(drained)
        assertTrue(file.exists())

        val failing = object : LedgerDao by dao {
            override suspend fun getAll(): List<LedgerEntryEntity> = error("disk gone")
        }
        val dir = tmp.newFolder("failing")
        runCatching { LedgerExporter(LedgerRepository(failing)).export(dir) }
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun `a chain that still starts at row one must start at genesis`() = runTest {
        val dao = MemoryDao()
        val repo = LedgerRepository(dao)
        repeat(2) { repo.append(draft(it)) }
        assertTrue(repo.verify().ok)

        // Rewrite the first row's link and re-seal it: every hash checks out, the start does not.
        val first = dao.rows.first { it.seq == 1L }
        val forged = first.copy(prevHash = "f".repeat(64)).let { it.copy(hash = LedgerChain.hashOf(it)) }
        dao.rows[dao.rows.indexOf(first)] = forged
        val v = repo.verify()
        assertFalse(v.ok)
        assertEquals(1L, v.brokenAtSeq)
    }

    @Test
    fun `the ledger database stays at version 1`() {
        // A version bump plus an OTA rollback would leave every older build unable to open the
        // ledger. New data goes into a sibling database; this one's schema is frozen.
        val dir = File("schemas/org.ethereumphone.andyclaw.ledger.db.LedgerDatabase")
        val files = dir.listFiles()?.map { it.name }?.sorted().orEmpty()
        assertEquals(listOf("1.json"), files)
    }
}
