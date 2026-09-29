package com.agui.community.koog.example

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import ai.koog.serialization.typeToken
import com.agui.community.koog.AgUiAgentConfig
import com.agui.community.koog.KoogAgUiAgent
import com.agui.community.koog.ktor.agUi
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.absoluteValue

/** Backend tool: executed by Koog on the server, rendered by the client from TOOL_CALL_RESULT. */
object GetWeatherTool : SimpleTool<GetWeatherTool.Args>(
    argsType = typeToken<Args>(),
    name = "get_weather",
    description = "Get the current weather for a city. Returns temperature in °C, conditions, humidity in %, wind speed in km/h.",
) {
    @Serializable
    data class Args(@property:LLMDescription("City name, e.g. Berlin") val location: String)

    private val conditions = listOf("sunny", "partly cloudy", "cloudy", "light rain", "windy")

    // Deterministic demo data derived from the city name; replace with a real weather API.
    override suspend fun execute(args: Args): String {
        val seed = args.location.lowercase().hashCode().absoluteValue
        val temperature = 5 + seed % 25
        return buildJsonObject {
            put("location", args.location)
            put("temperature", temperature)
            put("feels_like", temperature - 2)
            put("conditions", conditions[seed % conditions.size])
            put("humidity", 40 + seed % 50)
            put("wind_speed", 5 + seed % 30)
        }.toString()
    }
}

private const val SYSTEM_PROMPT = """
You are a friendly assistant in a demo app that shows how a Koog agent drives a web UI through the AG-UI protocol.
- For weather questions call get_weather.
- When the user asks to change the background/theme/colour call change_background with a CSS background value
  (a colour or a linear-gradient).
- When the user asks for a haiku call generate_haiku; do not also write the haiku in plain text.
- The shared state holds the user's todo list. To add, complete or remove todos call update_state with the
  complete new state. Each todo is {"id": string, "title": string, "done": boolean}.
- After a tool ran, answer briefly (one or two sentences).
"""

fun main() {
    val apiKey = System.getenv("OPENAI_API_KEY") ?: error("Set OPENAI_API_KEY")
    val executor = simpleOpenAIExecutor(apiKey)
    val model = when (System.getenv("OPENAI_MODEL")) {
        "gpt-4o-mini" -> OpenAIModels.Chat.GPT4oMini
        "gpt-5-mini" -> OpenAIModels.Chat.GPT5Mini
        else -> OpenAIModels.Chat.GPT4_1Mini
    }
    val agent = KoogAgUiAgent(
        promptExecutor = executor,
        model = model,
        toolRegistry = ToolRegistry { tool(GetWeatherTool) },
        systemPrompt = SYSTEM_PROMPT.trimIndent(),
        config = AgUiAgentConfig(shareState = true),
    )

    val port = System.getenv("PORT")?.toIntOrNull() ?: 8787
    embeddedServer(Netty, port = port) {
        install(CORS) {
            anyHost()
            allowMethod(HttpMethod.Post)
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.Accept)
        }
        routing {
            get("/health") { call.respondText("ok") }
            agUi("/agent") { agent }
        }
    }.start(wait = true)
}
