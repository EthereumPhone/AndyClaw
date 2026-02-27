package org.ethereumphone.andyclaw.skills.builtin

import android.content.Context
import android.content.Intent
import android.provider.Settings
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.ethereumphone.andyclaw.services.AndyClawAccessibilityService
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition

class UiAutomationSkill(private val context: Context) : AndyClawSkill {
    override val id = "ui_automation"
    override val name = "UI Automation"

    companion object {
        private const val POST_ACTION_SETTLE_MS = 200L
    }

    override val baseManifest = SkillManifest(
        description = buildString {
            appendLine("Interact with other apps' UI using the Android Accessibility Service.")
            appendLine("Requires the user to enable the AndyClaw accessibility service in device Settings.")
            appendLine()
            appendLine("Workflow: 1) get_ui_hierarchy to see the screen, 2) identify target by [N#] ref, 3) perform action.")
            appendLine("Action tools (tap, type, scroll, etc.) automatically return the updated hierarchy after execution,")
            appendLine("so you do NOT need to call get_ui_hierarchy again after each action — just read the 'hierarchy_after' field.")
            appendLine()
            appendLine("Use get_subtree to explore a specific node's children in more detail.")
            appendLine("Use get_all_windows to see dialogs, keyboard, system overlays alongside the main app.")
        },
        tools = listOf(
            ToolDefinition(
                name = "check_accessibility_status",
                description = "Check if the AndyClaw accessibility service is enabled. Call this before any UI automation to verify the service is active.",
                inputSchema = emptyObjectSchema(),
            ),
            ToolDefinition(
                name = "open_accessibility_settings",
                description = "Open the device's Accessibility Settings page so the user can enable the AndyClaw accessibility service.",
                inputSchema = emptyObjectSchema(),
            ),
            ToolDefinition(
                name = "get_ui_hierarchy",
                description = buildString {
                    append("Read the current screen's UI tree. Returns elements with [N#] reference tags. ")
                    append("Uses a smart cache — returns instantly if the screen hasn't changed since the last call. ")
                    append("Set force_refresh=true to bypass cache. Includes cache_hit and age_ms metadata.")
                },
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "force_refresh" to JsonObject(mapOf(
                            "type" to JsonPrimitive("boolean"),
                            "description" to JsonPrimitive("Force a fresh tree walk even if cache is available (default false)"),
                        )),
                    )),
                )),
            ),
            ToolDefinition(
                name = "get_subtree",
                description = "Expand a specific node's full subtree at deeper detail, including all children (even non-interactive ones the top-level dump skips). Useful for exploring complex containers, lists, or nested layouts.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "ref" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Element reference tag to expand, e.g. 'N5'"),
                        )),
                        "max_depth" to JsonObject(mapOf(
                            "type" to JsonPrimitive("integer"),
                            "description" to JsonPrimitive("Max depth to traverse (default 15, max 30)"),
                        )),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("ref"))),
                )),
            ),
            ToolDefinition(
                name = "get_all_windows",
                description = "List all visible windows: the main app, dialogs, keyboard (IME), system overlays, etc. Each window shows its type and a summary of its UI elements. Useful to see what's behind/on top of the main app.",
                inputSchema = emptyObjectSchema(),
            ),
            ToolDefinition(
                name = "tap_element",
                description = "Tap a UI element by [N#] ref. Returns the action result AND the updated UI hierarchy so you don't need to call get_ui_hierarchy again.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "ref" to refParam(),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("ref"))),
                )),
                requiresApproval = true,
            ),
            ToolDefinition(
                name = "long_press_element",
                description = "Long-press a UI element by [N#] ref. Returns action result + updated hierarchy.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "ref" to refParam(),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("ref"))),
                )),
                requiresApproval = true,
            ),
            ToolDefinition(
                name = "type_text",
                description = "Type text into an editable field by [N#] ref. Replaces existing text. Returns action result + updated hierarchy.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "ref" to refParam(),
                        "text" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("The text to type into the field"),
                        )),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("ref"), JsonPrimitive("text"))),
                )),
                requiresApproval = true,
            ),
            ToolDefinition(
                name = "scroll_element",
                description = "Scroll a scrollable container forward or backward. Returns action result + updated hierarchy.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "ref" to refParam(),
                        "direction" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("'forward' (down/right) or 'backward' (up/left)"),
                        )),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("ref"), JsonPrimitive("direction"))),
                )),
                requiresApproval = true,
            ),
            ToolDefinition(
                name = "find_element",
                description = "Search for UI elements by text, content description, or resource ID. Returns matching elements with refs from the current (cached) hierarchy.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "text" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Text or content description to search for (case-insensitive partial match)"),
                        )),
                        "resource_id" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Resource ID to search for (case-insensitive partial match)"),
                        )),
                    )),
                )),
            ),
            ToolDefinition(
                name = "swipe",
                description = "Swipe between two screen coordinates. Returns action result + updated hierarchy.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "start_x" to coordParam("Start X (pixels)"),
                        "start_y" to coordParam("Start Y (pixels)"),
                        "end_x" to coordParam("End X (pixels)"),
                        "end_y" to coordParam("End Y (pixels)"),
                        "duration_ms" to JsonObject(mapOf(
                            "type" to JsonPrimitive("integer"),
                            "description" to JsonPrimitive("Swipe duration in ms (default 300)"),
                        )),
                    )),
                    "required" to JsonArray(listOf(
                        JsonPrimitive("start_x"), JsonPrimitive("start_y"),
                        JsonPrimitive("end_x"), JsonPrimitive("end_y"),
                    )),
                )),
                requiresApproval = true,
            ),
            ToolDefinition(
                name = "tap_coordinates",
                description = "Tap at absolute screen coordinates. Returns action result + updated hierarchy.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "x" to coordParam("X (pixels)"),
                        "y" to coordParam("Y (pixels)"),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("x"), JsonPrimitive("y"))),
                )),
                requiresApproval = true,
            ),
            ToolDefinition(
                name = "press_button",
                description = "Press a system button: 'back', 'home', 'recents', 'notifications', 'quick_settings'. Returns action result + updated hierarchy.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "button" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("back | home | recents | notifications | quick_settings"),
                        )),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("button"))),
                )),
                requiresApproval = true,
            ),
            ToolDefinition(
                name = "wait_for_element",
                description = "Wait for a UI element matching text or resource ID to appear. Polls periodically. Returns the element ref when found.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "text" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Text to wait for"),
                        )),
                        "resource_id" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Resource ID to wait for"),
                        )),
                        "timeout_ms" to JsonObject(mapOf(
                            "type" to JsonPrimitive("integer"),
                            "description" to JsonPrimitive("Max wait time in ms (default 5000, max 15000)"),
                        )),
                    )),
                )),
            ),
        ),
    )

    override val privilegedManifest: SkillManifest? = null

    // ── Tool dispatch ─────────────────────────────────────────────────

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        if (tool == "check_accessibility_status") return checkAccessibilityStatus()
        if (tool == "open_accessibility_settings") return openAccessibilitySettings()

        val service = AndyClawAccessibilityService.instance
            ?: return SkillResult.Error(
                "Accessibility service is not active. " +
                "The user must enable 'AndyClaw' in Settings > Accessibility > Installed services. " +
                "Use open_accessibility_settings to open the settings page, or tell the user to do this manually."
            )

        return when (tool) {
            "get_ui_hierarchy" -> getUiHierarchy(service, params)
            "get_subtree" -> getSubtree(service, params)
            "get_all_windows" -> getAllWindows(service)
            "tap_element" -> tapElement(service, params)
            "long_press_element" -> longPressElement(service, params)
            "type_text" -> typeText(service, params)
            "scroll_element" -> scrollElement(service, params)
            "find_element" -> findElement(service, params)
            "swipe" -> swipe(service, params)
            "tap_coordinates" -> tapCoordinates(service, params)
            "press_button" -> pressButton(service, params)
            "wait_for_element" -> waitForElement(service, params)
            else -> SkillResult.Error("Unknown tool: $tool")
        }
    }

    // ── Status tools ──────────────────────────────────────────────────

    private fun checkAccessibilityStatus(): SkillResult {
        val enabled = AndyClawAccessibilityService.instance != null
        return SkillResult.Success(buildJsonObject {
            put("enabled", enabled)
            if (!enabled) {
                put("message", "Accessibility service is not enabled. Use open_accessibility_settings to open the settings page, then ask the user to enable 'AndyClaw'.")
            } else {
                put("message", "Accessibility service is active and ready for UI automation.")
            }
        }.toString())
    }

    private fun openAccessibilitySettings(): SkillResult {
        return try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            SkillResult.Success(buildJsonObject {
                put("opened", true)
                put("message", "Accessibility settings opened. Ask the user to find 'AndyClaw' in the list and enable it.")
            }.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to open accessibility settings: ${e.message}")
        }
    }

    // ── Read tools ────────────────────────────────────────────────────

    private fun getUiHierarchy(service: AndyClawAccessibilityService, params: JsonObject): SkillResult {
        return try {
            val forceRefresh = params["force_refresh"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            val wasCached = service.isCacheFresh && !forceRefresh
            val hierarchy = service.dumpAndRetain(forceRefresh)

            if (hierarchy.isBlank() || hierarchy == "No active window") {
                SkillResult.Error("No UI hierarchy available — the screen may be locked or empty")
            } else {
                val snapshot = service.getHierarchy()
                val ageMs = if (snapshot != null) System.currentTimeMillis() - snapshot.timestampMs else 0
                val result = buildString {
                    if (wasCached) appendLine("[cache_hit, age=${ageMs}ms]")
                    else appendLine("[fresh_walk]")
                    append(hierarchy)
                }
                SkillResult.Success(result)
            }
        } catch (e: Exception) {
            SkillResult.Error("Failed to read UI hierarchy: ${e.message}")
        }
    }

    private fun getSubtree(service: AndyClawAccessibilityService, params: JsonObject): SkillResult {
        val ref = params["ref"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: ref")
        val maxDepth = params["max_depth"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 30) ?: 15

        val subtree = service.expandSubtree(ref, maxDepth)
            ?: return SkillResult.Error("Element $ref not found. Call get_ui_hierarchy first to populate refs.")
        return SkillResult.Success(subtree)
    }

    private fun getAllWindows(service: AndyClawAccessibilityService): SkillResult {
        return try {
            val result = service.dumpAllWindows()
            SkillResult.Success(result)
        } catch (e: Exception) {
            SkillResult.Error("Failed to enumerate windows: ${e.message}")
        }
    }

    // ── Action tools (all return updated hierarchy) ───────────────────

    /**
     * After a mutating action, wait briefly for the UI to settle, then
     * grab a fresh hierarchy and append it to the result. This saves an
     * entire LLM round-trip per action.
     */
    private suspend fun withPostActionHierarchy(
        service: AndyClawAccessibilityService,
        actionResult: JsonObject,
    ): SkillResult {
        delay(POST_ACTION_SETTLE_MS)
        val hierarchy = service.dumpAndRetain(forceRefresh = true)
        val combined = buildString {
            appendLine(actionResult.toString())
            appendLine()
            appendLine("--- Updated UI hierarchy after action ---")
            append(hierarchy)
        }
        return SkillResult.Success(combined)
    }

    private suspend fun tapElement(service: AndyClawAccessibilityService, params: JsonObject): SkillResult {
        val ref = params["ref"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: ref")
        val node = service.findNodeByRef(ref)
            ?: return SkillResult.Error("Element $ref not found. Call get_ui_hierarchy first to refresh.")
        if (!service.tapNode(node)) {
            return SkillResult.Error("Failed to tap $ref — may not be clickable or stale. Try get_ui_hierarchy again.")
        }
        return withPostActionHierarchy(service, buildJsonObject { put("tapped", ref) })
    }

    private suspend fun longPressElement(service: AndyClawAccessibilityService, params: JsonObject): SkillResult {
        val ref = params["ref"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: ref")
        val node = service.findNodeByRef(ref)
            ?: return SkillResult.Error("Element $ref not found. Call get_ui_hierarchy first.")
        if (!service.longPressNode(node)) {
            return SkillResult.Error("Failed to long-press $ref")
        }
        return withPostActionHierarchy(service, buildJsonObject { put("long_pressed", ref) })
    }

    private suspend fun typeText(service: AndyClawAccessibilityService, params: JsonObject): SkillResult {
        val ref = params["ref"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: ref")
        val text = params["text"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: text")
        val node = service.findNodeByRef(ref)
            ?: return SkillResult.Error("Element $ref not found. Call get_ui_hierarchy first.")
        if (!node.isEditable) {
            return SkillResult.Error("Element $ref is not editable. Target an input field.")
        }
        if (!service.setNodeText(node, text)) {
            return SkillResult.Error("Failed to type text into $ref")
        }
        return withPostActionHierarchy(service, buildJsonObject {
            put("typed_in", ref)
            put("text", text)
        })
    }

    private suspend fun scrollElement(service: AndyClawAccessibilityService, params: JsonObject): SkillResult {
        val ref = params["ref"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: ref")
        val direction = params["direction"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: direction")
        val node = service.findNodeByRef(ref)
            ?: return SkillResult.Error("Element $ref not found. Call get_ui_hierarchy first.")
        val forward = when (direction.lowercase()) {
            "forward", "down", "right" -> true
            "backward", "up", "left" -> false
            else -> return SkillResult.Error("Invalid direction: $direction. Use 'forward' or 'backward'.")
        }
        if (!service.scrollNode(node, forward)) {
            return SkillResult.Error("Failed to scroll $ref — may not be scrollable or at the end.")
        }
        return withPostActionHierarchy(service, buildJsonObject {
            put("scrolled", ref)
            put("direction", direction)
        })
    }

    private fun findElement(service: AndyClawAccessibilityService, params: JsonObject): SkillResult {
        val text = params["text"]?.jsonPrimitive?.contentOrNull
        val resourceId = params["resource_id"]?.jsonPrimitive?.contentOrNull
        if (text == null && resourceId == null) {
            return SkillResult.Error("Provide at least one of: text, resource_id")
        }

        val results = mutableListOf<JsonObject>()

        if (text != null) {
            for ((ref, node) in service.findNodesByText(text)) {
                results.add(buildJsonObject {
                    put("ref", ref)
                    put("text", node.text?.toString() ?: "")
                    put("desc", node.contentDescription?.toString() ?: "")
                    put("clickable", node.isClickable)
                })
            }
        }
        if (resourceId != null) {
            for ((ref, node) in service.findNodesByResourceId(resourceId)) {
                val existing = results.any { it["ref"]?.jsonPrimitive?.contentOrNull == ref }
                if (!existing) {
                    results.add(buildJsonObject {
                        put("ref", ref)
                        put("resource_id", node.viewIdResourceName ?: "")
                        put("text", node.text?.toString() ?: "")
                        put("clickable", node.isClickable)
                    })
                }
            }
        }

        return if (results.isEmpty()) {
            SkillResult.Success(buildJsonObject {
                put("found", 0)
                put("message", "No matches. Try get_ui_hierarchy to see all elements.")
            }.toString())
        } else {
            SkillResult.Success(buildJsonObject {
                put("found", results.size)
                put("elements", JsonArray(results))
            }.toString())
        }
    }

    private suspend fun swipe(service: AndyClawAccessibilityService, params: JsonObject): SkillResult {
        val startX = params["start_x"]?.jsonPrimitive?.floatOrNull
            ?: return SkillResult.Error("Missing: start_x")
        val startY = params["start_y"]?.jsonPrimitive?.floatOrNull
            ?: return SkillResult.Error("Missing: start_y")
        val endX = params["end_x"]?.jsonPrimitive?.floatOrNull
            ?: return SkillResult.Error("Missing: end_x")
        val endY = params["end_y"]?.jsonPrimitive?.floatOrNull
            ?: return SkillResult.Error("Missing: end_y")
        val durationMs = params["duration_ms"]?.jsonPrimitive?.longOrNull?.coerceIn(100, 2000) ?: 300L

        if (!service.performSwipe(startX, startY, endX, endY, durationMs)) {
            return SkillResult.Error("Swipe gesture failed or was cancelled")
        }
        return withPostActionHierarchy(service, buildJsonObject {
            put("swiped", true)
            put("from", "${startX.toInt()},${startY.toInt()}")
            put("to", "${endX.toInt()},${endY.toInt()}")
        })
    }

    private suspend fun tapCoordinates(service: AndyClawAccessibilityService, params: JsonObject): SkillResult {
        val x = params["x"]?.jsonPrimitive?.floatOrNull
            ?: return SkillResult.Error("Missing: x")
        val y = params["y"]?.jsonPrimitive?.floatOrNull
            ?: return SkillResult.Error("Missing: y")

        if (!service.tapAtCoordinates(x, y)) {
            return SkillResult.Error("Tap at ($x, $y) failed or was cancelled")
        }
        return withPostActionHierarchy(service, buildJsonObject {
            put("tapped_at", "${x.toInt()},${y.toInt()}")
        })
    }

    private suspend fun pressButton(service: AndyClawAccessibilityService, params: JsonObject): SkillResult {
        val button = params["button"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing: button")

        val success = when (button.lowercase()) {
            "back" -> service.pressBack()
            "home" -> service.pressHome()
            "recents" -> service.pressRecents()
            "notifications" -> service.openNotifications()
            "quick_settings" -> service.openQuickSettings()
            else -> return SkillResult.Error("Unknown button: $button. Use: back, home, recents, notifications, quick_settings")
        }

        if (!success) return SkillResult.Error("Failed to press $button")
        return withPostActionHierarchy(service, buildJsonObject { put("pressed", button) })
    }

    private suspend fun waitForElement(service: AndyClawAccessibilityService, params: JsonObject): SkillResult {
        val text = params["text"]?.jsonPrimitive?.contentOrNull
        val resourceId = params["resource_id"]?.jsonPrimitive?.contentOrNull
        if (text == null && resourceId == null) {
            return SkillResult.Error("Provide at least one of: text, resource_id")
        }
        val timeoutMs = params["timeout_ms"]?.jsonPrimitive?.longOrNull?.coerceIn(500, 15_000) ?: 5000L

        val startTime = System.currentTimeMillis()
        val pollInterval = 400L

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            service.dumpAndRetain(forceRefresh = true)

            val matches = when {
                text != null -> service.findNodesByText(text)
                resourceId != null -> service.findNodesByResourceId(resourceId)
                else -> emptyList()
            }

            if (matches.isNotEmpty()) {
                val first = matches.first()
                return SkillResult.Success(buildJsonObject {
                    put("found", true)
                    put("ref", first.first)
                    put("text", first.second.text?.toString() ?: "")
                    put("waited_ms", System.currentTimeMillis() - startTime)
                }.toString())
            }

            delay(pollInterval)
        }

        return SkillResult.Success(buildJsonObject {
            put("found", false)
            put("message", "Element not found within ${timeoutMs}ms")
        }.toString())
    }

    // ── Schema helpers ────────────────────────────────────────────────

    private fun emptyObjectSchema() = JsonObject(mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(emptyMap()),
    ))

    private fun refParam() = JsonObject(mapOf(
        "type" to JsonPrimitive("string"),
        "description" to JsonPrimitive("Element [N#] reference from get_ui_hierarchy"),
    ))

    private fun coordParam(desc: String) = JsonObject(mapOf(
        "type" to JsonPrimitive("number"),
        "description" to JsonPrimitive(desc),
    ))
}
