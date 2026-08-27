package org.ethereumphone.andyclaw.flows

import android.content.Context
import android.util.Log
import org.ethereumphone.andyclaw.skills.NativeSkillRegistry
import org.ethereumphone.andyclaw.skills.builtin.FlowSkill
import java.io.File

/**
 * Rung 3's registry: what flows exist, which of them are trustworthy right now, and how
 * the rest of the app finds out.
 *
 * Three jobs.
 *
 * **Publishing.** Every valid flow becomes a named tool on [FlowSkill], and registering
 * that skill bumps `NativeSkillRegistry.version`, which is what makes `ToolSearchService`
 * rebuild its BM25 index. That is the whole resolution mechanism for the common case:
 * the model searching for "send a signal message" finds `signal_send_to_existing_thread`
 * and picks it over `agent_display_create` because it is specific — no separate resolver
 * needed.
 *
 * **Staleness.** `agent-os-design.md` §3: version-pin to the app, mark flows stale on
 * upgrade, revalidate lazily on next use. A stale flow keeps its tool — that is how it
 * gets the chance to revalidate — but drops its `targetPackages`, so it stops standing
 * in front of the display. A route nobody is sure about must not block the fallback.
 *
 * **Retirement.** A flow that aborts repeatedly is not a flow any more; after
 * [MAX_CONSECUTIVE_ABORTS] it is removed, and discovery is free to compile a new one.
 */
class FlowRepository(
    private val appContext: Context,
    private val registry: NativeSkillRegistry,
    signer: FlowSigner = KeystoreFlowSigner(),
    root: File = File(appContext.filesDir, FlowStore.DIR_NAME),
) {

    val store: FlowStore = FlowStore(root, signer)

    /** The skill the flows are published through. Registered once, rebuilt on change. */
    val skill: FlowSkill = FlowSkill(this, AgentDisplayFlowDriver(appContext))

    @Volatile
    private var published: List<StoredFlow> = emptyList()

    /** Every currently installed, verified flow. */
    fun flows(): List<StoredFlow> = published

    fun byToolName(toolName: String): StoredFlow? = published.firstOrNull { it.flow.toolName == toolName }

    /** Re-read the store and republish. Cheap enough to call on any change. */
    @Synchronized
    fun reload() {
        published = try {
            // Two flow ids can normalise to the same tool name (`a.b_c` and `a_b.c`).
            // Publishing both would give the registry two tools with one name and make
            // which one runs an accident of ordering.
            store.listAll().distinctBy { it.flow.toolName }
        } catch (e: Exception) {
            Log.w(TAG, "reload failed: ${e.message}")
            emptyList()
        }
        // register() removes the previous instance by id and bumps the version counter,
        // so this is how the tool list and the search index learn about the change.
        registry.register(skill)
        Log.i(TAG, "published ${published.size} flow(s): " +
            published.joinToString { "${it.flow.flow}@${it.flow.version}${if (it.meta.stale) " (stale)" else ""}" })
    }

    /** Install a freshly compiled flow. Validation happens inside the store. */
    @Synchronized
    fun install(flow: Flow): FlowInstallResult {
        val result = store.install(flow)
        if (result is FlowInstallResult.Installed) reload()
        return result
    }

    /** An app was replaced — every flow compiled against it is now suspect. */
    @Synchronized
    fun onPackageReplaced(packageName: String) {
        val marked = store.markStaleForPackage(packageName)
        if (marked > 0) {
            Log.i(TAG, "$packageName replaced: marked $marked flow(s) stale")
            reload()
        }
    }

    /** A replay finished. Success clears staleness; repeated failure retires the flow. */
    @Synchronized
    fun recordRun(hash: String, result: FlowRunResult, appVersion: String?) {
        val aborted = result !is FlowRunResult.Completed
        store.recordUse(hash, aborted = aborted, appVersion = appVersion)
        if (aborted) {
            store.setStale(hash, true)
            val meta = store.get(hash)?.meta
            if (meta != null && meta.aborts >= MAX_CONSECUTIVE_ABORTS) {
                Log.w(TAG, "retiring flow $hash after ${meta.aborts} aborts — discovery can recompile it")
                store.remove(hash)
            }
        }
        reload()
    }

    // ── Version pinning ───────────────────────────────────────────────

    /** `versionName` of an installed package, or null. */
    fun installedVersion(packageName: String): String? = try {
        appContext.packageManager.getPackageInfo(packageName, 0).versionName
    } catch (e: Exception) {
        null
    }

    /**
     * The range a flow compiled right now should be pinned to.
     *
     * "At least the version I was compiled against, below the next major." Pinning to
     * the exact build would retire every flow on a patch update; leaving it open would
     * replay into a redesigned UI. Within the range, the per-step checksums are what
     * actually catch drift — the range only decides when to stop trying.
     */
    fun suggestedRangeFor(packageName: String): String? {
        val version = installedVersion(packageName)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val major = version.takeWhile { it.isDigit() }.toIntOrNull() ?: return "=$version"
        return ">=$version,<${major + 1}"
    }

    companion object {
        private const val TAG = "FlowRepository"
        const val MAX_CONSECUTIVE_ABORTS = 3
    }
}
