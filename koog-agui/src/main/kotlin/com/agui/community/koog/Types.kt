package com.agui.community.koog

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * JSON configuration for the AG-UI wire format.
 *
 * Absent optional fields are omitted instead of written as `null`, because the TypeScript client
 * rejects `null` for optional fields. Unknown fields are ignored so newer clients can talk to this server.
 */
@OptIn(ExperimentalSerializationApi::class)
public val AgUiJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/**
 * Body of an AG-UI run request.
 *
 * @property resume answers to the interrupts that ended a previous run ([RunOutcome.Interrupt]), when this
 *   run continues from one.
 */
@Serializable
public data class RunAgentInput(
    val threadId: String,
    val runId: String,
    val messages: List<AgUiMessage> = emptyList(),
    val tools: List<AgUiTool> = emptyList(),
    val context: List<AgUiContext> = emptyList(),
    val state: JsonElement? = null,
    val forwardedProps: JsonElement? = null,
    val parentRunId: String? = null,
    val resume: List<ResumeEntry> = emptyList(),
)

/**
 * An answer to one [AgUiInterrupt], sent on the run that continues from it.
 *
 * @property payload the answer the interrupt asked for; any JSON value except `null` (the protocol rejects an
 *   explicit null here; leave it out instead).
 * @property metadata envelope information about the answer (signatures, routing keys), as opposed to [payload].
 */
@Serializable
public data class ResumeEntry(
    val interruptId: String,
    val status: ResumeStatus,
    val payload: JsonElement? = null,
    val metadata: JsonObject? = null,
) {
    init {
        require(payload !is JsonNull) { "payload must not be JSON null; use null to omit it" }
    }
}

/** Whether an interrupt was answered or abandoned. */
@Serializable
public enum class ResumeStatus {
    @SerialName("resolved")
    Resolved,

    @SerialName("cancelled")
    Cancelled,
}

/** A tool implemented by the client (frontend tool). */
@Serializable
public data class AgUiTool(
    val name: String,
    val description: String = "",
    val parameters: JsonObject? = null,
)

/** A piece of application context provided by the client. */
@Serializable
public data class AgUiContext(
    val description: String,
    val value: String,
)

/** A tool call inside an assistant message. */
@Serializable
public data class AgUiToolCall(
    val id: String,
    val type: String = "function",
    val function: AgUiFunctionCall,
)

/** Function name and JSON-encoded arguments of a tool call. */
@Serializable
public data class AgUiFunctionCall(
    val name: String,
    val arguments: String = "{}",
)

/**
 * A conversation message, discriminated by [role]. Encoding writes each message with its [role], so messages
 * can be sent in a [MessagesSnapshotEvent].
 *
 * Decoding is lenient where the adapter does not depend on the message: roles this library does not model,
 * and `activity` or `reasoning` messages that do not match their schema, decode to [UnknownMessage] instead
 * of failing the run. Fields the model does not cover (for example `encryptedValue` outside reasoning
 * messages, or `subagentRunId`) are ignored.
 */
@Serializable(with = AgUiMessageSerializer::class)
public sealed interface AgUiMessage {
    public val id: String
    public val role: String

    /** Extra information attached to the message. Omitted when `null`. */
    public val metadata: JsonObject?
}

@Serializable
public data class SystemMessage(
    override val id: String,
    val content: String,
    val name: String? = null,
    override val role: String = "system",
    override val metadata: JsonObject? = null,
) : AgUiMessage

@Serializable
public data class DeveloperMessage(
    override val id: String,
    val content: String,
    val name: String? = null,
    override val role: String = "developer",
    override val metadata: JsonObject? = null,
) : AgUiMessage

/** A user message. [content] is either a JSON string or an array of content parts. */
@Serializable
public data class UserMessage(
    override val id: String,
    val content: JsonElement,
    val name: String? = null,
    override val role: String = "user",
    override val metadata: JsonObject? = null,
) : AgUiMessage {
    public constructor(
        id: String,
        content: String,
        name: String? = null,
        role: String = "user",
        metadata: JsonObject? = null,
    ) : this(id, JsonPrimitive(content), name, role, metadata)
}

@Serializable
public data class AssistantMessage(
    override val id: String,
    val content: String? = null,
    val toolCalls: List<AgUiToolCall>? = null,
    val name: String? = null,
    override val role: String = "assistant",
    override val metadata: JsonObject? = null,
) : AgUiMessage

/** A tool result. [content] is either a JSON string or an array of content parts, like [UserMessage.content]. */
@Serializable
public data class ToolMessage(
    override val id: String,
    val toolCallId: String,
    val content: JsonElement = JsonPrimitive(""),
    val error: String? = null,
    override val role: String = "tool",
    override val metadata: JsonObject? = null,
) : AgUiMessage {
    public constructor(
        id: String,
        toolCallId: String,
        content: String,
        error: String? = null,
        role: String = "tool",
        metadata: JsonObject? = null,
    ) : this(id, toolCallId, JsonPrimitive(content), error, role, metadata)
}

/**
 * Structured progress that is not conversation content (for example a step the client renders as its own
 * widget). Usually created and updated with [ActivitySnapshotEvent]; [content] is open by key.
 */
@Serializable
public data class ActivityMessage(
    override val id: String,
    val activityType: String,
    val content: JsonObject,
    override val role: String = "activity",
    override val metadata: JsonObject? = null,
) : AgUiMessage

/** A span of the agent's reasoning. [encryptedValue] is a provider's opaque reasoning artefact. */
@Serializable
public data class ReasoningMessage(
    override val id: String,
    val content: String,
    val encryptedValue: String? = null,
    override val role: String = "reasoning",
    override val metadata: JsonObject? = null,
) : AgUiMessage

/**
 * Any message this library does not model, or a lenient message that did not match its schema. Only [id] and
 * [role] are kept, so it is not meant to be sent back to a client.
 */
@Serializable
public data class UnknownMessage(
    override val id: String = "",
    override val role: String = "",
) : AgUiMessage {
    override val metadata: JsonObject? get() = null
}

/**
 * Decodes by `role`. `system`, `developer`, `user`, `assistant` and `tool` must match their schema, because the
 * adapter hands them to the model; everything else falls back to [UnknownMessage] rather than failing the run.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object AgUiMessageSerializer : KSerializer<AgUiMessage> {
    override val descriptor: SerialDescriptor =
        SerialDescriptor("com.agui.community.koog.AgUiMessage", JsonObject.serializer().descriptor)

    override fun deserialize(decoder: Decoder): AgUiMessage {
        val input = decoder as? JsonDecoder ?: throw SerializationException("AgUiMessage can only be decoded from JSON")
        val json = input.json
        val element = input.decodeJsonElement() as? JsonObject
            ?: throw SerializationException("An AG-UI message must be a JSON object")
        val role = element.string("role")
        val strict = when (role) {
            "system" -> SystemMessage.serializer()
            "developer" -> DeveloperMessage.serializer()
            "user" -> UserMessage.serializer()
            "assistant" -> AssistantMessage.serializer()
            "tool" -> ToolMessage.serializer()
            else -> null
        }
        if (strict != null) return json.decodeFromJsonElement(strict, element)
        val lenient = when (role) {
            "activity" -> ActivityMessage.serializer()
            "reasoning" -> ReasoningMessage.serializer()
            else -> null
        }
        return lenient?.let {
            try {
                json.decodeFromJsonElement(it, element)
            } catch (e: IllegalArgumentException) { // includes SerializationException
                null
            }
        } ?: UnknownMessage(element.string("id").orEmpty(), role.orEmpty())
    }

    override fun serialize(encoder: Encoder, value: AgUiMessage) {
        when (value) {
            is SystemMessage -> encoder.encodeSerializableValue(SystemMessage.serializer(), value)
            is DeveloperMessage -> encoder.encodeSerializableValue(DeveloperMessage.serializer(), value)
            is UserMessage -> encoder.encodeSerializableValue(UserMessage.serializer(), value)
            is AssistantMessage -> encoder.encodeSerializableValue(AssistantMessage.serializer(), value)
            is ToolMessage -> encoder.encodeSerializableValue(ToolMessage.serializer(), value)
            is ActivityMessage -> encoder.encodeSerializableValue(ActivityMessage.serializer(), value)
            is ReasoningMessage -> encoder.encodeSerializableValue(ReasoningMessage.serializer(), value)
            is UnknownMessage -> encoder.encodeSerializableValue(UnknownMessage.serializer(), value)
        }
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/** Text of each user content part; non-text parts are replaced by a short placeholder. */
public fun UserMessage.textParts(): List<String> = content.textParts()

/** Text of each tool result content part; non-text parts are replaced by a short placeholder. */
public fun ToolMessage.textParts(): List<String> = content.textParts()

private fun JsonElement.textParts(): List<String> = when (this) {
    is JsonPrimitive -> listOf(contentOrNull.orEmpty())
    is JsonArray -> map { part ->
        val obj = part as? JsonObject ?: return@map part.toString()
        when (val type = obj["type"]?.jsonPrimitive?.contentOrNull) {
            "text" -> obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
            else -> "[${type ?: "unknown"} attachment omitted]"
        }
    }
    else -> listOf(toString())
}
