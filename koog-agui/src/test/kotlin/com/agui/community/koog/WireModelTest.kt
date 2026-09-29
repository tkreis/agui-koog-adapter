package com.agui.community.koog

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

class WireModelTest {

    private fun encode(event: AgUiEvent) = AgUiJson.encodeToString(AgUiEvent.serializer(), event)
    private fun decode(json: String) = AgUiJson.decodeFromString(AgUiEvent.serializer(), json)

    private val meta = buildJsonObject { put("trace", "x"); put("nothing", JsonNull) }
    private val interrupt = AgUiInterrupt(
        id = "i1",
        reason = "tool_approval",
        message = "Delete the plan?",
        toolCallId = "c1",
        responseSchema = buildJsonObject { put("type", "object") },
        expiresAt = "2026-10-01T00:00:00Z",
        metadata = buildJsonObject { put("a", 1) },
    )

    /** One instance of every event with only the required fields set. */
    private val minimalEvents = listOf(
        RunStartedEvent("t", "r"),
        RunFinishedEvent("t", "r"),
        RunErrorEvent("boom"),
        TextMessageStartEvent("m"),
        TextMessageContentEvent("m", ""),
        TextMessageEndEvent("m"),
        ReasoningStartEvent("m"),
        ReasoningMessageStartEvent("m"),
        ReasoningMessageContentEvent("m", "hmm"),
        ReasoningMessageEndEvent("m"),
        ReasoningEndEvent("m"),
        ToolCallStartEvent("c", "f"),
        ToolCallArgsEvent("c", "{}"),
        ToolCallEndEvent("c"),
        ToolCallResultEvent("m2", "c", "ok"),
        StateSnapshotEvent(buildJsonObject {}),
        MessagesSnapshotEvent(emptyList()),
        ActivitySnapshotEvent("a", "plan", buildJsonObject {}),
        CustomEvent("ping", buildJsonObject {}),
    )

    /** One instance of every event with every optional field set. */
    private val fullEvents = listOf(
        RunStartedEvent("t", "r", parentRunId = "p", timestamp = 1L, metadata = meta),
        RunFinishedEvent("t", "r", result = JsonArray(listOf(JsonPrimitive(1))), outcome = RunOutcome.Interrupt(listOf(interrupt)), timestamp = 2L, metadata = meta),
        RunFinishedEvent("t", "r", outcome = RunOutcome.Success(listOf("c")), timestamp = 3L, metadata = meta),
        RunFinishedEvent("t", "r", outcome = RunOutcome.Cancelled, timestamp = 4L, metadata = meta),
        RunErrorEvent("boom", code = "agent_error", timestamp = 5L, metadata = meta),
        TextMessageStartEvent("m", role = "assistant", timestamp = 6L, metadata = meta),
        TextMessageContentEvent("m", "hi", timestamp = 7L, metadata = meta),
        TextMessageEndEvent("m", timestamp = 8L, metadata = meta),
        ReasoningStartEvent("m", timestamp = 9L, metadata = meta),
        ReasoningMessageStartEvent("m", timestamp = 10L, metadata = meta),
        ReasoningMessageContentEvent("m", "hmm", timestamp = 11L, metadata = meta),
        ReasoningMessageEndEvent("m", timestamp = 12L, metadata = meta),
        ReasoningEndEvent("m", timestamp = 13L, metadata = meta),
        ToolCallStartEvent("c", "f", parentMessageId = "m", timestamp = 14L, metadata = meta),
        ToolCallArgsEvent("c", "{}", timestamp = 15L, metadata = meta),
        ToolCallEndEvent("c", timestamp = 16L, metadata = meta),
        ToolCallResultEvent("m2", "c", "ok", timestamp = 17L, metadata = meta),
        StateSnapshotEvent(buildJsonObject { put("a", 1) }, timestamp = 18L, metadata = meta),
        MessagesSnapshotEvent(
            listOf(
                SystemMessage("s", "sys", name = "n", metadata = meta),
                DeveloperMessage("d", "dev", metadata = meta),
                UserMessage("u", "hi", metadata = meta),
                AssistantMessage("a", content = "hello", toolCalls = listOf(AgUiToolCall("c", function = AgUiFunctionCall("f"))), metadata = meta),
                ToolMessage("tm", toolCallId = "c", content = "ok", error = "partial", metadata = meta),
                ActivityMessage("x", "plan", buildJsonObject { put("step", 2) }, metadata = meta),
                ReasoningMessage("r", "thinking", encryptedValue = "sig", metadata = meta),
            ),
            timestamp = 19L,
            metadata = meta,
        ),
        ActivitySnapshotEvent("a", "plan", buildJsonObject { put("step", 2) }, replace = false, timestamp = 20L, metadata = meta),
        CustomEvent("ping", JsonNull, timestamp = 21L, metadata = meta),
    )

    @Test
    fun `every event round-trips through AgUiJson`() {
        for (event in minimalEvents + fullEvents) {
            assertEquals(event, decode(encode(event)), "round trip of $event")
        }
    }

    @Test
    fun `absent optional fields are omitted, never written as null`() {
        for (event in minimalEvents) {
            assertFalse("null" in encode(event), "explicit null in ${encode(event)}")
        }
        assertEquals("""{"interruptId":"i","status":"cancelled"}""", AgUiJson.encodeToString(ResumeEntry.serializer(), ResumeEntry("i", ResumeStatus.Cancelled)))
        assertEquals("""{"id":"i","reason":"r"}""", AgUiJson.encodeToString(AgUiInterrupt.serializer(), AgUiInterrupt("i", "r")))
    }

    @Test
    fun `timestamp and metadata are written as base event fields and null values inside metadata survive`() {
        assertEquals(
            """{"type":"CUSTOM","name":"progress","value":3,"timestamp":1700000000000,"metadata":{"trace":"x","nothing":null}}""",
            encode(CustomEvent("progress", JsonPrimitive(3), timestamp = 1_700_000_000_000, metadata = meta)),
        )
    }

    @Test
    fun `interrupt outcome has the spec shape`() {
        assertEquals(
            """{"type":"RUN_FINISHED","threadId":"t","runId":"r","result":{"ok":true},"outcome":{"type":"interrupt","interrupts":""" +
                """[{"id":"i1","reason":"tool_approval","message":"Delete the plan?","toolCallId":"c1","responseSchema":{"type":"object"},""" +
                """"expiresAt":"2026-10-01T00:00:00Z","metadata":{"a":1}}]}}""",
            encode(RunFinishedEvent("t", "r", result = buildJsonObject { put("ok", true) }, outcome = RunOutcome.Interrupt(listOf(interrupt)))),
        )
        assertEquals(
            """{"type":"RUN_FINISHED","threadId":"t","runId":"r","outcome":{"type":"cancelled"}}""",
            encode(RunFinishedEvent("t", "r", outcome = RunOutcome.Cancelled)),
        )
        assertEquals(
            """{"type":"RUN_FINISHED","threadId":"t","runId":"r","outcome":{"type":"success"}}""",
            encode(RunFinishedEvent("t", "r", outcome = RunOutcome.Success())),
        )
    }

    @Test
    fun `interrupt outcome needs at least one interrupt`() {
        assertFailsWith<IllegalArgumentException> { RunOutcome.Interrupt(emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            decode("""{"type":"RUN_FINISHED","threadId":"t","runId":"r","outcome":{"type":"interrupt","interrupts":[]}}""")
        }
    }

    @Test
    fun `activity snapshot has the spec shape`() {
        assertEquals(
            """{"type":"ACTIVITY_SNAPSHOT","messageId":"a1","activityType":"plan_progress","content":{"step":2},"replace":false}""",
            encode(ActivitySnapshotEvent("a1", "plan_progress", buildJsonObject { put("step", 2) }, replace = false)),
        )
    }

    @Test
    fun `messages snapshot writes each message with its role`() {
        val event = MessagesSnapshotEvent(
            listOf(
                UserMessage("u1", "hi", metadata = buildJsonObject { put("source", "chat") }),
                AssistantMessage("a1", content = "hello"),
                ActivityMessage("x1", "plan_progress", buildJsonObject { put("step", 2) }),
            )
        )
        assertEquals(
            """{"type":"MESSAGES_SNAPSHOT","messages":[""" +
                """{"id":"u1","content":"hi","role":"user","metadata":{"source":"chat"}},""" +
                """{"id":"a1","content":"hello","role":"assistant"},""" +
                """{"id":"x1","activityType":"plan_progress","content":{"step":2},"role":"activity"}]}""",
            encode(event),
        )
    }

    @Test
    fun `explicit JSON null is rejected where the protocol forbids it and kept where it is data`() {
        assertFailsWith<IllegalArgumentException> { ResumeEntry("i", ResumeStatus.Resolved, payload = JsonNull) }
        assertFailsWith<IllegalArgumentException> { RunFinishedEvent("t", "r", result = JsonNull) }
        val decoded = AgUiJson.decodeFromString(ResumeEntry.serializer(), """{"interruptId":"i","status":"resolved","payload":null}""")
        assertEquals("""{"interruptId":"i","status":"resolved"}""", AgUiJson.encodeToString(ResumeEntry.serializer(), decoded))
        assertEquals("""{"type":"CUSTOM","name":"n","value":null}""", encode(CustomEvent("n", JsonNull)))
    }

    @Test
    fun `timestamps must stay within the range JSON numbers keep exactly`() {
        assertEquals(MAX_SAFE_TIMESTAMP, TextMessageEndEvent("m", timestamp = MAX_SAFE_TIMESTAMP).timestamp)
        assertEquals(-MAX_SAFE_TIMESTAMP, TextMessageEndEvent("m", timestamp = -MAX_SAFE_TIMESTAMP).timestamp)
        assertFailsWith<IllegalArgumentException> { TextMessageEndEvent("m", timestamp = MAX_SAFE_TIMESTAMP + 1) }
        assertFailsWith<IllegalArgumentException> { CustomEvent("n", JsonNull, timestamp = -MAX_SAFE_TIMESTAMP - 1) }
        assertFailsWith<IllegalArgumentException> { decode("""{"type":"RUN_ERROR","message":"x","timestamp":9007199254740992}""") }
    }

    @Test
    fun `message constructors keep their positional order and components`() {
        val (id, content, name, role) = SystemMessage("s", "sys", null, "system")
        assertEquals(listOf("s", "sys", null, "system"), listOf(id, content, name, role))
        assertEquals("developer", DeveloperMessage("d", "dev", null, "developer").component4())
        assertEquals("user", UserMessage("u", JsonPrimitive("hi"), null, "user").component4())
        assertEquals("assistant", AssistantMessage("a", "x", null, null, "assistant").component5())
        assertEquals("tool", ToolMessage("t", "c", "ok", null, "tool").component5())
        assertEquals(JsonPrimitive("ok"), ToolMessage("t", "c", "ok").content)
    }

    /** Messages in the shapes of `@ag-ui/core` 1.0.1 (version-DEKfpZNa.d.ts), every modelled field set. */
    private val upstreamMessages = """
        [
          {"id":"s1","role":"system","content":"Be brief.","name":"policy","metadata":{"source":"server"}},
          {"id":"d1","role":"developer","content":"Use tools.","name":"dev"},
          {"id":"u1","role":"user","name":"alex","metadata":{"clientId":"c-1"},"content":[
            {"type":"text","text":"What is this?"},
            {"type":"image","id":"p1","source":{"type":"url","value":"https://example.com/a.png","mimeType":"image/png"},"metadata":{"w":1}},
            {"type":"document","source":{"type":"data","value":"aGk=","mimeType":"application/pdf"}}
          ]},
          {"id":"r1","role":"reasoning","content":"Looking at the image.","encryptedValue":"enc-1","metadata":{"model":"m"}},
          {"id":"a1","role":"assistant","content":"Let me check.","name":"agent","toolCalls":[
            {"id":"c1","type":"function","function":{"name":"lookup","arguments":"{\"q\":\"a\"}"}}
          ]},
          {"id":"t1","role":"tool","toolCallId":"c1","error":"partial","content":[
            {"type":"text","text":"found"},
            {"type":"image","source":{"type":"file","value":"f-1","provider":"openai"}}
          ]},
          {"id":"t2","role":"tool","toolCallId":"c1","content":"plain"},
          {"id":"x1","role":"activity","activityType":"plan_progress","content":{"step":2,"done":null},"metadata":{"k":null}}
        ]
    """.trimIndent()

    @Test
    fun `upstream messages survive decode and re-encode in a MESSAGES_SNAPSHOT`() {
        val original = AgUiJson.parseToJsonElement("""{"type":"MESSAGES_SNAPSHOT","messages":$upstreamMessages}""")
        val event = assertIs<MessagesSnapshotEvent>(AgUiJson.decodeFromJsonElement(AgUiEvent.serializer(), original))

        assertEquals(
            listOf("SystemMessage", "DeveloperMessage", "UserMessage", "ReasoningMessage", "AssistantMessage", "ToolMessage", "ToolMessage", "ActivityMessage"),
            event.messages.map { it::class.simpleName },
        )
        assertEquals(original, AgUiJson.encodeToJsonElement(AgUiEvent.serializer(), event))
    }

    @Test
    fun `tool content parts reach the model as text`() {
        val tool = AgUiJson.decodeFromString(
            AgUiMessage.serializer(),
            """{"id":"t1","role":"tool","toolCallId":"c1","content":[{"type":"text","text":"found"},{"type":"image","source":{"type":"url","value":"x"}}]}""",
        )
        val history = listOf(AssistantMessage("a", toolCalls = listOf(AgUiToolCall("c1", function = AgUiFunctionCall("lookup")))), tool)
        val result = history.toKoogMessages().last().parts.single() as ai.koog.prompt.message.MessagePart.Tool.Result
        assertEquals("found\n[image attachment omitted]", result.output)
    }

    @Test
    fun `decodes resume entries from a RunAgentInput`() {
        val input = AgUiJson.decodeFromString(
            RunAgentInput.serializer(),
            """
            {"threadId":"t","runId":"r2","messages":[],
             "resume":[
               {"interruptId":"i1","status":"resolved","payload":{"approved":true},"metadata":{"sig":"s"}},
               {"interruptId":"i2","status":"cancelled"}
             ]}
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                ResumeEntry("i1", ResumeStatus.Resolved, buildJsonObject { put("approved", true) }, buildJsonObject { put("sig", "s") }),
                ResumeEntry("i2", ResumeStatus.Cancelled),
            ),
            input.resume,
        )
        assertEquals(input, AgUiJson.decodeFromString(RunAgentInput.serializer(), AgUiJson.encodeToString(RunAgentInput.serializer(), input)))
        assertEquals(emptyList(), AgUiJson.decodeFromString(RunAgentInput.serializer(), """{"threadId":"t","runId":"r"}""").resume)
    }

    @Test
    fun `message decoding stays lenient`() {
        val messages = AgUiJson.decodeFromString(
            RunAgentInput.serializer(),
            """
            {"threadId":"t","runId":"r","messages":[
              {"id":"1","role":"activity","activityType":"plan","content":{"step":1},"metadata":{"k":"v"}},
              {"id":"2","role":"activity","activityType":"plan"},
              {"id":"3","role":"reasoning","content":"thinking","metadata":"not an object"},
              {"id":"4","role":"user","content":"hi","metadata":{"k":"v"}},
              {"role":"activity","activityType":"plan","content":{}},
              {"id":"6","role":"activity","activityType":"plan","content":{},"metadata":[1]},
              {"id":"7","role":"activity","activityType":7,"content":"x"},
              {"id":8,"role":"reasoning"},
              {"id":"9","role":{"nested":true}}
            ]}
            """.trimIndent(),
        ).messages

        assertEquals(ActivityMessage("1", "plan", buildJsonObject { put("step", 1) }, metadata = buildJsonObject { put("k", "v") }), messages[0])
        assertEquals(UnknownMessage("2", "activity"), messages[1])
        assertEquals(UnknownMessage("3", "reasoning"), messages[2])
        assertEquals(buildJsonObject { put("k", "v") }, assertIs<UserMessage>(messages[3]).metadata)
        assertEquals(UnknownMessage("", "activity"), messages[4]) // missing id
        assertEquals(UnknownMessage("6", "activity"), messages[5]) // metadata is not an object
        assertEquals(UnknownMessage("7", "activity"), messages[6]) // wrong field types
        assertEquals(UnknownMessage("", "reasoning"), messages[7])
        assertEquals(UnknownMessage("9", ""), messages[8])
    }
}

class AgUiSequenceRulesTest {

    private val approval = RunOutcome.Interrupt(listOf(AgUiInterrupt("i1", "tool_approval", toolCallId = "c1")))

    @Test
    fun `custom and activity events may arrive inside an open text message and an interrupt ends the run`() {
        assertValidAgUiSequence(
            listOf(
                RunStartedEvent("t", "r"),
                TextMessageStartEvent("m"),
                TextMessageContentEvent("m", "Checking"),
                CustomEvent("progress", JsonPrimitive(1)),
                ActivitySnapshotEvent("a", "plan", JsonObject(emptyMap())),
                TextMessageEndEvent("m"),
                MessagesSnapshotEvent(listOf(AssistantMessage("m", content = "Checking"))),
                RunFinishedEvent("t", "r", outcome = approval),
            )
        )
    }

    @Test
    fun `an interrupt outcome does not allow open messages`() {
        assertFailsWith<AssertionError> {
            assertValidAgUiSequence(listOf(RunStartedEvent("t", "r"), TextMessageStartEvent("m"), RunFinishedEvent("t", "r", outcome = approval)))
        }
    }

    @Test
    fun `only RUN_ERROR may follow RUN_FINISHED`() {
        assertValidAgUiSequence(listOf(RunStartedEvent("t", "r"), RunFinishedEvent("t", "r"), RunErrorEvent("late")))
        assertFailsWith<AssertionError> {
            assertValidAgUiSequence(listOf(RunStartedEvent("t", "r"), RunFinishedEvent("t", "r"), CustomEvent("late", JsonNull)))
        }
    }
}

/** The reconciliation rules documented on [MessagesSnapshotEvent], checked with the client port in TestSupport. */
class MessagesSnapshotReconciliationTest {

    private val plan = ActivityMessage("x1", "plan", buildJsonObject { put("step", 1) })
    private val search = ActivityMessage("x2", "search", buildJsonObject { put("q", "a") })
    private val thought = ReasoningMessage("r1", "hmm")
    private val current = listOf(UserMessage("u1", "hi"), plan, AssistantMessage("a1", content = "old"), thought, search, UserMessage("u2", "local"))

    @Test
    fun `replaces in place, removes omitted conversation messages and appends new ones`() {
        val result = applyMessagesSnapshot(
            current,
            MessagesSnapshotEvent(listOf(UserMessage("u1", "hi"), AssistantMessage("a1", content = "new"), AssistantMessage("a2", content = "more"))),
        )
        // u2 was not echoed, so it is gone; the activity and reasoning messages survive an activity- and reasoning-free snapshot.
        assertEquals(listOf("u1", "x1", "a1", "r1", "x2", "a2"), result.map { it.id })
        assertEquals("new", (result[2] as AssistantMessage).content)
    }

    @Test
    fun `a snapshot with activity or reasoning messages is authoritative for them`() {
        val result = applyMessagesSnapshot(current, MessagesSnapshotEvent(listOf(UserMessage("u1", "hi"), plan, ReasoningMessage("r2", "new"))))
        assertEquals(listOf("u1", "x1", "r2"), result.map { it.id })
    }

    @Test
    fun `authoritativeActivityTypes limits which activity types the snapshot replaces`() {
        val authoritative = buildJsonObject {
            put("@ag-ui/client", buildJsonObject { put("authoritativeActivityTypes", JsonArray(listOf(JsonPrimitive("plan")))) })
        }
        val event = MessagesSnapshotEvent(listOf(UserMessage("u1", "hi"), search.copy(id = "x3")), metadata = authoritative)
        // plan is replaced by the snapshot, search is not; reasoning survives a reasoning-free snapshot.
        assertEquals(listOf("u1", "r1", "x2", "x3"), applyMessagesSnapshot(current, event).map { it.id })
        assertEquals(
            """{"type":"MESSAGES_SNAPSHOT","messages":[],"metadata":{"@ag-ui/client":{"authoritativeActivityTypes":["plan"]}}}""",
            AgUiJson.encodeToString(AgUiEvent.serializer(), MessagesSnapshotEvent(emptyList(), metadata = authoritative)),
        )
    }
}
