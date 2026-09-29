package com.agui.community.koog

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
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
 * @property payload the answer the interrupt asked for; any JSON value.
 * @property metadata envelope information about the answer (signatures, routing keys), as opposed to [payload].
 */
@Serializable
public data class ResumeEntry(
    val interruptId: String,
    val status: ResumeStatus,
    val payload: JsonElement? = null,
    val metadata: JsonObject? = null,
)

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
 * A conversation message, discriminated by [role]. Decoding is lenient: roles this library does not model
 * (for example `reasoning`) and malformed `activity` messages decode to [UnknownMessage] instead of failing
 * the run. Encoding writes each message with its [role], so messages can be sent in a [MessagesSnapshotEvent].
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
    override val metadata: JsonObject? = null,
    override val role: String = "system",
) : AgUiMessage

@Serializable
public data class DeveloperMessage(
    override val id: String,
    val content: String,
    val name: String? = null,
    override val metadata: JsonObject? = null,
    override val role: String = "developer",
) : AgUiMessage

/** A user message. [content] is either a JSON string or an array of content parts. */
@Serializable
public data class UserMessage(
    override val id: String,
    val content: JsonElement,
    val name: String? = null,
    override val metadata: JsonObject? = null,
    override val role: String = "user",
) : AgUiMessage {
    public constructor(id: String, content: String, name: String? = null, metadata: JsonObject? = null) :
        this(id, JsonPrimitive(content), name, metadata)
}

@Serializable
public data class AssistantMessage(
    override val id: String,
    val content: String? = null,
    val toolCalls: List<AgUiToolCall>? = null,
    val name: String? = null,
    override val metadata: JsonObject? = null,
    override val role: String = "assistant",
) : AgUiMessage

@Serializable
public data class ToolMessage(
    override val id: String,
    val toolCallId: String,
    val content: String = "",
    val error: String? = null,
    override val metadata: JsonObject? = null,
    override val role: String = "tool",
) : AgUiMessage

/**
 * Structured progress that is not conversation content (for example a step the client renders as its own
 * widget). Usually created and updated with [ActivitySnapshotEvent]; [content] is open by key.
 */
@Serializable
public data class ActivityMessage(
    override val id: String,
    val activityType: String,
    val content: JsonObject,
    override val metadata: JsonObject? = null,
    override val role: String = "activity",
) : AgUiMessage

/**
 * Any message this library does not model. Only [id] and [role] are kept (nothing else is read, so it never
 * fails decoding); it is not meant to be sent back to a client.
 */
@Serializable
public data class UnknownMessage(
    override val id: String = "",
    override val role: String = "",
) : AgUiMessage {
    override val metadata: JsonObject? get() = null
}

internal object AgUiMessageSerializer : JsonContentPolymorphicSerializer<AgUiMessage>(AgUiMessage::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<AgUiMessage> =
        when (element.jsonObject["role"]?.jsonPrimitive?.contentOrNull) {
            "system" -> SystemMessage.serializer()
            "developer" -> DeveloperMessage.serializer()
            "user" -> UserMessage.serializer()
            "assistant" -> AssistantMessage.serializer()
            "tool" -> ToolMessage.serializer()
            "activity" -> if (element.isWellFormedActivity()) ActivityMessage.serializer() else UnknownMessage.serializer()
            else -> UnknownMessage.serializer()
        }
}

private fun JsonElement.isWellFormedActivity(): Boolean =
    (jsonObject["activityType"] as? JsonPrimitive)?.isString == true && jsonObject["content"] is JsonObject

/** Text of each user content part; non-text parts are replaced by a short placeholder. */
public fun UserMessage.textParts(): List<String> = when (val c = content) {
    is JsonPrimitive -> listOf(c.contentOrNull.orEmpty())
    is JsonArray -> c.map { part ->
        val obj = part as? JsonObject ?: return@map part.toString()
        when (val type = obj["type"]?.jsonPrimitive?.contentOrNull) {
            "text" -> obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
            else -> "[${type ?: "unknown"} attachment omitted]"
        }
    }
    else -> listOf(c.toString())
}
