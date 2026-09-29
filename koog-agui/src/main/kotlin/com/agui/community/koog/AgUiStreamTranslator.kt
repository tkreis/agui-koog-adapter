package com.agui.community.koog

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import io.github.oshai.kotlinlogging.KotlinLogging

/** A tool call the model made during one turn, with the id announced to the AG-UI client. */
public data class CollectedToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

/**
 * Translates the Koog [StreamFrame]s of one LLM turn into AG-UI events.
 *
 * Koog's stream is segment-based: text, reasoning and tool-call segments never interleave, and each
 * segment ends with a `*Complete` frame. The translator keeps AG-UI's start/content/end invariants even
 * when a provider only sends complete frames, sends a tool call without an id, or omits the tool name on
 * the first delta.
 *
 * Tool calls whose names are in [silentTools] are collected but not announced (used for `update_state`).
 *
 * Not thread-safe; use one instance per turn.
 */
public class AgUiStreamTranslator(
    public val messageId: String,
    private val silentTools: Set<String> = emptySet(),
    private val newId: () -> String,
) {
    private var textSegments = 0
    private var openTextId: String? = null
    private val text = StringBuilder()

    private var reasoningSegments = 0
    private var openReasoningId: String? = null
    private val reasoning = StringBuilder()

    /** Parts of the assistant message for the Koog prompt, in arrival order. */
    private val parts = mutableListOf<MessagePart.ResponsePart>()

    private var pending: PendingCall? = null
    private val calls = mutableListOf<CollectedToolCall>()

    private var finishReason: String? = null
    private var metaInfo: ResponseMetaInfo = ResponseMetaInfo.Empty

    /** Tool calls completed so far, in call order. */
    public val toolCalls: List<CollectedToolCall> get() = calls.toList()

    public fun onFrame(frame: StreamFrame): List<AgUiEvent> = buildList {
        when (frame) {
            is StreamFrame.TextDelta -> {
                closeReasoning(); closeToolCall()
                val id = openText()
                if (frame.text.isNotEmpty()) {
                    text.append(frame.text)
                    add(TextMessageContentEvent(id, frame.text))
                }
            }

            is StreamFrame.TextComplete -> {
                if (openTextId == null && frame.text.isNotEmpty()) {
                    closeReasoning(); closeToolCall()
                    text.append(frame.text)
                    add(TextMessageContentEvent(openText(), frame.text))
                }
                closeText()
            }

            is StreamFrame.ReasoningDelta -> {
                closeText(); closeToolCall()
                val delta = frame.text ?: frame.summary
                val id = openReasoning()
                if (!delta.isNullOrEmpty()) {
                    reasoning.append(delta)
                    add(ReasoningMessageContentEvent(id, delta))
                }
            }

            is StreamFrame.ReasoningComplete -> {
                if (openReasoningId == null) {
                    val content = frame.content.joinToString("").ifEmpty { frame.summary.orEmpty().joinToString("") }
                    if (content.isNotEmpty()) {
                        closeText(); closeToolCall()
                        add(ReasoningMessageContentEvent(openReasoning(), content))
                    }
                }
                // Keep the provider's reasoning (incl. encrypted signature) so it can be replayed on the next turn.
                closeReasoning(MessagePart.Reasoning(frame.content, frame.summary, frame.encrypted, frame.id))
            }

            is StreamFrame.ToolCallDelta -> {
                closeText(); closeReasoning()
                val call = pending?.takeIf { it.continuedBy(frame.id, frame.index) } ?: startCall(frame.id, frame.index)
                if (call.name == null && !frame.name.isNullOrEmpty()) call.name = frame.name
                frame.content?.let(call::appendArgs)
                announce(call)
            }

            is StreamFrame.ToolCallComplete -> {
                closeText(); closeReasoning()
                // Providers that skip deltas only send the complete frame.
                val call = pending?.takeIf { it.continuedBy(frame.id, null) } ?: startCall(frame.id, frame.index)
                if (call.name == null) call.name = frame.name
                if (call.args.isEmpty()) call.appendArgs(frame.content)
                closeToolCall()
            }

            is StreamFrame.End -> {
                finishReason = frame.finishReason
                metaInfo = frame.metaInfo
                closeAll()
            }
        }
    }

    /** Closes everything still open. Call once when the stream ends. */
    public fun finish(): List<AgUiEvent> = buildList { closeAll() }

    /**
     * The assistant message of this turn for the Koog prompt. Tool call ids match the ids announced
     * to the client, so the client's tool results on the next run line up with the history.
     */
    public fun assistantMessage(): Message.Assistant? =
        if (parts.isEmpty()) null else Message.Assistant(parts.toList(), metaInfo, finishReason, id = messageId)

    private fun MutableList<AgUiEvent>.openText(): String {
        openTextId?.let { return it }
        val id = if (textSegments == 0) messageId else "$messageId-$textSegments"
        textSegments++
        openTextId = id
        add(TextMessageStartEvent(id))
        return id
    }

    private fun MutableList<AgUiEvent>.closeText() {
        val id = openTextId ?: return
        add(TextMessageEndEvent(id))
        if (text.isNotEmpty()) parts += MessagePart.Text(text.toString())
        text.clear()
        openTextId = null
    }

    private fun MutableList<AgUiEvent>.openReasoning(): String {
        openReasoningId?.let { return it }
        val id = "$messageId-reasoning-$reasoningSegments"
        reasoningSegments++
        openReasoningId = id
        add(ReasoningStartEvent(id))
        add(ReasoningMessageStartEvent(id))
        return id
    }

    private fun MutableList<AgUiEvent>.closeReasoning(complete: MessagePart.Reasoning? = null) {
        openReasoningId?.let {
            add(ReasoningMessageEndEvent(it))
            add(ReasoningEndEvent(it))
        }
        val part = complete ?: reasoning.takeIf { it.isNotEmpty() }?.let { MessagePart.Reasoning(it.toString()) }
        part?.let { parts += it }
        reasoning.clear()
        openReasoningId = null
    }

    /** Emits START once the name is known, then any buffered argument fragments. */
    private fun MutableList<AgUiEvent>.announce(call: PendingCall) {
        val name = call.name ?: return
        if (name in silentTools) return
        if (!call.started) {
            add(ToolCallStartEvent(call.id, name, parentMessageId = messageId))
            call.started = true
        }
        if (call.unsentArgs.isNotEmpty()) {
            add(ToolCallArgsEvent(call.id, call.unsentArgs.toString()))
            call.unsentArgs.clear()
        }
    }

    private fun MutableList<AgUiEvent>.closeToolCall() {
        val call = pending ?: return
        pending = null
        // A call that never received a name cannot be executed or answered; drop it.
        val name = call.name?.takeIf { it.isNotEmpty() } ?: run {
            logger.warn { "Dropping tool call ${call.id} without a name (args: ${call.args.length} chars)" }
            return
        }
        announce(call)
        if (call.started) add(ToolCallEndEvent(call.id))
        val collected = CollectedToolCall(call.id, name, call.args.toString().ifBlank { "{}" })
        calls += collected
        parts += MessagePart.Tool.Call(collected.id, collected.name, collected.arguments)
    }

    private fun MutableList<AgUiEvent>.closeAll() {
        closeText(); closeReasoning(); closeToolCall()
    }

    private fun MutableList<AgUiEvent>.startCall(providerId: String?, index: Int?): PendingCall {
        closeToolCall()
        val id = providerId?.takeUnless { it.isBlank() }
        return PendingCall(id, id ?: newId(), index).also { pending = it }
    }

    private companion object {
        private val logger = KotlinLogging.logger { }
    }

    private class PendingCall(val providerId: String?, val id: String, val index: Int?) {
        var name: String? = null
        var started = false
        val args = StringBuilder()
        val unsentArgs = StringBuilder()

        /** A frame belongs to this call unless it carries a different provider id or stream index. */
        fun continuedBy(frameId: String?, frameIndex: Int?): Boolean =
            (frameId.isNullOrBlank() || providerId == null || frameId == providerId) &&
                (frameIndex == null || frameIndex == index)

        fun appendArgs(fragment: String) {
            args.append(fragment)
            unsentArgs.append(fragment)
        }
    }
}
