package com.agui.community.koog

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
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

/** Body of an AG-UI run request. */
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
)

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
 * A conversation message. Decoding is lenient: roles this library does not understand
 * (for example `activity`) decode to [UnknownMessage] instead of failing the run.
 */
@Serializable(with = AgUiMessageSerializer::class)
public sealed interface AgUiMessage {
    public val id: String
    public val role: String
}

@Serializable
public data class SystemMessage(
    override val id: String,
    val content: String,
    val name: String? = null,
    override val role: String = "system",
) : AgUiMessage

@Serializable
public data class DeveloperMessage(
    override val id: String,
    val content: String,
    val name: String? = null,
    override val role: String = "developer",
) : AgUiMessage

/** A user message. [content] is either a JSON string or an array of content parts. */
@Serializable
public data class UserMessage(
    override val id: String,
    val content: JsonElement,
    val name: String? = null,
    override val role: String = "user",
) : AgUiMessage {
    public constructor(id: String, content: String) : this(id, JsonPrimitive(content))
}

@Serializable
public data class AssistantMessage(
    override val id: String,
    val content: String? = null,
    val toolCalls: List<AgUiToolCall>? = null,
    val name: String? = null,
    override val role: String = "assistant",
) : AgUiMessage

@Serializable
public data class ToolMessage(
    override val id: String,
    val toolCallId: String,
    val content: String = "",
    val error: String? = null,
    override val role: String = "tool",
) : AgUiMessage

/** Any message with a role this library does not model; kept as raw JSON. */
@Serializable
public data class UnknownMessage(
    override val id: String = "",
    override val role: String = "",
) : AgUiMessage

internal object AgUiMessageSerializer : JsonContentPolymorphicSerializer<AgUiMessage>(AgUiMessage::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<AgUiMessage> =
        when (element.jsonObject["role"]?.jsonPrimitive?.contentOrNull) {
            "system" -> SystemMessage.serializer()
            "developer" -> DeveloperMessage.serializer()
            "user" -> UserMessage.serializer()
            "assistant" -> AssistantMessage.serializer()
            "tool" -> ToolMessage.serializer()
            else -> UnknownMessage.serializer()
        }
}

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

/** Plain text of a user message, parts joined by newlines. */
public fun UserMessage.textContent(): String = textParts().joinToString("\n")
