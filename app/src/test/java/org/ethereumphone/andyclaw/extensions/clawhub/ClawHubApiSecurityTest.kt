package org.ethereumphone.andyclaw.extensions.clawhub

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ClawHubApiSecurityTest {

    @Test
    fun `downloadAndExtract allows safe archives`() {
        val zip = buildZip(
            listOf(
                "SKILL.md" to "# safe".toByteArray(),
                "scripts/run.sh" to "echo hi".toByteArray(),
            )
        )

        withApiServingZip(zip) { api ->
            withTempDir { dir ->
                val ok = api.downloadAndExtract(slug = "safe-skill", targetDir = dir)
                assertTrue(ok)
                assertTrue(File(dir, "SKILL.md").isFile)
                assertEquals("# safe", File(dir, "SKILL.md").readText())
                assertTrue(File(dir, "scripts/run.sh").isFile)
            }
        }
    }

    @Test
    fun `downloadAndExtract rejects zip slip paths`() {
        val zip = buildZip(listOf("../evil.txt" to "pwn".toByteArray()))

        withApiServingZip(zip) { api ->
            withTempDir { dir ->
                val ok = api.downloadAndExtract(slug = "evil-skill", targetDir = dir)
                assertFalse(ok)
                assertFalse(File(dir.parentFile, "evil.txt").exists())
            }
        }
    }

    @Test
    fun `downloadAndExtract rejects too many entries`() {
        val entries = (0..2000).map { idx -> "f$idx.txt" to ByteArray(0) }
        val zip = buildZip(entries)

        withApiServingZip(zip) { api ->
            withTempDir { dir ->
                val ok = api.downloadAndExtract(slug = "too-many", targetDir = dir)
                assertFalse(ok)
            }
        }
    }

    @Test
    fun `downloadAndExtract rejects oversized uncompressed entry`() {
        val oversized = ByteArray(5 * 1024 * 1024 + 1) { 'a'.code.toByte() }
        val zip = buildZip(listOf("big.bin" to oversized))

        withApiServingZip(zip) { api ->
            withTempDir { dir ->
                val ok = api.downloadAndExtract(slug = "oversized", targetDir = dir)
                assertFalse(ok)
            }
        }
    }

    private fun withApiServingZip(zipBytes: ByteArray, block: suspend (ClawHubApi) -> Unit) {
        runBlocking {
            val server = MockWebServer()
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(Buffer().write(zipBytes))
            )
            server.start()
            try {
                val api = ClawHubApi(registryUrl = server.url("/").toString())
                block(api)
            } finally {
                server.shutdown()
            }
        }
    }

    private fun buildZip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                val entry = ZipEntry(name)
                zip.putNextEntry(entry)
                if (!name.endsWith("/")) {
                    zip.write(content)
                }
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun withTempDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("clawhub-api-test-").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }
}
