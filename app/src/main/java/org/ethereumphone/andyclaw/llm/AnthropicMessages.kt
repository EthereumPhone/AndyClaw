package org.ethereumphone.andyclaw.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class MessagesRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val system: String? = null,
    val messages: List<Message>,
    val tools: List<JsonObject>? = null,
    val stream: Boolean = false,
)

@Serializable
data class MessagesResponse(
    val id: String,
    val type: String,
    val role: String,
    val content: List<ContentBlock>,
    val model: String,
    @SerialName("stop_reason") val stopReason: String?,
    val usage: Usage? = null,
)

@Serializable
data class Usage(
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0,
)

@Serializable
data class Message(
    val role: String,
    val content: MessageContent,
) {
    companion object {
        fun user(text: String) = Message("user", MessageContent.Text(text))
        fun assistant(blocks: List<ContentBlock>) = Message("assistant", MessageContent.Blocks(blocks))
        fun toolResult(toolUseId: String, content: String, isError: Boolean = false) = Message(
            "user",
            MessageContent.Blocks(listOf(
                ContentBlock.ToolResult(
                    toolUseId = toolUseId,
                    content = content,
                    isError = isError,
                )
            ))
        )
    }
}

@Serializable
sealed class MessageContent {
    @Serializable
    @SerialName("text")
    data class Text(val value: String) : MessageContent()

    @Serializable
    @SerialName("blocks")
    data class Blocks(val blocks: List<ContentBlock>) : MessageContent()
}

object MessageContentSerializer : kotlinx.serialization.KSerializer<MessageContent> {
    override val descriptor = kotlinx.serialization.descriptors.buildClassSerialDescriptor("MessageContent")

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: MessageContent) {
        val jsonEncoder = encoder as kotlinx.serialization.json.JsonEncoder
        when (value) {
            is MessageContent.Text -> jsonEncoder.encodeJsonElement(kotlinx.serialization.json.JsonPrimitive(value.value))
            is MessageContent.Blocks -> jsonEncoder.encodeJsonElement(
                kotlinx.serialization.json.JsonArray(value.blocks.map { block ->
                    kotlinx.serialization.json.Json.encodeToJsonElement(ContentBlock.serializer(), block)
                })
            )
        }
    }

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): MessageContent {
        val jsonDecoder = decoder as kotlinx.serialization.json.JsonDecoder
        val element = jsonDecoder.decodeJsonElement()
        return when (element) {
            is kotlinx.serialization.json.JsonPrimitive -> MessageContent.Text(element.content)
            is kotlinx.serialization.json.JsonArray -> {
                val blocks = element.map {
                    kotlinx.serialization.json.Json.decodeFromJsonElement(ContentBlock.serializer(), it)
                }
                MessageContent.Blocks(blocks)
            }
            else -> throw kotlinx.serialization.SerializationException("Unexpected content format")
        }
    }
}

@Serializable
sealed class ContentBlock {
    @Serializable
    @SerialName("text")
    data class TextBlock(
        val text: String,
    ) : ContentBlock()

    @Serializable
    @SerialName("thinking")
    data class ThinkingBlock(
        val thinking: String,
    ) : ContentBlock()

    @Serializable
    @SerialName("redacted_thinking")
    data class RedactedThinkingBlock(
        val data: String = "",
    ) : ContentBlock()

    @Serializable
    @SerialName("tool_use")
    data class ToolUseBlock(
        val id: String,
        val name: String,
        val input: JsonObject,
    ) : ContentBlock()

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        @SerialName("tool_use_id") val toolUseId: String,
        val content: String,
        @SerialName("is_error") val isError: Boolean = false,
    ) : ContentBlock()
}
