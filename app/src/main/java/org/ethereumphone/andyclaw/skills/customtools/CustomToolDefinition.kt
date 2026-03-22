package org.ethereumphone.andyclaw.skills.customtools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class CustomToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    val code: String,
    val createdAt: String,
)
