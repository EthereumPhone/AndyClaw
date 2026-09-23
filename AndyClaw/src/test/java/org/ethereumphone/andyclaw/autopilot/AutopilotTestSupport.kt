package org.ethereumphone.andyclaw.autopilot

/** Builders shared by the autopilot tests. */
internal object T {

    fun button(id: Int, label: String, y: Int = 300, x: Int = 360, viewId: String? = null, type: String = "button") =
        ScreenElement(id = id, type = type, label = label, viewId = viewId, actions = listOf("click"), centerX = x, centerY = y)

    fun field(id: Int, hint: String, y: Int = 650, value: String? = null, viewId: String? = null) =
        ScreenElement(id = id, type = "text_field", hint = hint, value = value, viewId = viewId,
            actions = listOf("click", "set_text"), centerX = 300, centerY = y)

    fun text(id: Int, label: String, y: Int = 200) =
        ScreenElement(id = id, type = "text", label = label, centerX = 360, centerY = y)

    fun screen(pkg: String, title: String, vararg elements: ScreenElement) =
        ScreenSnapshot(packageName = pkg, title = title, elements = elements.toList())

    fun plan(vararg steps: PlanStep, values: Map<String, String> = emptyMap(), say: String? = null) =
        AutopilotPlan(packageName = "com.msg", goal = "Send 'hi' to Anna", steps = steps.toList(), values = values, say = say)

    fun choice(choice: String, confidence: Double, runnerUp: Double = 0.0, runnerUpKey: String = "back") =
        JevAnswer.Choice(choice, mapOf(choice to confidence, runnerUpKey to runnerUp), confidence)

    fun response(vararg answers: Pair<String, JevAnswer>) = JevResponse(answers.toMap(), rttMs = 90)
}

/** A screen graph: acting on a labelled element moves to another named screen. */
internal class FakeDevice(
    private val screens: Map<String, ScreenSnapshot>,
    /** "screenName|tap:Label" or "screenName|type:Label" or "screenName|back" -> next screen name */
    private val transitions: Map<String, String>,
    start: String,
) : AutopilotDevice {
    var current = start
    val performed = mutableListOf<String>()
    val typed = mutableListOf<Pair<String, String>>()
    var launched: String? = null

    override suspend fun ensureApp(packageName: String): Boolean {
        launched = packageName
        return true
    }

    override suspend fun snapshot(): ScreenSnapshot = screens.getValue(current)

    override suspend fun perform(option: StepOption, screen: ScreenSnapshot, plan: AutopilotPlan): ActionOutcome {
        val target = option.elementId?.let { screen.byId(it) }
        val name = target?.name
        val key = when (option) {
            is StepOption.Tap -> "tap:$name"
            is StepOption.Type -> "type:$name"
            StepOption.Back -> "back"
            else -> option.key
        }
        performed += key
        if (option is StepOption.Type) typed += name.orEmpty() to plan.values[option.valueKey].orEmpty()
        val next = transitions["$current|$key"]
        return if (next != null) {
            current = next
            ActionOutcome(ok = true, changedScreen = true, actMs = 5, settleMs = 100)
        } else {
            ActionOutcome(ok = true, changedScreen = false, actMs = 5, settleMs = 250)
        }
    }
}

/** Answers Jev requests with a function of the request, the way a test wants the model to behave. */
internal class ScriptedJev(private val answer: (JevRequest) -> Map<String, JevAnswer>) : JevClient {
    val requests = mutableListOf<JevRequest>()
    override suspend fun evaluate(request: JevRequest): JevResponse {
        requests += request
        return JevResponse(answer(request), rttMs = 90)
    }

    companion object {
        /** The option key whose description mentions [label] and starts with [verb]. */
        fun keyFor(request: JevRequest, questionId: String, verb: String, label: String): String? =
            (request.questions[questionId] as? JevQuestion.Choice)?.options
                ?.entries?.firstOrNull { (_, d) -> d.startsWith(verb) && d.contains("\"$label\"") }?.key
    }
}
