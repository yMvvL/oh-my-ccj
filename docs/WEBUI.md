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
| `GET` | `/api/config` | what the settings form needs: current values, whether a key exists, where it is stored |
| `POST` | `/api/config` | save provider/model/key settings and switch to them immediately |
| `POST` | `/api/config/test` | send one tiny request with the posted settings **without saving** |

### Settings

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
