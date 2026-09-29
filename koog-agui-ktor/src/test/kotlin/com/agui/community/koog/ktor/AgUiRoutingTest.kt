package com.agui.community.koog.ktor

import com.agui.community.koog.RunFinishedEvent
import com.agui.community.koog.RunStartedEvent
import com.agui.community.koog.TextMessageContentEvent
import com.agui.community.koog.TextMessageEndEvent
import com.agui.community.koog.TextMessageStartEvent
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgUiRoutingTest {

    @Test
    fun `streams events as SSE data frames`() = testApplication {
        routing {
            agUiEvents("/agent") { input ->
                flowOf(
                    RunStartedEvent(input.threadId, input.runId),
                    TextMessageStartEvent("m"),
                    TextMessageContentEvent("m", "hi"),
                    TextMessageEndEvent("m"),
                    RunFinishedEvent(input.threadId, input.runId),
                )
            }
        }
        val response = client.post("/agent") {
            header(HttpHeaders.Accept, "text/event-stream")
            contentType(ContentType.Application.Json)
            setBody("""{"threadId":"t","runId":"r","messages":[{"id":"1","role":"user","content":"hi"}]}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.headers[HttpHeaders.ContentType]!!.startsWith("text/event-stream"))
        val frames = response.bodyAsText().split("\n\n").filter { it.isNotBlank() }
        assertEquals(5, frames.size)
        assertTrue(frames.all { it.startsWith("data: {\"type\":") })
        assertEquals("data: {\"type\":\"RUN_STARTED\",\"threadId\":\"t\",\"runId\":\"r\"}", frames.first())
    }

    @Test
    fun `failures of the event source end the stream with RUN_ERROR`() = testApplication {
        routing {
            agUiEvents("/agent") { input ->
                flow {
                    emit(RunStartedEvent(input.threadId, input.runId))
                    throw java.io.IOException("upstream LLM unreachable")
                }
            }
        }
        val body = client.post("/agent") {
            contentType(ContentType.Application.Json)
            setBody("""{"threadId":"t","runId":"r","messages":[]}""")
        }.bodyAsText()

        val last = body.split("\n\n").last { it.isNotBlank() }
        assertTrue("\"type\":\"RUN_ERROR\"" in last && "upstream LLM unreachable" in last && "\"code\":\"agent_error\"" in last, last)
    }

    @Test
    fun `rejects malformed input with 400`() = testApplication {
        routing { agUiEvents("/agent") { flowOf() } }
        val response = client.post("/agent") {
            contentType(ContentType.Application.Json)
            setBody("""{"messages":[]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
