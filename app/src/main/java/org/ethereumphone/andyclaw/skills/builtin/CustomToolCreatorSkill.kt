package org.ethereumphone.andyclaw.skills.builtin

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.NativeSkillRegistry
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition
import org.ethereumphone.andyclaw.skills.customtools.CustomToolDefinition
import org.ethereumphone.andyclaw.skills.customtools.CustomToolExecutor
import org.ethereumphone.andyclaw.skills.customtools.CustomToolStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CustomToolCreatorSkill(
    private val context: Context,
    private val customToolStore: CustomToolStore,
    private val customToolExecutor: CustomToolExecutor,
    private val nativeSkillRegistry: NativeSkillRegistry,
    private val onToolsChanged: () -> Unit,
) : AndyClawSkill {

    companion object {
        private const val TAG = "CustomToolCreatorSkill"
        private val NAME_REGEX = Regex("^[a-z][a-z0-9_]{0,48}$")
        private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    }

    override val id = "custom-tool-creator"
    override val name = "Custom Tool Creator"

    override val baseManifest = SkillManifest(
        description = "Create reusable executable tools at runtime. " +
            "Custom tools run Java/BeanShell code (like execute_code) but persist across conversations and " +
            "become regular tools you can call by name. Use this when you find yourself repeatedly writing " +
            "similar code with execute_code. Always provide test_params to validate the code before saving.",
        tools = listOf(
            ToolDefinition(
                name = "create_custom_tool",
                description = "Create a new custom tool with executable Java/BeanShell code. " +
                    "The code is test-run with the provided test_params before saving — if the test fails, " +
                    "the tool is NOT saved and the error is returned so you can fix it. " +
                    "The tool's parameters are bound as named variables in the BeanShell context " +
                    "(in addition to context, packageManager, contentResolver, filesDir). " +
                    "The tool becomes immediately available after creation.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("name") {
                            put("type", "string")
                            put("description", "Tool name: lowercase letters, numbers, underscores only (e.g. 'battery_report', 'wifi_status')")
                        }
                        putJsonObject("description") {
                            put("type", "string")
                            put("description", "Clear description of what the tool does, shown to the LLM when selecting tools")
                        }
                        putJsonObject("parameters") {
                            put("type", "object")
                            put("description", "JSON Schema object defining the tool's input parameters. " +
                                "Must have 'type': 'object' and 'properties'. Each parameter key will be " +
                                "available as a named variable in the BeanShell code.")
                        }
                        putJsonObject("code") {
                            put("type", "string")
                            put("description", "Java/BeanShell code to execute when the tool is called. " +
                                "Has access to: context, packageManager, contentResolver, filesDir, " +
                                "plus all parameter names as variables. Use print()/System.out.println() " +
                                "for output. The last expression value is returned.")
                        }
                        putJsonObject("test_params") {
                            put("type", "object")
                            put("description", "Test parameters to validate the code before saving. " +
                                "Must match the parameters schema. The code will be executed with these " +
                                "values — if it fails, the tool is NOT saved.")
                        }
                    }
                    putJsonArray("required") {
                        add(JsonPrimitive("name"))
                        add(JsonPrimitive("description"))
                        add(JsonPrimitive("parameters"))
                        add(JsonPrimitive("code"))
                        add(JsonPrimitive("test_params"))
                    }
                },
                requiresApproval = true,
            ),
            ToolDefinition(
                name = "list_custom_tools",
                description = "List all custom tools that have been created, with their names, descriptions, and parameter schemas.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {}
                },
            ),
            ToolDefinition(
                name = "delete_custom_tool",
                description = "Delete a custom tool by name. The tool will no longer be available.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("name") {
                            put("type", "string")
                            put("description", "Name of the custom tool to delete")
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("name")) }
                },
                requiresApproval = true,
            ),
            ToolDefinition(
                name = "test_custom_tool",
                description = "Test an existing custom tool with new parameters without recreating it. " +
                    "Useful for debugging or verifying a tool works with different inputs.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("name") {
                            put("type", "string")
                            put("description", "Name of the custom tool to test")
                        }
                        putJsonObject("test_params") {
                            put("type", "object")
                            put("description", "Parameters to test the tool with")
                        }
                    }
                    putJsonArray("required") {
                        add(JsonPrimitive("name"))
                        add(JsonPrimitive("test_params"))
                    }
                },
            ),
        ),
    )

    override val privilegedManifest: SkillManifest? = null

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        Log.d(TAG, "Executing tool: $tool")
        return when (tool) {
            "create_custom_tool" -> executeCreate(params, tier)
            "list_custom_tools" -> executeList()
            "delete_custom_tool" -> executeDelete(params)
            "test_custom_tool" -> executeTest(params)
            else -> SkillResult.Error("Unknown custom-tool-creator tool: $tool")
        }
    }

    // ── create_custom_tool ──────────────────────────────────────────────

    private fun executeCreate(params: JsonObject, tier: Tier): SkillResult {
        val name = params["name"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: name")
        val description = params["description"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: description")
        val parameters = try {
            params["parameters"]?.jsonObject
                ?: return SkillResult.Error("Missing required parameter: parameters")
        } catch (e: Exception) {
            return SkillResult.Error("Invalid parameters: must be a JSON object with 'type': 'object' and 'properties'")
        }
        val code = params["code"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: code")
        val testParams = try {
            params["test_params"]?.jsonObject
                ?: return SkillResult.Error("Missing required parameter: test_params")
        } catch (e: Exception) {
            return SkillResult.Error("Invalid test_params: must be a JSON object")
        }

        // Validate name format
        if (!NAME_REGEX.matches(name)) {
            return SkillResult.Error(
                "Invalid tool name '$name'. Must start with a lowercase letter, " +
                    "contain only lowercase letters, numbers, and underscores, " +
                    "and be at most 49 characters (e.g. 'battery_report', 'wifi_status')."
            )
        }

        // Check for conflicts with existing tools
        val existingToolNames = nativeSkillRegistry.getTools(tier).map { it.name }.toSet()
        if (name in existingToolNames) {
            return SkillResult.Error(
                "A tool named '$name' already exists. Choose a different name."
            )
        }

        // Check for existing custom tool with same name
        if (customToolStore.exists(name)) {
            return SkillResult.Error(
                "A custom tool named '$name' already exists. " +
                    "Use delete_custom_tool first, or choose a different name."
            )
        }

        // Validate parameters schema has required fields
        if (parameters["type"]?.jsonPrimitive?.contentOrNull != "object") {
            return SkillResult.Error("Parameters schema must have 'type': 'object'")
        }

        // Test-run the code with test_params
        Log.i(TAG, "Test-running custom tool '$name' before saving...")
        val testResult = customToolExecutor.execute(code, testParams)

        if (testResult is SkillResult.Error) {
            return SkillResult.Error(
                "Test-run FAILED — tool NOT saved.\n\n" +
                    "Fix the code and try again.\n\n" +
                    "Error:\n${testResult.message}"
            )
        }

        // Test passed — save the tool
        val toolDef = CustomToolDefinition(
            name = name,
            description = description,
            parameters = parameters,
            code = code,
            createdAt = DATE_FMT.format(Date()),
        )

        return try {
            customToolStore.save(toolDef)
            onToolsChanged()

            val testOutput = (testResult as SkillResult.Success).data
            Log.i(TAG, "Created custom tool '$name'")
            SkillResult.Success(
                "Custom tool '$name' created successfully!\n\n" +
                    "The tool is now available and can be called by name.\n\n" +
                    "Test-run output:\n$testOutput"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save custom tool '$name': ${e.message}", e)
            SkillResult.Error("Failed to save custom tool: ${e.message}")
        }
    }

    // ── list_custom_tools ───────────────────────────────────────────────

    private fun executeList(): SkillResult {
        val tools = customToolStore.loadAll()
        if (tools.isEmpty()) {
            return SkillResult.Success("No custom tools created yet. Use create_custom_tool to make one.")
        }

        val sb = StringBuilder("## Custom Tools (${tools.size})\n\n")
        for (tool in tools) {
            sb.appendLine("### ${tool.name}")
            sb.appendLine("Description: ${tool.description}")
            sb.appendLine("Created: ${tool.createdAt}")
            sb.appendLine("Parameters: ${tool.parameters}")
            sb.appendLine()
        }

        return SkillResult.Success(sb.toString().trimEnd())
    }

    // ── delete_custom_tool ──────────────────────────────────────────────

    private fun executeDelete(params: JsonObject): SkillResult {
        val name = params["name"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: name")

        if (!customToolStore.exists(name)) {
            return SkillResult.Error("Custom tool '$name' not found. Use list_custom_tools to see available tools.")
        }

        return try {
            customToolStore.delete(name)
            onToolsChanged()

            Log.i(TAG, "Deleted custom tool '$name'")
            SkillResult.Success("Custom tool '$name' deleted and unregistered.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete custom tool '$name': ${e.message}", e)
            SkillResult.Error("Failed to delete custom tool: ${e.message}")
        }
    }

    // ── test_custom_tool ────────────────────────────────────────────────

    private fun executeTest(params: JsonObject): SkillResult {
        val name = params["name"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: name")
        val testParams = try {
            params["test_params"]?.jsonObject
                ?: return SkillResult.Error("Missing required parameter: test_params")
        } catch (e: Exception) {
            return SkillResult.Error("Invalid test_params: must be a JSON object")
        }

        val tool = customToolStore.load(name)
            ?: return SkillResult.Error("Custom tool '$name' not found. Use list_custom_tools to see available tools.")

        Log.i(TAG, "Testing custom tool '$name'...")
        return customToolExecutor.execute(tool.code, testParams)
    }
}
