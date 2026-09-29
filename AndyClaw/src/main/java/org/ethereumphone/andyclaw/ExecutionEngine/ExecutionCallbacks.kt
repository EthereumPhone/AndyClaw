package org.ethereumphone.andyclaw.ExecutionEngine

import kotlinx.serialization.json.JsonObject

/**
 * Callbacks for the execution engine to communicate with the UI/host.
 * These mirror AgentLoop.Callbacks but are scoped to execution only.
 */
interface ExecutionCallbacks {
    /** Called when a tool starts executing. */
    fun onToolStarted(toolName: String)

    /** Called when a tool completes (success or error). */
    fun onToolCompleted(toolName: String, result: ToolCallResult)

    /** Called when a tool is blocked by a pre-flight check. */
    fun onToolBlocked(toolName: String, reason: String)

    /** User must approve before tool can execute. Returns true if approved. */
    suspend fun onApprovalNeeded(
        description: String,
        toolName: String? = null,
        toolInput: JsonObject? = null,
    ): Boolean

    /**
     * What the model is told about the call [onApprovalNeeded] just said no to, or null for
     * [ParallelExecutionEngine.NOT_APPROVED]. Asked once, straight after that answer, for the same
     * call — so a host that queued the call for the user to approve later can say that, rather
     * than a bare "not approved" that reads like the user's refusal.
     */
    fun notApprovedMessage(toolName: String?, toolInput: JsonObject?): String? = null

    /** User must grant Android permissions. Returns true if granted. */
    suspend fun onPermissionsNeeded(permissions: List<String>): Boolean

    /**
     * The last word before [toolName] starts — after every pre-flight check and any approval,
     * which can outlast a STOP. A reason to refuse it, or null to let it run.
     */
    fun vetoStart(toolName: String): String? = null

    /**
     * [toolName] was running when its run was cancelled. It may have finished underneath — a
     * send completes under NonCancellable — and its result is gone, so this is the only chance
     * to say it happened. Called from the cancelled coroutine: must not suspend.
     */
    fun onToolInterrupted(toolName: String) {}
}
