package org.ethereumphone.andyclaw.skills

data class SkillManifest(
    val description: String,
    val tools: List<ToolDefinition>,
    val permissions: List<String> = emptyList(),
)
