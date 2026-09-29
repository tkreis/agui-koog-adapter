package com.agui.community.koog

import ai.koog.prompt.message.MessagePart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    fun `drops roles it does not model and keeps placeholders for attachments`() {
        val input = AgUiJson.decodeFromString(
            RunAgentInput.serializer(),
            """
            {"threadId":"t","runId":"r","messages":[
              {"id":"1","role":"reasoning","content":"thinking"},
              {"id":"2","role":"activity","activityType":"x","content":{}},
              {"id":"3","role":"user","content":[{"type":"image","source":{"type":"url","value":"x"}}]}
            ]}
            """.trimIndent(),
        )
        val messages = input.messages.toKoogMessages()

        assertEquals(1, messages.size)
        assertEquals("[image attachment omitted]", (messages.single().parts.single() as MessagePart.Text).text)
    }
}
