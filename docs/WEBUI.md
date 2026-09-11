# Web UI

`ccj` — no arguments — runs the same agent loop behind a local HTTP server with a single-page UI, and
that page is where the model gets configured (`--repl` gives the terminal front end instead). No
framework, no build step, no new dependency: the server is `com.sun.net.httpserver` and the page is
three files packed into the jar under `/web/`.

It exists because the loop is not terminal-shaped: streaming text, tool cards and approvals are all
easier to look at as a page. Design constraint that shaped everything below — **the browser is an
untrusted client**: it gets no shell, no filesystem, and every side effect still goes through
`Approver`.

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/` | the page (`index.html`) |
| `GET` | `/app.js`, `/style.css` | assets, served from the jar |
| `GET` | `/api/status` | provider, model, base URL, cwd, session, tools, busy flag |
| `GET` | `/api/events` | SSE stream of everything the loop reports |
| `POST` | `/api/message` | `{"text": "..."}` — start a turn; `409` when one is already running |
| `POST` | `/api/abort` | stop the running turn at the next safe point |
| `POST` | `/api/approval` | `{"id": "...", "allow": true, "remember": false}` — answer a pending approval |
| `POST` | `/api/auto-approve` | `{"enabled": true}` — flip the whole session to auto-approve |
| `POST` | `/api/session` | `{"action": "new"}` or `{"action": "resume", "id": "..."}` |
| `GET` | `/api/sessions` | session summaries, newest first |
| `GET` | `/api/history` | the current session replayed as render events (see below) |
| `GET` | `/api/workspaces` | every workspace, with the active one, its path and its session count |
| `POST` | `/api/workspaces` | `{"name": "...", "path": "..."}` — register a workspace (creates the directory) |
| `POST` | `/api/workspace` | `{"name": "..."}` — switch to a workspace |
| `DELETE` | `/api/workspace?name=...` | forget a workspace; its session files stay on disk |
| `GET` | `/api/config` | what the settings form needs: current values, whether a key exists, where it is stored |
| `POST` | `/api/config` | save provider/model/key settings and switch to them immediately |
| `POST` | `/api/config/test` | send one tiny request with the posted settings **without saving** |

### Workspaces

A workspace is a working directory plus the sessions that belong to it. Switching one changes where
tools resolve relative paths **and** which conversation history the page shows — the two are the same
decision, because a session only makes sense next to the files it was talking about.

```json
{"active": "oh-my-ccj",
 "workspaces": [
   {"name": "oh-my-ccj", "path": "/home/you/oh-my-ccj", "sessions": 12, "active": true},
   {"name": "api", "path": "/home/you/api", "sessions": 3, "active": false}
 ]}
```

- The registry is `<home>/workspaces.json`. The workspace `ccj` started in is seeded from the starting
  directory and is the active one; it keeps using the top-level `<home>/sessions` directory, so
  sessions from before workspaces existed stay where they were.
- Workspaces added later store their sessions under `<home>/workspaces/<name>/sessions/`, and the
  directory is created if it does not exist.
- Model settings (`config.json`) are **not** per workspace: a key and a model are a property of the
  account, not of the folder. Everything else — working directory, sessions, usage totals — is.
- Switching starts a **new** session in the target workspace (an empty one leaves no file); the
  sessions list then shows that workspace's history to resume from.
- `DELETE` only forgets the registry entry. Deleting conversation files because a folder was removed
  from a list would be a surprising thing for a tool to do.
- `ccj --workspace <name>` does the same thing for the terminal front end.

## Settings

`ccj` with no arguments serves the web UI, and the UI is where a model gets configured — no JSON
editing, no restart. `GET /api/config` returns:

```json
{
  "configured": true,
  "provider": "openai",
  "model": "gpt-4o-mini",
  "baseUrl": "https://api.openai.com/v1",
  "apiKeyEnv": "OPENAI_API_KEY",
  "apiKeySource": "env",
  "maxSteps": 25,
  "temperature": null,
  "maxTokens": null,
  "configFile": "/home/you/.oh-my-ccj/config.json",
  "providers": ["openai", "anthropic", "openai-compatible", "deepseek", "groq", "ollama", "custom"]
}
```

`apiKeySource` is `config`, `env` or `none`; the key itself is never sent to the browser. `POST
/api/config` takes any subset of
`{provider, model, baseUrl, apiKey, apiKeyEnv, maxSteps, temperature, maxTokens}` and returns the new
`status` payload. Rules:

- An omitted or empty `apiKey` keeps the stored one; `"clearApiKey": true` deletes it.
- The provider is built and validated **before** anything is written, so a typo cannot leave a broken
  config behind; a rejected change returns `400` with `{"error": "..."}` and changes nothing.
- Fields the form does not manage (system prompt, output caps, auto-approve) are preserved in the file.
- The file is written with `0600` permissions because it may hold a key.

`POST /api/config/test` runs one minimal turn with the posted values (no save) and answers
`{"ok": true, "reply": "...", "elapsedMs": 420}` or `400 {"error": "HTTP 401: bad key"}`. It is the
only endpoint that spends the user's tokens, and only when they ask for it.

While no provider is configured the UI still works: the status reports `configured: false`, sending a
turn returns `409 {"error": "no model configured — open Settings"}`, and the settings form opens on
first load.

All JSON is UTF-8. Errors are `{"error": "..."}` with a non-2xx status. The server binds loopback;
binding another interface requires an explicit token, and every request must then carry it as
`?token=` or `Authorization: Bearer`.

## SSE events

One JSON object per `data:` line, each with a `type`, and an `id:` line so a reconnecting browser can
resume with `Last-Event-ID` (the server keeps a small replay buffer).

| `type` | Fields | Meaning |
|---|---|---|
| `status` | as `/api/status`, plus `busy` | sent on connect, after session switches and when a turn ends |
| `user` | `text` | the turn's input, echoed so the transcript is server-authoritative |
| `text` | `delta` | assistant prose, streamed |
| `reasoning` | `delta` | reasoning/thinking content, streamed |
| `tool` | `id`, `name`, `summary`, `state` (`start`\|`end`), `ok`, `elapsedMs`, `output` | tool call lifecycle; `summary` is a one-line argument digest |
| `approval` | `id`, `title`, `detail` | the loop is blocked waiting for an answer |
| `approval-closed` | `id`, `allow` | answered (by a browser or by timeout) |
| `notice` | `text` | token usage, retries, step-limit warnings |
| `done` | `finalText`, `aborted` | the turn finished |
| `error` | `message` | the turn failed |
| `usage` | `turns`, `steps`, `inputTokens`, `outputTokens`, `cachedInputTokens`, `cacheHitRate`, `toolCalls`, `toolErrors`, `elapsedMs` | running totals for the current session |

## History and usage

A page that loads, reloads, or switches sessions must show the conversation that already exists —
otherwise resuming a session looks like it worked and then shows an empty screen. `GET /api/history`
returns the current session encoded as **the same event objects the stream emits**, so the page
renders history and live turns with one code path:

```json
{"sessionId": "20260911-233115-94cf",
 "usage": {"turns": 1, "steps": 2, "inputTokens": 1200, "outputTokens": 340,
           "cachedInputTokens": 900, "cacheHitRate": 0.75, "toolCalls": 1,
           "toolErrors": 0, "elapsedMs": 480},
 "events": [
   {"type": "user", "text": "read pom.xml", "replay": true},
   {"type": "tool", "id": "call_1", "name": "read", "summary": "pom.xml",
    "state": "start", "replay": true},
   {"type": "tool", "id": "call_1", "name": "read", "state": "end", "ok": true,
    "elapsedMs": null, "output": "…", "replay": true},
   {"type": "text", "delta": "Reading it now.", "replay": true}
 ]}
```

Replay events carry `replay: true` and have no SSE id (they are not part of the live stream, and the
dedupe that protects reconnects does not apply to them). A tool call and its result are paired by
`id`, so the renderer produces the same card it would have produced live. Two fields differ from the
live stream: replayed `tool` end events have `elapsedMs: null` because the session file stores the
conversation and not timings, and no `notice` events are replayed at all — token lines are transient,
and the usage panel is fed by `usage` instead.

`usage` totals belong to the session, not to the process: every turn end appends one accounting line
to the session file (`{"type":"usage","input_tokens":…}`) and reopening a session restores it, so a
resumed conversation continues its cache hit rate instead of starting at zero. They arrive as an
event during and after every turn and are also embedded in the `status` payload as a `usage` object,
so a fresh page has numbers before the first turn. `turns` counts user turns, `steps` counts model
turns (a turn that calls three tools is three steps plus the answer), and `elapsedMs` is time spent
inside turns. `cachedInputTokens` and `cacheHitRate` are `null` when the
provider reported no cache figures — "no information" and "nothing was cached" are different things,
and reporting 0% for a local model would be a lie.

Cache accounting per protocol: OpenAI-compatible endpoints report
`prompt_tokens_details.cached_tokens` (DeepSeek and others use `prompt_cache_hit_tokens`), which is
already included in `prompt_tokens`; Anthropic reports `cache_read_input_tokens` and
`cache_creation_input_tokens` **in addition to** `input_tokens`, so the prompt size is their sum and
the hit rate is `cache_read / prompt size`.

## Session lifecycle

A session file is created on the **first message**, not when a session id is minted: opening the
CLI, or pressing `New session`, must not leave an empty file behind that pollutes the session list.
`POST /api/session {"action": "new"}` on an already-empty session is a no-op and says so through a
`notice` event rather than minting yet another id.

Accounting records share the file but are not messages: `FileSession` filters them out of the
conversation, and the last one wins when a session is reopened.

## Approval

`WebApprover` publishes an `approval` event and blocks the loop thread, so a tool call that needs a
human cannot proceed on its own. Timeout is **deny** — the safe default, and the same rule the CLI
uses when stdin is not a terminal. `remember: true` flips the session to auto-approve, which the UI
shows as a visible, revertible state.

## Reuse

The web layer is a front end like any other: it builds the same `Provider`, `ToolRegistry`,
`FileSession` and `AgentLoop` the CLI does, and implements `AgentListener` to translate callbacks
into events. Sessions are shared with the CLI, so `ccj --web` can pick up a conversation started in
the terminal.

## Room for a router

Nothing here assumes the model is a first-party endpoint: it is one `baseUrl` + `model`, which is
exactly what an OpenAI-compatible router needs. A router panel (model catalogue, key health, request
logs, cost) adds `GET /api/router/*` endpoints plus a pane in the page; the status payload already
carries `provider`, `model` and `baseUrl`, so the pane has somewhere to start without redesigning
anything above.
