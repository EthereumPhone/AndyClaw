package org.ethereumphone.andyclaw.ui.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.json.JSONObject

/**
 * Transforms raw tool result strings into human-readable summaries for the chat UI.
 * The raw results are preserved for the LLM/session storage; only the display is affected.
 */
object ToolResultFormatter {

    data class Formatted(
        val summary: String,
        val detail: String,
        val explorerUrl: String? = null,
    )

    fun format(toolName: String, rawResult: String, input: JsonObject? = null): Formatted {
        return try {
            when (toolName) {
                "web_search" -> formatWebSearch(unwrap(rawResult))
                "fetch_webpage" -> formatFetchWebpage(unwrap(rawResult))
                "run_shell_command", "run_termux_command" -> formatShellOutput(unwrap(rawResult))
                "execute_code" -> formatCodeExecution(unwrap(rawResult))
                "setTerminalText" -> formatSetTerminalText(rawResult, input)
                "clearTerminalText" -> Formatted("Cleared", "Terminal display cleared.")
                "list_directory" -> formatListDirectory(unwrap(rawResult))
                "read_file" -> formatReadFile(unwrap(rawResult))
                "write_file" -> formatWriteFile(unwrap(rawResult))
                "file_info" -> formatFileInfo(unwrap(rawResult))
                "agent_display_get_ui_tree" -> formatUiTree(rawResult)
                "agent_display_get_info" -> formatDisplayInfo(rawResult)
                "propose_transaction",
                "agent_send_transaction",
                "send_native_token",
                "agent_send_native_token",
                "send_token",
                "agent_send_token",
                "propose_token_transfer",
                "agent_transfer_token",
                "agent_swap" -> formatTransaction(unwrap(rawResult))
                else -> formatDefault(rawResult)
            }
        } catch (_: Exception) {
            fallback(rawResult)
        }
    }

    private fun unwrap(text: String): String {
        var s = text.trim()
        val startTag = s.indexOf("<tool_output")
        if (startTag >= 0) {
            val contentStart = s.indexOf(">", startTag)
            val contentEnd = s.lastIndexOf("</tool_output>")
            if (contentStart >= 0 && contentEnd > contentStart) {
                s = s.substring(contentStart + 1, contentEnd).trim()
            }
        }
        s = s.replace(Regex("\\[BOUNDARY_\\w+:[^]]*]"), "").trim()
        return s
    }

    private fun stripImageBoilerplate(text: String): String =
        text.replace(Regex("\\s*The image is attached[^.]*\\.?"), "")
            .replace(Regex("\\s*—\\s*analyze it[^.]*\\.?"), "")
            .trim()

    // ── web_search ──────────────────────────────────────────────

    private fun formatWebSearch(raw: String): Formatted {
        val json = JSONObject(raw)
        val query = json.optString("query", "")
        val results = json.optJSONArray("results") ?: JSONArray()
        val count = results.length()

        val summary = "Searched \"$query\" — $count result${if (count != 1) "s" else ""}"

        val detail = buildString {
            for (i in 0 until count) {
                val r = results.getJSONObject(i)
                val title = r.optString("title", "")
                val url = r.optString("url", "")
                val snippet = r.optString("snippet", "")
                val domain = extractDomain(url)

                append("• $title")
                if (domain.isNotBlank()) append("  ($domain)")
                if (snippet.isNotBlank()) append("\n  $snippet")
                if (i < count - 1) append("\n")
            }
        }
        return Formatted(summary, detail.ifBlank { summary })
    }

    // ── fetch_webpage ───────────────────────────────────────────

    private fun formatFetchWebpage(raw: String): Formatted {
        val json = JSONObject(raw)
        val url = json.optString("url", "")
        val contentLength = json.optInt("content_length", 0)
        val content = json.optString("content", "")
        val domain = extractDomain(url)
        val sizeStr = formatSize(contentLength.toLong())

        val summary = "Fetched $domain ($sizeStr)"
        val detail = if (content.length > 500) content.take(500) + "…" else content
        return Formatted(summary, detail.ifBlank { summary })
    }

    // ── shell / termux ──────────────────────────────────────────

    private fun formatShellOutput(raw: String): Formatted {
        val json = JSONObject(raw)
        val exitCode = json.optInt("exit_code", -1)
        val output = json.optString("output", json.optString("stdout", "")).trimEnd()
        val stderr = json.optString("stderr", "").trimEnd()
        val truncated = json.optBoolean("truncated", false)

        val exitLabel = if (exitCode == 0) "Exit 0" else "Exit $exitCode"
        val firstLine = output.lines().firstOrNull { it.isNotBlank() }?.take(60) ?: ""
        val summary = if (firstLine.isNotBlank()) {
            "$exitLabel — $firstLine${if (firstLine.length >= 60) "…" else ""}"
        } else {
            exitLabel
        }

        val detail = buildString {
            if (output.isNotBlank()) append(output)
            if (stderr.isNotBlank()) {
                if (isNotBlank()) append("\n")
                append("stderr: $stderr")
            }
            if (truncated) {
                if (isNotBlank()) append("\n")
                append("[output truncated]")
            }
        }
        return Formatted(summary, detail.ifBlank { summary })
    }

    // ── execute_code ────────────────────────────────────────────

    private fun formatCodeExecution(raw: String): Formatted {
        val json = JSONObject(raw)
        val returnValue = json.optString("return_value", "")
        val output = json.optString("output", "").trimEnd()
        val timeMs = json.optLong("execution_time_ms", -1)

        val summary = buildString {
            if (timeMs > 0) append("${timeMs}ms")
            if (returnValue.isNotBlank() && returnValue != "null") {
                if (isNotBlank()) append(" → ")
                append(returnValue.take(50))
            }
        }.ifBlank { "Executed" }

        val detail = buildString {
            if (returnValue.isNotBlank() && returnValue != "null") append("→ $returnValue")
            if (output.isNotBlank()) {
                if (isNotBlank()) append("\n")
                append(output)
            }
        }
        return Formatted(summary, detail.ifBlank { summary })
    }

    // ── terminal display ────────────────────────────────────────

    private fun formatSetTerminalText(raw: String, input: JsonObject?): Formatted {
        val text = input?.get("text")?.jsonPrimitive?.contentOrNull
        return if (text != null) {
            val display = if (text.length > 40) text.take(40) + "…" else text
            Formatted("\"$display\"", "Display set to: $text")
        } else {
            Formatted("Updated", raw)
        }
    }

    // ── file operations ─────────────────────────────────────────

    private fun formatListDirectory(raw: String): Formatted {
        val arr = JSONArray(raw)
        val count = arr.length()
        val summary = "$count item${if (count != 1) "s" else ""}"
        val detail = buildString {
            for (i in 0 until minOf(count, 30)) {
                val item = arr.get(i)
                if (item is JSONObject) {
                    val name = item.optString("name", "")
                    val type = item.optString("type", "")
                    val prefix = if (type == "directory") "/" else " "
                    append("$prefix$name")
                } else {
                    append("  $item")
                }
                if (i < count - 1) append("\n")
            }
            if (count > 30) append("\n  … and ${count - 30} more")
        }
        return Formatted(summary, detail.ifBlank { summary })
    }

    private fun formatReadFile(raw: String): Formatted {
        return try {
            val json = JSONObject(raw)
            val content = json.optString("content", "")
            val path = json.optString("path", "")
            val name = path.substringAfterLast("/")
            val sizeStr = formatSize(content.length.toLong())
            Formatted(
                "$name ($sizeStr)",
                if (content.length > 500) content.take(500) + "…" else content,
            )
        } catch (_: Exception) {
            val firstLine = raw.lines().firstOrNull { it.isNotBlank() }?.take(60) ?: ""
            Formatted(firstLine, if (raw.length > 500) raw.take(500) + "…" else raw)
        }
    }

    private fun formatWriteFile(raw: String): Formatted {
        return try {
            val json = JSONObject(raw)
            val path = json.optString("path", "")
            val name = path.substringAfterLast("/")
            Formatted("Wrote $name", raw)
        } catch (_: Exception) {
            fallback(raw)
        }
    }

    private fun formatFileInfo(raw: String): Formatted {
        return try {
            val json = JSONObject(raw)
            val name = json.optString("name",
                json.optString("path", "").substringAfterLast("/"))
            val size = json.optLong("size", -1)
            val sizeStr = if (size >= 0) formatSize(size) else ""
            Formatted(
                if (sizeStr.isNotBlank()) "$name — $sizeStr" else name,
                raw,
            )
        } catch (_: Exception) {
            fallback(raw)
        }
    }

    // ── agent display ───────────────────────────────────────────

    private fun formatUiTree(raw: String): Formatted {
        val lineCount = raw.count { it == '\n' } + 1
        return Formatted("UI tree ($lineCount nodes)", raw)
    }

    private fun formatDisplayInfo(raw: String): Formatted {
        return try {
            val json = JSONObject(raw)
            val w = json.optInt("width", 0)
            val h = json.optInt("height", 0)
            Formatted("${w}×${h}", raw)
        } catch (_: Exception) {
            fallback(raw)
        }
    }

    // ── transactions ──────────────────────────────────────────────

    private fun txExplorerUrl(txHash: String): String {
        return "https://blockscan.com/tx/$txHash"
    }

    private fun formatTransaction(raw: String): Formatted {
        val json = JSONObject(raw)
        val userOpHash = json.optString("user_op_hash", "")
        val chainId = json.optInt("chain_id", 1)
        val status = json.optString("status", "")
        val amount = json.optString("amount", "")
        val token = json.optString("symbol", json.optString("token", ""))
        val to = json.optString("to", "")

        val summary = buildString {
            if (status == "submitted") append("Submitted")
            else append(status.replaceFirstChar { it.uppercase() })
            if (amount.isNotBlank() && token.isNotBlank()) {
                append(" — $amount $token")
            }
        }.ifBlank { "Transaction submitted" }

        val detail = buildString {
            if (amount.isNotBlank() && token.isNotBlank()) appendLine("Amount: $amount $token")
            if (to.isNotBlank()) appendLine("To: $to")
            if (userOpHash.isNotBlank()) appendLine("UserOp: ${userOpHash.take(18)}…")
            appendLine("Chain: $chainId")
        }.trimEnd()

        val explorerUrl = if (userOpHash.isNotBlank()) {
            txExplorerUrl(userOpHash)
        } else null

        return Formatted(summary, detail, explorerUrl)
    }

    // ── default / fallback ──────────────────────────────────────

    private fun formatDefault(raw: String): Formatted {
        val clean = stripImageBoilerplate(raw).trimEnd()
        val firstLine = clean.lines().firstOrNull { it.isNotBlank() }?.take(80) ?: ""
        val summary = if (firstLine.length >= 80) "$firstLine…" else firstLine
        return Formatted(summary, clean)
    }

    private fun fallback(raw: String): Formatted {
        val firstLine = raw.lines().firstOrNull { it.isNotBlank() }?.take(80) ?: ""
        return Formatted(firstLine, raw)
    }

    // ── helpers ─────────────────────────────────────────────────

    private fun extractDomain(url: String): String = try {
        java.net.URI(url).host?.removePrefix("www.") ?: url.take(40)
    } catch (_: Exception) {
        url.take(40)
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 0 -> ""
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
    }
}
