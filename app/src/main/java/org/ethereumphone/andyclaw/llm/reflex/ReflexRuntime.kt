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
 * - M2 (292 MB) is downloaded on demand ([downloadActor]) and pinned the same way. Without it
 *   the M1-only actions still work and intents go to the agent.
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

    private val _actorState = MutableStateFlow(if (actorFile().isFile) ActorState.READY else ActorState.ABSENT)
    /** Whether M2 is on the phone, for the settings row. */
    val actorState: StateFlow<ActorState> = _actorState.asStateFlow()
    private val _actorProgress = MutableStateFlow(0f)
    val actorProgress: StateFlow<Float> = _actorProgress.asStateFlow()

    enum class ActorState { ABSENT, DOWNLOADING, READY, FAILED }

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

    @Volatile
    private var lastFetchFailureMs = 0L

    /**
     * Starts M2's download when nobody would mind: the phone is on an unmetered network, not in
     * Battery Saver, with room to spare, and the last attempt did not fail in the past
     * [FETCH_RETRY_MS]. Home-screen users never open AndyClaw's settings, so this is how M2
     * arrives. Cheap to call on every turn.
     */
    fun maybeFetchActor() {
        if (_actorState.value == ActorState.READY || _actorState.value == ActorState.DOWNLOADING) return
        if (System.currentTimeMillis() - lastFetchFailureMs < FETCH_RETRY_MS) return
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return
        if (!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
            !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        ) return
        if (context.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true) return
        if (context.filesDir.usableSpace < ACTOR_BYTES + MIN_FREE_BYTES) return
        scope.launch { downloadActor() }
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
     * Downloads M2 from [ACTOR_URL] to a temp file, checks its sha256 against [PINNED], and only
     * then moves it into place. A wrong hash is deleted, never loaded.
     */
    suspend fun downloadActor(): Boolean = withContext(Dispatchers.IO) {
        if (actorFile().isFile) return@withContext true
        if (_actorState.value == ActorState.DOWNLOADING) return@withContext false
        _actorState.value = ActorState.DOWNLOADING
        _actorProgress.value = 0f
        dir.mkdirs()
        val tmp = File(dir, "$ACTOR.tmp")
        try {
            val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
            http.newCall(Request.Builder().url(ACTOR_URL).build()).execute().use { resp ->
                check(resp.isSuccessful) { "HTTP ${resp.code}" }
                val body = checkNotNull(resp.body)
                val total = body.contentLength().takeIf { it > 0 } ?: ACTOR_BYTES
                val sha = copyHashing(body.byteStream(), tmp) { done -> _actorProgress.value = (done.toFloat() / total).coerceIn(0f, 1f) }
                check(sha == PINNED.getValue(ACTOR)) { "sha256 mismatch" }
            }
            check(tmp.renameTo(actorFile())) { "rename failed" }
            _actorState.value = ActorState.READY
            true
        } catch (e: Exception) {
            org.ethereumphone.andyclaw.ExecutionEngine.rethrowIfCancelled(e)
            Log.w(TAG, "actor download failed: ${e.message}")
            tmp.delete()
            _actorState.value = ActorState.FAILED
            lastFetchFailureMs = System.currentTimeMillis()
            false
        }
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
        private const val ACTOR_BYTES = 291_545_376L

        private const val FETCH_RETRY_MS = 6 * 3_600_000L
        private const val MIN_FREE_BYTES = 1_000_000_000L

        /**
         * Labels that may run on the device once instant actions are switched on. Not the ones
         * that cut the phone off — airplane mode on, mobile data off — where a wrong guess leaves
         * the user without the agent to undo it.
         */
        val INSTANT_LABELS: Set<String> =
            (ReflexSpec.ACTIONS.keys + ReflexSpec.INTENTS.keys) - setOf("airplane_on", "mobile_data_off")

        /** Where M2 is fetched from. Pinned by [PINNED]; the host is trusted for availability only. */
        const val ACTOR_URL = "https://storage.googleapis.com/dgen-updates/models/reflex/$ACTOR"

        val PINNED = mapOf(
            ENCODER to "beb8936a5fbcd63caddb76deb542bf4ec90613d59ad4e283deb66694ac4f67b7",
            HEADS to "e0ccb364ffd49d057dc5ecfec858c41fc99f51fd6c7be468d11ed85772ea1bb5",
            LABELS to "6880e739892957347694c52e7be62ffc816856e4f7ff9acf50585f00e017fb21",
            ACTOR to "a688fa85a4dd4c83b89130d9778da5a8c24acc292e47f7b50110fe29fc5efe1b",
        )
    }
}
