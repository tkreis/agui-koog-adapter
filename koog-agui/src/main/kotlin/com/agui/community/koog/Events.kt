package com.agui.community.koog

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement

/**
 * AG-UI protocol events (spec 1.0). The `type` field is the serial name of each subclass.
 *
 * Only the events this adapter emits are modelled; see the AG-UI spec for the full list.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
public sealed interface AgUiEvent

// ---------- Lifecycle ----------

@Serializable
@SerialName("RUN_STARTED")
public data class RunStartedEvent(
    val threadId: String,
    val runId: String,
    val parentRunId: String? = null,
) : AgUiEvent

@Serializable
@SerialName("RUN_FINISHED")
public data class RunFinishedEvent(
    val threadId: String,
    val runId: String,
    val result: JsonElement? = null,
    val outcome: RunOutcome? = null,
) : AgUiEvent

@Serializable
@SerialName("RUN_ERROR")
public data class RunErrorEvent(
    val message: String,
    val code: String? = null,
) : AgUiEvent

/** Outcome carried by [RunFinishedEvent]. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
public sealed interface RunOutcome {
    /** The run ended normally; [pendingToolCallIds] lists frontend tool calls the client must answer. */
    @Serializable
    @SerialName("success")
    public data class Success(val pendingToolCallIds: List<String>? = null) : RunOutcome
}

// ---------- Text messages ----------

@Serializable
@SerialName("TEXT_MESSAGE_START")
public data class TextMessageStartEvent(
    val messageId: String,
    val role: String = "assistant",
) : AgUiEvent

@Serializable
@SerialName("TEXT_MESSAGE_CONTENT")
public data class TextMessageContentEvent(
    val messageId: String,
    val delta: String,
) : AgUiEvent

@Serializable
@SerialName("TEXT_MESSAGE_END")
public data class TextMessageEndEvent(val messageId: String) : AgUiEvent

// ---------- Reasoning ----------

@Serializable
@SerialName("REASONING_START")
public data class ReasoningStartEvent(val messageId: String) : AgUiEvent

@Serializable
@SerialName("REASONING_MESSAGE_START")
public data class ReasoningMessageStartEvent(
    val messageId: String,
    val role: String = "reasoning",
) : AgUiEvent

@Serializable
@SerialName("REASONING_MESSAGE_CONTENT")
public data class ReasoningMessageContentEvent(
    val messageId: String,
    val delta: String,
) : AgUiEvent

@Serializable
@SerialName("REASONING_MESSAGE_END")
public data class ReasoningMessageEndEvent(val messageId: String) : AgUiEvent

@Serializable
@SerialName("REASONING_END")
public data class ReasoningEndEvent(val messageId: String) : AgUiEvent

// ---------- Tool calls ----------

@Serializable
@SerialName("TOOL_CALL_START")
public data class ToolCallStartEvent(
    val toolCallId: String,
    val toolCallName: String,
    val parentMessageId: String? = null,
) : AgUiEvent

@Serializable
@SerialName("TOOL_CALL_ARGS")
public data class ToolCallArgsEvent(
    val toolCallId: String,
    val delta: String,
) : AgUiEvent

@Serializable
@SerialName("TOOL_CALL_END")
public data class ToolCallEndEvent(val toolCallId: String) : AgUiEvent

@Serializable
@SerialName("TOOL_CALL_RESULT")
public data class ToolCallResultEvent(
    val messageId: String,
    val toolCallId: String,
    val content: String,
    val role: String = "tool",
) : AgUiEvent

// ---------- State ----------

@Serializable
@SerialName("STATE_SNAPSHOT")
public data class StateSnapshotEvent(val snapshot: JsonElement) : AgUiEvent

/** Server-Sent Events framing expected by `@ag-ui/client`: one `data:` line per event, LF only. */
public object SseEncoder {
    public const val CONTENT_TYPE: String = "text/event-stream"

    public fun encode(event: AgUiEvent): String =
        "data: " + AgUiJson.encodeToString(AgUiEvent.serializer(), event) + "\n\n"
}
