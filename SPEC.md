# Spec: AG-UI protocol support for Koog

Status: v1 implemented in this repository (`koog-agui`, `koog-agui-ktor`, `example/`). Verified 2026-09-29: live e2e (5 scenarios, gpt-4.1-mini) green through `@ag-ui/client` 1.0.0 verifier; browser rendering checked.
Later on 2026-09-29 the wire model was extended with interrupts/resume, `CUSTOM`, `ACTIVITY_SNAPSHOT`, `MESSAGES_SNAPSHOT` and the
base event fields, checked against the `@ag-ui/core` 1.0.1 typings and schemas; 50 unit tests green. Published through JitPack (§5.1).
Targets: AG-UI protocol **1.0** (`@ag-ui/client` 1.0.0, `@ag-ui/core` 1.0.1), Koog **1.3.0**, Kotlin 2.3, JVM 21.

## 1. Goal

A Koog agent should be usable as an AG-UI backend, so any AG-UI frontend (CopilotKit, the AG-UI dojo, a plain
`@ag-ui/client` `HttpAgent`) can chat with it, see tokens stream, see backend tool calls and results,
let the model call **frontend tools** (tools implemented in the browser, used for generative UI and
human-in-the-loop), and share state between agent and UI.

Non-goals for v1: server-side thread persistence, protobuf transport, subagent events, predictive state updates,
`ACTIVITY_DELTA`, and interrupt *behaviour* in `KoogAgUiAgent`. Interrupts, resume entries, activity messages,
`MESSAGES_SNAPSHOT` and `CUSTOM` are modelled as wire types so other event sources can use them (§5), but
`KoogAgUiAgent` never emits them and ignores `RunAgentInput.resume`.

## 2. How AG-UI works (the parts that matter)

- Transport: client sends `POST <url>` with `Content-Type: application/json`, `Accept: text/event-stream`
  and a `RunAgentInput` body. Server answers `200 text/event-stream`; each event is one
  `data: <json>\n\n` frame (LF only). The server closes the stream after the terminal event.
- `RunAgentInput`: `threadId`, `runId`, `messages` (required) plus optional `state`, `tools`, `context`,
  `forwardedProps`, `parentRunId`, `resume`. The **whole conversation** comes from the client on every run;
  the server is stateless.
- Messages are discriminated by `role`: `system`, `developer`, `user` (string or content parts),
  `assistant` (`content?`, `toolCalls?[{id, type:"function", function:{name, arguments}}]`),
  `tool` (`toolCallId`, `content` as string or content parts, `error?`), `reasoning` (`content`, `encryptedValue?`),
  `activity` (`activityType`, `content` object). Every message may carry `metadata`.
- Events are discriminated by `type`. Absent optional fields must be **omitted**, not `null`
  (the client's zod schemas reject `null`, including a JSON `null` for `RUN_FINISHED.result` and
  `resume[].payload`). Every event may carry `timestamp` (an integer within ±(2^53 − 1); ms since epoch by
  convention) and `metadata` (an object; JSON `null` values inside it are data and are kept). `rawEvent` is not modelled.
- `RUN_FINISHED` carries an optional `result` (any JSON) and `outcome`: `success` (`pendingToolCallIds?`),
  `interrupt` (`interrupts`, at least one: `{id, reason, message?, toolCallId?, responseSchema?, expiresAt?, metadata?}`)
  or `cancelled`. Absent means success. A run that continues from an interrupt sends
  `resume: [{interruptId, status: "resolved"|"cancelled", payload?, metadata?}]` in its `RunAgentInput`.
- `ACTIVITY_SNAPSHOT(messageId, activityType, content, replace?)` creates or overwrites an `activity` message
  (`replace:false` leaves an existing one untouched); `CUSTOM(name, value)` is the application's own event.
- `MESSAGES_SNAPSHOT(messages)` declares the producer's messages. `@ag-ui/client` 1.0 applies it per role: a known id
  is replaced in place, new ids are appended in snapshot order, and an existing message whose id is missing is
  **removed** if it is `user`, `assistant`, `tool`, `system` or `developer`. So a snapshot must be complete and
  echo the client's message ids. A missing `reasoning` message survives only a snapshot without reasoning messages.
  A missing `activity` message survives only a snapshot without activity messages, or, when the event's
  `metadata["@ag-ui/client"].authoritativeActivityTypes` is a list, only if its `activityType` is not listed
  (`null` there removes every missing activity).
- Client-side verifier rules (enforced by `@ag-ui/client`):
  - first event is `RUN_STARTED` (or `RUN_ERROR`); nothing but `RUN_ERROR`/new `RUN_STARTED` after `RUN_FINISHED`;
  - `TEXT_MESSAGE_START` → `CONTENT*` → `END` per `messageId`; `TOOL_CALL_START` → `ARGS*` → `END` per `toolCallId`;
  - `RUN_FINISHED` fails if any text message, tool call, reasoning message or step is still open;
  - `TOOL_CALL_RESULT` is not checked against a prior start; ended tool calls without a result are allowed;
  - `CUSTOM`, `ACTIVITY_SNAPSHOT` and `MESSAGES_SNAPSHOT` are not tied to open messages and may arrive while text streams.
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
| consecutive `tool` messages | one `Message.User` with `MessagePart.Tool.Result(id, toolName, content, isError = error != null)` per message; tool name is looked up from the preceding assistant tool call; content parts are joined by newlines, non-text parts become placeholders |
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
- Any exception → `RUN_ERROR(code = "agent_error")` and end the stream (the protocol does not require closing open messages first).
  The exception is logged server-side; its text reaches the client only with `exposeErrorDetails = true`.
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
    config = AgUiAgentConfig(shareState = true, maxTurns = 10),   // also: stateToolName, statePrompt, includeContext, exposeErrorDetails
    installFeatures = { /* any Koog feature */ },
)
val events: Flow<AgUiEvent> = agent.run(input: RunAgentInput)
```

- `AgUiJson`: the kotlinx `Json` instance (unknown keys ignored, `explicitNulls = false`, `type` / `role` discriminators).
- `AgUiEvent` sealed hierarchy + `RunAgentInput`, `AgUiMessage`, `AgUiTool` wire model. Beyond what the adapter emits
  it covers `RunOutcome.Interrupt` / `RunOutcome.Cancelled` with `AgUiInterrupt`, `RunAgentInput.resume` (`ResumeEntry`,
  `ResumeStatus`), `CustomEvent`, `ActivitySnapshotEvent`, `MessagesSnapshotEvent`, `ActivityMessage`,
  `ReasoningMessage`, tool messages with content parts, and `timestamp` / `metadata` on every event and `metadata`
  on every message. Messages encode with their `role`, so upstream messages survive decode → `MESSAGES_SNAPSHOT`
  (fields not modelled, such as `encryptedValue` outside reasoning messages and `subagentRunId`, are dropped).
  Values the client schemas reject cannot be built: timestamps outside ±`MAX_SAFE_TIMESTAMP`, JSON `null` as
  `RunFinishedEvent.result` or `ResumeEntry.payload`. `system`/`developer`/`user`/`assistant`/`tool` messages must
  match their schema; any other message, including an `activity` or `reasoning` message that does not, decodes to
  `UnknownMessage`. (Own, lean, lenient
  decoding; the community Kotlin SDK `kotlin-core` 0.4.1 was evaluated and rejected because it lags spec 1.0:
  it fails to decode `reasoning` messages and tools without `parameters`, and rejects empty text deltas).
- `SseEncoder.encode(event): String` → `data: <json>\n\n`; `Throwable.toRunErrorEvent(includeDetails = false)` for terminal errors (the Ktor route never includes details).
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

### 5.1 Publishing

Both library modules apply `maven-publish` (jar, sources jar, empty javadoc jar, POM with license and SCM).
`jitpack.yml` builds with OpenJDK 21 and `./gradlew publishToMavenLocal -x test`. Under JitPack (`JITPACK=true`)
the group becomes `com.github.tkreis.agui-koog-adapter` and the version the tag or commit, so the POM dependency of
`koog-agui-ktor` on `koog-agui` resolves there too; locally it stays `com.ag-ui.community:*:0.1.0-SNAPSHOT`.

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
- `STATE_DELTA` generation (needs a JSON Patch diff); emitting `MESSAGES_SNAPSHOT` or activity events from
  `KoogAgUiAgent`; human-in-the-loop on backend tools through the interrupt outcome (the wire types exist, the
  run-loop semantics do not).
- Multimodal user parts → `MessagePart.Attachment`.
