package org.ethereumphone.andyclaw.skills.customtools

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CustomToolStorePathsTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun def(name: String) = CustomToolDefinition(
        name = name, description = "d", parameters = JsonObject(emptyMap()), code = "print(1);", createdAt = "t",
    )

    @Test
    fun `names create_custom_tool accepts round-trip`() {
        val store = CustomToolStore(tmp.newFolder("custom-tools"))
        store.save(def("battery_report"))
        assertTrue(store.exists("battery_report"))
        assertNotNull(store.load("battery_report"))
        assertEquals(listOf("battery_report"), store.loadAll().map { it.name })
        assertTrue(store.delete("battery_report"))
    }

    @Test
    fun `traversal names reach nothing`() {
        val files = tmp.newFolder("files")
        val store = CustomToolStore(File(files, "custom-tools"))
        val queue = File(files, "pending_approvals.json").apply { writeText("[]") }
        File(files, "evil.json").writeText("""{"name":"x","description":"","parameters":{},"code":"boom","createdAt":""}""")

        assertFalse(store.delete("../pending_approvals"))
        assertTrue(queue.isFile)
        assertFalse(store.exists("../pending_approvals"))
        assertNull(store.load("../evil"))
        try {
            store.save(def("../evil2"))
            throw AssertionError("save accepted a traversal name")
        } catch (_: IllegalArgumentException) {
        }
        assertFalse(File(files, "evil2.json").exists())
    }

    @Test
    fun `name rule matches the creator's`() {
        assertTrue(CustomToolStore.isValidName("a"))
        assertTrue(CustomToolStore.isValidName("wifi_status2"))
        for (bad in listOf("", "A", "_x", "1x", "a-b", "a.b", "..", "a/b", "a" + "b".repeat(49))) {
            assertFalse(bad, CustomToolStore.isValidName(bad))
        }
    }

    @Test
    fun `a definition whose name the store refuses is not loaded`() {
        val dir = tmp.newFolder("custom-tools")
        File(dir, "ok.json").writeText("""{"name":"../x","description":"","parameters":{},"code":"","createdAt":""}""")
        assertTrue(CustomToolStore(dir).loadAll().isEmpty())
    }
}
