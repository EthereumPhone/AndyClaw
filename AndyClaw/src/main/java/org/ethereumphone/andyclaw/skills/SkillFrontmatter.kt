package org.ethereumphone.andyclaw.skills

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parser for SKILL.md frontmatter blocks.
 * Frontmatter is delimited by `---` lines at the top of the file and contains YAML-like key: value pairs.
 * The `metadata` value may be a JSON object **or** nested YAML; both formats are supported.
 *
 * Mirrors OpenClaw's frontmatter.ts and skills/frontmatter.ts.
 */
object SkillFrontmatter {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Parse the frontmatter block from SKILL.md content.
     * Returns a map of key → value (all strings).
     */
    fun parse(content: String): Map<String, String> {
        val lines = content.lines()
        if (lines.isEmpty() || lines[0].trim() != "---") return emptyMap()

        val endIndex = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (endIndex < 0) return emptyMap()

        val frontmatterLines = lines.subList(1, endIndex + 1)
        return parseFrontmatterLines(frontmatterLines)
    }

    /**
     * Extract the body content (after frontmatter) from a SKILL.md file.
     */
    fun extractBody(content: String): String {
        val lines = content.lines()
        if (lines.isEmpty() || lines[0].trim() != "---") return content

        val endIndex = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (endIndex < 0) return content

        return lines.drop(endIndex + 2).joinToString("\n").trimStart()
    }

    /**
     * Resolve OpenClaw metadata from parsed frontmatter.
     * Tries JSON first, then falls back to YAML-subset parsing.
     */
    fun resolveMetadata(frontmatter: Map<String, String>): SkillMetadata? {
        val raw = frontmatter["metadata"] ?: return null

        val parsed: JsonObject = tryParseAsJson(raw)
            ?: tryParseAsYaml(raw)
            ?: return null

        val openclawRaw = parsed["openclaw"]?.jsonObject
            ?: parsed["andyclaw"]?.jsonObject
            ?: return null

        return try {
            parseMetadataObject(openclawRaw)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Resolve invocation policy from parsed frontmatter.
     */
    fun resolveInvocationPolicy(frontmatter: Map<String, String>): SkillInvocationPolicy {
        return SkillInvocationPolicy(
            userInvocable = parseBool(frontmatter["user-invocable"], true),
            disableModelInvocation = parseBool(frontmatter["disable-model-invocation"], false),
        )
    }

    // ── Metadata object parsing ─────────────────────────────────────

    private fun parseMetadataObject(obj: JsonObject): SkillMetadata {
        val requiresRaw = obj["requires"]?.jsonObject
        val installRaw = try { obj["install"]?.jsonArray } catch (_: Exception) { null }
        val executionRaw = try { obj["execution"]?.jsonObject } catch (_: Exception) { null }

        return SkillMetadata(
            always = obj["always"]?.jsonPrimitive?.booleanOrNull ?: false,
            skillKey = obj["skillKey"]?.jsonPrimitive?.content,
            primaryEnv = obj["primaryEnv"]?.jsonPrimitive?.content,
            emoji = obj["emoji"]?.jsonPrimitive?.content,
            homepage = obj["homepage"]?.jsonPrimitive?.content,
            os = parseStringList(obj["os"]),
            requires = requiresRaw?.let { req ->
                SkillRequirements(
                    bins = parseStringList(req["bins"]),
                    anyBins = parseStringList(req["anyBins"]),
                    env = parseStringList(req["env"]),
                )
            },
            install = installRaw?.mapNotNull { elem ->
                try {
                    val spec = elem.jsonObject
                    val kind = spec["kind"]?.jsonPrimitive?.content
                        ?: spec["type"]?.jsonPrimitive?.content
                        ?: return@mapNotNull null
                    SkillInstallSpec(
                        id = spec["id"]?.jsonPrimitive?.content,
                        kind = kind,
                        label = spec["label"]?.jsonPrimitive?.content,
                        bins = parseStringList(spec["bins"]),
                        os = parseStringList(spec["os"]),
                        formula = spec["formula"]?.jsonPrimitive?.content,
                        pkg = spec["package"]?.jsonPrimitive?.content,
                        module = spec["module"]?.jsonPrimitive?.content,
                        url = spec["url"]?.jsonPrimitive?.content,
                    )
                } catch (_: Exception) { null }
            } ?: emptyList(),
            execution = executionRaw?.let { parseExecutionSpec(it) },
        )
    }

    private fun parseExecutionSpec(obj: JsonObject): SkillExecutionSpec? {
        val type = obj["type"]?.jsonPrimitive?.content ?: return null
        val entrypoint = obj["entrypoint"]?.jsonPrimitive?.content ?: return null
        val toolsRaw = try { obj["tools"]?.jsonArray } catch (_: Exception) { null }

        return SkillExecutionSpec(
            type = type,
            entrypoint = entrypoint,
            setup = obj["setup"]?.jsonPrimitive?.content,
            tools = toolsRaw?.mapNotNull { parseToolSpec(it) } ?: emptyList(),
        )
    }

    private fun parseToolSpec(element: JsonElement): SkillToolSpec? {
        val obj = try { element.jsonObject } catch (_: Exception) { return null }
        val name = obj["name"]?.jsonPrimitive?.content ?: return null
        val argsRaw = try { obj["args"]?.jsonObject } catch (_: Exception) { null }

        return SkillToolSpec(
            name = name,
            description = obj["description"]?.jsonPrimitive?.content ?: "",
            entrypoint = obj["entrypoint"]?.jsonPrimitive?.content,
            args = argsRaw?.entries?.associate { (key, value) ->
                key to parseArgSpec(value)
            } ?: emptyMap(),
        )
    }

    private fun parseArgSpec(element: JsonElement): SkillArgSpec {
        val obj = try { element.jsonObject } catch (_: Exception) {
            return SkillArgSpec(type = "string")
        }
        return SkillArgSpec(
            type = obj["type"]?.jsonPrimitive?.content ?: "string",
            description = obj["description"]?.jsonPrimitive?.content ?: "",
            required = obj["required"]?.jsonPrimitive?.booleanOrNull ?: false,
            default = obj["default"]?.jsonPrimitive?.content,
        )
    }

    // ── JSON / YAML parsing helpers ─────────────────────────────────

    private fun tryParseAsJson(raw: String): JsonObject? {
        return try {
            json.parseToJsonElement(raw).jsonObject
        } catch (_: Exception) {
            null
        }
    }

    private fun tryParseAsYaml(raw: String): JsonObject? {
        return try {
            val element = YamlSubsetParser.parse(raw)
            element as? JsonObject
        } catch (_: Exception) {
            null
        }
    }

    private fun parseStringList(element: JsonElement?): List<String> {
        if (element == null) return emptyList()
        return try {
            element.jsonArray.map { it.jsonPrimitive.content }
        } catch (_: Exception) {
            try {
                element.jsonPrimitive.content.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    // ── Frontmatter line parsing ────────────────────────────────────

    /**
     * Parse YAML-like frontmatter lines into key-value pairs.
     * Handles multi-line values (indented continuation or JSON blocks).
     */
    private fun parseFrontmatterLines(lines: List<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var currentKey: String? = null
        val currentValue = StringBuilder()

        fun flush() {
            val key = currentKey ?: return
            result[key] = currentValue.toString().trim()
            currentKey = null
            currentValue.clear()
        }

        for (line in lines) {
            val keyMatch = Regex("^([a-zA-Z][a-zA-Z0-9_-]*):\\s*(.*)$").find(line)
            if (keyMatch != null && !line.startsWith(" ") && !line.startsWith("\t")) {
                flush()
                currentKey = keyMatch.groupValues[1]
                currentValue.append(keyMatch.groupValues[2])
            } else if (currentKey != null) {
                currentValue.append("\n").append(line)
            }
        }
        flush()

        return result.mapValues { (_, v) ->
            val trimmed = v.trim()
            if ((trimmed.startsWith("\"") && trimmed.endsWith("\"")) ||
                (trimmed.startsWith("'") && trimmed.endsWith("'"))
            ) {
                trimmed.substring(1, trimmed.length - 1)
            } else {
                trimmed
            }
        }
    }

    private fun parseBool(value: String?, default: Boolean): Boolean {
        if (value == null) return default
        return when (value.trim().lowercase()) {
            "true", "yes", "1" -> true
            "false", "no", "0" -> false
            else -> default
        }
    }
}

// ── Minimal YAML-subset parser ──────────────────────────────────────
//
// Handles the subset of YAML used in OpenClaw SKILL.md frontmatter:
//   - nested objects via indentation
//   - inline arrays  [a, b, c]
//   - block sequences (- item)
//   - quoted / unquoted scalars, booleans, numbers

internal object YamlSubsetParser {

    fun parse(yaml: String): JsonElement? {
        val lines = yaml.lines()
            .mapIndexed { idx, line -> IndexedLine(idx, line) }
            .filter { it.text.isNotBlank() && !it.text.trimStart().startsWith("#") }
        if (lines.isEmpty()) return null
        return parseBlock(lines, 0, lines.size)
    }

    private data class IndexedLine(val index: Int, val text: String)

    private fun indent(line: IndexedLine): Int =
        line.text.length - line.text.trimStart().length

    private fun parseBlock(lines: List<IndexedLine>, from: Int, to: Int): JsonElement? {
        if (from >= to) return null
        val first = lines[from].text.trimStart()
        return if (first.startsWith("- ")) {
            parseSequence(lines, from, to)
        } else {
            parseMapping(lines, from, to)
        }
    }

    private fun parseMapping(lines: List<IndexedLine>, from: Int, to: Int): JsonObject {
        val map = linkedMapOf<String, JsonElement>()
        val base = indent(lines[from])
        var i = from

        while (i < to) {
            val lineIndent = indent(lines[i])
            if (lineIndent < base) break
            if (lineIndent > base) { i++; continue }

            val trimmed = lines[i].text.trimStart()
            val colonIdx = trimmed.indexOf(':')
            if (colonIdx < 0) { i++; continue }

            val key = trimmed.substring(0, colonIdx).trim()
            val valueStr = trimmed.substring(colonIdx + 1).trim()

            if (valueStr.isNotEmpty()) {
                map[key] = parseScalar(valueStr)
                i++
            } else {
                val childFrom = i + 1
                var childTo = childFrom
                while (childTo < to && indent(lines[childTo]) > base) childTo++
                if (childFrom < childTo) {
                    parseBlock(lines, childFrom, childTo)?.let { map[key] = it }
                }
                i = childTo
            }
        }
        return JsonObject(map)
    }

    private fun parseSequence(lines: List<IndexedLine>, from: Int, to: Int): JsonArray {
        val items = mutableListOf<JsonElement>()
        val base = indent(lines[from])
        var i = from

        while (i < to) {
            val lineIndent = indent(lines[i])
            if (lineIndent < base) break

            val trimmed = lines[i].text.trimStart()
            if (!trimmed.startsWith("- ")) { i++; continue }

            val after = trimmed.removePrefix("-").trimStart()

            if (after.contains(':') && !after.startsWith("[") && !after.startsWith("\"") && !after.startsWith("'")) {
                // Mapping item — gather its continuation lines
                val contentIndent = base + 2
                val syntheticLines = mutableListOf(
                    IndexedLine(lines[i].index, " ".repeat(contentIndent) + after),
                )
                var j = i + 1
                while (j < to) {
                    val nextIndent = indent(lines[j])
                    if (nextIndent <= base) break
                    syntheticLines.add(lines[j])
                    j++
                }
                items.add(parseMapping(syntheticLines, 0, syntheticLines.size))
                i = j
            } else {
                items.add(parseScalar(after))
                i++
            }
        }
        return JsonArray(items)
    }

    private fun parseScalar(value: String): JsonElement {
        val trimmed = value.trim()

        // Inline array
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            val inner = trimmed.substring(1, trimmed.length - 1)
            val parts = splitRespectingQuotes(inner, ',')
            return JsonArray(parts.map { parseScalar(it) })
        }

        // Quoted string
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\"")) ||
            (trimmed.startsWith("'") && trimmed.endsWith("'"))
        ) {
            return JsonPrimitive(trimmed.substring(1, trimmed.length - 1))
        }

        // Boolean
        when (trimmed.lowercase()) {
            "true", "yes" -> return JsonPrimitive(true)
            "false", "no" -> return JsonPrimitive(false)
        }

        // Null
        if (trimmed.lowercase() == "null" || trimmed == "~") return JsonPrimitive("")

        // Number
        trimmed.toLongOrNull()?.let { return JsonPrimitive(it) }
        trimmed.toDoubleOrNull()?.let { return JsonPrimitive(it) }

        // Plain string
        return JsonPrimitive(trimmed)
    }

    private fun splitRespectingQuotes(input: String, delimiter: Char): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var inQuote = false
        var quoteChar = ' '

        for (c in input) {
            when {
                !inQuote && (c == '"' || c == '\'') -> { inQuote = true; quoteChar = c; current.append(c) }
                inQuote && c == quoteChar -> { inQuote = false; current.append(c) }
                !inQuote && c == delimiter -> { parts.add(current.toString().trim()); current.clear() }
                else -> current.append(c)
            }
        }
        if (current.isNotBlank()) parts.add(current.toString().trim())
        return parts
    }
}
