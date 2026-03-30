package org.ethereumphone.andyclaw.backup

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ethereumphone.andyclaw.NodeApp
import org.ethereumphone.andyclaw.agenttx.db.AgentTxDatabase
import org.ethereumphone.andyclaw.agenttx.db.entity.AgentTxEntity
import org.ethereumphone.andyclaw.memory.db.MemoryDatabase
import org.ethereumphone.andyclaw.memory.db.entity.MemoryChunkEntity
import org.ethereumphone.andyclaw.memory.db.entity.MemoryEntryEntity
import org.ethereumphone.andyclaw.memory.db.entity.MemoryEntryTagCrossRef
import org.ethereumphone.andyclaw.memory.db.entity.MemoryTagEntity
import org.ethereumphone.andyclaw.sessions.db.SessionDatabase
import org.ethereumphone.andyclaw.sessions.db.entity.SessionEntity
import org.ethereumphone.andyclaw.sessions.db.entity.SessionMessageEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Handles creating and restoring AndyClaw backup archives.
 *
 * Backup is a ZIP file containing JSON exports of all user data:
 * settings, memories, sessions, transactions, custom tools, and files.
 *
 * When a password is provided the ZIP is encrypted with AES-256-GCM
 * using a key derived via PBKDF2. The encrypted file starts with a
 * 4-byte magic header ("ACBK") so it can be detected without trying
 * to parse a ZIP first.
 */
class BackupManager(private val context: Context) {

    companion object {
        private const val TAG = "BackupManager"
        private const val BACKUP_VERSION = 1
        const val MIME_TYPE = "application/octet-stream"
        const val FILE_EXTENSION = ".andyclaw.backup"

        // Encryption constants
        private val MAGIC = byteArrayOf(0x41, 0x43, 0x42, 0x4B) // "ACBK"
        private const val SALT_LENGTH = 16
        private const val IV_LENGTH = 12
        private const val GCM_TAG_BITS = 128
        private const val PBKDF2_ITERATIONS = 210_000
        private const val KEY_LENGTH_BITS = 256
    }

    private val app get() = context.applicationContext as NodeApp

    // ── Export ──────────────────────────────────────────────────────────

    /**
     * Create a backup archive.
     * @param password  If non-null the archive is AES-256-GCM encrypted.
     */
    suspend fun createBackup(
        outputStream: OutputStream,
        password: String? = null,
    ) = withContext(Dispatchers.IO) {
        // Build the ZIP into memory
        val zipBytes = ByteArrayOutputStream()
        ZipOutputStream(zipBytes).use { zip ->
            writeZipEntry(zip, "manifest.json", buildManifest().toString())
            writeZipEntry(zip, "settings.json", exportSettings().toString())
            writeZipEntry(zip, "memories.json", exportMemories().toString())
            writeZipEntry(zip, "sessions.json", exportSessions().toString())
            writeZipEntry(zip, "transactions.json", exportTransactions().toString())
            app.soulManager.read()?.let { writeZipEntry(zip, "soul.md", it) }
            app.userStoryManager.read()?.let { writeZipEntry(zip, "user_story.md", it) }
            writeZipEntry(zip, "heartbeat_logs.json", exportHeartbeatLogs().toString())
            writeZipEntry(zip, "custom_tools.json", exportCustomTools().toString())
            exportClawHubSkills(zip)
        }

        val plaintext = zipBytes.toByteArray()

        if (password.isNullOrEmpty()) {
            outputStream.write(plaintext)
        } else {
            encrypt(plaintext, password, outputStream)
        }

        Log.i(TAG, "Backup created successfully (encrypted=${!password.isNullOrEmpty()})")
    }

    // ── Import ──────────────────────────────────────────────────────────

    /**
     * Check whether a backup file is encrypted by peeking at the magic header.
     */
    suspend fun isEncrypted(inputStream: InputStream): Boolean = withContext(Dispatchers.IO) {
        val header = ByteArray(MAGIC.size)
        val read = inputStream.read(header)
        read == MAGIC.size && header.contentEquals(MAGIC)
    }

    /**
     * Parse the backup manifest without restoring anything.
     * @param password  Required if the backup is encrypted; ignored otherwise.
     */
    suspend fun readManifest(
        inputStream: InputStream,
        password: String? = null,
    ): BackupInfo? = withContext(Dispatchers.IO) {
        try {
            val zipStream = decryptIfNeeded(inputStream, password)
            ZipInputStream(zipStream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "manifest.json") {
                        val json = JSONObject(zip.readBytes().decodeToString())
                        return@withContext BackupInfo(
                            version = json.optInt("version", 0),
                            appVersionName = json.optString("appVersionName", ""),
                            appVersionCode = json.optInt("appVersionCode", 0),
                            timestamp = json.optLong("timestamp", 0L),
                            deviceName = json.optString("deviceName", "Unknown"),
                            aiName = json.optString("aiName", "AndyClaw"),
                        )
                    }
                    entry = zip.nextEntry
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read backup manifest", e)
            null
        }
    }

    /**
     * Restore all data from a backup archive.
     * @param password  Required if the backup is encrypted; ignored otherwise.
     */
    suspend fun restoreBackup(
        inputStream: InputStream,
        password: String? = null,
    ) = withContext(Dispatchers.IO) {
        val zipStream = decryptIfNeeded(inputStream, password)
        val entries = mutableMapOf<String, ByteArray>()

        ZipInputStream(zipStream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes()
                entry = zip.nextEntry
            }
        }

        // Validate manifest
        val manifest = entries["manifest.json"]
            ?.let { JSONObject(it.decodeToString()) }
            ?: throw IllegalArgumentException("Invalid backup: missing manifest")
        val version = manifest.optInt("version", 0)
        if (version > BACKUP_VERSION) {
            throw IllegalArgumentException("Backup version $version is newer than supported ($BACKUP_VERSION)")
        }

        entries["settings.json"]?.let { importSettings(JSONObject(it.decodeToString())) }
        entries["memories.json"]?.let { importMemories(JSONObject(it.decodeToString())) }
        entries["sessions.json"]?.let { importSessions(JSONObject(it.decodeToString())) }
        entries["transactions.json"]?.let { importTransactions(JSONArray(it.decodeToString())) }
        entries["soul.md"]?.let { app.soulManager.write(it.decodeToString()) }
        entries["user_story.md"]?.let { app.userStoryManager.write(it.decodeToString()) }
        entries["heartbeat_logs.json"]?.let { importHeartbeatLogs(JSONArray(it.decodeToString())) }
        entries["custom_tools.json"]?.let { importCustomTools(JSONArray(it.decodeToString())) }
        importClawHubSkills(entries)

        Log.i(TAG, "Backup restored successfully")
    }

    // ── Encryption ──────────────────────────────────────────────────────

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun encrypt(plaintext: ByteArray, password: String, out: OutputStream) {
        val random = SecureRandom()
        val salt = ByteArray(SALT_LENGTH).also { random.nextBytes(it) }
        val iv = ByteArray(IV_LENGTH).also { random.nextBytes(it) }
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext)
        out.write(MAGIC)
        out.write(salt)
        out.write(iv)
        out.write(ciphertext)
    }

    /**
     * If the stream starts with MAGIC, decrypt it and return a stream
     * over the plaintext ZIP. Otherwise return the raw bytes as-is.
     *
     * @throws javax.crypto.AEADBadTagException if the password is wrong.
     */
    private fun decryptIfNeeded(inputStream: InputStream, password: String?): InputStream {
        val allBytes = inputStream.readBytes()

        // Check magic header
        if (allBytes.size >= MAGIC.size && allBytes.sliceArray(MAGIC.indices).contentEquals(MAGIC)) {
            // Encrypted
            require(!password.isNullOrEmpty()) { "This backup is password-protected" }
            val offset = MAGIC.size
            val salt = allBytes.sliceArray(offset until offset + SALT_LENGTH)
            val iv = allBytes.sliceArray(offset + SALT_LENGTH until offset + SALT_LENGTH + IV_LENGTH)
            val ciphertext = allBytes.sliceArray(offset + SALT_LENGTH + IV_LENGTH until allBytes.size)
            val key = deriveKey(password, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            val plaintext = cipher.doFinal(ciphertext)
            return ByteArrayInputStream(plaintext)
        }

        // Not encrypted — return as-is
        return ByteArrayInputStream(allBytes)
    }

    // ── Settings export/import ──────────────────────────────────────────

    private fun exportSettings(): JSONObject {
        val result = JSONObject()
        val values = app.securePrefs.exportAllValues()
        for ((key, value) in values) {
            when (value) {
                is String -> result.put(key, JSONObject().put("type", "string").put("value", value))
                is Boolean -> result.put(key, JSONObject().put("type", "boolean").put("value", value))
                is Int -> result.put(key, JSONObject().put("type", "int").put("value", value))
                is Long -> result.put(key, JSONObject().put("type", "long").put("value", value))
                is Float -> result.put(key, JSONObject().put("type", "float").put("value", value.toDouble()))
                is Set<*> -> result.put(key, JSONObject().put("type", "stringset").put("value", JSONArray(value.toList())))
            }
        }
        return result
    }

    private fun importSettings(json: JSONObject) {
        val values = mutableMapOf<String, Any?>()
        for (key in json.keys()) {
            val entry = json.getJSONObject(key)
            val type = entry.getString("type")
            values[key] = when (type) {
                "string" -> entry.getString("value")
                "boolean" -> entry.getBoolean("value")
                "int" -> entry.getInt("value")
                "long" -> entry.getLong("value")
                "float" -> entry.getDouble("value").toFloat()
                "stringset" -> {
                    val arr = entry.getJSONArray("value")
                    (0 until arr.length()).map { arr.getString(it) }.toSet()
                }
                else -> null
            }
        }
        app.securePrefs.importAllValues(values)
    }

    // ── Memory export/import ────────────────────────────────────────────

    private suspend fun exportMemories(): JSONObject {
        val memoryDao = MemoryDatabase.getInstance(context).memoryDao()
        val result = JSONObject()

        val entries = JSONArray()
        for (entry in memoryDao.getAllEntries()) {
            entries.put(JSONObject().apply {
                put("id", entry.id)
                put("agentId", entry.agentId)
                put("content", entry.content)
                put("source", entry.source)
                put("importance", entry.importance.toDouble())
                put("hash", entry.hash)
                put("createdAt", entry.createdAt)
                put("updatedAt", entry.updatedAt)
                put("accessedAt", entry.accessedAt)
                put("accessCount", entry.accessCount)
            })
        }
        result.put("entries", entries)

        val tags = JSONArray()
        for (tag in memoryDao.getAllTags()) {
            tags.put(JSONObject().apply {
                put("id", tag.id)
                put("name", tag.name)
            })
        }
        result.put("tags", tags)

        val xrefs = JSONArray()
        for (xref in memoryDao.getAllEntryTagCrossRefs()) {
            xrefs.put(JSONObject().apply {
                put("memoryId", xref.memoryId)
                put("tagId", xref.tagId)
            })
        }
        result.put("entryTags", xrefs)

        val chunks = JSONArray()
        for (entry in memoryDao.getAllEntries()) {
            for (chunk in memoryDao.getChunksByMemory(entry.id)) {
                chunks.put(JSONObject().apply {
                    put("chunkUuid", chunk.chunkUuid)
                    put("memoryId", chunk.memoryId)
                    put("text", chunk.text)
                    put("startOffset", chunk.startOffset)
                    put("endOffset", chunk.endOffset)
                    put("hash", chunk.hash)
                    put("updatedAt", chunk.updatedAt)
                })
            }
        }
        result.put("chunks", chunks)

        return result
    }

    private suspend fun importMemories(json: JSONObject) {
        val memoryDao = MemoryDatabase.getInstance(context).memoryDao()

        memoryDao.deleteAllEntries()

        val entriesArr = json.getJSONArray("entries")
        val entries = (0 until entriesArr.length()).map { i ->
            val obj = entriesArr.getJSONObject(i)
            MemoryEntryEntity(
                id = obj.getString("id"),
                agentId = obj.getString("agentId"),
                content = obj.getString("content"),
                source = obj.getString("source"),
                importance = obj.getDouble("importance").toFloat(),
                hash = obj.getString("hash"),
                createdAt = obj.getLong("createdAt"),
                updatedAt = obj.getLong("updatedAt"),
                accessedAt = obj.optLong("accessedAt", 0L),
                accessCount = obj.optInt("accessCount", 0),
            )
        }
        memoryDao.insertEntries(entries)

        val tagsArr = json.getJSONArray("tags")
        val tags = (0 until tagsArr.length()).map { i ->
            val obj = tagsArr.getJSONObject(i)
            MemoryTagEntity(
                id = obj.getLong("id"),
                name = obj.getString("name"),
            )
        }
        memoryDao.insertTags(tags)

        val xrefsArr = json.getJSONArray("entryTags")
        val xrefs = (0 until xrefsArr.length()).map { i ->
            val obj = xrefsArr.getJSONObject(i)
            MemoryEntryTagCrossRef(
                memoryId = obj.getString("memoryId"),
                tagId = obj.getLong("tagId"),
            )
        }
        memoryDao.insertEntryTagCrossRefs(xrefs)

        val chunksArr = json.optJSONArray("chunks")
        if (chunksArr != null && chunksArr.length() > 0) {
            val chunks = (0 until chunksArr.length()).map { i ->
                val obj = chunksArr.getJSONObject(i)
                MemoryChunkEntity(
                    chunkUuid = obj.getString("chunkUuid"),
                    memoryId = obj.getString("memoryId"),
                    text = obj.getString("text"),
                    startOffset = obj.getInt("startOffset"),
                    endOffset = obj.getInt("endOffset"),
                    hash = obj.getString("hash"),
                    updatedAt = obj.getLong("updatedAt"),
                )
            }
            memoryDao.insertChunks(chunks)
        }
    }

    // ── Session export/import ───────────────────────────────────────────

    private suspend fun exportSessions(): JSONObject {
        val sessionDao = SessionDatabase.getInstance(context).sessionDao()
        val result = JSONObject()

        val sessions = JSONArray()
        for (session in sessionDao.getAllSessions()) {
            sessions.put(JSONObject().apply {
                put("id", session.id)
                put("agentId", session.agentId)
                put("title", session.title)
                put("model", session.model)
                put("createdAt", session.createdAt)
                put("updatedAt", session.updatedAt)
                put("label", session.label)
                put("thinkingLevel", session.thinkingLevel)
                put("inputTokens", session.inputTokens)
                put("outputTokens", session.outputTokens)
                put("totalTokens", session.totalTokens)
                put("isAborted", session.isAborted)
            })
        }
        result.put("sessions", sessions)

        val messages = JSONArray()
        for (msg in sessionDao.getAllMessages()) {
            messages.put(JSONObject().apply {
                put("id", msg.id)
                put("sessionId", msg.sessionId)
                put("role", msg.role)
                put("content", msg.content)
                put("toolName", msg.toolName)
                put("toolCallId", msg.toolCallId)
                put("timestamp", msg.timestamp)
                put("orderIndex", msg.orderIndex)
            })
        }
        result.put("messages", messages)

        return result
    }

    private suspend fun importSessions(json: JSONObject) {
        val sessionDao = SessionDatabase.getInstance(context).sessionDao()

        sessionDao.deleteAllSessions()

        val sessionsArr = json.getJSONArray("sessions")
        val sessions = (0 until sessionsArr.length()).map { i ->
            val obj = sessionsArr.getJSONObject(i)
            SessionEntity(
                id = obj.getString("id"),
                agentId = obj.getString("agentId"),
                title = obj.getString("title"),
                model = obj.nullableString("model"),
                createdAt = obj.getLong("createdAt"),
                updatedAt = obj.getLong("updatedAt"),
                label = obj.nullableString("label"),
                thinkingLevel = obj.nullableString("thinkingLevel"),
                inputTokens = obj.optInt("inputTokens", 0),
                outputTokens = obj.optInt("outputTokens", 0),
                totalTokens = obj.optInt("totalTokens", 0),
                isAborted = obj.optBoolean("isAborted", false),
            )
        }
        sessionDao.insertSessions(sessions)

        val messagesArr = json.getJSONArray("messages")
        val messages = (0 until messagesArr.length()).map { i ->
            val obj = messagesArr.getJSONObject(i)
            SessionMessageEntity(
                id = obj.getString("id"),
                sessionId = obj.getString("sessionId"),
                role = obj.getString("role"),
                content = obj.getString("content"),
                toolName = obj.nullableString("toolName"),
                toolCallId = obj.nullableString("toolCallId"),
                timestamp = obj.getLong("timestamp"),
                orderIndex = obj.getInt("orderIndex"),
            )
        }
        sessionDao.insertMessages(messages)
    }

    // ── Transaction export/import ───────────────────────────────────────

    private suspend fun exportTransactions(): JSONArray {
        val txDao = AgentTxDatabase.getInstance(context).agentTxDao()
        val result = JSONArray()
        for (tx in txDao.getAll()) {
            result.put(JSONObject().apply {
                put("id", tx.id)
                put("userOpHash", tx.userOpHash)
                put("chainId", tx.chainId)
                put("to", tx.to)
                put("amount", tx.amount)
                put("token", tx.token)
                put("toolName", tx.toolName)
                put("timestamp", tx.timestamp)
            })
        }
        return result
    }

    private suspend fun importTransactions(arr: JSONArray) {
        val txDao = AgentTxDatabase.getInstance(context).agentTxDao()
        txDao.deleteAll()
        val txs = (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            AgentTxEntity(
                id = obj.getString("id"),
                userOpHash = obj.getString("userOpHash"),
                chainId = obj.getInt("chainId"),
                to = obj.getString("to"),
                amount = obj.getString("amount"),
                token = obj.getString("token"),
                toolName = obj.getString("toolName"),
                timestamp = obj.getLong("timestamp"),
            )
        }
        txDao.insertAll(txs)
    }

    // ── Heartbeat log export/import ─────────────────────────────────────

    private fun exportHeartbeatLogs(): JSONArray {
        val result = JSONArray()
        for (entry in app.heartbeatLogStore.getAll()) {
            result.put(JSONObject().apply {
                put("timestampMs", entry.timestampMs)
                put("outcome", entry.outcome)
                put("prompt", entry.prompt)
                put("responseText", entry.responseText)
                put("error", entry.error)
                put("durationMs", entry.durationMs)
                val tools = JSONArray()
                for (tc in entry.toolCalls) {
                    tools.put(JSONObject().apply {
                        put("toolName", tc.toolName)
                        put("result", tc.result)
                    })
                }
                put("toolCalls", tools)
            })
        }
        return result
    }

    private fun importHeartbeatLogs(arr: JSONArray) {
        val file = java.io.File(context.filesDir, "heartbeat-logs.json")
        file.writeText(arr.toString())
    }

    // ── Custom tool export/import ───────────────────────────────────────

    private fun exportCustomTools(): JSONArray {
        val result = JSONArray()
        for (tool in app.customToolStore.loadAll()) {
            result.put(JSONObject().apply {
                put("name", tool.name)
                put("description", tool.description)
                put("parameters", tool.parameters.toString())
                put("code", tool.code)
                put("createdAt", tool.createdAt)
            })
        }
        return result
    }

    private fun importCustomTools(arr: JSONArray) {
        val toolsDir = java.io.File(context.filesDir, "custom-tools")
        if (toolsDir.exists()) {
            toolsDir.listFiles()?.forEach { it.delete() }
        }
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val def = org.ethereumphone.andyclaw.skills.customtools.CustomToolDefinition(
                name = obj.getString("name"),
                description = obj.getString("description"),
                parameters = kotlinx.serialization.json.Json.parseToJsonElement(
                    obj.getString("parameters")
                ) as kotlinx.serialization.json.JsonObject,
                code = obj.getString("code"),
                createdAt = obj.getString("createdAt"),
            )
            app.customToolStore.save(def)
        }
    }

    // ── ClawHub skills export/import ────────────────────────────────────

    private fun exportClawHubSkills(zip: ZipOutputStream) {
        val skillsDir = app.clawHubSkillsDir
        if (!skillsDir.exists()) return

        skillsDir.walkTopDown().forEach { file ->
            if (file.isFile) {
                val relativePath = file.relativeTo(skillsDir).path
                val zipPath = "clawhub-skills/$relativePath"
                zip.putNextEntry(ZipEntry(zipPath))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private suspend fun importClawHubSkills(entries: Map<String, ByteArray>) {
        val prefix = "clawhub-skills/"
        val skillEntries = entries.filter { it.key.startsWith(prefix) }
        if (skillEntries.isEmpty()) return

        val skillsDir = app.clawHubSkillsDir

        skillsDir.listFiles()?.forEach { it.deleteRecursively() }

        for ((path, data) in skillEntries) {
            val relativePath = path.removePrefix(prefix)
            val target = java.io.File(skillsDir, relativePath)
            target.parentFile?.mkdirs()
            target.writeBytes(data)
        }

        try {
            app.extensionEngine.discoverAndRegister()
        } catch (_: Exception) {
            // Best-effort; user can rescan from settings
        }
    }

    // ── Manifest ────────────────────────────────────────────────────────

    private fun buildManifest(): JSONObject {
        val pm = context.packageManager
        val packageInfo = pm.getPackageInfo(context.packageName, 0)
        return JSONObject().apply {
            put("version", BACKUP_VERSION)
            put("appVersionName", packageInfo.versionName ?: "")
            put("appVersionCode", packageInfo.longVersionCode)
            put("timestamp", System.currentTimeMillis())
            put("deviceName", app.securePrefs.displayName.value)
            put("aiName", app.securePrefs.aiName.value)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun writeZipEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun JSONObject.nullableString(key: String): String? {
        if (isNull(key) || !has(key)) return null
        val v = optString(key, "")
        return v.takeIf { it.isNotEmpty() && it != "null" }
    }
}

data class BackupInfo(
    val version: Int,
    val appVersionName: String,
    val appVersionCode: Int,
    val timestamp: Long,
    val deviceName: String,
    val aiName: String,
)
