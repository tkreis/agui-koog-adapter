import { StrictMode, useEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import type { Message, ToolCall } from "@ag-ui/core";
import { createAgent, newId, runWithFrontendTools, type AppState, type Todo } from "./agent";
import "./styles.css";

type Haiku = { japanese: string[]; english: string[]; gradient: string };

const suggestions = [
  "What's the weather in Munich?",
  "Change the background to a sunset gradient",
  "Write a haiku about the sea",
  "Add todos: water plants, call Alex",
];

function safeParse(json: string): Record<string, unknown> {
  try {
    return JSON.parse(json);
  } catch {
    return {};
  }
}

function WeatherCard({ call, result }: { call: ToolCall; result?: string }) {
  const args = safeParse(call.function.arguments);
  if (!result) return <div className="card weather loading">Fetching weather for {String(args.location ?? "…")}…</div>;
  const w = safeParse(result) as Record<string, string | number>;
  return (
    <div className="card weather" data-testid="weather-card">
      <div className="weather-head">
        <span className="city">{w.location}</span>
        <span className="temp">{w.temperature} °C</span>
      </div>
      <div className="conditions">{w.conditions}</div>
      <div className="weather-grid">
        <span>Feels like {w.feels_like} °C</span>
        <span>Humidity {w.humidity} %</span>
        <span>Wind {w.wind_speed} km/h</span>
      </div>
    </div>
  );
}

function HaikuCard({ haiku }: { haiku: Partial<Haiku> }) {
  return (
    <div className="card haiku" data-testid="haiku-card" style={{ background: haiku.gradient }}>
      {(haiku.japanese ?? []).map((line, i) => (
        <div key={i} className="haiku-line">
          <div className="jp">{line}</div>
          <div className="en">{haiku.english?.[i]}</div>
        </div>
      ))}
    </div>
  );
}

function ToolCallView({ call, results }: { call: ToolCall; results: Map<string, string> }) {
  switch (call.function.name) {
    case "get_weather":
      return <WeatherCard call={call} result={results.get(call.id)} />;
    case "generate_haiku":
      return <HaikuCard haiku={safeParse(call.function.arguments) as Partial<Haiku>} />;
    case "change_background":
      return (
        <div className="chip" data-testid="background-chip">
          <span className="swatch" style={{ background: String(safeParse(call.function.arguments).background ?? "") }} />
          background changed
        </div>
      );
    default:
      return <div className="chip">tool: {call.function.name}</div>;
  }
}

function TodoPanel({ todos, onToggle }: { todos: Todo[]; onToggle: (id: string) => void }) {
  return (
    <aside className="todos" data-testid="todo-panel">
      <h2>Shared state · todos</h2>
      {todos.length === 0 && <p className="muted">Empty. Ask the agent to add some.</p>}
      <ul>
        {todos.map((t) => (
          <li key={t.id} className={t.done ? "done" : ""}>
            <label>
              <input type="checkbox" checked={t.done} onChange={() => onToggle(t.id)} /> {t.title}
            </label>
          </li>
        ))}
      </ul>
    </aside>
  );
}

function App() {
  const agent = useMemo(createAgent, []);
  const [messages, setMessages] = useState<Message[]>([]);
  const [state, setState] = useState<AppState>({ todos: [] });
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [background, setBackground] = useState<string>("");
  const [input, setInput] = useState("");
  const bottom = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const sub = agent.subscribe({
      onMessagesChanged: ({ messages }) => setMessages([...messages]),
      onStateChanged: ({ state }) => setState(structuredClone(state) as AppState),
    });
    return () => sub.unsubscribe();
  }, [agent]);

  useEffect(() => {
    bottom.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages]);

  const toolResults = useMemo(() => {
    const map = new Map<string, string>();
    messages.forEach((m) => m.role === "tool" && typeof m.content === "string" && map.set(m.toolCallId, m.content));
    return map;
  }, [messages]);

  async function send(text: string) {
    if (!text.trim() || running) return;
    setInput("");
    setError(null);
    agent.addMessage({ id: newId("user"), role: "user", content: text });
    setRunning(true);
    try {
      await runWithFrontendTools(agent, {
        change_background: ({ background }) => {
          setBackground(String(background));
          return "Background changed.";
        },
        generate_haiku: () => "Haiku displayed to the user.",
      });
    } catch (e) {
      setError(String(e));
    } finally {
      setRunning(false);
    }
  }

  function toggleTodo(id: string) {
    const todos = state.todos.map((t) => (t.id === id ? { ...t, done: !t.done } : t));
    agent.setState({ ...state, todos });
  }

  return (
    <div className="page" style={{ background: background || undefined }}>
      <main className="chat">
        <header>
          <h1>Koog agent · AG-UI</h1>
          <span className={`status ${running ? "on" : ""}`}>{running ? "running" : "idle"}</span>
        </header>
        <div className="messages" data-testid="messages">
          {messages.map((m) => {
            if (m.role === "user")
              return (
                <div key={m.id} className="bubble user">
                  {typeof m.content === "string" ? m.content : "[content]"}
                </div>
              );
            if (m.role === "assistant")
              return (
                <div key={m.id} className="assistant">
                  {m.content && <div className="bubble bot">{m.content}</div>}
                  {m.toolCalls?.map((call) => <ToolCallView key={call.id} call={call} results={toolResults} />)}
                </div>
              );
            return null;
          })}
          {error && <div className="error">{error}</div>}
          <div ref={bottom} />
        </div>
        <div className="suggestions">
          {suggestions.map((s) => (
            <button key={s} disabled={running} onClick={() => send(s)}>
              {s}
            </button>
          ))}
        </div>
        <form
          className="composer"
          onSubmit={(e) => {
            e.preventDefault();
            send(input);
          }}
        >
          <input value={input} onChange={(e) => setInput(e.target.value)} placeholder="Ask the Koog agent…" />
          <button type="submit" disabled={running}>
            Send
          </button>
        </form>
      </main>
      <TodoPanel todos={state.todos ?? []} onToggle={toggleTodo} />
    </div>
  );
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
