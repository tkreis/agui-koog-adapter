# Spec: AG-UI protocol support for Koog

Status: v1 implemented in this repository (`koog-agui`, `koog-agui-ktor`, `example/`). Verified 2026-09-29: 28 unit tests green; live e2e (5 scenarios, gpt-4.1-mini) green through `@ag-ui/client` 1.0.0 verifier; browser rendering checked.
Targets: AG-UI protocol **1.0** (`@ag-ui/client` 1.0.0), Koog **1.3.0**, Kotlin 2.3, JVM 21.

## 1. Goal

A Koog agent should be usable as an AG-UI backend, so any AG-UI frontend (CopilotKit, the AG-UI dojo, a plain
`@ag-ui/client` `HttpAgent`) can chat with it, see tokens stream, see backend tool calls and results,
let the model call **frontend tools** (tools implemented in the browser, used for generative UI and
human-in-the-loop), and share state between agent and UI.

Non-goals for v1: server-side thread persistence, protobuf transport, `resume`/interrupt outcomes,
activity events, subagent events, predictive state updates.

## 2. How AG-UI works (the parts that matter)

- Transport: client sends `POST <url>` with `Content-Type: application/json`, `Accept: text/event-stream`
  and a `RunAgentInput` body. Server answers `200 text/event-stream`; each event is one
  `data: <json>\n\n` frame (LF only). The server closes the stream after the terminal event.
- `RunAgentInput`: `threadId`, `runId`, `messages` (required) plus optional `state`, `tools`, `context`,
  `forwardedProps`, `parentRunId`, `resume`. The **whole conversation** comes from the client on every run;
  the server is stateless.
- Messages are discriminated by `role`: `system`, `developer`, `user` (string or content parts),
  `assistant` (`content?`, `toolCalls?[{id, type:"function", function:{name, arguments}}]`),
  `tool` (`toolCallId`, `content`, `error?`), `reasoning`, `activity`.
- Events are discriminated by `type`. Absent optional fields must be **omitted**, not `null`
  (the client's zod schemas reject `null`).
- Client-side verifier rules (enforced by `@ag-ui/client`):
  - first event is `RUN_STARTED` (or `RUN_ERROR`); nothing but `RUN_ERROR`/new `RUN_STARTED` after `RUN_FINISHED`;
  - `TEXT_MESSAGE_START` → `CONTENT*` → `END` per `messageId`; `TOOL_CALL_START` → `ARGS*` → `END` per `toolCallId`;
  - `RUN_FINISHED` fails if any text message, tool call, reasoning message or step is still open;
  - `TOOL_CALL_RESULT` is not checked against a prior start; ended tool calls without a result are allowed.
- Frontend tools: the client advertises `tools` (name, description, JSON-schema `parameters`). If the model
  calls one, the agent streams `TOOL_CALL_START/ARGS/END`, **must not** emit `TOOL_CALL_RESULT`, and finishes the
  run (`RUN_FINISHED`, outcome `success` with `pendingToolCallIds`). The client runs the tool (usually renders UI)
  and starts a new run whose `messages` contain the assistant tool call plus a `role:"tool"` result.
- Backend tools: executed by the server; reported as `TOOL_CALL_START/ARGS/END` followed by
  `TOOL_CALL_RESULT` (own `messageId`, `toolCallId`, `content`, `role:"tool"`).
- Shared state: `input.state` is the current UI state; the agent pushes `STATE_SNAPSHOT` (full) or
  `STATE_DELTA` (RFC 6902 patch).

## 3. How the Spring AI integration does it (reference design)

Source: `ag-ui/sdks/community/java/spring` (published as `com.ag-ui.community:ag-ui-spring-ai:2.0.0`).

| Concern | Spring AI approach |
|---|---|
| Endpoint | `AgUiController` (WebFlux/WebMVC): `POST /agent[/{beanName}]`, `text/event-stream`, body parsed with a Jackson `Serializer`; each event → `data:` frame; errors after stream start → `RUN_ERROR`. |
| Wire model | Own Java records + Jackson mixins (`java-core`), not generated code. |
| Run skeleton | `RUN_STARTED` → optional `STATE_SNAPSHOT` → turns → optional `MESSAGES_SNAPSHOT` → `RUN_FINISHED` (or `RUN_ERROR`). |
| History | `RunAgentInput.messages` converted to Spring AI messages each run (`AssistantMessage` with `ToolCall`s, `ToolResponseMessage`, system/developer → `SystemMessage`). |
| Frontend tools | Wrapped as `AgUiToolCallback` placeholders (definition only, `call()` throws). Spring AI's automatic tool execution is **disabled**; the adapter owns the tool loop. |
| Tool loop | After each streamed turn: backend calls → execute via `ToolCallingManager`, emit `TOOL_CALL_RESULT`, re-prompt. Any frontend call → stop, finish run. Loop cap 8. |
| Streaming | A per-turn `SpringAiEventTranslator` state machine turns chunks into `TEXT_MESSAGE_*` / `TOOL_CALL_*` / `REASONING_*` (from `<think>` tags); closes open text before a tool call starts; `parentMessageId` = current assistant message id. |
| State | Opt-in `shareState`: state JSON injected as a system message, an `update_state({state})` tool is advertised; its calls are swallowed and converted to `STATE_SNAPSHOT` (or diffed into `STATE_DELTA`). |
| Gaps | `context`/`forwardedProps` ignored, no step events, one assistant id per turn. |

## 4. Mapping onto Koog

Koog has no AG-UI support today. The closest in-tree analogue is the ACP feature
(`agents-features-acp`, `channelFlow { AIAgent(...) { install(AcpAgent) { eventsProducer = this@channelFlow } } }`).

Koog building blocks used:

| Need | Koog API |
|---|---|
| Seed history | `AIAgentConfig(prompt = Prompt(messages, id), model, maxAgentIterations)` + `AIAgent(promptExecutor, agentConfig, strategy, toolRegistry, installFeatures)` |
| Custom loop | `functionalStrategy<Unit, String> { ... }` (`AIAgentFunctionalContext`) |
| Token streaming | `llm.writeSession { requestLLMStreaming() }` → `Flow<StreamFrame>`; frames: `TextDelta/TextComplete`, `ToolCallDelta/ToolCallComplete`, `ReasoningDelta/ReasoningComplete`, `End`. Segments are sequential; each ends with a `*Complete` frame. |
| Keep history | streaming does **not** append the answer; append `frames.toMessageResponse()` manually. |
| Descriptor-only (frontend) tools | `llm.writeSession { tools = tools + frontendDescriptors }`. Never executed because the strategy stops before `executeTool`. |
| Backend tools | normal Koog `ToolRegistry`; run with `executeTool(call)` → `ReceivedToolResult`; append result with `toMessagePart()`. |
| JSON Schema → tool | `ToolDescriptor` + `ToolParameterType` (converter written in the adapter, modelled on `DefaultMcpToolDescriptorParser`). |
| Features | caller can still `install(...)` any Koog feature (tracing, EventHandler, OpenTelemetry) via `installFeatures`. |

Why a strategy and not only a Koog `Feature`: a feature can observe frames and tool calls, but cannot stop the
agent loop when a frontend tool is called; and `singleRunStrategy` would try to execute the frontend tool,
get "tool not found" and feed that error back to the model. The adapter therefore owns the loop, exactly as the
Spring AI adapter disables Spring AI's tool execution.

### 4.1 Message conversion (AG-UI → Koog)

| AG-UI | Koog |
|---|---|
| `system`, `developer` | `Message.System` |
| `user` (string) | `Message.User(content)` |
| `user` (parts) | `Message.User` with one `MessagePart.Text` per part; binary parts → text placeholder `[<type> attachment omitted]` (v1) |
| `assistant` | `Message.Assistant(parts = [Text?] + Tool.Call(id, name, arguments)*)` |
| consecutive `tool` messages | one `Message.User` with `MessagePart.Tool.Result(id, toolName, content, isError = error != null)` per message; tool name is looked up from the preceding assistant tool call |
| `reasoning`, `activity`, unknown roles | dropped (decoded leniently, never fail the run) |

Server-side preamble, in this order: configured system prompt, then (if `context` non-empty) a system message
listing `description: value` pairs, then (if state sharing is on) a system message with the
current state JSON (`{}` if the client sent none) and the `update_state` instructions.

Frontend tool descriptors are advertised on every LLM request so Koog's `MissingToolsConversionStrategy` does not
flatten historic frontend tool calls into plain text.

### 4.2 Event translation (Koog frames → AG-UI)

One `AgUiStreamTranslator` per LLM turn, one fresh assistant `messageId` per turn. A second text segment in the same turn
(text after a tool call) gets `<messageId>-N`, because the verifier forbids reusing a closed message id.

| Frame | Events |
|---|---|
| `TextDelta(text)` | `TEXT_MESSAGE_START(messageId, role=assistant)` on first delta, then `TEXT_MESSAGE_CONTENT(delta)` (empty deltas skipped) |
| `TextComplete` | `TEXT_MESSAGE_END` (or START/CONTENT/END if no delta was seen) |
| `ReasoningDelta` / `ReasoningComplete` | `REASONING_START` + `REASONING_MESSAGE_START/CONTENT/END` + `REASONING_END` (id `<messageId>-reasoning-N`) |
| `ToolCallDelta` (new call) | close open text, `TOOL_CALL_START(toolCallId, toolCallName, parentMessageId)`; content → `TOOL_CALL_ARGS` |
| `ToolCallComplete` | `TOOL_CALL_END` (or START/ARGS/END if no delta was seen); a missing id is synthesised |
| `End` | close anything still open |
| state tool call (`update_state`) | no `TOOL_CALL_*`; after the turn: `STATE_SNAPSHOT(snapshot)` if the arguments hold a valid state |

### 4.3 Run loop

```
RUN_STARTED(threadId, runId)
[STATE_SNAPSHOT(input.state)]              if shareState and state present
repeat (maxTurns):
    stream one LLM turn → translated events
    append assistant message to Koog prompt
    calls = tool calls of this turn
    if calls empty → break
    handle update_state → STATE_SNAPSHOT, tool result "State updated." appended
                          (invalid arguments → error result, state untouched)
    frontend calls present → break (pending)
    execute backend calls → TOOL_CALL_RESULT(messageId, toolCallId, content), results appended
RUN_FINISHED(threadId, runId, outcome = {type:"success", pendingToolCallIds?})
```

- A turn containing both backend and frontend calls: backend calls are executed and reported, then the run ends
  with the frontend calls pending (the client re-runs with the frontend results; Koog history stays valid since
  every call id gets a result from one side).
- Unknown tool names (neither backend nor frontend): executed through Koog anyway so the model gets Koog's
  "tool not found" failure and can recover.
- Loop cap (`maxTurns`, default 10) reached without a final answer → `RUN_ERROR(code = "max_turns")`.
  (Koog's `maxAgentIterations` does not bound functional strategies, so `maxTurns` is the only guard.)
- Any exception → `RUN_ERROR(message, code = "agent_error")` and end the stream (the protocol does not require closing open messages first).
- Reasoning, text and tool calls are appended to the Koog prompt in arrival order, including provider reasoning
  signatures (`MessagePart.Reasoning.encrypted`), so reasoning models can continue a backend-tool loop.
- Client disconnect cancels the coroutine; the Koog agent is cancelled with it.

## 5. Public API

Module `koog-agui` (group `com.ag-ui.community`, package `com.agui.community.koog`):

```kotlin
val agent = KoogAgUiAgent(
    promptExecutor = simpleOpenAIExecutor(apiKey),
    model = OpenAIModels.Chat.GPT4_1Mini,
    toolRegistry = ToolRegistry { tool(GetWeatherTool) },   // backend tools
    systemPrompt = "You are a helpful assistant.",
    config = AgUiAgentConfig(shareState = true, maxTurns = 10),   // also: stateToolName, statePrompt, includeContext
    installFeatures = { /* any Koog feature */ },
)
val events: Flow<AgUiEvent> = agent.run(input: RunAgentInput)
```

- `AgUiJson`: the kotlinx `Json` instance (unknown keys ignored, `explicitNulls = false`, `type` / `role` discriminators).
- `AgUiEvent` sealed hierarchy + `RunAgentInput`, `AgUiMessage`, `AgUiTool` wire model (own, lean, lenient
  decoding; the community Kotlin SDK `kotlin-core` 0.4.1 was evaluated and rejected because it lags spec 1.0:
  it fails to decode `reasoning` messages and tools without `parameters`, and rejects empty text deltas).
- `SseEncoder.encode(event): String` → `data: <json>\n\n`; `Throwable.toRunErrorEvent()` for terminal errors.
- Building blocks are public for custom strategies: `toKoogMessages()`, `JsonSchemaToolDescriptors`,
  `AgUiStreamTranslator`.

Module `koog-agui-ktor`:

```kotlin
routing {
    agUi("/agent") { input -> agent }        // POST, SSE, CORS left to the app
}
```

Spring (phase 2, not implemented here): a `KoogAgUiController` in the style of the Spring AI starter,
`Flow<AgUiEvent>.asFlux()` mapped to `ServerSentEvent<String>`, auto-configured from Koog's
`koog-spring-boot-starter` `PromptExecutor` bean.

## 6. Example and verification

`example/server` (Ktor, port 8787, OpenAI `gpt-4.1-mini`, needs `OPENAI_API_KEY`):

- backend tool `get_weather(location)` (returns JSON; tests backend tool rendering),
- frontend tools come from the web client: `change_background(background)`, `generate_haiku(japanese, english, gradient)`,
- shared state: a `todos` list the model updates with `update_state`.

`example/web` (Vite + React + `@ag-ui/client` 1.0 `HttpAgent`):

- chat with streamed text, renders backend `get_weather` calls as weather cards, renders `generate_haiku` as
  a haiku card, applies `change_background` to the page, shows shared state as a todo list,
- after a frontend tool runs it sends the `tool` result and re-runs (the AG-UI frontend-tool round trip).

Verification layers:

1. Unit tests (no network): message conversion, JSON schema conversion, translator event order,
   full-run tests with a scripted `PromptExecutor` (text, backend tool loop, frontend tool stop, state tool,
   error → `RUN_ERROR`), SSE framing through Ktor `testApplication`.
2. Protocol conformance with the real client: `example/web/scripts/e2e.ts` drives the live server with
   `@ag-ui/client` `HttpAgent` (which runs the official verifier) and real OpenAI, for each scenario.
3. Browser: the web app rendered in a browser, screenshots of the generative UI.

## 7. Open questions / follow-ups

- Upstream location: `ag-ui/integrations/community/koog` (Kotlin adapter + tiny TS `HttpAgent` subclass + dojo
  registration) vs. a Koog module `agents-features-agui`. The code is dependency-light so either works.
- `STATE_DELTA` generation (needs a JSON Patch diff), `MESSAGES_SNAPSHOT`, `resume`/interrupt outcome for
  human-in-the-loop on backend tools, activity events.
- Multimodal user parts → `MessagePart.Attachment`.
