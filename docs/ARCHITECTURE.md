# Architecture

How `ccj` is put together, why each seam is where it is, and what the wire actually looks like.

## Layers

```
  cli/          CliOptions -> Config -> Cli -> (one-shot | REPL | --web)
   |                                        the only System.exit in the codebase
   v
  core/         AgentLoop ---- Provider (interface)  ---- provider/ (HTTP + SSE)
                  |            ToolRegistry (interface) ---- tool/ (filesystem, shell)
                  |            Session (interface)      ---- session/ (JSONL on disk)
                  |            AgentListener (interface) -- ui/ (ConsoleRenderer)
                  v                                        -- web/ (AgentHub + HttpApi + page)
             Config / AppPaths / Message / Json
```

Dependencies point one way: `cli` knows `core`, `provider`, `tool`, `session`, `ui`; `core` knows
nothing about any of them. That is what makes the loop testable headless — the same `AgentLoop` runs
behind a scripted provider in unit tests, behind a mock HTTP server in end-to-end tests, and against
a real relay in production.

## Message model

`core.Message` is a sealed interface over four records, deliberately shaped like a conversation
rather than like any vendor payload:

```java
sealed interface Message
  record System(String text)
  record User(String text)
  record Assistant(String text, List<ToolCall> toolCalls)
  record ToolResult(String toolCallId, String toolName, String content, boolean error)

  record ToolCall(String id, String name, String arguments)   // arguments: raw JSON text
```

Two decisions carry weight:

- **Tool arguments stay raw JSON text.** The core never parses them; providers serialize them onto
  the wire, tools parse them when invoked. A malformed argument string therefore cannot destabilise
  the conversation — it becomes an error result. It also keeps Jackson out of `core`'s public types.
- **`ToolResult.error` is a first-class flag**, not a string prefix, because both wire formats can
  express it (`is_error` for Anthropic; the OpenAI path annotates the content) and the model behaves
  differently when it can tell "the tool refused" from "the tool answered".

`Assistant` is the only message that can carry tool calls, and `Assistant.hasToolCalls()` is what
decides whether the loop continues or stops. There is no separate "done" signal to get out of sync.

## The loop

```
run(input):
  append User(input)
  step = 0
  while step < maxSteps:
      if aborted: return                      # cooperative abort, checked between steps and calls
      onTurnStart(step)
      assistant = provider.complete(request, events -> listener)
      append assistant; onAssistant(assistant)
      if !assistant.hasToolCalls(): return    # model answered; the turn is over
      for call in assistant.toolCalls():
          if aborted: return
          onToolStart(call)
          result = tools.execute(call, context)   # never throws: failures become error results
          onToolEnd(call, result, elapsedMillis)
          append ToolResult(call, result)
      step++
  onNotice("stopped after N steps ...")
```

Properties this buys:

- **Bounded.** `maxSteps` caps model turns per user input, so a model that loops on a broken tool
  cannot burn tokens forever, and the user is told why the run stopped.
- **Fault-isolated.** `ToolRegistry.execute` converts unknown tool names, invalid argument JSON and
  thrown exceptions into `ToolResult.error`, so the model gets to read its own mistake and retry.
  Provider failures are the exception: they abort the run as `AgentException`, because there is
  nothing for the model to react to when the model is what's unreachable.
- **Observable.** The listener receives deltas as they stream (so text renders live), the assembled
  message per turn, and tool start/end with timings. `AgentListener` defaults to no-ops, so a
  headless run needs no renderer at all.
- **Resumable.** Every message is appended to the session as it is created, not at the end of the
  run, so a killed process still leaves a conversation that can be reopened.

## Providers

`Provider.complete(Request, Consumer<Event>)` returns the assembled `Assistant` and streams
`Event`s (text delta, reasoning delta, tool-call start, usage, retry) while the answer arrives.
Blocking-callback rather than reactive because the transport is a blocking line stream and the
listener is what keeps the UI honest.

| Concern | OpenAI-compatible | Anthropic |
|---|---|---|
| Endpoint | `POST {baseUrl}/chat/completions` | `POST {baseUrl}/v1/messages` |
| Auth | `Authorization: Bearer` | `x-api-key` + `anthropic-version` |
| System prompt | a `system` message | top-level `system` field |
| Assistant tool calls | `tool_calls[].function.arguments` (JSON **string**) | content block `tool_use.input` (object) |
| Tool results | one `role: tool` message per result | `tool_result` blocks inside a `user` message |
| Streaming frames | `choices[0].delta.*` | `content_block_start/delta/stop`, `message_delta` |

Both parsers face the same hostile reality: frames arrive split at arbitrary byte boundaries, so a
JSON payload can be delivered in pieces, and tool-call arguments arrive as a sequence of fragments
that must be concatenated before they are valid JSON. The OpenAI path assembles `tool_calls` by
`index` (first fragment carries `id` and `name`, later ones only `arguments`); the Anthropic path
concatenates `partial_json` per content block. The tests feed both parsers deliberately fragmented
input, because that is the failure mode that only shows up in production.

`Transport` (package-private) owns one retry policy for both: up to 3 attempts with exponential
backoff and jitter on connection-phase `IOException`, 408, 429 and 5xx. Two things are deliberately
*not* retried: 4xx other than 408/429 (the request is wrong, retrying it wastes time and money) and
mid-stream failures (deltas already reached the user; replaying would duplicate them).

## Tools and approval

`Tool` is three strings and one method: `name`, `description`, `parametersJson` (a hand-written JSON
Schema) and `execute(argumentsJson, ctx)`. `ToolRegistry` is the only caller and the only place where
failures become results.

`ToolContext` carries the working directory, the byte cap for bulk output, and the `Approver`.
`read`/`glob`/`grep` are read-only and never ask. `write`/`edit`/`bash` call
`ctx.approve(title, detail)` before touching anything, where the detail is what a human needs to
judge the call: the resolved path or the full command, the working directory, the timeout, and an
explicit marker when the path escapes the session cwd. Denial returns
`ToolResult.error("rejected by user")` and performs no I/O at all.

The interactive approver denies by default when `System.console()` is null. A pipe, a cron job or a
CI runner therefore cannot accidentally approve a `rm`; `--yolo` is the explicit opt-in. Failing
closed is the whole point of having an approval step.

Output discipline: every tool that can produce bulk output respects `outputLimitBytes`. `bash`
keeps the head *and* tail with an explicit `... omitted N bytes ...` marker, because the interesting
part of a failing build is usually the end.

## Sessions

One JSONL file per session, `~/.oh-my-ccj/sessions/<id>.jsonl`, appended and flushed per message:

```json
{"type":"user","text":"create proof.txt"}
{"type":"assistant","text":"","tool_calls":[{"id":"call_1","name":"bash","arguments":"{\"command\": \"touch proof.txt\"}"}]}
{"type":"tool_result","tool_call_id":"call_1","tool_name":"bash","content":"exit code 0\n","error":false}
{"type":"assistant","text":"Created proof.txt.","tool_calls":[]}
```

JSONL rather than a database because it is append-only, crash-safe without transactions, greppable,
and resumable by reading the file back — which is exactly `SessionStore.open`. `MessageCodec` owns
the discriminator (`type`) and the round-trip, and it is written by hand so the on-disk format is
version-independent of any library's reflection rules. `--resume <id>` in a later process replays
the file into `FileSession` and the model sees the full prior conversation on the next request.

## Rendering

`ConsoleRenderer` implements `AgentListener` and owns three pieces of state machines: whether a text
block is open, which tool calls have been drawn, and how many tools are running (the spinner is
reference-counted). Two details exist because they were wrong the first time and were caught in a
manual terminal run:

- The spinner must be **paused** while an approval prompt owns the terminal. A spinner redrawing
  itself over a question the user is answering is worse than no spinner.
- Tool cards are printed on `onAssistant` (which carries the arguments) rather than on
  `onToolStart` (which does not), with a pending-set to avoid printing the same call twice.

Everything degrades to plain lines when there is no TTY or when `NO_COLOR` is set.

## Configuration

`Config` is a record of nullable fields meaning "not specified here", with `merge` layering sources
and `resolved()` filling in the provider-dependent defaults
(`openai` → `api.openai.com/v1` + `OPENAI_API_KEY`, `anthropic` → `api.anthropic.com` +
`ANTHROPIC_API_KEY`). Precedence is defaults → file → `CCJ_*` environment → flags, applied by
`Config.layered`. This keeps every source independently testable and means the rest of the code only
ever sees a complete configuration.

## Testing strategy

| Level | What it pins |
|---|---|
| `SseTest` | frame splitting, CRLF, comments, `data: [DONE]`, multi-line payloads |
| `OpenAiProviderTest` / `AnthropicProviderTest` | the exact outgoing body shape, and fragmented stream reassembly into the right `Assistant` |
| `*ToolTest` | match semantics, ambiguity errors, truncation markers, timeout kill, denial performing no I/O |
| `AgentLoopTest` | turn counting, tool round-trips, error recovery, abort, step cap, provider failure wrapping |
| `ConfigTest` | precedence, provider-dependent defaults, redaction, malformed input |
| `CliEndToEndTest` | CLI → HTTP → SSE → tool → real file on disk, both providers, denial path, exit codes |

The end-to-end tests are the ones that matter: nothing below `Cli` is stubbed, and the mock server
uses chunked transfer encoding with deliberately fragmented frames so the client faces the same
boundaries a real relay produces. Everything runs offline with no API key.

## Extension points

- **New tool**: implement `Tool`, register it in `Tools.standard()` (or a custom `ToolRegistry`).
  Nothing else changes; the loop advertises whatever the registry contains.
- **New provider**: implement `Provider` (one method) and add a case to `Providers.create`. If it is
  OpenAI-shaped, `OpenAiProvider` already works — point `baseUrl` at the relay.
- **New front end**: implement `AgentListener`; the loop never references the renderer type.
- **New persistence**: implement `Session`; `MemorySession` shows the minimum.
