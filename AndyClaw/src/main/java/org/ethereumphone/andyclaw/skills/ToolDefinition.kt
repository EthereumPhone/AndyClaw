package org.ethereumphone.andyclaw.skills

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val requiresApproval: Boolean = false,
)
