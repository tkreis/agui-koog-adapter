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
                """{"id":"u1","content":"hi","metadata":{"source":"chat"},"role":"user"},""" +
                """{"id":"a1","content":"hello","role":"assistant"},""" +
                """{"id":"x1","activityType":"plan_progress","content":{"step":2},"role":"activity"}]}""",
            encode(event),
        )
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
              {"id":"4","role":"user","content":"hi","metadata":{"k":"v"}}
            ]}
            """.trimIndent(),
        ).messages

        assertEquals(ActivityMessage("1", "plan", buildJsonObject { put("step", 1) }, buildJsonObject { put("k", "v") }), messages[0])
        assertEquals(UnknownMessage("2", "activity"), messages[1])
        assertEquals(UnknownMessage("3", "reasoning"), messages[2])
        assertEquals(buildJsonObject { put("k", "v") }, assertIs<UserMessage>(messages[3]).metadata)
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
