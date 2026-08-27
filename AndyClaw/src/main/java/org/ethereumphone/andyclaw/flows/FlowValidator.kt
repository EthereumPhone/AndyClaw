package org.ethereumphone.andyclaw.flows

import org.ethereumphone.andyclaw.skills.ToolEffect

/** One reason a flow may not compile. [stepIndex] is null for whole-flow problems. */
data class FlowValidationError(
    val code: String,
    val message: String,
    val stepIndex: Int? = null,
) {
    override fun toString(): String =
        if (stepIndex == null) "[$code] $message" else "[$code] step $stepIndex: $message"
}

sealed interface FlowValidation {

    val errors: List<FlowValidationError>

    val isValid: Boolean get() = errors.isEmpty()

    data object Valid : FlowValidation {
        override val errors: List<FlowValidationError> = emptyList()
    }

    data class Invalid(override val errors: List<FlowValidationError>) : FlowValidation
}

/**
 * What may become a flow.
 *
 * A flow is replayed with no model watching it, against a live app, carrying the user's
 * authority. The whole safety argument for doing that rests on refusing to compile
 * anything that cannot be replayed *safely*, so this is a gate and not a linter:
 * [FlowStore] will not install a flow that does not pass, and `FlowSkill` will not
 * register a tool for one.
 *
 * The three non-negotiables are `agent-os-design.md` §3's own rules:
 *
 * 1. **Selectors are `view_id` first — never text, never coordinates.** Text breaks on a
 *    locale change; coordinates break on everything, including a rotation the recorder
 *    never saw. The IR can *express* both, so a recorder can record what really
 *    happened; this is where the recording is refused rather than replayed.
 * 2. **Every flow carries pre- and postconditions.** A flow that cannot assert it worked
 *    does not run.
 * 3. **No `checkpoint:` before an irreversible step, no compile.** §6: the checkpoint
 *    opcode is how a flow declares where its irreversible boundary sits.
 *
 * And one refusal that is not a rule but a hard stop: a step that touches payment or
 * authentication ([FlowStepEffects.SENSITIVE_TOKENS]) makes the whole flow
 * uncompilable. §6 — "never automate an auth or payment confirmation step. Not 'ask
 * first' — never."
 */
object FlowValidator {

    /** A replay has to stay bounded; a flow this long is a discovery session, not a flow. */
    const val MAX_STEPS = 64
    const val MAX_TIMEOUT_MS = 30_000L
    private val PLACEHOLDER = Regex("\\{\\{\\s*([A-Za-z0-9_]+)\\s*}}")
    private val FLOW_ID = Regex("^[a-z0-9]+(?:[._-][a-z0-9]+)*$")

    fun validate(flow: Flow): FlowValidation {
        val errors = mutableListOf<FlowValidationError>()

        validateHeader(flow, errors)
        validateConditions(flow, errors)
        validateSteps(flow, errors)
        validateCheckpoints(flow, errors)

        return if (errors.isEmpty()) FlowValidation.Valid else FlowValidation.Invalid(errors)
    }

    // ── Header ────────────────────────────────────────────────────────

    private fun validateHeader(flow: Flow, errors: MutableList<FlowValidationError>) {
        if (flow.flow.isBlank() || !FLOW_ID.matches(flow.flow)) {
            errors += FlowValidationError(
                "bad_flow_id",
                "flow id must be lowercase dotted, e.g. 'signal.send_to_existing_thread' (was '${flow.flow}')",
            )
        }
        if (flow.version < 1) {
            errors += FlowValidationError("bad_version", "version must be >= 1 (was ${flow.version})")
        }
        if (flow.app.isBlank() || !flow.app.contains('.')) {
            errors += FlowValidationError(
                "bad_app",
                "app must be an Android package name (was '${flow.app}')",
            )
        }
        if (flow.appVersionRange.isBlank()) {
            errors += FlowValidationError(
                "missing_version_range",
                "app_version_range is required — a flow is pinned to the UI it was compiled against",
            )
        } else if (!AppVersionRange.isParseable(flow.appVersionRange)) {
            errors += FlowValidationError(
                "bad_version_range",
                "app_version_range '${flow.appVersionRange}' is not a range this build understands",
            )
        }
        if (flow.steps.isEmpty()) {
            errors += FlowValidationError("no_steps", "a flow with no steps does nothing")
        }
        if (flow.steps.size > MAX_STEPS) {
            errors += FlowValidationError(
                "too_many_steps",
                "${flow.steps.size} steps exceeds the $MAX_STEPS-step ceiling",
            )
        }
        val duplicateParams = flow.params.groupBy { it }.filterValues { it.size > 1 }.keys
        if (duplicateParams.isNotEmpty()) {
            errors += FlowValidationError(
                "duplicate_params",
                "params repeat: ${duplicateParams.joinToString()}",
            )
        }
    }

    // ── Conditions ────────────────────────────────────────────────────

    private fun validateConditions(flow: Flow, errors: MutableList<FlowValidationError>) {
        if (flow.preconditions.isEmpty()) {
            errors += FlowValidationError(
                "no_preconditions",
                "every flow carries preconditions — without one it cannot tell it is on the right screen",
            )
        }
        if (flow.postconditions.isEmpty()) {
            errors += FlowValidationError(
                "no_postconditions",
                "every flow carries postconditions — a flow that cannot assert it worked does not run",
            )
        }
        for (condition in flow.preconditions + flow.postconditions) {
            if (condition.viewId.isNullOrBlank()) {
                errors += FlowValidationError(
                    "condition_without_view_id",
                    "condition '${condition.opcode}' must name a view_id",
                )
            }
        }
    }

    // ── Steps ─────────────────────────────────────────────────────────

    private fun validateSteps(flow: Flow, errors: MutableList<FlowValidationError>) {
        val declared = flow.params.toSet()
        flow.steps.forEachIndexed { index, step ->
            when (step) {
                is TapStep -> {
                    validateSelector(
                        index = index,
                        viewId = step.viewId,
                        text = step.text,
                        x = step.x,
                        y = step.y,
                        errors = errors,
                    )
                    if ((step.index ?: 0) < 0) {
                        errors += FlowValidationError("bad_index", "index must be >= 0", index)
                    }
                    if (step.effect != null && FlowStepEffects.declaredEffect(step) == null) {
                        errors += FlowValidationError(
                            "bad_effect",
                            "effect '${step.effect}' is not one of ${ToolEffect.entries.joinToString { it.name.lowercase() }}",
                            index,
                        )
                    }
                }

                is TypeStep -> {
                    validateSelector(
                        index = index,
                        viewId = step.target.viewId,
                        text = step.target.text,
                        x = step.target.x,
                        y = step.target.y,
                        errors = errors,
                    )
                    checkPlaceholders(step.value, declared, index, errors)
                }

                is WaitForStep -> {
                    if (step.viewId.isNullOrBlank()) {
                        errors += FlowValidationError(
                            "wait_without_view_id",
                            "wait_for must name the view_id it is waiting for",
                            index,
                        )
                    }
                    if (step.timeoutMs <= 0 || step.timeoutMs > MAX_TIMEOUT_MS) {
                        errors += FlowValidationError(
                            "bad_timeout",
                            "timeout_ms must be within 1..$MAX_TIMEOUT_MS (was ${step.timeoutMs})",
                            index,
                        )
                    }
                    step.nodeTextContains?.let { checkPlaceholders(it, declared, index, errors) }
                }

                is AssertStep -> {
                    if (step.viewId.isNullOrBlank()) {
                        errors += FlowValidationError(
                            "assert_without_view_id",
                            "assert must name the view_id it is asserting about",
                            index,
                        )
                    }
                    if (step.nodeTextContains.isNullOrBlank() && step.expectChecksum.isNullOrBlank()) {
                        errors += FlowValidationError(
                            "empty_assert",
                            "assert must check something — node_text_contains or expect_checksum",
                            index,
                        )
                    }
                    step.nodeTextContains?.let { checkPlaceholders(it, declared, index, errors) }
                }

                is CheckpointStep -> {
                    if (step.name.isBlank()) {
                        errors += FlowValidationError("unnamed_checkpoint", "checkpoint needs a name", index)
                    }
                }
            }

            // The hard stop. Payment and authentication are never automated — not
            // behind a checkpoint, not behind an approval. The agent fills the form
            // and hands over the phone.
            if (FlowStepEffects.of(step) == ToolEffect.SENSITIVE) {
                errors += FlowValidationError(
                    "sensitive_step",
                    "this step targets payment or authentication (${targetOf(step)}), which is never " +
                        "automated — the flow must stop here and hand off to the user",
                    index,
                )
            }
        }
    }

    private fun validateSelector(
        index: Int,
        viewId: String?,
        text: String?,
        x: Double?,
        y: Double?,
        errors: MutableList<FlowValidationError>,
    ) {
        if (x != null || y != null) {
            errors += FlowValidationError(
                "coordinate_selector",
                "selectors are view_id first, never coordinates — ($x, $y) is a different place on " +
                    "every screen size, orientation and app update",
                index,
            )
        }
        if (!text.isNullOrBlank()) {
            errors += FlowValidationError(
                "text_selector",
                "selectors are view_id first, never text — '$text' breaks the moment the device " +
                    "changes language",
                index,
            )
        }
        if (viewId.isNullOrBlank()) {
            errors += FlowValidationError(
                "missing_view_id",
                "every step selects by view_id; this one names no node",
                index,
            )
        }
    }

    private fun checkPlaceholders(
        value: String,
        declared: Set<String>,
        index: Int,
        errors: MutableList<FlowValidationError>,
    ) {
        for (match in PLACEHOLDER.findAll(value)) {
            val name = match.groupValues[1]
            if (name !in declared) {
                errors += FlowValidationError(
                    "undeclared_param",
                    "'{{$name}}' is not in params — a flow may only substitute what it declares",
                    index,
                )
            }
        }
    }

    // ── Checkpoints ───────────────────────────────────────────────────

    private fun validateCheckpoints(flow: Flow, errors: MutableList<FlowValidationError>) {
        var seenCheckpoint = false
        flow.steps.forEachIndexed { index, step ->
            if (step is CheckpointStep) {
                seenCheckpoint = true
                return@forEachIndexed
            }
            if (FlowStepEffects.of(step) == ToolEffect.IRREVERSIBLE && !seenCheckpoint) {
                errors += FlowValidationError(
                    "missing_checkpoint",
                    "'${targetOf(step)}' cannot be undone and no checkpoint precedes it — a flow " +
                        "must declare where its irreversible boundary sits",
                    index,
                )
            }
        }
    }

    private fun targetOf(step: FlowStep): String = when (step) {
        is TapStep -> step.viewId ?: step.text ?: "(${step.x},${step.y})"
        is TypeStep -> step.target.viewId ?: step.target.text ?: "(untargeted)"
        is WaitForStep -> step.viewId ?: "(untargeted)"
        is AssertStep -> step.viewId ?: "(untargeted)"
        is CheckpointStep -> step.name
    }
}
