package com.agui.community.koog

import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WireFormatTest {

    @Test
    fun `decodes a RunAgentInput as sent by @ag-ui-client, including unknown roles and tools without parameters`() {
        val json = """
            {"threadId":"t","runId":"r","state":{},"forwardedProps":{},
             "messages":[
               {"id":"1","role":"user","content":"hi"},
               {"id":"2","role":"user","content":[{"type":"text","text":"look"},{"type":"image","source":{"type":"url","value":"x"}}]},
               {"id":"3","role":"assistant","toolCalls":[{"id":"c","type":"function","function":{"name":"f","arguments":"{}"}}]},
               {"id":"4","role":"tool","toolCallId":"c","content":"done"},
               {"id":"5","role":"reasoning","content":"thinking"},
               {"id":"6","role":"activity","activityType":"x","content":{}}
             ],
             "tools":[{"name":"noop","description":"no params"}],
             "context":[{"description":"d","value":"v"}]}
        """.trimIndent()
        val input = AgUiJson.decodeFromString(RunAgentInput.serializer(), json)

        assertEquals(6, input.messages.size)
        assertEquals("look\n[image attachment omitted]", (input.messages[1] as UserMessage).textContent())
        assertIs<UnknownMessage>(input.messages[4])
        assertEquals(null, input.tools.single().parameters)
        assertEquals(4, input.messages.toKoogMessages().size) // user, user, assistant, tool-results
    }

    @Test
    fun `encodes events with a type discriminator and without nulls`() {
        val encoded = SseEncoder.encode(ToolCallStartEvent("c1", "get_weather"))
        assertEquals("data: {\"type\":\"TOOL_CALL_START\",\"toolCallId\":\"c1\",\"toolCallName\":\"get_weather\"}\n\n", encoded)
        assertFalse("null" in SseEncoder.encode(RunFinishedEvent("t", "r")))
        assertEquals(
            "{\"type\":\"RUN_FINISHED\",\"threadId\":\"t\",\"runId\":\"r\",\"outcome\":{\"type\":\"success\",\"pendingToolCallIds\":[\"a\"]}}",
            AgUiJson.encodeToString(AgUiEvent.serializer(), RunFinishedEvent("t", "r", outcome = RunOutcome.Success(listOf("a")))),
        )
        assertEquals(
            "{\"type\":\"TEXT_MESSAGE_START\",\"messageId\":\"m\",\"role\":\"assistant\"}",
            AgUiJson.encodeToString(AgUiEvent.serializer(), TextMessageStartEvent("m")),
        )
    }
}

class MessageConversionTest {

    @Test
    fun `maps every role and groups consecutive tool results`() {
        val messages = listOf(
            SystemMessage("s", "sys"),
            DeveloperMessage("d", "dev"),
            UserMessage("u", AgUiJson.parseToJsonElement("""[{"type":"text","text":"a"},{"type":"text","text":"b"}]""")),
            AssistantMessage(
                "a", content = "calling",
                toolCalls = listOf(
                    AgUiToolCall("c1", function = AgUiFunctionCall("f", "{}")),
                    AgUiToolCall("c2", function = AgUiFunctionCall("g", "")),
                ),
            ),
            ToolMessage("t1", toolCallId = "c1", content = "one"),
            ToolMessage("t2", toolCallId = "c2", error = "boom"),
            ToolMessage("t3", toolCallId = "missing", content = "?"),
        ).toKoogMessages()

        assertEquals(listOf("System", "System", "User", "Assistant", "User"), messages.map { it::class.simpleName })
        assertEquals(listOf("a", "b"), messages[2].parts.map { (it as MessagePart.Text).text })
        val call = messages[3].parts.filterIsInstance<MessagePart.Tool.Call>()[1]
        assertEquals("{}", call.args) // blank arguments normalised
        val results = messages[4].parts.map { it as MessagePart.Tool.Result }
        assertEquals(listOf("f", "g", "unknown"), results.map { it.tool })
        assertTrue(results[1].isError)
        assertEquals("Error: boom", results[1].output)
    }
}

class AgUiStreamTranslatorTest {

    private fun translate(frames: List<StreamFrame>, silent: Set<String> = emptySet()): Pair<List<AgUiEvent>, AgUiStreamTranslator> {
        val translator = AgUiStreamTranslator("m", silent, counter("gen"))
        val events = frames.flatMap(translator::onFrame) + translator.finish()
        return events to translator
    }

    @Test
    fun `text then tool call closes the text first and keeps parent id`() {
        val (events, translator) = translate(
            listOf(
                StreamFrame.TextDelta("Let me check."),
                StreamFrame.TextComplete("Let me check."),
                StreamFrame.ToolCallDelta("c1", "get_weather", "{\"lo", 0),
                StreamFrame.ToolCallDelta(null, null, "cation\":\"Rome\"}", 0),
                StreamFrame.ToolCallComplete("c1", "get_weather", "{\"location\":\"Rome\"}", 0),
                StreamFrame.End("tool_calls"),
            )
        )
        assertEquals(
            listOf(
                TextMessageStartEvent("m"), TextMessageContentEvent("m", "Let me check."), TextMessageEndEvent("m"),
                ToolCallStartEvent("c1", "get_weather", "m"),
                ToolCallArgsEvent("c1", "{\"lo"), ToolCallArgsEvent("c1", "cation\":\"Rome\"}"),
                ToolCallEndEvent("c1"),
            ),
            events,
        )
        assertEquals(listOf(CollectedToolCall("c1", "get_weather", "{\"location\":\"Rome\"}")), translator.toolCalls)
        val message = translator.assistantMessage()!!
        assertEquals(2, message.parts.size)
    }

    @Test
    fun `complete-only frames and missing ids still produce valid sequences`() {
        val (events, translator) = translate(
            listOf(
                StreamFrame.TextComplete("Hi"),
                StreamFrame.ToolCallComplete(null, "f", "{}", null),
                StreamFrame.End(),
            )
        )
        assertEquals(
            listOf(
                TextMessageStartEvent("m"), TextMessageContentEvent("m", "Hi"), TextMessageEndEvent("m"),
                ToolCallStartEvent("gen-1", "f", "m"), ToolCallArgsEvent("gen-1", "{}"), ToolCallEndEvent("gen-1"),
            ),
            events,
        )
        assertEquals("gen-1", translator.toolCalls.single().id)
    }

    @Test
    fun `tool name arriving after the first delta delays TOOL_CALL_START`() {
        val (events, _) = translate(
            listOf(
                StreamFrame.ToolCallDelta("c1", null, "{\"a\":", 0),
                StreamFrame.ToolCallDelta(null, "f", "1}", 0),
                StreamFrame.ToolCallComplete("c1", "f", "{\"a\":1}", 0),
            )
        )
        assertEquals(
            listOf(ToolCallStartEvent("c1", "f", "m"), ToolCallArgsEvent("c1", "{\"a\":1}"), ToolCallEndEvent("c1")),
            events,
        )
    }

    @Test
    fun `reasoning is wrapped in reasoning start and end`() {
        val (events, _) = translate(
            listOf(
                StreamFrame.ReasoningDelta(text = "hmm"),
                StreamFrame.ReasoningComplete(null, listOf("hmm")),
                StreamFrame.TextDelta("ok"),
                StreamFrame.End(),
            )
        )
        assertEquals(
            listOf(
                ReasoningStartEvent("m-reasoning-0"), ReasoningMessageStartEvent("m-reasoning-0"),
                ReasoningMessageContentEvent("m-reasoning-0", "hmm"),
                ReasoningMessageEndEvent("m-reasoning-0"), ReasoningEndEvent("m-reasoning-0"),
                TextMessageStartEvent("m"), TextMessageContentEvent("m", "ok"), TextMessageEndEvent("m"),
            ),
            events,
        )
    }

    @Test
    fun `assistant message keeps reasoning and part order for the next turn`() {
        val (_, translator) = translate(
            listOf(
                StreamFrame.ReasoningDelta(text = "think"),
                StreamFrame.ReasoningComplete("r1", listOf("think"), encrypted = "sig"),
                StreamFrame.ToolCallComplete("c1", "f", "{}", 0),
                StreamFrame.TextComplete("after"),
                StreamFrame.End(),
            )
        )
        val parts = translator.assistantMessage()!!.parts
        assertEquals(listOf("Reasoning", "Call", "Text"), parts.map { it::class.simpleName })
        assertEquals("sig", (parts[0] as ai.koog.prompt.message.MessagePart.Reasoning).encrypted)
    }

    @Test
    fun `silent tools are collected but not announced`() {
        val (events, translator) = translate(toolTurn("s", "update_state", "{\"state\":{}}"), silent = setOf("update_state"))
        assertTrue(events.isEmpty())
        assertEquals("update_state", translator.toolCalls.single().name)
    }

    @Test
    fun `assistant message keeps announced ids`() {
        val (_, translator) = translate(listOf(StreamFrame.ToolCallDelta(null, "f", "{}", 0), StreamFrame.End()))
        val call = translator.assistantMessage()!!.parts.single() as ai.koog.prompt.message.MessagePart.Tool.Call
        assertEquals("gen-1", call.id)
        assertIs<Message.Assistant>(translator.assistantMessage())
    }
}

class JsonSchemaToolDescriptorsTest {

    @Test
    fun `converts nested schemas`() {
        val schema = AgUiJson.parseToJsonElement(
            """
            {"type":"object","required":["japanese","gradient"],
             "properties":{
               "japanese":{"type":"array","description":"lines","items":{"type":"string"}},
               "gradient":{"type":"string","enum":["sunset","ocean"]},
               "count":{"type":["integer","null"]},
               "step":{"${'$'}ref":"#/${'$'}defs/Step"}
             },
             "${'$'}defs":{"Step":{"type":"object","properties":{"done":{"type":"boolean"}},"required":["done"]}}}
            """.trimIndent()
        ) as JsonObject
        val descriptor = JsonSchemaToolDescriptors.toToolDescriptor(AgUiTool("generate_haiku", "Haiku", schema))

        assertEquals(listOf("japanese", "gradient"), descriptor.requiredParameters.map { it.name })
        assertEquals(listOf("count", "step"), descriptor.optionalParameters.map { it.name })
        val japanese = descriptor.requiredParameters[0]
        assertEquals("lines", japanese.description)
        assertEquals(ToolParameterType.List(ToolParameterType.String), japanese.type)
        assertIs<ToolParameterType.Enum>(descriptor.requiredParameters[1].type)
        assertIs<ToolParameterType.AnyOf>(descriptor.optionalParameters[0].type)
        val step = descriptor.optionalParameters[1].type as ToolParameterType.Object
        assertEquals(listOf("done"), step.requiredProperties)
    }

    @Test
    fun `tool without parameters has no parameters`() {
        val descriptor = JsonSchemaToolDescriptors.toToolDescriptor(AgUiTool("ping"))
        assertTrue(descriptor.requiredParameters.isEmpty() && descriptor.optionalParameters.isEmpty())
    }
}
