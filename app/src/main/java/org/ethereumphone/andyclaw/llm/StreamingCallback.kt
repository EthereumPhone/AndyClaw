package org.ethereumphone.andyclaw.llm

import kotlinx.serialization.json.JsonObject

interface StreamingCallback {
    fun onToken(text: String)
    fun onToolUse(id: String, name: String, input: JsonObject)
    fun onComplete(response: MessagesResponse)
    fun onError(error: Throwable)
}
