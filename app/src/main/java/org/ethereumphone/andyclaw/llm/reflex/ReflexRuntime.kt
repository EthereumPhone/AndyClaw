package org.ethereumphone.andyclaw.llm.reflex

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.ethereumphone.andyclaw.autopilot.SensitiveApps
import org.ethereumphone.andyclaw.llm.LlamaCpp
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * The reflex models in AndyClaw's process: where their files live, and the [ReflexRouter] over
 * them. Built lazily, off the main thread, the first time a turn asks.
 *
 * - M1 (encoder, heads, labels) ships as APK assets and is copied once to
 *   `filesDir/models/reflex/` — llama.cpp needs a real path — and checked against [PINNED].
 *   A subdirectory, so `GgufRegistry`'s BYO-model list (top level of `models/`) never shows it.
 * - M2 (292 MB) is not in the APK. On ethOS the user downloads it from Settings › Your dGEN1 ›
 *   AndyClaw: Settings fetches it and hands it over through [ReflexProvider]'s staging file, and
 *   [installStaged] checks it against [PINNED]. Elsewhere AndyClaw's own settings fetch it
 *   ([startActorDownload]). Nothing downloads it by itself. Without it the M1-only actions still
 *   work and intents go to the agent.
 *
 * Every name carries the model version: an update never loads a mixed set.
 */
class ReflexRuntime(
    private val context: Context,
    private val llamaCpp: LlamaCpp,
) {
    private val dir = File(context.filesDir, "models/reflex")

    @Volatile
    private var router: ReflexRouter? = null
    @Volatile
    private var failed = false
    private val buildLock = Any()

    @Volatile
    private var actor: ActorModel? = null

    /** Background work that outlives a turn: the shadow's M2 call and its record. */
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    val shadow: ReflexShadow by lazy { ReflexShadow(File(context.filesDir, "reflex_shadow.json")) }

    /** What Reflex has done on this phone and when, for the settings that show it. */
    val activity: ReflexActivity by lazy { ReflexActivity(File(context.filesDir, "reflex_activity.json")) }

    private val _actorState = MutableStateFlow(if (actorFile().isFile) ActorState.READY else ActorState.ABSENT)
    /** Whether M2 is on the phone, for the settings rows and [ReflexProvider]. */
    val actorState: StateFlow<ActorState> = _actorState.asStateFlow()
    private val _actorProgress = MutableStateFlow(0f)
    val actorProgress: StateFlow<Float> = _actorProgress.asStateFlow()

    /** DOWNLOADING and FAILED are AndyClaw's own download; INSTALLING is a hand-over from Settings. */
    enum class ActorState { ABSENT, DOWNLOADING, INSTALLING, READY, FAILED }

    /**
     * The router, or null when M1 cannot run (no files, a bad hash, a failed load). Blocking: the
     * first call copies 37 MB and loads the encoder (~0.3 s). Never call it on the main thread.
     */
    fun routerOrNull(): ReflexRouter? {
        router?.let { return it }
        if (failed) return null
        synchronized(buildLock) {
            router?.let { return it }
            if (failed) return null
            return runCatching { build() }
                .onFailure { failed = true; Log.w(TAG, "reflex models unavailable", it) }
                .getOrNull()
                ?.also { router = it }
        }
    }

    private val building = java.util.concurrent.atomic.AtomicBoolean(false)

    /** M1 for Settings: `ready`, `loading` (not built yet, or building), or `failed`. */
    fun m1State(): String = when {
        router != null -> "ready"
        failed -> "failed"
        else -> "loading"
    }

    /**
     * The router if it is built; otherwise starts building it in the background and returns null.
     * A turn never waits for the first extraction and load.
     */
    fun routerIfReady(): ReflexRouter? {
        router?.let { return it }
        if (!failed && building.compareAndSet(false, true)) {
            Thread({ try { routerOrNull() } finally { building.set(false) } }, "reflex-build").start()
        }
        return null
    }

    /** The launcher label of [pkg], for a reply ("Opening Telegram."). */
    fun appLabel(pkg: String): String? = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()

    /**
     * AndyClaw's own download, for a phone without ethOS Settings (whose page is the way on a
     * dGEN1). Claims the download and starts it; false if M2 is already here, or a download or an
     * install is already running. The claim is made before this returns, so a second tap cannot
     * start another download into the same temp file.
     */
    fun startActorDownload(): Boolean {
        if (actorFile().isFile) {
            _actorState.value = ActorState.READY
            return false
        }
        while (true) {
            val current = _actorState.value
            if (current == ActorState.DOWNLOADING || current == ActorState.INSTALLING || current == ActorState.READY) return false
            if (_actorState.compareAndSet(current, ActorState.DOWNLOADING)) break
        }
        _actorProgress.value = 0f
        scope.launch { downloadActor() }
        return true
    }

    /** One line for the launcher's settings: what shadow mode has seen so far. */
    fun shadowSummary(): String {
        val s = shadow.stats()
        if (s.fired + s.missed + s.correctAbstain == 0) return "Nothing seen yet."
        val agreed = if (s.fired > 0) " (${s.agreed * 100 / s.fired} % matched the AI)" else ""
        return "Would have handled ${s.fired} request${if (s.fired == 1) "" else "s"} on the phone$agreed; " +
            "left ${s.missed} to the AI that it could have done."
    }

    private fun build(): ReflexRouter {
        val t0 = System.nanoTime()
        val encoder = extract(ENCODER)
        val heads = ReflexHeads.parse(extract(HEADS).readBytes(), ReflexLabels.parse(extract(LABELS).readText()))
        val m1 = ReflexModel(encoder, heads)
        // Load now, so the first user turn does not pay for it.
        checkNotNull(m1.classify("hello")) { "encoder did not load" }
        Log.i(TAG, "reflex ready in ${(System.nanoTime() - t0) / 1_000_000} ms (M2 ${actorState.value})")
        return ReflexRouter(m1, ::actorOrNull, ReflexResolver(AndroidDevice(context)))
    }

    /** M2, if its file is on the phone and verified. */
    private fun actorOrNull(): ActorModel? {
        actor?.let { return it }
        val f = actorFile()
        if (!f.isFile) return null
        return ActorModel(llamaCpp, f, scope, onUnloaded = { router?.classify("warm") }).also { actor = it }
    }

    private fun actorFile() = File(dir, ACTOR)

    /**
     * Downloads M2 to a temp file from each of [ACTOR_URLS] in turn until one gives a file whose
     * sha256 matches [PINNED], and only then moves it into place. A wrong hash is deleted, never
     * loaded. Runs only after [startActorDownload] has claimed the download.
     */
    private suspend fun downloadActor(): Boolean = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val tmp = File(dir, "$ACTOR.tmp")
        var done = false
        try {
            val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
            for (url in ACTOR_URLS) {
                try {
                    _actorProgress.value = 0f
                    http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                        check(resp.isSuccessful) { "HTTP ${resp.code}" }
                        val body = checkNotNull(resp.body)
                        val total = body.contentLength().takeIf { it > 0 } ?: ACTOR_BYTES
                        val sha = copyHashing(body.byteStream(), tmp) { n -> _actorProgress.value = (n.toFloat() / total).coerceIn(0f, 1f) }
                        check(sha == PINNED.getValue(ACTOR)) { "sha256 mismatch" }
                    }
                    check(tmp.renameTo(actorFile())) { "rename failed" }
                    done = true
                    break
                } catch (e: Exception) {
                    org.ethereumphone.andyclaw.ExecutionEngine.rethrowIfCancelled(e)
                    // The host, not the URL, goes in the log.
                    Log.w(TAG, "actor download from ${url.toHttpUrlOrNull()?.host} failed: ${e.message}")
                    tmp.delete()
                }
            }
        } finally {
            // A cancel ends here too: never leave the state at DOWNLOADING with nothing running.
            if (done) {
                _actorState.value = ActorState.READY
            } else {
                tmp.delete()
                _actorState.value = ActorState.FAILED
            }
        }
        done
    }

    // ── The hand-over from Settings (ReflexProvider) ─────────────────────────

    private val installLock = Any()

    val isInstalling: Boolean get() = _actorState.value == ActorState.INSTALLING

    /** Where Settings writes the downloaded M2 before [installStaged]. */
    fun stagingFile() = File(dir, "$ACTOR.staging")

    /** A fresh, empty staging file, or null while an install or AndyClaw's own download runs. */
    fun freshStaging(): File? {
        val state = _actorState.value
        if (state == ActorState.INSTALLING || state == ActorState.DOWNLOADING) return null
        dir.mkdirs()
        return stagingFile().also { it.delete(); it.createNewFile() }
    }

    /**
     * Installs the file Settings staged: its sha256 must be [PINNED]'s, or it is deleted and
     * nothing changes. Null on success, else an error code from the provider contract
     * (`no_staging`, `bad_hash`, `busy`, `io`). The staging file is always gone afterwards.
     */
    fun installStaged(): String? = synchronized(installLock) {
        while (true) {
            val current = _actorState.value
            if (current == ActorState.INSTALLING || current == ActorState.DOWNLOADING) return "busy"
            if (_actorState.compareAndSet(current, ActorState.INSTALLING)) break
        }
        val staged = stagingFile()
        var error: String? = null
        try {
            if (!staged.isFile || staged.length() == 0L) {
                error = "no_staging"
            } else {
                val sha = staged.inputStream().use { input -> sha256(input) }
                if (sha != PINNED.getValue(ACTOR)) {
                    error = "bad_hash"
                } else if (!staged.renameTo(actorFile())) {
                    error = "io"
                } else {
                    // The loaded model, if any, is the file just replaced: let it be rebuilt.
                    actor = null
                    olderActors().forEach { it.delete() }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "actor install failed: ${e.javaClass.simpleName}")
            error = "io"
        } finally {
            staged.delete()
            _actorState.value = if (actorFile().isFile) ActorState.READY else ActorState.ABSENT
        }
        Log.i(TAG, "actor install: ${error ?: "ok"}")
        error
    }

    /**
     * Removes M2 (Settings' "Remove download"). A turn already generating keeps its mapped copy
     * until it ends; the next one finds no model and leaves intents to the agent.
     */
    fun deleteActor(): Boolean = synchronized(installLock) {
        if (_actorState.value == ActorState.DOWNLOADING || _actorState.value == ActorState.INSTALLING) return false
        actor = null
        llamaCpp.unloadIfLoaded(actorFile().absolutePath)
        val ok = (!actorFile().exists() || actorFile().delete()) && olderActors().all { it.delete() }
        stagingFile().delete()
        _actorState.value = if (actorFile().isFile) ActorState.READY else ActorState.ABSENT
        ok
    }

    /** The version of M2 on the phone: [ACTOR_VERSION], an older one's, or 0. */
    fun installedActorVersion(): Int {
        if (actorFile().isFile) return ACTOR_VERSION
        return olderActors().mapNotNull { ACTOR_FILE.matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0
    }

    /** Bytes M2 takes on the phone (0 if absent). */
    fun actorBytesOnDisk(): Long = actorFile().takeIf { it.isFile }?.length() ?: olderActors().sumOf { it.length() }

    /** Usable space where M2 goes. */
    fun freeBytes(): Long = dir.also { it.mkdirs() }.usableSpace

    /** M2 files of another version, left from before an update. */
    private fun olderActors(): List<File> =
        dir.listFiles { f -> f.name != ACTOR && ACTOR_FILE.matches(f.name) }?.toList().orEmpty()

    private fun sha256(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Copies asset [name] to [dir] once, verified; later starts reuse the verified copy. */
    private fun extract(name: String): File {
        dir.mkdirs()
        val out = File(dir, name)
        val marker = File(dir, "$name.sha256")
        val want = PINNED.getValue(name)
        if (out.isFile && marker.isFile && marker.readText() == want) return out
        val tmp = File(dir, "$name.tmp")
        val sha = context.assets.open("reflex/$name").use { copyHashing(it, tmp) {} }
        if (sha != want) {
            tmp.delete()
            error("asset $name has sha256 $sha, expected $want")
        }
        check(tmp.renameTo(out)) { "rename $name failed" }
        marker.writeText(want)
        return out
    }

    private fun copyHashing(input: InputStream, to: File, progress: (Long) -> Unit): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 16)
        var done = 0L
        FileOutputStream(to).use { out ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                out.write(buf, 0, n)
                done += n
                progress(done)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Volume and app lookup for [ReflexResolver]. */
    private class AndroidDevice(private val context: Context) : ReflexResolver.Device {
        private val audio = context.getSystemService(AudioManager::class.java)

        private fun streamId(stream: String) = when (stream) {
            "music" -> AudioManager.STREAM_MUSIC
            "ring" -> AudioManager.STREAM_RING
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "alarm" -> AudioManager.STREAM_ALARM
            "voice_call" -> AudioManager.STREAM_VOICE_CALL
            "system" -> AudioManager.STREAM_SYSTEM
            else -> null
        }

        override fun streamMax(stream: String) = streamId(stream)?.let { audio.getStreamMaxVolume(it) }
        override fun streamVolume(stream: String) = streamId(stream)?.let { audio.getStreamVolume(it) }

        /** Exact label, then a unique prefix, then a unique substring. Two candidates is no answer. */
        override fun resolveApp(name: String): String? {
            val want = normalise(name).takeIf { it.isNotEmpty() } ?: return null
            val pm = context.packageManager
            val apps = pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                PackageManager.ResolveInfoFlags.of(0),
            ).map { normalise(it.loadLabel(pm).toString()) to it.activityInfo.packageName }
                .distinctBy { it.second }
            for (match in listOf<(String) -> Boolean>({ it == want }, { it.startsWith(want) }, { want in it })) {
                val hits = apps.filter { match(it.first) }.map { it.second }.distinct()
                if (hits.size == 1) return hits[0].takeUnless { SensitiveApps.isSensitive(it) || it == context.packageName }
                if (hits.size > 1) return null
            }
            return null
        }

        private fun normalise(s: String) = s.lowercase()
            .replace(Regex("\\b(the|app)\\b"), " ")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    companion object {
        private const val TAG = "ReflexRuntime"

        /** The model version every file name carries; `~/dgen1-llm/release/` built v1. */
        const val MODEL_ID = "reflex-v1"
        const val ENCODER = "reflex-encoder-v1-q8_0.gguf"
        const val HEADS = "reflex-heads-v1.bin"
        const val LABELS = "reflex-labels-v1.json"
        const val ACTOR = "reflex-actor-v1-q8_0.gguf"
        /** The version [ACTOR] carries; Settings shows it and compares it with what is installed. */
        const val ACTOR_VERSION = 1
        const val ACTOR_BYTES = 291_545_376L
        private val ACTOR_FILE = Regex("reflex-actor-v(\\d+)-q8_0\\.gguf")

        /**
         * Labels that may run on the device once instant actions are switched on. Not the ones
         * that cut the phone off — airplane mode on, mobile data off — where a wrong guess leaves
         * the user without the agent to undo it.
         */
        val INSTANT_LABELS: Set<String> =
            (ReflexSpec.ACTIONS.keys + ReflexSpec.INTENTS.keys) - setOf("airplane_on", "mobile_data_off")

        /**
         * Where M2 is fetched from, in order. Pinned by [PINNED]; a host is trusted for
         * availability only. Cloudflare R2 first (no egress charge); GCS only when R2 fails.
         */
        val ACTOR_URLS = listOf(
            "https://updates.freedomfactory.io/models/reflex/$ACTOR",
            "https://storage.googleapis.com/dgen-updates/models/reflex/$ACTOR",
        )

        val PINNED = mapOf(
            ENCODER to "beb8936a5fbcd63caddb76deb542bf4ec90613d59ad4e283deb66694ac4f67b7",
            HEADS to "e0ccb364ffd49d057dc5ecfec858c41fc99f51fd6c7be468d11ed85772ea1bb5",
            LABELS to "6880e739892957347694c52e7be62ffc816856e4f7ff9acf50585f00e017fb21",
            ACTOR to "a688fa85a4dd4c83b89130d9778da5a8c24acc292e47f7b50110fe29fc5efe1b",
        )
    }
}
