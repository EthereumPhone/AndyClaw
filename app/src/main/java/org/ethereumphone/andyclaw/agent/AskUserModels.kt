package org.ethereumphone.andyclaw.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Question type for the ask_user tool.
 * - [SINGLE_SELECT]: Pick one option, or type a custom answer (keyboard at bottom).
 * - [MULTI_SELECT]: Pick multiple options, with an editable blank option at bottom.
 * - [RANKED_CHOICE]: Reorder options by preference (1st = most preferred).
 */
enum class QuestionType {
    SINGLE_SELECT,
    MULTI_SELECT,
    RANKED_CHOICE;

    companion object {
        fun fromString(value: String?): QuestionType = when (value?.lowercase()?.trim()) {
            "multi_select", "multi" -> MULTI_SELECT
            "ranked_choice", "ranked" -> RANKED_CHOICE
            else -> SINGLE_SELECT
        }
    }
}

/** A single question in an ask_user batch. */
data class AskUserQuestion(
    val question: String,
    val type: QuestionType = QuestionType.SINGLE_SELECT,
    val options: List<String> = emptyList(),
)

/** The full ask_user request (one or more questions). */
data class AskUserRequest(
    val questions: List<AskUserQuestion>,
)

/** The user's answer to a single question. */
data class QuestionAnswer(
    val question: String,
    /** Single string for SINGLE_SELECT, list joined with "; " for MULTI/RANKED. */
    val answer: String,
    /** Ordered selections for MULTI_SELECT and RANKED_CHOICE. */
    val selections: List<String> = emptyList(),
)

/** The full response to an ask_user request. */
data class AskUserResponse(
    val answers: List<QuestionAnswer>,
)

// ── JSON Parsing ─────────────────────────────────────────────────────

/** Parse the ask_user tool input JSON into an [AskUserRequest]. */
fun parseAskUserInput(input: JsonObject): AskUserRequest {
    val questionsElement = input["questions"]
    // Handle proper array of question objects
    if (questionsElement is JsonArray) {
        val questions = questionsElement.map { element ->
            val obj = element.jsonObject
            val optionsElement = obj["options"]
            val options = if (optionsElement is JsonArray) {
                optionsElement.map { it.jsonPrimitive.content }
            } else emptyList()
            AskUserQuestion(
                question = obj["question"]?.jsonPrimitive?.contentOrNull ?: "",
                type = QuestionType.fromString(obj["type"]?.jsonPrimitive?.contentOrNull),
                options = options,
            )
        }
        return AskUserRequest(questions)
    }
    // Fallback: "questions" is a string, or "question" field (backward compat)
    val question = questionsElement?.jsonPrimitive?.contentOrNull
        ?: input["question"]?.jsonPrimitive?.contentOrNull
        ?: "Can you clarify?"
    return AskUserRequest(listOf(AskUserQuestion(question)))
}

// ── JSON Serialization ───────────────────────────────────────────────

/** Serialize an [AskUserResponse] to a human-readable + LLM-parseable string. */
fun formatAskUserResponse(response: AskUserResponse, request: AskUserRequest? = null): String {
    if (response.answers.size == 1) {
        val a = response.answers[0]
        val qType = request?.questions?.firstOrNull()?.type
        return if (qType == QuestionType.RANKED_CHOICE && a.selections.isNotEmpty()) {
            "User ranked (1st = most preferred): ${a.selections.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString(", ")}"
        } else if (a.selections.isNotEmpty()) {
            "User selected: ${a.selections.joinToString(", ")}"
        } else {
            "User answered: ${a.answer}"
        }
    }
    return buildString {
        appendLine("User answered ${response.answers.size} questions:")
        for ((i, a) in response.answers.withIndex()) {
            val qType = request?.questions?.getOrNull(i)?.type
            if (qType == QuestionType.RANKED_CHOICE && a.selections.isNotEmpty()) {
                appendLine("${i + 1}. ${a.question} → Ranked: ${a.selections.mapIndexed { j, s -> "${j + 1}. $s" }.joinToString(", ")}")
            } else if (a.selections.isNotEmpty()) {
                appendLine("${i + 1}. ${a.question} → ${a.selections.joinToString(", ")}")
            } else {
                appendLine("${i + 1}. ${a.question} → ${a.answer}")
            }
        }
    }.trimEnd()
}

/** Format the Q&A for display in chat history (user-facing). */
fun formatAskUserForChat(request: AskUserRequest, response: AskUserResponse): String {
    return buildString {
        for ((i, a) in response.answers.withIndex()) {
            val q = request.questions.getOrNull(i)
            if (i > 0) appendLine()
            appendLine("Q: ${a.question}")
            when (q?.type) {
                QuestionType.RANKED_CHOICE -> {
                    append("A: ")
                    append(a.selections.mapIndexed { j, s -> "${j + 1}. $s" }.joinToString(", "))
                }
                QuestionType.MULTI_SELECT -> {
                    append("A: ${a.selections.joinToString(", ")}")
                }
                else -> {
                    append("A: ${a.answer}")
                }
            }
        }
    }
}
