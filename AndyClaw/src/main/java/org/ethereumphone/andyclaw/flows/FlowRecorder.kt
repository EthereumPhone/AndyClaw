package org.ethereumphone.andyclaw.flows

/** One display action as it happened, with the screen shape either side of it. */
data class RecordedAction(
    val tool: String,
    val viewId: String? = null,
    val text: String? = null,
    val x: Double? = null,
    val y: Double? = null,
    /** Package the action was aimed at, when the action itself names one. */
    val packageName: String? = null,
    val checksumBefore: String = NodeTreeChecksum.UNKNOWN,
    val checksumAfter: String = NodeTreeChecksum.UNKNOWN,
    val ok: Boolean = true,
    val timestampMs: Long = 0L,
)

/**
 * A recording, turned into the best IR that can be made mechanically.
 *
 * It is deliberately **not** a valid flow: it has no postconditions and no checkpoints,
 * because neither can be derived from watching taps. Those are what the compiling model
 * adds in [org.ethereumphone.andyclaw.flows] task 2.7, and [FlowValidator] is what
 * refuses the draft until it has them. A recording is evidence; a flow is a claim.
 */
data class FlowDraft(
    val flow: Flow,
    val actions: List<RecordedAction>,
    /** Actions with no opcode in the IR — a swipe, a back press, a scroll. */
    val unsupportedActions: List<String>,
) {
    val isMechanicallyComplete: Boolean get() = unsupportedActions.isEmpty()
}

/**
 * Watches a display session so a successful one can become a flow.
 *
 * `agent-os-design.md` §3: "discovery produces artifacts, artifacts serve traffic." The
 * recorder is the first half of that — it wraps the existing display tools rather than
 * reimplementing them, so there is exactly one code path that drives the device and the
 * recording cannot drift from what actually ran.
 *
 * What it records, per action, is the plan's three things: the a11y node that was
 * targeted (`viewIdResourceName`, never a coordinate), the action, and the perceptual
 * checksum of the resulting node tree. Coordinate taps are recorded **as coordinates**
 * on purpose — the recording stays honest, and [FlowValidator] is where the resulting
 * draft is refused.
 */
class FlowRecorder(private val clock: () -> Long = System::currentTimeMillis) {

    private val actions = mutableListOf<RecordedAction>()

    @Volatile
    var isRecording: Boolean = false
        private set

    /**
     * Bumped on every [start]. A caller that wants to compile "the session that happened
     * during my task" compares this against the value it saw when it began — the
     * recorder is process-wide, and a session it did not cause is not its to compile.
     */
    @Volatile
    var sessionId: Long = 0L
        private set

    /** The package the session is driving, learned from the first launch action. */
    @Volatile
    var packageName: String? = null
        private set

    val recorded: List<RecordedAction> get() = synchronized(actions) { actions.toList() }

    fun start() {
        synchronized(actions) { actions.clear() }
        packageName = null
        sessionId++
        isRecording = true
    }

    fun stop() {
        isRecording = false
    }

    fun record(action: RecordedAction) {
        if (!isRecording) return
        synchronized(actions) {
            if (actions.size >= MAX_ACTIONS) return
            actions += action.copy(timestampMs = if (action.timestampMs == 0L) clock() else action.timestampMs)
        }
        action.packageName?.let { if (packageName == null) packageName = it }
    }

    /** Convenience for the decorator: build and record in one call. */
    fun record(
        tool: String,
        viewId: String?,
        text: String?,
        x: Double?,
        y: Double?,
        packageName: String?,
        treeBefore: String?,
        treeAfter: String?,
        ok: Boolean,
    ) = record(
        RecordedAction(
            tool = tool,
            viewId = viewId,
            text = text,
            x = x,
            y = y,
            packageName = packageName,
            checksumBefore = NodeTreeChecksum.of(treeBefore),
            checksumAfter = NodeTreeChecksum.of(treeAfter),
            ok = ok,
        ),
    )

    /**
     * The mechanical half of compilation: recorded actions to IR steps.
     *
     * Only actions with an opcode survive. Everything else — a swipe, a back press, a
     * scroll — is listed in [FlowDraft.unsupportedActions] so the caller can see that
     * replaying these steps alone would not reproduce the session.
     */
    fun draft(flowId: String, appVersionRange: String, app: String? = null): FlowDraft? {
        val recordedActions = recorded.filter { it.ok }
        if (recordedActions.isEmpty()) return null
        val pkg = app ?: packageName ?: return null

        val steps = mutableListOf<FlowStep>()
        val unsupported = mutableListOf<String>()

        for (action in recordedActions) {
            when (action.tool) {
                // Getting the app on screen is the interpreter's job, not a step.
                in LAUNCH_TOOLS -> Unit
                in PASSIVE_TOOLS -> Unit

                "agent_display_click_node", "agent_display_long_click_node" ->
                    steps += TapStep(
                        viewId = action.viewId,
                        expectChecksum = action.checksumBefore.takeIf { it.isNotEmpty() },
                    )

                "agent_display_set_node_text" ->
                    steps += TypeStep(
                        target = Selector(viewId = action.viewId),
                        value = action.text.orEmpty(),
                        expectChecksum = action.checksumBefore.takeIf { it.isNotEmpty() },
                    )

                // Recorded faithfully so the validator can refuse it, rather than
                // quietly dropped so a flow looks replayable when it is not.
                "agent_display_tap", "agent_display_long_press", "agent_display_double_tap" -> {
                    steps += TapStep(
                        x = action.x,
                        y = action.y,
                        expectChecksum = action.checksumBefore.takeIf { it.isNotEmpty() },
                    )
                    unsupported += "${action.tool} (coordinates)"
                }

                else -> unsupported += action.tool
            }
        }

        val firstTarget = steps.firstNotNullOfOrNull {
            when (it) {
                is TapStep -> it.viewId
                is TypeStep -> it.target.viewId
                else -> null
            }
        }

        val flow = Flow(
            flow = flowId,
            version = 1,
            app = pkg,
            appVersionRange = appVersionRange,
            params = emptyList(),
            preconditions = firstTarget?.let { listOf(NodeExists(viewId = it)) } ?: emptyList(),
            steps = steps,
            // Left empty on purpose: nothing in a recording says what success looked
            // like. The compiler supplies it, and the validator insists on it.
            postconditions = emptyList(),
        )

        return FlowDraft(flow = flow, actions = recordedActions, unsupportedActions = unsupported)
    }

    /** The recording rendered for a model that is being asked to compile it. */
    fun describeForCompiler(): String = buildString {
        appendLine("Recorded display session (${recorded.size} actions):")
        recorded.forEachIndexed { i, a ->
            append("  ").append(i).append(". ").append(a.tool)
            a.viewId?.let { append(" view_id=").append(it) }
            a.text?.let { append(" text=\"").append(it.take(60)).append('"') }
            if (a.x != null || a.y != null) append(" coordinates=(").append(a.x).append(',').append(a.y).append(')')
            append(" screen_before=").append(a.checksumBefore.ifEmpty { "?" })
            append(" screen_after=").append(a.checksumAfter.ifEmpty { "?" })
            if (!a.ok) append(" FAILED")
            appendLine()
        }
    }

    companion object {
        /** A session longer than this is not a flow, it is a wander. */
        const val MAX_ACTIONS = 128

        private val LAUNCH_TOOLS = setOf(
            "agent_display_create",
            "agent_display_launch_activity",
            "agent_display_launch_intent",
        )

        /** Reads. They change nothing, so they are not steps. */
        private val PASSIVE_TOOLS = setOf(
            "agent_display_look",
            "agent_display_get_ui_tree",
            "agent_display_screenshot",
            "agent_display_get_node_info",
            "agent_display_current_activity",
            "agent_display_get_info",
            "agent_display_get_clipboard",
            "agent_display_destroy",
            "agent_display_destroy_and_promote",
        )
    }
}
