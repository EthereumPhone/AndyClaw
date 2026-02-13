package org.ethereumphone.andyclaw.skills

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parser for SKILL.md frontmatter blocks.
 * Frontmatter is delimited by `---` lines at the top of the file and contains YAML-like key: value pairs.
 * The `metadata` value is a JSON5/JSON object containing OpenClaw-specific metadata.
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
     */
    fun resolveMetadata(frontmatter: Map<String, String>): SkillMetadata? {
        val raw = frontmatter["metadata"] ?: return null
        return try {
            val parsed = json.parseToJsonElement(raw).jsonObject
            val openclawRaw = parsed["openclaw"]?.jsonObject
                ?: parsed["andyclaw"]?.jsonObject
                ?: return null
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

    private fun parseMetadataObject(obj: JsonObject): SkillMetadata {
        val requiresRaw = obj["requires"]?.jsonObject
        val installRaw = try { obj["install"]?.jsonArray } catch (_: Exception) { null }

        return SkillMetadata(
            always = obj["always"]?.jsonPrimitive?.boolean ?: false,
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
        )
    }

    private fun parseStringList(element: kotlinx.serialization.json.JsonElement?): List<String> {
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

        // Unquote string values
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
