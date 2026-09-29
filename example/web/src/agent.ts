import { HttpAgent } from "@ag-ui/client";
import type { AgentSubscriber } from "@ag-ui/client";
import type { Message, Tool, ToolCall } from "@ag-ui/core";

const DEFAULT_AGENT_URL = "http://localhost:8787/agent";

/** Frontend tools: advertised to the Koog agent, executed here in the client. */
export const frontendTools: Tool[] = [
  {
    name: "change_background",
    description: "Change the background of the chat page. Use any valid CSS background value, e.g. a colour or linear-gradient.",
    parameters: {
      type: "object",
      properties: {
        background: { type: "string", description: "CSS background value" },
      },
      required: ["background"],
    },
  },
  {
    name: "generate_haiku",
    description: "Show a haiku to the user as a card. Provide three lines in Japanese and their English translation.",
    parameters: {
      type: "object",
      properties: {
        japanese: { type: "array", items: { type: "string" }, description: "Three lines in Japanese" },
        english: { type: "array", items: { type: "string" }, description: "Three lines in English" },
        gradient: { type: "string", description: "CSS linear-gradient used as card background" },
      },
      required: ["japanese", "english", "gradient"],
    },
  },
];

export type FrontendToolHandlers = Record<string, (args: Record<string, unknown>) => string | Promise<string>>;

export interface Todo {
  id: string;
  title: string;
  done: boolean;
}

export interface AppState {
  todos: Todo[];
}

export function createAgent(url = import.meta.env?.VITE_AGENT_URL ?? DEFAULT_AGENT_URL): HttpAgent {
  return new HttpAgent({ url, initialState: { todos: [] } satisfies AppState });
}

/** Assistant tool calls that no tool message has answered yet, limited to frontend tools. */
export function pendingFrontendCalls(messages: Message[]): ToolCall[] {
  const answered = new Set(messages.filter((m) => m.role === "tool").map((m) => (m as { toolCallId: string }).toolCallId));
  const names = new Set(frontendTools.map((t) => t.name));
  return messages
    .flatMap((m) => (m.role === "assistant" ? (m.toolCalls ?? []) : []))
    .filter((call) => names.has(call.function.name) && !answered.has(call.id));
}

let counter = 0;
export const newId = (prefix: string) => `${prefix}-${Date.now().toString(36)}-${(counter++).toString(36)}`;

/**
 * Runs the agent and completes the AG-UI frontend-tool round trip: when a run finishes with frontend tool
 * calls pending, execute them locally, append `tool` messages and run again (bounded).
 */
export async function runWithFrontendTools(
  agent: HttpAgent,
  handlers: FrontendToolHandlers,
  subscriber?: AgentSubscriber,
  maxRounds = 4,
): Promise<{ runs: number; frontendCalls: ToolCall[] }> {
  const executed: ToolCall[] = [];
  for (let round = 1; round <= maxRounds; round++) {
    await agent.runAgent(
      {
        tools: frontendTools,
        context: [{ description: "Name of the user", value: "Demo User" }],
      },
      subscriber,
    );
    const pending = pendingFrontendCalls(agent.messages);
    if (pending.length === 0) return { runs: round, frontendCalls: executed };
    for (const call of pending) {
      const handler = handlers[call.function.name];
      let content: string;
      let error: string | undefined;
      try {
        const args = JSON.parse(call.function.arguments || "{}");
        content = handler ? await handler(args) : `No handler for ${call.function.name}`;
      } catch (e) {
        content = "";
        error = String(e);
      }
      agent.addMessage({ id: newId("tool"), role: "tool", toolCallId: call.id, content, ...(error ? { error } : {}) });
      executed.push(call);
    }
  }
  return { runs: maxRounds, frontendCalls: executed };
}
