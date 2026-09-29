package com.agui.community.koog

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toMessageResponse
import ai.koog.serialization.typeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlin.test.assertTrue
import kotlin.test.fail

val TestModel = LLModel(
    provider = LLMProvider.OpenAI,
    id = "scripted",
    capabilities = listOf(LLMCapability.Tools, LLMCapability.Completion),
    contextLength = 128_000L,
)

/** A [PromptExecutor] that replays one scripted list of stream frames per LLM call and records requests. */
class ScriptedExecutor(vararg turns: List<StreamFrame>) : PromptExecutor() {
    private val remaining = ArrayDeque(turns.toList())
    val prompts = mutableListOf<Prompt>()
    val tools = mutableListOf<List<ToolDescriptor>>()

    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> {
        prompts += prompt
        this.tools += tools
        val turn = remaining.removeFirstOrNull() ?: fail("No scripted turn left for request #${prompts.size}")
        return flow { turn.forEach { emit(it) } }
    }

    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant =
        executeStreaming(prompt, model, tools).toList().toMessageResponse()

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        ModerationResult(isHarmful = false, categories = emptyMap())

    override fun close() = Unit
}

fun textTurn(vararg chunks: String): List<StreamFrame> =
    chunks.map { StreamFrame.TextDelta(it) } +
        StreamFrame.TextComplete(chunks.joinToString("")) +
        StreamFrame.End("stop")

fun toolTurn(id: String, name: String, vararg argChunks: String): List<StreamFrame> =
    listOf(StreamFrame.ToolCallDelta(id, name, argChunks.firstOrNull(), 0)) +
        argChunks.drop(1).map { StreamFrame.ToolCallDelta(null, null, it, 0) } +
        StreamFrame.ToolCallComplete(id, name, argChunks.joinToString(""), 0) +
        StreamFrame.End("tool_calls")

object GetWeatherTool : SimpleTool<GetWeatherTool.Args>(
    argsType = typeToken<Args>(),
    name = "get_weather",
    description = "Get the weather for a location",
) {
    @Serializable
    data class Args(@property:LLMDescription("City name") val location: String)

    override suspend fun execute(args: Args): String = """{"location":"${args.location}","temperature":21}"""
}

fun counter(prefix: String = "id"): () -> String {
    var n = 0
    return { "$prefix-${++n}" }
}

/**
 * Checks the ordering rules that `@ag-ui/client`'s verifier enforces
 * (sdks/typescript/packages/client/src/verify/verify.ts).
 */
fun assertValidAgUiSequence(events: List<AgUiEvent>) {
    assertTrue(events.isNotEmpty(), "no events")
    val first = events.first()
    assertTrue(first is RunStartedEvent || first is RunErrorEvent, "first event must be RUN_STARTED, was $first")
    val openText = mutableSetOf<String>()
    val openTools = mutableSetOf<String>()
    val openReasoning = mutableSetOf<String>()
    var finished = false
    for (event in events.drop(1)) {
        if (finished) fail("event after terminal event: $event")
        when (event) {
            is RunStartedEvent -> fail("second RUN_STARTED while run active")
            is TextMessageStartEvent -> assertTrue(openText.add(event.messageId), "text ${event.messageId} already open")
            is TextMessageContentEvent -> assertTrue(event.messageId in openText, "content for closed text ${event.messageId}")
            is TextMessageEndEvent -> assertTrue(openText.remove(event.messageId), "end for closed text ${event.messageId}")
            is ToolCallStartEvent -> assertTrue(openTools.add(event.toolCallId), "tool ${event.toolCallId} already open")
            is ToolCallArgsEvent -> assertTrue(event.toolCallId in openTools, "args for closed tool ${event.toolCallId}")
            is ToolCallEndEvent -> assertTrue(openTools.remove(event.toolCallId), "end for closed tool ${event.toolCallId}")
            is ReasoningMessageStartEvent -> assertTrue(openReasoning.add(event.messageId))
            is ReasoningMessageContentEvent -> assertTrue(event.messageId in openReasoning)
            is ReasoningMessageEndEvent -> assertTrue(openReasoning.remove(event.messageId))
            is RunFinishedEvent -> {
                assertTrue(openText.isEmpty() && openTools.isEmpty() && openReasoning.isEmpty(), "RUN_FINISHED with open items")
                finished = true
            }
            is RunErrorEvent -> finished = true
            else -> Unit
        }
    }
    assertTrue(finished, "run did not end with RUN_FINISHED or RUN_ERROR")
}

fun Message.text(): String =
    parts.filterIsInstance<ai.koog.prompt.message.MessagePart.Text>().joinToString("") { it.text }
