# koog-agui

[AG-UI protocol](https://github.com/ag-ui-protocol/ag-ui) support for [Koog](https://docs.koog.ai/) agents.
Any AG-UI frontend (the `@ag-ui/client` `HttpAgent`, CopilotKit, the AG-UI dojo) can talk to a Koog agent. The adapter supports:

- streamed text and reasoning,
- backend tool calls with their results,
- frontend tools (generative UI, human in the loop),
- shared state.

See [SPEC.md](SPEC.md) for the design, the protocol mapping and how it compares to the Spring AI integration.

| Module | Contents |
|---|---|
| `koog-agui` | Wire model (`RunAgentInput`, `AgUiEvent`, `AgUiJson`, `SseEncoder`), `KoogAgUiAgent`, `AgUiStreamTranslator`, AG-UI → Koog message and JSON-schema conversion |
| `koog-agui-ktor` | `Route.agUi(path) { agent }`: POST endpoint that streams SSE |
| `example/server` | Ktor + OpenAI demo agent: backend tool `get_weather`, shared todo state |
| `example/web` | Vite/React client on `@ag-ui/client` 1.0: renders weather cards and haiku cards, applies background changes, shows the todo list; `scripts/e2e.ts` runs a live protocol check |

## Usage

```kotlin
val agent = KoogAgUiAgent(
    promptExecutor = simpleOpenAIExecutor(apiKey),
    model = OpenAIModels.Chat.GPT4_1Mini,
    toolRegistry = ToolRegistry { tool(GetWeatherTool) },   // executed on the server
    systemPrompt = "You are a helpful assistant.",
    config = AgUiAgentConfig(shareState = true),
)

embeddedServer(Netty, port = 8787) {
    routing { agUi("/agent") { agent } }
}.start(wait = true)
```

The browser supplies frontend tools through `RunAgentInput.tools`. When the model calls one of them:

1. The server streams `TOOL_CALL_*` for the call.
2. The run finishes with `outcome.pendingToolCallIds`.
3. The client executes the tool and starts a new run with the result.

## Run the example

```bash
./gradlew :example:server:installDist
OPENAI_API_KEY=... example/server/build/install/server/bin/server   # http://localhost:8787/agent

cd example/web && pnpm install
pnpm dev                                   # http://localhost:5178
AGENT_URL=http://localhost:8787/agent pnpm e2e   # live check through the official client + verifier
```

Optional environment variables:

- `PORT` (default `8787`)
- `OPENAI_MODEL` (`gpt-4.1-mini` default, or `gpt-4o-mini`, `gpt-5-mini`)

## Tests

`./gradlew test` needs no network. The tests use a scripted `PromptExecutor` plus a Kotlin port of the ordering rules from `@ag-ui/client`'s verifier. They cover:

- text streaming,
- the backend tool loop,
- frontend tool stop and replay,
- mixed turns,
- the state tool,
- context,
- errors and the turn cap,
- translator edge cases,
- JSON schema conversion,
- the wire format,
- SSE framing through Ktor.

`vendor/` has clones of `ag-ui` and `koog` that were used as reference. The build does not use them.
