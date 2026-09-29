/**
 * Live protocol check: drives the Koog AG-UI server with the official @ag-ui/client HttpAgent
 * (which validates every event with its schemas and verifier) against a real LLM.
 *
 *   AGENT_URL=http://localhost:8787/agent pnpm e2e
 */
import type { BaseEvent } from "@ag-ui/core";
import { createAgent, runWithFrontendTools, type AppState } from "../src/agent";

type Scenario = { name: string; prompt: string; check: (ctx: Ctx) => void };
type Ctx = {
  events: BaseEvent[];
  state: AppState;
  frontendCalls: { name: string; args: Record<string, unknown> }[];
  runs: number;
  finalText: string;
};

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

const types = (events: BaseEvent[]) => events.map((e) => e.type as string);

const scenarios: Scenario[] = [
  {
    name: "streamed text",
    prompt: "Say hello in exactly five words.",
    check: ({ events, finalText }) => {
      assert(types(events).filter((t) => t === "TEXT_MESSAGE_CONTENT").length > 1, "text should stream in several deltas");
      assert(finalText.length > 0, "assistant text expected");
    },
  },
  {
    name: "backend tool (get_weather) with result",
    prompt: "What's the weather in Berlin right now?",
    check: ({ events }) => {
      const start = events.find((e) => e.type === "TOOL_CALL_START") as { toolCallName?: string } | undefined;
      assert(start?.toolCallName === "get_weather", "get_weather tool call expected");
      const result = events.find((e) => e.type === "TOOL_CALL_RESULT") as { content?: string } | undefined;
      assert(result && JSON.parse(result.content!).temperature !== undefined, "TOOL_CALL_RESULT with weather JSON expected");
    },
  },
  {
    name: "frontend tool (change_background) round trip",
    prompt: "Please change the background to a dark blue gradient.",
    check: ({ frontendCalls, runs, events }) => {
      const call = frontendCalls.find((c) => c.name === "change_background");
      assert(call && typeof call.args.background === "string", "change_background call with a background string expected");
      assert(runs >= 2, "client must re-run after executing the frontend tool");
      const finished = events.filter((e) => e.type === "RUN_FINISHED") as { outcome?: { pendingToolCallIds?: string[] } }[];
      assert(finished[0]?.outcome?.pendingToolCallIds?.length, "first RUN_FINISHED should list pending frontend tool calls");
    },
  },
  {
    name: "generative UI (generate_haiku)",
    prompt: "Write me a haiku about autumn leaves.",
    check: ({ frontendCalls }) => {
      const call = frontendCalls.find((c) => c.name === "generate_haiku");
      assert(call, "generate_haiku call expected");
      assert(Array.isArray(call.args.japanese) && (call.args.japanese as unknown[]).length === 3, "3 Japanese lines expected");
      assert(Array.isArray(call.args.english) && (call.args.english as unknown[]).length === 3, "3 English lines expected");
    },
  },
  {
    name: "shared state (update_state)",
    prompt: "Add two todos: 'buy oat milk' and 'book train to Hamburg'.",
    check: ({ state, events }) => {
      assert(state.todos.length === 2, `expected 2 todos, got ${JSON.stringify(state.todos)}`);
      assert(state.todos.some((t) => /oat milk/i.test(t.title)), "oat milk todo expected");
      assert(!types(events).includes("TOOL_CALL_START") || !events.some((e) => (e as { toolCallName?: string }).toolCallName === "update_state"),
        "update_state must not be shown as a tool call");
    },
  },
];

async function runScenario(s: Scenario): Promise<void> {
  const agent = createAgent(process.env.AGENT_URL);
  agent.addMessage({ id: `u-${Date.now()}`, role: "user", content: s.prompt });
  const events: BaseEvent[] = [];
  const frontendCalls: Ctx["frontendCalls"] = [];
  const handlers = {
    change_background: (args: Record<string, unknown>) => {
      frontendCalls.push({ name: "change_background", args });
      return "Background changed.";
    },
    generate_haiku: (args: Record<string, unknown>) => {
      frontendCalls.push({ name: "generate_haiku", args });
      return "Haiku displayed to the user.";
    },
  };
  const { runs } = await runWithFrontendTools(agent, handlers, {
    onEvent: ({ event }) => {
      events.push(event);
    },
    onRunErrorEvent: ({ event }) => {
      throw new Error(`RUN_ERROR: ${event.message}`);
    },
  });
  const last = [...agent.messages].reverse().find((m) => m.role === "assistant" && typeof m.content === "string" && m.content);
  s.check({ events, state: agent.state as AppState, frontendCalls, runs, finalText: (last?.content as string) ?? "" });
  console.log(`PASS  ${s.name}  (runs=${runs}, events=${events.length})`);
  if (last?.content) console.log(`      assistant: ${String(last.content).slice(0, 120)}`);
}

let failed = 0;
for (const s of scenarios) {
  try {
    await runScenario(s);
  } catch (e) {
    failed++;
    console.error(`FAIL  ${s.name}: ${(e as Error).message}`);
  }
}
console.log(failed ? `\n${failed} scenario(s) failed` : "\nall scenarios passed");
process.exit(failed ? 1 : 0);
