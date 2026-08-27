package org.ethereumphone.andyclaw.frames

import java.io.File
import java.security.MessageDigest
import java.util.logging.Logger

/** How much of the past the store is allowed to keep. */
data class FrameRetention(
    /** Whole sessions, newest kept. */
    val maxSessions: Int = 20,
    /** Total bytes across every session. */
    val maxBytes: Long = 64L * 1024 * 1024,
    /** Frames in one session. At one frame a second this is a ten-minute session. */
    val maxFramesPerSession: Int = 600,
)

/** One stored frame: where it is, when it was taken, and how big it is. */
data class FrameRef(
    val sessionId: String,
    val index: Int,
    val timestampMs: Long,
    val id: String,
    val sizeBytes: Long,
)

/**
 * The frames the agent saw, kept.
 *
 * `LauncherBindingService` already pulls a JPEG off the agent display once a second and
 * hands it to the launcher, and then throws it away. Keeping them is what turns "the agent
 * did something on your behalf" into "watch exactly what I did as you" — the strongest
 * trust artifact in the product, and, as `andyclaw-to-agent-first.md` §4 puts it, a
 * storage decision rather than a feature build.
 *
 * **Retention is not optional.** A frame a second at a few tens of kilobytes fills a phone
 * in days, so the cap exists before the capture is enabled rather than after: whole
 * sessions are evicted oldest-first, by count and by total bytes, and a single session
 * stops writing at [FrameRetention.maxFramesPerSession] rather than growing without end.
 * Sessions are the eviction unit on purpose — half a recording is worse than none, because
 * a replay that silently starts in the middle misrepresents what happened.
 *
 * Ids are relative paths, so an exported ledger row still names its frames after the app's
 * data directory has moved.
 */
class SessionFrameStore(
    private val root: File,
    private val retention: FrameRetention = FrameRetention(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val log = Logger.getLogger("SessionFrameStore")

    init {
        runCatching { root.mkdirs() }
    }

    /**
     * Open a session for writing.
     *
     * Reopening an existing session id continues it rather than restarting: the launcher
     * can stop and restart a capture stream within one agent turn, and two half-recordings
     * under one session would replay as a jump cut.
     */
    fun beginSession(sessionId: String): FrameSession {
        val dir = sessionDir(sessionId)
        runCatching { dir.mkdirs() }
        val existing = frameFiles(dir).size
        return FrameSession(sessionId, dir, existing)
    }

    /** Every frame of a session, in the order it was captured. */
    fun frames(sessionId: String): List<FrameRef> {
        val dir = sessionDir(sessionId)
        return frameFiles(dir).mapNotNull { file -> toRef(sessionId, dir, file) }
    }

    /** The bytes of one frame, or null if it is gone. */
    fun read(ref: FrameRef): ByteArray? = read(ref.id)

    /**
     * The file behind a frame id, if it is still there.
     *
     * Exists so a viewer can be handed a read-only fd for one frame rather than the bytes
     * over a binder transaction. Same id validation as [read] -- an id is `<dir>/<file>`
     * and nothing else, so this cannot be walked out of [root].
     */
    fun fileFor(frameId: String): File? = resolve(frameId)?.takeIf { it.isFile }

    fun read(frameId: String): ByteArray? {
        val file = resolve(frameId) ?: return null
        return try {
            if (file.isFile) file.readBytes() else null
        } catch (e: Exception) {
            log.warning("unreadable frame $frameId: ${e.message}")
            null
        }
    }

    /** Session directory names, oldest first. */
    fun sessions(): List<String> = sessionDirs().map { it.name }

    fun totalBytes(): Long = sessionDirs().sumOf { dir -> frameFiles(dir).sumOf { it.length() } }

    fun deleteSession(sessionId: String) {
        runCatching { sessionDir(sessionId).deleteRecursively() }
    }

    fun deleteAll() {
        runCatching { root.deleteRecursively() }
        runCatching { root.mkdirs() }
    }

    /**
     * Apply the caps.
     *
     * Runs after a session closes rather than after every frame: pruning is a directory
     * walk, and doing one per frame would put it on a once-a-second timer for the whole
     * length of a session.
     */
    fun prune() {
        var dirs = sessionDirs()

        while (dirs.size > retention.maxSessions) {
            val oldest = dirs.first()
            runCatching { oldest.deleteRecursively() }
            dirs = dirs.drop(1)
        }

        var total = dirs.sumOf { dir -> frameFiles(dir).sumOf { it.length() } }
        while (total > retention.maxBytes && dirs.isNotEmpty()) {
            val oldest = dirs.first()
            total -= frameFiles(oldest).sumOf { it.length() }
            runCatching { oldest.deleteRecursively() }
            dirs = dirs.drop(1)
        }
    }

    // ── Writing ───────────────────────────────────────────────────────

    /** A session open for writing. Not thread-safe; one capture loop owns one of these. */
    inner class FrameSession internal constructor(
        val sessionId: String,
        private val dir: File,
        startIndex: Int,
    ) {
        private var index = startIndex
        private val written = mutableListOf<String>()

        /** True once the per-session cap stopped the recording short. */
        var truncated: Boolean = false
            private set

        /** Frame ids written by this handle, in order. */
        val frameIds: List<String> get() = written.toList()

        /** Store one JPEG. Returns its id, or null if it was refused or failed. */
        fun write(jpeg: ByteArray): String? {
            if (jpeg.isEmpty()) return null
            if (index >= retention.maxFramesPerSession) {
                if (!truncated) {
                    truncated = true
                    log.info("session $sessionId hit the ${retention.maxFramesPerSession}-frame cap")
                }
                return null
            }
            val ts = clock()
            val name = "%06d-%d%s".format(index, ts, FRAME_SUFFIX)
            return try {
                File(dir, name).writeBytes(jpeg)
                index++
                val id = "${dir.name}/$name"
                written += id
                id
            } catch (e: Exception) {
                log.warning("could not write frame $index of $sessionId: ${e.message}")
                null
            }
        }

        /** Stop writing and apply the caps. */
        fun close() {
            if (written.isEmpty() && frameFiles(dir).isEmpty()) {
                // Nothing was captured — leaving an empty directory behind would make it
                // count against the session cap and evict a real recording.
                runCatching { dir.delete() }
            }
            prune()
        }
    }

    // ── Paths ─────────────────────────────────────────────────────────

    /**
     * A directory name derived from [sessionId] that cannot escape [root].
     *
     * Session ids come in from the launcher over binder and are not this app's to trust.
     * The readable part is kept for the sake of anyone looking at the directory, and a
     * hash of the original is appended so two ids that sanitise the same do not collide.
     */
    internal fun dirNameFor(sessionId: String): String {
        val safe = sessionId.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
            .joinToString("")
            .take(48)
            .ifBlank { "session" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(sessionId.toByteArray(Charsets.UTF_8))
            .take(4)
            .joinToString("") { "%02x".format(it) }
        return "$safe-$digest"
    }

    private fun sessionDir(sessionId: String) = File(root, dirNameFor(sessionId))

    private fun sessionDirs(): List<File> =
        (root.listFiles { f: File -> f.isDirectory } ?: emptyArray())
            .sortedBy { dir -> frameFiles(dir).firstOrNull()?.let { parseTimestamp(it.name) } ?: dir.lastModified() }

    private fun frameFiles(dir: File): List<File> =
        (dir.listFiles { f: File -> f.isFile && f.name.endsWith(FRAME_SUFFIX) } ?: emptyArray())
            .sortedBy { it.name }

    private fun resolve(frameId: String): File? {
        // Ids are `<dir>/<file>` and nothing else. Anything with a path separator beyond
        // that single one, or with a `..` in it, is not something this store wrote.
        val parts = frameId.split('/')
        if (parts.size != 2) return null
        if (parts.any { it.isBlank() || it == "." || it == ".." }) return null
        return File(File(root, parts[0]), parts[1])
    }

    private fun toRef(sessionId: String, dir: File, file: File): FrameRef? {
        val index = file.name.substringBefore('-').toIntOrNull() ?: return null
        return FrameRef(
            sessionId = sessionId,
            index = index,
            timestampMs = parseTimestamp(file.name) ?: file.lastModified(),
            id = "${dir.name}/${file.name}",
            sizeBytes = file.length(),
        )
    }

    private fun parseTimestamp(name: String): Long? =
        name.substringAfter('-', "").substringBefore('.').toLongOrNull()

    companion object {
        const val DIR_NAME = "session_frames"
        private const val FRAME_SUFFIX = ".jpg"
    }
}
