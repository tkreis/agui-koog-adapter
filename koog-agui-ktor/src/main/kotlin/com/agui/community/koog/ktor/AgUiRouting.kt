package com.agui.community.koog.ktor

import com.agui.community.koog.AgUiEvent
import com.agui.community.koog.AgUiJson
import com.agui.community.koog.KoogAgUiAgent
import com.agui.community.koog.RunAgentInput
import com.agui.community.koog.RunErrorCodes
import com.agui.community.koog.RunErrorEvent
import com.agui.community.koog.SseEncoder
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.cacheControl
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.http.CacheControl
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/**
 * Exposes an AG-UI endpoint: `POST path` with a `RunAgentInput` JSON body, answered with a
 * `text/event-stream` of AG-UI events.
 *
 * Invalid request bodies get `400` before the stream starts; failures after that are sent in-stream
 * as `RUN_ERROR`. CORS and authentication are left to the application.
 */
public fun Route.agUi(path: String, agent: suspend ApplicationCall.(RunAgentInput) -> KoogAgUiAgent): Route =
    agUiEvents(path) { input -> agent(input).run(input) }

/** Lower-level variant for any AG-UI event source. */
public fun Route.agUiEvents(path: String, events: suspend ApplicationCall.(RunAgentInput) -> Flow<AgUiEvent>): Route =
    post(path) {
        val input = try {
            AgUiJson.decodeFromString(RunAgentInput.serializer(), call.receiveText())
        } catch (e: IllegalArgumentException) { // includes SerializationException
            call.respondText("Invalid RunAgentInput: ${e.message}", status = HttpStatusCode.BadRequest)
            return@post
        }

        call.response.cacheControl(CacheControl.NoCache(null))
        call.response.header("X-Accel-Buffering", "no")
        call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
            try {
                call.events(input).collect { event ->
                    writeStringUtf8(SseEncoder.encode(event))
                    flush()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                writeStringUtf8(SseEncoder.encode(RunErrorEvent(e.message ?: "Agent run failed", RunErrorCodes.AGENT_ERROR)))
                flush()
            }
        }
    }
