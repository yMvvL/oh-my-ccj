# Web UI

`ccj --web` runs the same agent loop behind a local HTTP server with a single-page UI. No framework,
no build step, no new dependency: the server is `com.sun.net.httpserver` and the page is three files
packed into the jar under `/web/`.

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
