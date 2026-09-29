package org.ethereumphone.andyclaw.summary

import org.ethereumphone.andyclaw.llm.AnthropicModels
import org.ethereumphone.andyclaw.llm.LlmProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class ExecutiveSummaryModelIdTest {
  private val fallback = AnthropicModels.MINIMAX_M3.modelId

  @Test
  fun `custom and open router pass an unknown id through`() {
    assertEquals("llama3.2:latest", ExecutiveSummaryManager.resolveSummaryModelId(LlmProvider.CUSTOM, "llama3.2:latest"))
    assertEquals("mistralai/some-model", ExecutiveSummaryManager.resolveSummaryModelId(LlmProvider.OPEN_ROUTER, "mistralai/some-model"))
  }

  @Test
  fun `other providers keep the enum fallback`() {
    assertEquals(fallback, ExecutiveSummaryManager.resolveSummaryModelId(LlmProvider.ETHOS_PREMIUM, "mistralai/some-model"))
    assertEquals(fallback, ExecutiveSummaryManager.resolveSummaryModelId(LlmProvider.CUSTOM, "  "))
  }

  @Test
  fun `known ids resolve through the enum`() {
    val id = AnthropicModels.QWEN_3_8_FLASH.modelId
    assertEquals(id, ExecutiveSummaryManager.resolveSummaryModelId(LlmProvider.CUSTOM, id))
    assertEquals(id, ExecutiveSummaryManager.resolveSummaryModelId(LlmProvider.ETHOS_PREMIUM, id))
  }
}
