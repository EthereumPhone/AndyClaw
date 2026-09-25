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
    autopilot: () -> org.ethereumphone.andyclaw.autopilot.AutopilotToolHandler? = { null },
    noConfirm: () -> Boolean = { false },
) {

    val store: FlowStore = FlowStore(root, signer)

    /** The skill the flows are published through. Registered once, rebuilt on change. */
    val skill: FlowSkill = FlowSkill(this, AgentDisplayFlowDriver(appContext), autopilot, noConfirm)

    @Volatile
    private var published: List<StoredFlow> = emptyList()

    /** Every currently installed, verified flow. */
    fun flows(): List<StoredFlow> = published

    fun byToolName(toolName: String): StoredFlow? = published.firstOrNull { it.flow.toolName == toolName }

    /** Re-read the store and republish. Only on a change to what is published. */
    @Synchronized
    fun reload() {
        published = try {
            // Two flow ids can normalise to the same tool name (`a.b_c` and `a_b.c`), and a
            // crashed install can leave two versions of one id. Publishing both would give the
            // registry two tools with one name; the newest version wins.
            store.listAll().sortedByDescending { it.flow.version }.distinctBy { it.flow.toolName }
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

    /**
     * Install a flow the model compiled from a recorded session — only if it is that session:
     * the same taps and types on the same nodes, and conditions the recorded screens showed.
     * See [FlowCompileConformance].
     */
    @Synchronized
    fun installDiscovered(draft: FlowDraft, compiled: Flow, firstTree: String?, finalTree: String?): FlowInstallResult {
        val problems = FlowCompileConformance.check(draft, compiled, firstTree, finalTree)
        if (problems.isNotEmpty()) {
            return FlowInstallResult.Rejected(problems.map { FlowValidationError("nonconforming", it) })
        }
        return install(compiled)
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

    /** An app was uninstalled for good: its flows can never run again, so they go. */
    @Synchronized
    fun onPackageRemoved(packageName: String) {
        val gone = store.listAll().filter { it.flow.app == packageName }
        if (gone.isEmpty()) return
        gone.forEach { store.remove(it.hash) }
        Log.i(TAG, "$packageName removed: retired ${gone.size} flow(s)")
        reload()
    }

    /**
     * A replay finished. Success clears staleness; repeated failure retires the flow.
     *
     * Only outcomes that say something about the flow are written down ([FlowRunAccounting]):
     * a STOP, a missing value or a refused checkpoint used to count toward retirement and mark
     * a good flow stale. And the tool list is only republished when what it shows changed —
     * staleness or retirement — not after every replay, which rescanned the directory and
     * rebuilt the tool search index on the warm path this rung exists to make fast.
     */
    @Synchronized
    fun recordRun(hash: String, result: FlowRunResult, appVersion: String?) {
        if (!FlowRunAccounting.counts(result)) return
        val wasStale = published.firstOrNull { it.hash == hash }?.meta?.stale
        val aborted = result !is FlowRunResult.Completed
        store.recordUse(hash, aborted = aborted, appVersion = appVersion)
        if (aborted) store.setStale(hash, true)
        val meta = store.meta(hash)
        if (aborted && meta != null && meta.aborts >= MAX_CONSECUTIVE_ABORTS) {
            Log.w(TAG, "retiring flow $hash after ${meta.aborts} aborts — discovery can recompile it")
            store.remove(hash)
            reload()
            return
        }
        if (meta?.stale != wasStale) reload()
    }

    // ── Version pinning ───────────────────────────────────────────────

    /** `versionName` of an installed package, or null. */
    fun installedVersion(packageName: String): String? = try {
        appContext.packageManager.getPackageInfo(packageName, 0).versionName
    } catch (e: Exception) {
        null
    }

    /** The range a flow compiled right now should be pinned to; see [AppVersionRange.suggestedFor]. */
    fun suggestedRangeFor(packageName: String): String? =
        AppVersionRange.suggestedFor(installedVersion(packageName))

    companion object {
        private const val TAG = "FlowRepository"
        const val MAX_CONSECUTIVE_ABORTS = 3
    }
}
