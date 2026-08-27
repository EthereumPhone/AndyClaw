package org.ethereumphone.andyclaw.skills.customtools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.ethereumphone.andyclaw.skills.ToolEffect

@Serializable
data class CustomToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    val code: String,
    val createdAt: String,
    /**
     * What running this tool does — see [ToolEffect].
     *
     * Defaulted and trailing so definitions written by an older build deserialize
     * unchanged. `null` resolves to [ToolEffect.IRREVERSIBLE]: a tool the model
     * wrote for itself does not get to be treated as harmless by omission.
     */
    val effect: ToolEffect? = null,
)
