package org.ethereumphone.andyclaw.bench

import android.content.Context
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import org.ethereumphone.andyclaw.NodeApp
import org.ethereumphone.andyclaw.agent.AgentLoop
import org.ethereumphone.andyclaw.agent.TokenUsageSnapshot
import org.ethereumphone.andyclaw.autopilot.AutopilotEvent
import org.ethereumphone.andyclaw.autopilot.FlowsOff
import org.ethereumphone.andyclaw.llm.AnthropicModels
import org.ethereumphone.andyclaw.llm.LlmProvider
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.builtin.AgentDisplayBinder
import org.ethereumphone.andyclaw.skills.builtin.FlowSkill
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * AndyBench: the same prompts, run the same way the chat runs them, timed and checked against
 * the device's actual state — once with the autopilot off (the baseline) and once with it on.
 * These are the numbers to quote.
 *
 * Started from the Agent Display developer screen only. There is deliberately no adb or
 * broadcast entry point: a way to inject prompts from outside the app is a way to drive the
 * agent from outside the app.
 */
object AndyBench {

    private const val TAG = "AndyBench"
    private const val TASK_TIMEOUT_MS = 180_000L

    sealed interface Check {
        /** A Settings value equals [value] afterwards. */
        data class SettingEquals(val namespace: String, val key: String, val value: String) : Check
        /** The agent's reply mentions [text]. */
        data class ReplyContains(val text: String) : Check
        /** The agent display's top activity name contains [text]. */
        data class TopActivityContains(val text: String) : Check
        /** Nothing machine-checkable; the run counts as done if the agent finished without error. */
        data object Finished : Check
    }

    data class Task(
        val id: String,
        val prompt: String,
        val check: Check,
        /** Settings to put before each run so the task has something to do. */
        val setup: List<Check.SettingEquals> = emptyList(),
    )

    val TASKS = listOf(
        Task("dark_mode_on", "Open the Settings app on your virtual display and turn on dark theme.",
            Check.SettingEquals("secure", "ui_night_mode", "2"),
            setup = listOf(Check.SettingEquals("secure", "ui_night_mode", "1"))),
        Task("dark_mode_off", "Open the Settings app on your virtual display and turn off dark theme.",
            Check.SettingEquals("secure", "ui_night_mode", "1"),
            setup = listOf(Check.SettingEquals("secure", "ui_night_mode", "2"))),
        Task("screen_timeout", "In the Settings app on your virtual display, set the screen timeout to 5 minutes.",
            Check.SettingEquals("system", "screen_off_timeout", "300000"),
            setup = listOf(Check.SettingEquals("system", "screen_off_timeout", "30000"))),
        Task("battery_saver", "In the Settings app on your virtual display, turn on Battery Saver.",
            Check.SettingEquals("global", "low_power", "1"),
            setup = listOf(Check.SettingEquals("global", "low_power", "0"))),
        Task("wifi_page", "Open the Wi-Fi settings page in the Settings app on your virtual display.",
            Check.TopActivityContains("Settings")),
        Task("calculator", "Use the calculator app on your virtual display to compute 12 times 7 and tell me the result.",
            Check.ReplyContains("84")),
        // Nothing here sends, posts or buys: a benchmark must be safe to run on a real phone.
        Task("clock_timer", "Open the Clock app on your virtual display and start a 5 minute timer.",
            Check.Finished),
    )

    enum class Mode { BASELINE, AUTOPILOT }

    data class RunResult(
        val taskId: String,
        val mode: Mode,
        val ok: Boolean,
        val durationMs: Long,
        val modelCalls: Int,
        val autopilotSteps: Int?,
        val autopilotHelps: Int?,
        val error: String?,
    )

    /**
     * Runs every task [runs] times in [mode]. Settings the tasks touch are put back afterwards.
     * [progress] gets one line per run.
     */
    suspend fun run(app: NodeApp, mode: Mode, runs: Int, progress: (String) -> Unit): List<RunResult> {
        val prefs = app.securePrefs
        val autopilotBefore = prefs.autopilotEnabled.value
        val touched = TASKS.flatMap { it.setup + listOfNotNull(it.check as? Check.SettingEquals) }
            .distinctBy { it.namespace to it.key }
            .associateWith { read(app, it) }
        prefs.setAutopilotEnabled(mode == Mode.AUTOPILOT)
        val results = ArrayList<RunResult>()
        try {
            for (task in TASKS) {
                repeat(runs) { i ->
                    task.setup.forEach { write(app, it) }
                    val r = runOnce(app, task, mode)
                    results += r
                    val line = "${mode.name.lowercase()} ${task.id} #${i + 1}: ${if (r.ok) "ok" else "FAIL"} " +
                        "${r.durationMs}ms modelCalls=${r.modelCalls}" +
                        (r.autopilotSteps?.let { " apSteps=$it apHelps=${r.autopilotHelps}" } ?: "") +
                        (r.error?.let { " err=$it" } ?: "")
                    Log.i(TAG, line)
                    progress(line)
                    // Let the display park and the app settle between runs.
                    delay(1_500)
                }
            }
        } finally {
            prefs.setAutopilotEnabled(autopilotBefore)
            // A setting that had no value before goes back to having none, not to what the bench left.
            touched.forEach { (setting, old) -> if (old != null) write(app, setting.copy(value = old)) else clear(app, setting) }
        }
        save(app, mode, results)
        summarize(results).forEach { Log.i(TAG, it); progress(it) }
        return results
    }

    private suspend fun runOnce(app: NodeApp, task: Task, mode: Mode): RunResult {
        val prefs = app.securePrefs
        val modelId = prefs.selectedModel.value
        val provider = prefs.selectedProvider.value
        val model = AnthropicModels.fromModelId(modelId) ?: AnthropicModels.MINIMAX_M3
        val tier = org.ethereumphone.andyclaw.skills.tier.OsCapabilities.currentTier()
        // The user's compiled flows stay out of it: replayed, the bench measures replays and counts
        // its misses against them; compiled, its tasks land in the user's store.
        val enabled = prefs.enabledSkills.value - FlowSkill.SKILL_ID
        val loop = AgentLoop(
            client = app.getLlmClient(),
            skillRegistry = app.nativeSkillRegistry,
            tier = tier,
            enabledSkillIds = enabled,
            model = model,
            aiName = app.userStoryManager.getAiName(),
            userStory = app.userStoryManager.read(),
            soulContent = app.soulManager.read(),
            safetyLayer = app.createSafetyLayer(),
            toolSearchService = app.createToolSearchService(tier, enabled),
            budgetConfig = app.createBudgetConfig(),
            compactionConfig = prefs.compactionConfig.value,
            customModelIdOverride = if (provider == LlmProvider.CUSTOM && modelId.isNotBlank()) modelId else null,
            provenance = org.ethereumphone.andyclaw.ExecutionEngine.Provenance.USER,
            enforceProvenance = prefs.provenanceEnforcementEnabled.value,
            flowRecorder = null,
            flowRepository = null,
        )

        val done = CompletableDeferred<String?>()
        val text = StringBuilder()
        var apSteps: Int? = null
        var apHelps: Int? = null
        // What the agent display showed when the run finished. Read in onComplete, before the
        // run's end releases the display and parks it — read afterwards, it was never there.
        var topAtEnd: String? = null
        val callbacks = object : AgentLoop.Callbacks {
            override fun onToken(token: String) { text.append(token) }
            override fun onToolExecution(toolName: String) {}
            override fun onToolResult(toolName: String, result: SkillResult, input: JsonObject?) {}
            override suspend fun onApprovalNeeded(description: String, toolName: String?, toolInput: JsonObject?) = true
            override suspend fun onPermissionsNeeded(permissions: List<String>) = true
            override fun onAgentStep(event: AutopilotEvent) {
                if (event.kind == AutopilotEvent.Kind.DONE || event.kind == AutopilotEvent.Kind.FAILED) {
                    apSteps = (apSteps ?: 0) + event.step
                    apHelps = (apHelps ?: 0) + event.plannerCalls
                }
            }
            override fun onComplete(fullText: String, tokenUsage: TokenUsageSnapshot?) {
                if (task.check is Check.TopActivityContains) {
                    topAtEnd = try { AgentDisplayBinder.serviceOrNull()?.currentActivity } catch (e: Exception) { null }
                }
                done.complete(null)
            }
            override fun onError(error: Throwable) { done.complete(error.message ?: error.javaClass.simpleName) }
        }

        val started = System.currentTimeMillis()
        val error = withTimeoutOrNull(TASK_TIMEOUT_MS) {
            withContext(FlowsOff()) { loop.run(task.prompt, emptyList(), callbacks) }
            done.await()
        } ?: if (done.isCompleted) done.getCompleted() else "timeout"
        val duration = System.currentTimeMillis() - started
        val ok = error == null && verify(app, task.check, text.toString(), topAtEnd)
        return RunResult(task.id, mode, ok, duration, loop.lastRunModelCalls, apSteps, apHelps, error)
    }

    private fun verify(app: Context, check: Check, reply: String, topAtEnd: String?): Boolean = when (check) {
        is Check.SettingEquals -> read(app, check) == check.value
        is Check.ReplyContains -> reply.contains(check.text, ignoreCase = true)
        is Check.TopActivityContains -> topAtEnd?.contains(check.text, ignoreCase = true) == true
        Check.Finished -> true
    }

    private fun read(app: Context, s: Check.SettingEquals): String? {
        val cr = app.contentResolver
        return when (s.namespace) {
            "secure" -> Settings.Secure.getString(cr, s.key)
            "system" -> Settings.System.getString(cr, s.key)
            else -> Settings.Global.getString(cr, s.key)
        }
    }

    private fun write(app: Context, s: Check.SettingEquals) {
        val cr = app.contentResolver
        try {
            when (s.namespace) {
                "secure" -> Settings.Secure.putString(cr, s.key, s.value)
                "system" -> Settings.System.putString(cr, s.key, s.value)
                else -> Settings.Global.putString(cr, s.key, s.value)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot set ${s.namespace}/${s.key}: ${e.message}")
        }
    }

    /** Back to no value at all. A setting with a validator may refuse that; it then keeps the bench's. */
    private fun clear(app: Context, s: Check.SettingEquals) {
        val cr = app.contentResolver
        try {
            when (s.namespace) {
                "secure" -> Settings.Secure.putString(cr, s.key, null)
                "system" -> Settings.System.putString(cr, s.key, null)
                else -> Settings.Global.putString(cr, s.key, null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "cannot clear ${s.namespace}/${s.key}: ${e.message}")
        }
    }

    fun summarize(results: List<RunResult>): List<String> =
        results.groupBy { it.taskId to it.mode }.map { (key, rs) ->
            val times = rs.map { it.durationMs }.sorted()
            val p50 = times[times.size / 2]
            val p90 = times[((times.size - 1) * 9) / 10]
            val ok = rs.count { it.ok }
            val calls = rs.map { it.modelCalls }.average()
            "SUMMARY ${key.second.name.lowercase()} ${key.first}: p50=${p50}ms p90=${p90}ms " +
                "success=$ok/${rs.size} modelCalls=${"%.1f".format(calls)}"
        }

    private fun save(app: Context, mode: Mode, results: List<RunResult>) {
        try {
            val dir = File(app.filesDir, "andybench").apply { mkdirs() }
            val arr = JSONArray()
            results.forEach { r ->
                arr.put(JSONObject()
                    .put("task", r.taskId).put("mode", r.mode.name).put("ok", r.ok)
                    .put("ms", r.durationMs).put("modelCalls", r.modelCalls)
                    .put("apSteps", r.autopilotSteps ?: JSONObject.NULL)
                    .put("apHelps", r.autopilotHelps ?: JSONObject.NULL)
                    .put("error", r.error ?: JSONObject.NULL))
            }
            File(dir, "${mode.name.lowercase()}-${System.currentTimeMillis()}.json").writeText(arr.toString(2))
        } catch (e: Exception) {
            Log.w(TAG, "could not save results: ${e.message}")
        }
    }
}
