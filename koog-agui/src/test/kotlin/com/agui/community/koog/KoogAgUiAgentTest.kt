package com.agui.community.koog

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KoogAgUiAgentTest {

    private val changeBackground = AgUiTool(
        name = "change_background",
        description = "Change the page background",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { putJsonObject("background") { put("type", "string") } }
            put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("background"))))
        },
    )

    private fun input(vararg messages: AgUiMessage, tools: List<AgUiTool> = emptyList(), state: kotlinx.serialization.json.JsonElement? = null) =
        RunAgentInput(threadId = "t1", runId = "r1", messages = messages.toList(), tools = tools, state = state)

    private fun agent(executor: ScriptedExecutor, config: AgUiAgentConfig = AgUiAgentConfig()) = KoogAgUiAgent(
        promptExecutor = executor,
        model = TestModel,
        toolRegistry = ToolRegistry { tool(GetWeatherTool) },
        systemPrompt = "You are helpful.",
        config = config,
        idGenerator = counter(),
    )

    @Test
    fun `streams text as a single assistant message`() = runTest {
        val executor = ScriptedExecutor(textTurn("Hel", "lo"))
        val events = agent(executor).run(input(UserMessage("u1", "hi"))).toList()

        assertValidAgUiSequence(events)
        assertEquals(
            listOf(
                RunStartedEvent("t1", "r1"),
                TextMessageStartEvent("id-1"),
                TextMessageContentEvent("id-1", "Hel"),
                TextMessageContentEvent("id-1", "lo"),
                TextMessageEndEvent("id-1"),
                RunFinishedEvent("t1", "r1", outcome = RunOutcome.Success()),
            ),
            events,
        )
        val prompt = executor.prompts.single()
        assertIs<Message.System>(prompt.messages[0])
        assertEquals("hi", prompt.messages[1].text())
    }

    @Test
    fun `executes backend tools, reports results and continues`() = runTest {
        val executor = ScriptedExecutor(
            toolTurn("call-1", "get_weather", "{\"location\":", "\"Berlin\"}"),
            textTurn("It is 21 °C."),
        )
        val events = agent(executor).run(input(UserMessage("u1", "weather in Berlin?"))).toList()

        assertValidAgUiSequence(events)
        val types = events.map { it::class.simpleName }
        assertEquals(
            listOf(
                "RunStartedEvent", "ToolCallStartEvent", "ToolCallArgsEvent", "ToolCallArgsEvent", "ToolCallEndEvent",
                "ToolCallResultEvent", "TextMessageStartEvent", "TextMessageContentEvent", "TextMessageEndEvent",
                "RunFinishedEvent",
            ),
            types,
        )
        val start = events.filterIsInstance<ToolCallStartEvent>().single()
        assertEquals(ToolCallStartEvent("call-1", "get_weather", parentMessageId = "id-1"), start)
        val result = events.filterIsInstance<ToolCallResultEvent>().single()
        assertEquals("call-1", result.toolCallId)
        assertTrue("\"temperature\":21" in result.content)
        assertNull(events.filterIsInstance<RunFinishedEvent>().single().outcome.let { (it as RunOutcome.Success).pendingToolCallIds })

        // Second request carries the assistant tool call and the tool result.
        val second = executor.prompts[1].messages
        val call = (second[second.size - 2] as Message.Assistant).parts.filterIsInstance<MessagePart.Tool.Call>().single()
        assertEquals("call-1", call.id)
        val toolResult = (second.last() as Message.User).parts.filterIsInstance<MessagePart.Tool.Result>().single()
        assertEquals("call-1", toolResult.id)
        assertEquals("get_weather", toolResult.tool)
    }

    @Test
    fun `stops at frontend tool calls and reports them as pending`() = runTest {
        val executor = ScriptedExecutor(toolTurn("call-9", "change_background", "{\"background\":\"blue\"}"))
        val events = agent(executor).run(input(UserMessage("u1", "make it blue"), tools = listOf(changeBackground))).toList()

        assertValidAgUiSequence(events)
        assertTrue(events.none { it is ToolCallResultEvent }, "frontend tools must not get a server result")
        val finished = events.last() as RunFinishedEvent
        assertEquals(RunOutcome.Success(listOf("call-9")), finished.outcome)
        assertEquals(1, executor.prompts.size)
        val advertised = executor.tools.single().map { it.name }.toSet()
        assertEquals(setOf("get_weather", "change_background"), advertised)
        val descriptor = executor.tools.single().first { it.name == "change_background" }
        assertEquals(listOf("background"), descriptor.requiredParameters.map { it.name })
    }

    @Test
    fun `replays frontend tool results from the next run`() = runTest {
        val executor = ScriptedExecutor(textTurn("Done, the background is blue."))
        val history = arrayOf(
            UserMessage("u1", "make it blue"),
            AssistantMessage("a1", toolCalls = listOf(AgUiToolCall("call-9", function = AgUiFunctionCall("change_background", "{\"background\":\"blue\"}")))),
            ToolMessage("tm1", toolCallId = "call-9", content = "ok"),
        )
        val events = agent(executor).run(input(*history, tools = listOf(changeBackground))).toList()

        assertValidAgUiSequence(events)
        val messages = executor.prompts.single().messages
        val call = (messages[2] as Message.Assistant).parts.single() as MessagePart.Tool.Call
        assertEquals("change_background", call.tool)
        val result = (messages[3] as Message.User).parts.single() as MessagePart.Tool.Result
        assertEquals("call-9", result.id)
        assertEquals("change_background", result.tool)
        assertEquals("ok", result.output)
    }

    @Test
    fun `mixed turn runs backend tools and leaves frontend calls pending`() = runTest {
        val turn = listOf(
            ai.koog.prompt.streaming.StreamFrame.ToolCallComplete("c1", "get_weather", "{\"location\":\"Paris\"}", 0),
            ai.koog.prompt.streaming.StreamFrame.ToolCallComplete("c2", "change_background", "{\"background\":\"red\"}", 1),
            ai.koog.prompt.streaming.StreamFrame.End("tool_calls"),
        )
        val events = agent(ScriptedExecutor(turn)).run(input(UserMessage("u1", "x"), tools = listOf(changeBackground))).toList()

        assertValidAgUiSequence(events)
        assertEquals(listOf("c1"), events.filterIsInstance<ToolCallResultEvent>().map { it.toolCallId })
        assertEquals(RunOutcome.Success(listOf("c2")), (events.last() as RunFinishedEvent).outcome)
    }

    @Test
    fun `update_state becomes a state snapshot, not a tool call`() = runTest {
        val executor = ScriptedExecutor(
            toolTurn("s1", "update_state", "{\"state\":{\"todos\":[\"buy milk\"]}}"),
            textTurn("Added."),
        )
        val initial = buildJsonObject { put("todos", kotlinx.serialization.json.JsonArray(emptyList())) }
        val events = agent(executor, AgUiAgentConfig(shareState = true))
            .run(input(UserMessage("u1", "add buy milk"), state = initial)).toList()

        assertValidAgUiSequence(events)
        assertTrue(events.none { it is ToolCallStartEvent }, "state tool must be silent")
        val snapshots = events.filterIsInstance<StateSnapshotEvent>()
        assertEquals(initial, snapshots.first().snapshot)
        assertEquals("buy milk", snapshots.last().snapshot.jsonObject["todos"]!!.let { (it as kotlinx.serialization.json.JsonArray)[0].jsonPrimitive.content })
        val system = executor.prompts.first().messages.filterIsInstance<Message.System>().joinToString { it.text() }
        assertTrue("\"todos\":[]" in system, "state is shown to the model")
        assertTrue(executor.tools.first().any { it.name == "update_state" })
    }

    @Test
    fun `invalid update_state arguments leave the state untouched`() = runTest {
        val executor = ScriptedExecutor(toolTurn("s1", "update_state", "{}"), textTurn("Sorry."))
        val events = agent(executor, AgUiAgentConfig(shareState = true))
            .run(input(UserMessage("u1", "x"), state = buildJsonObject { put("todos", "keep") })).toList()

        assertValidAgUiSequence(events)
        assertEquals(1, events.filterIsInstance<StateSnapshotEvent>().size) // only the initial snapshot
        val result = (executor.prompts[1].messages.last() as Message.User).parts.single() as MessagePart.Tool.Result
        assertTrue(result.isError)
    }

    @Test
    fun `context is passed to the model`() = runTest {
        val executor = ScriptedExecutor(textTurn("Hi Alex"))
        agent(executor).run(
            RunAgentInput("t", "r", messages = listOf(UserMessage("u", "hi")), context = listOf(AgUiContext("Name of the user", "Alex")))
        ).toList()
        val system = executor.prompts.single().messages.filterIsInstance<Message.System>().joinToString { it.text() }
        assertTrue("- Name of the user: Alex" in system)
    }

    @Test
    fun `failures end the run with RUN_ERROR`() = runTest {
        val executor = ScriptedExecutor() // no turn scripted -> executor throws
        val events = agent(executor).run(input(UserMessage("u1", "hi"))).toList()

        assertValidAgUiSequence(events)
        assertIs<RunStartedEvent>(events.first())
        assertEquals(RunErrorCodes.AGENT_ERROR, assertIs<RunErrorEvent>(events.last()).code)
    }

    @Test
    fun `stops after maxTurns`() = runTest {
        val loop = Array(3) { toolTurn("c$it", "get_weather", "{\"location\":\"Oslo\"}") }
        val executor = ScriptedExecutor(*loop)
        val events = agent(executor, AgUiAgentConfig(maxTurns = 2)).run(input(UserMessage("u1", "loop"))).toList()

        assertValidAgUiSequence(events)
        assertEquals(2, executor.prompts.size)
        assertEquals(RunErrorCodes.MAX_TURNS, assertIs<RunErrorEvent>(events.last()).code)
    }
}
