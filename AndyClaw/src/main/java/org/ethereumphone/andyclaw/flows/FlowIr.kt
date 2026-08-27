package org.ethereumphone.andyclaw.flows

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Flow IR — the compiled, replayable form of a UI task.
 *
 * This is `agent-os-design.md` §3's schema, key for key. The design doc writes it as
 * YAML; on disk it is the same document in JSON, because that is what the app already
 * parses and what [FlowCodec] can canonicalise byte-for-byte for content addressing.
 *
 * ```json
 * {
 *   "flow": "signal.send_to_existing_thread",
 *   "version": 3,
 *   "app": "org.thoughtcrime.securesms",
 *   "app_version_range": ">=7.2,<8.0",
 *   "params": ["contact_name", "body"],
 *   "preconditions": [ {"node_exists": {"view_id": "conversation_list"}} ],
 *   "steps": [
 *     {"tap": {"view_id": "search_button"}},
 *     {"type": {"target": {"view_id": "search_input"}, "value": "{{contact_name}}"}},
 *     {"wait_for": {"view_id": "search_result_item", "timeout_ms": 1500}},
 *     {"tap": {"view_id": "search_result_item", "index": 0}},
 *     {"assert": {"node_text_contains": "{{contact_name}}", "view_id": "toolbar_title"}},
 *     {"type": {"target": {"view_id": "compose_text"}, "value": "{{body}}"}},
 *     {"checkpoint": "send"},
 *     {"tap": {"view_id": "send_button"}}
 *   ],
 *   "postconditions": [ {"node_exists": {"view_id": "conversation_item_sent"}} ]
 * }
 * ```
 *
 * Two fields are additions the design doc's *rules* require but its example does not
 * show, and both are optional so the example above parses unchanged:
 *
 * - `expect_checksum` on a step is "perceptual checksum per step" ([NodeTreeChecksum]).
 *   The interpreter asserts it **before** the step acts; a mismatch aborts the replay
 *   rather than guessing.
 * - `effect` on a `tap` lets a compiler mark a step irreversible explicitly. It can
 *   only ever *raise* the classification — see [FlowStepEffects].
 *
 * A flow is executable code carrying the user's authority. Nothing here is trusted on
 * sight: [FlowValidator] gates what may compile and [FlowStore] refuses anything whose
 * content hash or HMAC does not verify.
 */
@Serializable
data class Flow(
    /** Stable id, `app.action` by convention — e.g. `signal.send_to_existing_thread`. */
    val flow: String,
    /** Bumped when the flow is recompiled against a changed UI. */
    val version: Int,
    /** The package this flow drives. */
    val app: String,
    /** npm-style range the flow was compiled against, e.g. `">=7.2,<8.0"`. */
    @SerialName("app_version_range") val appVersionRange: String,
    /** Names substitutable as `{{name}}` in step values. */
    val params: List<String> = emptyList(),
    val preconditions: List<Condition> = emptyList(),
    val steps: List<FlowStep> = emptyList(),
    val postconditions: List<Condition> = emptyList(),
) {
    /**
     * The tool this flow is registered as:
     * `signal.send_to_existing_thread` -> `flow_signal_send_to_existing_thread`.
     *
     * The prefix is reserved, and it is not decoration. `NativeSkillRegistry` treats
     * flows as a built-in skill, and built-ins do not get the name-collision protection
     * external skills do — an unprefixed flow called `send_sms` would quietly take over
     * the real one. Nothing else in the app claims `flow_`.
     */
    val toolName: String
        get() = TOOL_PREFIX + flow.lowercase().map { if (it.isLetterOrDigit()) it else '_' }
            .joinToString("").replace(Regex("_+"), "_").trim('_')

    companion object {
        const val TOOL_PREFIX = "flow_"
    }
}

// ══════════════════════════════════════════════════════════════════════
// Steps
// ══════════════════════════════════════════════════════════════════════

/** One opcode. `tap`, `type`, `wait_for`, `assert`, `checkpoint` — the design doc's five. */
@Serializable(with = FlowStepSerializer::class)
sealed interface FlowStep {
    /** The opcode key this step serialises under. */
    val opcode: String

    /**
     * Node-tree shape the step expects to find before it runs, if the compiler
     * recorded one. Mismatch aborts the replay.
     */
    val expectChecksum: String?
}

/**
 * Tap a node. `view_id` is the only selector a flow may compile with — [text], [x] and
 * [y] exist so a recorder can faithfully record what actually happened and
 * [FlowValidator] can then **reject** it. Text breaks on a locale change, coordinates
 * break on everything.
 */
@Serializable
data class TapStep(
    @SerialName("view_id") val viewId: String? = null,
    /** Which match to take when the view id repeats (a list row). */
    val index: Int? = null,
    /** Recorded-but-invalid selector: a text match. Fails validation. */
    val text: String? = null,
    /** Recorded-but-invalid selector: raw coordinates. Fails validation. */
    val x: Double? = null,
    /** Recorded-but-invalid selector: raw coordinates. Fails validation. */
    val y: Double? = null,
    /** Explicit effect. May only raise the classification — see [FlowStepEffects]. */
    val effect: String? = null,
    @SerialName("expect_checksum") override val expectChecksum: String? = null,
) : FlowStep {
    override val opcode: String get() = "tap"
}

/** Set text on an editable node. [value] may contain `{{param}}` placeholders. */
@Serializable
data class TypeStep(
    val target: Selector,
    val value: String,
    @SerialName("expect_checksum") override val expectChecksum: String? = null,
) : FlowStep {
    override val opcode: String get() = "type"
}

/** Poll until a node appears, or give up. Giving up aborts the flow. */
@Serializable
data class WaitForStep(
    @SerialName("view_id") val viewId: String? = null,
    @SerialName("node_text_contains") val nodeTextContains: String? = null,
    @SerialName("timeout_ms") val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    @SerialName("expect_checksum") override val expectChecksum: String? = null,
) : FlowStep {
    override val opcode: String get() = "wait_for"

    companion object {
        const val DEFAULT_TIMEOUT_MS = 2_000L
    }
}

/** Assert the screen is what the flow thinks it is. A failed assert aborts. */
@Serializable
data class AssertStep(
    @SerialName("view_id") val viewId: String? = null,
    @SerialName("node_text_contains") val nodeTextContains: String? = null,
    @SerialName("expect_checksum") override val expectChecksum: String? = null,
) : FlowStep {
    override val opcode: String get() = "assert"
}

/**
 * The irreversible boundary. `agent-os-design.md` §6: "The `checkpoint:` opcode in
 * Flow IR is how a flow declares where its irreversible boundary sits."
 *
 * Serialises as a bare string — `{"checkpoint": "send"}` — matching the design doc.
 */
@Serializable
data class CheckpointStep(val name: String) : FlowStep {
    override val opcode: String get() = "checkpoint"
    override val expectChecksum: String? get() = null
}

/** A node selector. `view_id` first, always. */
@Serializable
data class Selector(
    @SerialName("view_id") val viewId: String? = null,
    val index: Int? = null,
    /** Recorded-but-invalid: a text match. Fails validation. */
    val text: String? = null,
    /** Recorded-but-invalid: raw coordinates. Fails validation. */
    val x: Double? = null,
    /** Recorded-but-invalid: raw coordinates. Fails validation. */
    val y: Double? = null,
)

// ══════════════════════════════════════════════════════════════════════
// Conditions
// ══════════════════════════════════════════════════════════════════════

/**
 * A pre- or postcondition. "Every flow carries pre/postconditions. A flow that can't
 * assert it worked doesn't run."
 */
@Serializable(with = ConditionSerializer::class)
sealed interface Condition {
    val opcode: String
    val viewId: String?
}

@Serializable
data class NodeExists(
    @SerialName("view_id") override val viewId: String? = null,
    val index: Int? = null,
) : Condition {
    override val opcode: String get() = "node_exists"
}

@Serializable
data class NodeTextContains(
    @SerialName("view_id") override val viewId: String? = null,
    val value: String = "",
) : Condition {
    override val opcode: String get() = "node_text_contains"
}

// ══════════════════════════════════════════════════════════════════════
// Serializers — one key per element, exactly as the design doc writes it
// ══════════════════════════════════════════════════════════════════════

/**
 * `{"tap": {...}}`, `{"checkpoint": "send"}` — the opcode is the key, not a
 * `"type"` discriminator field, because that is the shape `agent-os-design.md` §3
 * specifies and the shape a person hand-reads most easily.
 */
object FlowStepSerializer : KSerializer<FlowStep> {

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("FlowStep")

    override fun serialize(encoder: Encoder, value: FlowStep) {
        val out = encoder as? JsonEncoder
            ?: throw SerializationException("FlowStep can only be written as JSON")
        val payload: JsonElement = when (value) {
            is TapStep -> out.json.encodeToJsonElement(TapStep.serializer(), value)
            is TypeStep -> out.json.encodeToJsonElement(TypeStep.serializer(), value)
            is WaitForStep -> out.json.encodeToJsonElement(WaitForStep.serializer(), value)
            is AssertStep -> out.json.encodeToJsonElement(AssertStep.serializer(), value)
            is CheckpointStep -> JsonPrimitive(value.name)
        }
        out.encodeJsonElement(buildJsonObject { put(value.opcode, payload) })
    }

    override fun deserialize(decoder: Decoder): FlowStep {
        val input = decoder as? JsonDecoder
            ?: throw SerializationException("FlowStep can only be read from JSON")
        val obj = input.decodeJsonElement() as? JsonObject
            ?: throw SerializationException("A flow step must be an object with one opcode key")
        if (obj.size != 1) {
            throw SerializationException(
                "A flow step must carry exactly one opcode, found ${obj.keys.joinToString()}"
            )
        }
        val (opcode, payload) = obj.entries.first()
        return when (opcode) {
            "tap" -> input.json.decodeFromJsonElement(TapStep.serializer(), payload)
            "type" -> input.json.decodeFromJsonElement(TypeStep.serializer(), payload)
            "wait_for" -> input.json.decodeFromJsonElement(WaitForStep.serializer(), payload)
            "assert" -> input.json.decodeFromJsonElement(AssertStep.serializer(), payload)
            // `checkpoint: send` is a bare string in the design doc; accept the object
            // form too so a compiler that writes {"checkpoint":{"name":"send"}} still loads.
            "checkpoint" -> CheckpointStep(
                (payload as? JsonPrimitive)?.content
                    ?: (payload as? JsonObject)?.get("name")?.jsonPrimitive?.content
                    ?: throw SerializationException("checkpoint needs a name")
            )
            else -> throw SerializationException(
                "Unknown flow opcode '$opcode'. The five opcodes are tap, type, wait_for, assert, checkpoint."
            )
        }
    }
}

/** `{"node_exists": {"view_id": "..."}}`. */
object ConditionSerializer : KSerializer<Condition> {

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Condition")

    override fun serialize(encoder: Encoder, value: Condition) {
        val out = encoder as? JsonEncoder
            ?: throw SerializationException("Condition can only be written as JSON")
        val payload: JsonElement = when (value) {
            is NodeExists -> out.json.encodeToJsonElement(NodeExists.serializer(), value)
            is NodeTextContains -> out.json.encodeToJsonElement(NodeTextContains.serializer(), value)
        }
        out.encodeJsonElement(buildJsonObject { put(value.opcode, payload) })
    }

    override fun deserialize(decoder: Decoder): Condition {
        val input = decoder as? JsonDecoder
            ?: throw SerializationException("Condition can only be read from JSON")
        val obj = input.decodeJsonElement() as? JsonObject
            ?: throw SerializationException("A condition must be an object with one key")
        if (obj.size != 1) {
            throw SerializationException(
                "A condition must carry exactly one key, found ${obj.keys.joinToString()}"
            )
        }
        val (opcode, payload) = obj.entries.first()
        return when (opcode) {
            "node_exists" -> input.json.decodeFromJsonElement(NodeExists.serializer(), payload)
            "node_text_contains" ->
                input.json.decodeFromJsonElement(NodeTextContains.serializer(), payload)
            else -> throw SerializationException(
                "Unknown condition '$opcode'. Supported: node_exists, node_text_contains."
            )
        }
    }
}
