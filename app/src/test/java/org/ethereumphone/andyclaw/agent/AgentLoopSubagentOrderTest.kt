package org.ethereumphone.andyclaw.agent

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.ethereumphone.andyclaw.skills.NativeSkillRegistry
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolEffect
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * A sub-agent's calls run the way the main loop's streamed ones do: one that is not
 * concurrency-safe alone, and in the order the model wrote them.
 */
class AgentLoopSubagentOrderTest {

    @Before
    fun setup() {
        ExecutionEngineFactory.clearRouteMemory()
    }

    @Test
    fun `a sub-agent's tap and type do not run at the same time`() = runBlocking {
        val running = AtomicInteger(0)
        val most = AtomicInteger(0)
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val registry = NativeSkillRegistry().apply {
            register(testSkill(
                "ui",
                testTool("fake_tap", ToolEffect.IRREVERSIBLE),
                testTool("fake_type", ToolEffect.IRREVERSIBLE),
            ) { tool, _ ->
                most.accumulateAndGet(running.incrementAndGet()) { a, b -> maxOf(a, b) }
                delay(50)
                order += tool
                running.decrementAndGet()
                SkillResult.Success("ok")
            })
        }
        val client = ScriptedLlmClient(listOf(
            listOf(toolUse("s1", AgentLoop.SPAWN_SUBAGENT_TOOL_NAME, buildJsonObject { put("task", JsonPrimitive("fill the field")) })),
            listOf(toolUse("t1", "fake_tap"), toolUse("t2", "fake_type")),
        ))

        AgentLoop(client, registry, Tier.OPEN, enabledSkillIds = setOf("ui"))
            .run("fill the field", emptyList(), RecordingCallbacks())

        assertEquals(1, most.get())
        assertEquals(listOf("fake_tap", "fake_type"), order)
    }
}
