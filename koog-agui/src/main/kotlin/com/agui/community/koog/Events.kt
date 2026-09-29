package com.agui.community.koog

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * AG-UI protocol events (spec 1.0). The `type` field is the serial name of each subclass.
 *
 * Modelled: the events this adapter emits, plus `CUSTOM`, `ACTIVITY_SNAPSHOT` and `MESSAGES_SNAPSHOT` for
 * event sources that need them. See the AG-UI spec for the full list.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
public sealed interface AgUiEvent {
    /** When the event was created; by convention milliseconds since the Unix epoch. Omitted when `null`. */
    public val timestamp: Long?

    /** Extra information attached to the event. Omitted when `null`; values inside may be JSON `null`. */
    public val metadata: JsonObject?
}

// ---------- Lifecycle ----------

@Serializable
@SerialName("RUN_STARTED")
public data class RunStartedEvent(
    val threadId: String,
    val runId: String,
    val parentRunId: String? = null,
    override val timestamp: Long? = null,
    override val metadata: JsonObject? = null,
) : AgUiEvent

/**
 * Closes a run that did not fail. [result] is the run's return value (any JSON); an absent [outcome] means
 * [RunOutcome.Success].
 */
@Serializable
@SerialName("RUN_FINISHED")
public data class RunFinishedEvent(
    val threadId: String,
    val runId: String,
    val result: JsonElement? = null,
    val outcome: RunOutcome? = null,
    override val timestamp: Long? = null,
    override val metadata: JsonObject? = null,
) : AgUiEvent

@Serializable
@SerialName("RUN_ERROR")
public data class RunErrorEvent(
    val message: String,
    val code: String? = null,
    override val timestamp: Long? = null,
    override val metadata: JsonObject? = null,
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

    /**
     * The run is paused until a later run answers every interrupt through [RunAgentInput.resume].
     * Needs at least one interrupt.
     */
    @Serializable
    @SerialName("interrupt")
    public data class Interrupt(val interrupts: List<AgUiInterrupt>) : RunOutcome {
        init {
            require(interrupts.isNotEmpty()) { "An interrupt outcome needs at least one interrupt" }
        }
    }

    /** The run was stopped before it completed and did not fail. The next run is a new run, not a resume. */
    @Serializable
    @SerialName("cancelled")
    public data object Cancelled : RunOutcome
}

/**
 * Something a run needs from outside before it can continue, such as an approval. A [ResumeEntry] answers it
 * by [id].
 *
 * @property reason why the run stopped; an open string (for example `"tool_approval"`).
 * @property message human-readable prompt for whoever answers.
 * @property toolCallId the tool call this interrupt concerns, when it is a tool approval.
 * @property responseSchema JSON Schema of the expected answer, so a client can build a form for it.
 * @property expiresAt when the interrupt stops being answerable; by convention ISO 8601.
 */
@Serializable
public data class AgUiInterrupt(
    val id: String,
    val reason: String,
    val message: String? = null,
    val toolCallId: String? = null,
    val responseSchema: JsonObject? = null,
    val expiresAt: String? = null,
    val metadata: JsonObject? = null,
)

// ---------- Text messages ----------

@Serializable
@SerialName("TEXT_MESSAGE_START")
public data class TextMessageStartEvent(
    val messageId: String,
    val role: String = "assistant",
    override val timestamp: Long? = null,
    override val metadata: JsonObject? = null,
) : AgUiEvent

@Serializable
@SerialName("TEXT_MESSAGE_CONTENT")
public data class TextMessageContentEvent(
    val messageId: String,
    val delta: String,
    override val timestamp: Long? = null,
    override val metadata: JsonObject? = null,
) : AgUiEvent

@Serializable
@SerialName("TEXT_MESSAGE_END")
public data class TextMessageEndEvent(
    val messageId: String,
    override val timestamp: Long? = null,
    override val metadata: JsonObject? = null,
) : AgUiEvent

// ---------- Reasoning ----------

@Serializable
@SerialName("REASONING_START")
public data class ReasoningStartEvent(
    val messageId: String,
    override val timestamp: Long? = null,
    override val metadata: JsonObject? = null,
) : AgUiEvent

@Serializable
@SerialName("REASONING_MESSAGE_START")
public data class ReasoningMessageStartEvent(
    val messageId: String,
    val role: String = "reasoning",
    override val timestamp: Long? = null,
    override val metadata: JsonObject? = null,
) : AgUiEvent

@Serializable
@SerialName("REASONING_MESSAGE_CONTENT")
public data class ReasoningMessageContentEvent(
    val messageId: String,
    val delta: String,
    override val timestamp: Long? = null,
    override val metadata: JsonObject? = null,
) : AgUiEvent

@Serializable
@SerialName("REASONING_MESSAGE_END")
public data class ReasoningMessageEndEvent(
    val messageId: String,
    override val timestamp: Long? = null,
    override val metadata: JsonObject? = null,
) : AgUiEvent

@Serializable
@SerialName("REASONING_END")
public data class ReasoningEndEvent(
    val messageId: String,
    override val timestamp: Long? = null,
    override val metadata: JsonObject? = null,
) : AgUiEvent

// ---------- Tool calls ----------

@Serializable
@SerialName("TOOL_CALL_START")
public data class ToolCallStartEvent(
    val toolCallId: String,
    val toolCallName: String,
    val parentMessageId: String? = null,
    override val timestamp: Long? = null,
    override val metadata: JsonObject? = null,
) : AgUiEvent

@Serializable
@SerialName("TOOL_CALL_ARGS")
public data class ToolCallArgsEvent(
    val toolCallId: String,
    val delta: String,
    override val timestamp: Long? = null,
    override val metadata: JsonObject? = null,
) : AgUiEvent

@Serializable
@SerialName("TOOL_CALL_END")
public data class ToolCallEndEvent(
    val toolCallId: String,
    override val timestamp: Long? = null,
    override val metadata: JsonObject? = null,
) : AgUiEvent

@Serializable
@SerialName("TOOL_CALL_RESULT")
public data class ToolCallResultEvent(
    val messageId: String,
    val toolCallId: String,
    val content: String,
    val role: String = "tool",
    override val timestamp: Long? = null,
    override val metadata: JsonObject? = null,
) : AgUiEvent

// ---------- State and snapshots ----------

@Serializable
@SerialName("STATE_SNAPSHOT")
public data class StateSnapshotEvent(
    val snapshot: JsonElement,
    override val timestamp: Long? = null,
    override val metadata: JsonObject? = null,
) : AgUiEvent

/**
 * The complete, ordered list of messages the producer owns. The client replaces messages with the same id in
 * place and keeps messages it holds on its own.
 */
@Serializable
@SerialName("MESSAGES_SNAPSHOT")
public data class MessagesSnapshotEvent(
    val messages: List<AgUiMessage>,
    override val timestamp: Long? = null,
    override val metadata: JsonObject? = null,
) : AgUiEvent

// ---------- Activity ----------

/**
 * Creates the [ActivityMessage] [messageId], or overwrites it when it exists. [replace] `false` leaves an
 * existing message untouched (it is not a merge); absent means overwrite.
 */
@Serializable
@SerialName("ACTIVITY_SNAPSHOT")
public data class ActivitySnapshotEvent(
    val messageId: String,
    val activityType: String,
    val content: JsonObject,
    val replace: Boolean? = null,
    override val timestamp: Long? = null,
    override val metadata: JsonObject? = null,
) : AgUiEvent

// ---------- Custom ----------

/** An application's own event: [name] routes it, [value] is any JSON. The protocol gives it no meaning. */
@Serializable
@SerialName("CUSTOM")
public data class CustomEvent(
    val name: String,
    val value: JsonElement,
    override val timestamp: Long? = null,
    override val metadata: JsonObject? = null,
) : AgUiEvent
