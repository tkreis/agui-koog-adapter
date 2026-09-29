package com.agui.community.koog

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.FunctionalAIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.context.AIAgentFunctionalContext
import ai.koog.agents.core.agent.functionalStrategy
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/** Behaviour switches for [KoogAgUiAgent]. */
public data class AgUiAgentConfig(
    /** Expose `input.state` to the model and let it replace the state with the [stateToolName] tool. */
    val shareState: Boolean = false,
    val stateToolName: String = "update_state",
    /** Instructions appended after the state JSON in the system message; defaults to [defaultStatePrompt]. */
    val statePrompt: String = defaultStatePrompt(stateToolName),
    /** Include `input.context` as a system message. */
    val includeContext: Boolean = true,
    /** Maximum number of LLM turns per run (each backend tool round trip is one turn). */
    val maxTurns: Int = 10,
) {
    public companion object {
        public fun defaultStatePrompt(stateToolName: String): String =
            "This is the shared application state that the user sees. To change it, call the " +
                "$stateToolName tool with the complete new state object (not a diff). Keep fields you do not change."
    }
}

/**
 * Runs a Koog agent for one AG-UI request and streams the result as AG-UI events.
 *
 * Every run is stateless: the conversation comes from [RunAgentInput.messages]. Backend tools come from
 * [toolRegistry] and are executed by Koog; client tools from [RunAgentInput.tools] are advertised to the
 * model but never executed on the server — if the model calls one, the run finishes and the client
 * executes it, then starts a new run with the result. A client tool named like a backend tool is ignored
 * (the backend tool wins).
 *
 * @param installFeatures installs Koog features (tracing, event handlers, …) on the per-run agent.
 */
public class KoogAgUiAgent(
    private val promptExecutor: PromptExecutor,
    private val model: LLModel,
    private val toolRegistry: ToolRegistry = ToolRegistry.EMPTY,
    private val systemPrompt: String? = null,
    private val config: AgUiAgentConfig = AgUiAgentConfig(),
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val installFeatures: FunctionalAIAgent.FeatureContext.() -> Unit = {},
) {

    /** Streams the AG-UI events for [input]. Collection cancels with the caller (e.g. client disconnect). */
    public fun run(input: RunAgentInput): Flow<AgUiEvent> = channelFlow {
        send(RunStartedEvent(input.threadId, input.runId, input.parentRunId))
        try {
            val inputState = input.state?.takeUnless { it is JsonNull }
            if (config.shareState && inputState != null) send(StateSnapshotEvent(inputState))
            val pendingFrontendCallIds = runAgent(input, inputState ?: JsonObject(emptyMap()), events = this)
            send(RunFinishedEvent(input.threadId, input.runId, outcome = RunOutcome.Success(pendingFrontendCallIds.ifEmpty { null })))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            send(e.toRunErrorEvent())
        }
    }

    /** Runs the Koog agent; returns ids of frontend tool calls the client still has to answer. */
    private suspend fun runAgent(input: RunAgentInput, state: JsonElement, events: SendChannel<AgUiEvent>): List<String> {
        val frontendTools = input.tools
            .filter { toolRegistry.getToolOrNull(it.name) == null && !(config.shareState && it.name == config.stateToolName) }
            .map(JsonSchemaToolDescriptors::toToolDescriptor)
        val extraTools = frontendTools + listOfNotNull(stateToolDescriptor().takeIf { config.shareState })
        val pendingFrontendCallIds = mutableListOf<String>()

        val strategy = functionalStrategy<Unit, Unit>("ag-ui") {
            llm.writeSession { tools = tools + extraTools }
            runTurns(events, frontendTools.map { it.name }.toSet(), pendingFrontendCallIds)
        }
        AIAgent(
            promptExecutor = promptExecutor,
            // Functional strategies are not bounded by Koog's iteration limit; maxTurns is the guard.
            agentConfig = AIAgentConfig(Prompt(buildPrompt(input, state), input.threadId), model, config.maxTurns),
            strategy = strategy,
            toolRegistry = toolRegistry,
            installFeatures = installFeatures,
        ).run(Unit)
        return pendingFrontendCallIds
    }

    private suspend fun AIAgentFunctionalContext.runTurns(
        events: SendChannel<AgUiEvent>,
        frontendNames: Set<String>,
        pendingFrontendCallIds: MutableList<String>,
    ) {
        // Inside the Koog context `config` is the agent config, so read our settings explicitly.
        val settings = this@KoogAgUiAgent.config
        val silent = if (settings.shareState) setOf(settings.stateToolName) else emptySet()
        repeat(settings.maxTurns) {
            val translator = AgUiStreamTranslator(idGenerator(), silent, idGenerator)
            llm.writeSession { requestLLMStreaming() }.collect { frame -> translator.onFrame(frame).forEach { events.send(it) } }
            translator.finish().forEach { events.send(it) }
            translator.assistantMessage()?.let { message -> llm.writeSession { appendPrompt { message(message) } } }

            val calls = translator.toolCalls
            if (calls.isEmpty()) return

            val results = mutableListOf<MessagePart.Tool.Result>()
            for (call in calls) {
                when {
                    call.name in silent -> {
                        val newState = parseStateArgument(call.arguments)
                        newState?.let { events.send(StateSnapshotEvent(it)) }
                        results += MessagePart.Tool.Result(
                            call.id, call.name,
                            if (newState != null) "State updated." else "Invalid state: expected {\"state\": {...}}.",
                            isError = newState == null,
                        )
                    }

                    call.name in frontendNames -> pendingFrontendCallIds += call.id

                    else -> {
                        val part = executeTool(MessagePart.Tool.Call(call.id, call.name, call.arguments)).toMessagePart()
                        events.send(ToolCallResultEvent(idGenerator(), call.id, part.output))
                        results += part
                    }
                }
            }
            if (results.isNotEmpty()) {
                llm.writeSession { appendPrompt { message(Message.User(results, RequestMetaInfo.Empty)) } }
            }
            // Frontend tools pending: the client executes them and starts the next run.
            if (pendingFrontendCallIds.isNotEmpty()) return
        }
        throw MaxTurnsReachedException(settings.maxTurns)
    }

    private fun buildPrompt(input: RunAgentInput, state: JsonElement): List<Message> = buildList {
        fun system(text: String) = add(Message.System(text, RequestMetaInfo.Empty))
        systemPrompt?.let(::system)
        if (config.includeContext && input.context.isNotEmpty()) {
            system(input.context.joinToString("\n", "Context provided by the application:\n") { "- ${it.description}: ${it.value}" })
        }
        if (config.shareState) system("Current shared state:\n$state\n\n${config.statePrompt}")
        addAll(input.messages.toKoogMessages())
    }

    private fun stateToolDescriptor() = ToolDescriptor(
        name = config.stateToolName,
        description = "Replace the shared application state with a new complete state object.",
        requiredParameters = listOf(
            ToolParameterDescriptor(
                "state", "The complete new state object.",
                ToolParameterType.Object(properties = emptyList(), additionalProperties = true),
            )
        ),
    )

    /** Accepts `{"state": {...}}` or `{"state": "<json>"}` (some models double-encode); anything else is rejected. */
    private fun parseStateArgument(arguments: String): JsonElement? = runCatching {
        val state = (AgUiJson.parseToJsonElement(arguments) as? JsonObject)?.get("state")
        val decoded = if (state is JsonPrimitive && state.isString) AgUiJson.parseToJsonElement(state.content) else state
        decoded?.takeIf { it is JsonObject || it is JsonArray }
    }.getOrNull()
}

/** Values of [RunErrorEvent.code] emitted by this adapter. */
public object RunErrorCodes {
    public const val AGENT_ERROR: String = "agent_error"
    public const val MAX_TURNS: String = "max_turns"
}

private class MaxTurnsReachedException(maxTurns: Int) :
    IllegalStateException("Stopped after $maxTurns LLM turns without a final answer")

/** Maps a failed run to its terminal AG-UI event. */
public fun Throwable.toRunErrorEvent(): RunErrorEvent = RunErrorEvent(
    message = message ?: "Agent run failed",
    code = if (this is MaxTurnsReachedException) RunErrorCodes.MAX_TURNS else RunErrorCodes.AGENT_ERROR,
)
