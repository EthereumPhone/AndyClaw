package org.ethereumphone.andyclaw.bench

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log
import org.ethereumphone.andyclaw.NodeApp
import org.ethereumphone.andyclaw.llm.AnthropicModels
import org.ethereumphone.andyclaw.llm.LlmProvider
import org.ethereumphone.andyclaw.skills.tier.OsCapabilities
import org.json.JSONObject

/**
 * `agentbench/`'s way to finish onboarding without the UI: what `OnboardingViewModel.submit`
 * writes, from a JSON argument, so an emulator can be provisioned by a script.
 *
 * Debug source set only — it does not exist in the release APK that ships in the ethOS tree —
 * and even here only adb's shell or root may call it. It sets configuration; it never runs a
 * prompt. Prompts go through `LauncherBindingService`, the launcher's own contract.
 *
 * ```
 * adb shell content call --uri content://org.ethereumphone.andyclaw.agentbench.seed \
 *     --method seed --arg '{"provider":"OPEN_ROUTER","apiKey":"sk-or-…"}'
 * adb shell content call --uri content://org.ethereumphone.andyclaw.agentbench.seed --method state
 * ```
 */
class AgentBenchSeedProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val uid = Binder.getCallingUid()
        if (uid != Process.SHELL_UID && uid != Process.ROOT_UID) {
            throw SecurityException("agentbench seed is for adb only (uid=$uid)")
        }
        val app = context!!.applicationContext as NodeApp
        val out = Bundle()
        when (method) {
            "seed" -> {
                seed(app, JSONObject(arg ?: "{}"))
                out.putString("result", state(app).toString())
            }
            "state" -> out.putString("result", state(app).toString())
            // The tool catalog exactly as ToolSearchService indexes it, so the harness can replay
            // discovery offline over every task prompt without a model call.
            "catalog" -> {
                val search = app.createToolSearchService(OsCapabilities.currentTier(), app.securePrefs.enabledSkills.value)
                val arr = org.json.JSONArray()
                search?.catalogSnapshot().orEmpty().forEach { e ->
                    arr.put(JSONObject().put("tool", e.toolName).put("skill", e.skillId)
                        .put("description", e.description).put("hint", e.searchHint ?: JSONObject.NULL))
                }
                out.putString("result", arr.toString())
            }
            else -> throw IllegalArgumentException("unknown method $method")
        }
        return out
    }

    private fun seed(app: NodeApp, cfg: JSONObject) {
        val prefs = app.securePrefs
        cfg.optString("walletAddress").takeIf { it.isNotBlank() }?.let { address ->
            prefs.setWalletAuth(address, cfg.optString("walletSignature"))
        }
        cfg.optString("provider").takeIf { it.isNotBlank() }?.let { name ->
            val provider = LlmProvider.valueOf(name)
            prefs.setSelectedProvider(provider)
            prefs.setRoutingProvider(provider)
            AnthropicModels.routingModelForProvider(provider)?.let { prefs.setRoutingModel(it.modelId) }
            if (!cfg.has("model")) prefs.setSelectedModel(AnthropicModels.defaultForProvider(provider).modelId)
        }
        cfg.optString("model").takeIf { it.isNotBlank() }?.let { prefs.setSelectedModel(it) }
        cfg.optString("apiKey").takeIf { it.isNotBlank() }?.let { prefs.setApiKey(it) }
        if (cfg.has("autopilot")) prefs.setAutopilotEnabled(cfg.getBoolean("autopilot"))
        if (cfg.has("smartRouting")) prefs.setSmartRoutingEnabled(cfg.getBoolean("smartRouting"))
        if (cfg.has("yolo")) prefs.setYoloMode(cfg.getBoolean("yolo"))

        val aiName = cfg.optString("aiName", "AndyClaw")
        if (!app.userStoryManager.exists() || cfg.has("story")) {
            app.userStoryManager.write(cfg.optString("story",
                "# Name: $aiName\n\n## Story\nYou are being evaluated by an automated harness on an emulator."))
            prefs.setAiName(aiName)
        }
        // Onboarding's default for a user who ticks everything; a device that picked fewer
        // skills is a narrower agent, so this is the ceiling being measured.
        if (cfg.optBoolean("allSkills", prefs.enabledSkills.value.isEmpty())) {
            prefs.setAllSkillsEnabled(app.nativeSkillRegistry.getAll().map { it.id }.toSet())
        }
        // No OS heartbeat on an emulator, and a background run would share the display lease.
        prefs.setHeartbeatIntervalMinutes(-1)
        Log.i(TAG, "seeded: ${state(app)}")
    }

    private fun state(app: NodeApp): JSONObject {
        val prefs = app.securePrefs
        return JSONObject()
            .put("tier", OsCapabilities.currentTier().name)
            .put("provider", prefs.selectedProvider.value.name)
            .put("model", prefs.selectedModel.value)
            .put("walletAuth", prefs.walletSignature.value.startsWith("0x"))
            .put("apiKey", prefs.apiKey.value.isNotBlank())
            .put("story", app.userStoryManager.exists())
            .put("enabledSkills", prefs.enabledSkills.value.size)
            .put("registeredSkills", app.nativeSkillRegistry.getAll().size)
            .put("autopilot", prefs.autopilotEnabled.value)
            .put("smartRouting", prefs.smartRoutingEnabled.value)
            .put("yolo", prefs.yoloMode.value)
    }

    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int = 0

    private companion object {
        const val TAG = "AgentBenchSeed"
    }
}
