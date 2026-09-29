package com.agui.community.koog

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo

/**
 * Converts AG-UI conversation history into Koog messages.
 *
 * - `system` / `developer` → [Message.System]
 * - `user` → [Message.User] with one [MessagePart.Text] per content part (non-text parts become placeholders)
 * - `assistant` → [Message.Assistant] with optional text and [MessagePart.Tool.Call]s
 * - consecutive `tool` messages → one [Message.User] holding [MessagePart.Tool.Result]s (content parts joined
 *   by newlines, non-text parts become placeholders)
 * - `activity`, `reasoning` and other roles are dropped
 */
public fun List<AgUiMessage>.toKoogMessages(): List<Message> {
    val toolNames = mutableMapOf<String, String>()
    val result = mutableListOf<Message>()
    val pendingResults = mutableListOf<MessagePart.Tool.Result>()

    fun flushResults() {
        if (pendingResults.isNotEmpty()) {
            result += Message.User(pendingResults.toList(), RequestMetaInfo.Empty)
            pendingResults.clear()
        }
    }

    for (message in this) {
        if (message !is ToolMessage) flushResults()
        when (message) {
            is SystemMessage -> result += Message.System(message.content, RequestMetaInfo.Empty, id = message.id)
            is DeveloperMessage -> result += Message.System(message.content, RequestMetaInfo.Empty, id = message.id)
            is UserMessage -> result += Message.User(message.textParts().map { MessagePart.Text(it) }, RequestMetaInfo.Empty, id = message.id)
            is AssistantMessage -> {
                val parts = buildList<MessagePart.ResponsePart> {
                    message.content?.takeIf { it.isNotEmpty() }?.let { add(MessagePart.Text(it)) }
                    message.toolCalls.orEmpty().forEach { call ->
                        toolNames[call.id] = call.function.name
                        add(MessagePart.Tool.Call(call.id, call.function.name, call.function.arguments.ifBlank { "{}" }))
                    }
                }
                if (parts.isNotEmpty()) {
                    result += Message.Assistant(parts, ResponseMetaInfo.Empty, id = message.id)
                }
            }
            is ToolMessage -> pendingResults += MessagePart.Tool.Result(
                id = message.toolCallId,
                tool = toolNames[message.toolCallId] ?: "unknown",
                output = message.error?.let { "Error: $it" } ?: message.textParts().joinToString("\n"),
                isError = message.error != null,
            )
            is ActivityMessage, is ReasoningMessage, is UnknownMessage -> Unit
        }
    }
    flushResults()
    return result
}
