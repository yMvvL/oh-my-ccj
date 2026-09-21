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
             Config / AppPaths / Message / Json / SessionRepair (a sendable projection of history)
```

Dependencies point one way: `cli` knows `core`, `provider`, `tool`, `session`, `ui`; `core` knows
nothing about any of them. That is what makes the loop testable headless — the same `AgentLoop` runs
behind a scripted provider in unit tests, behind a mock HTTP server in end-to-end tests, and against
a real relay in production.

### 目录 → 职责

```
cli/       argument parsing, wiring, REPL, exit codes   (the only place that calls System.exit)
ui/        AgentListener implementation: streaming text, tool cards, spinner, ANSI
web/       the same loop behind HTTP: agent hub, SSE stream, approval handshake, single page
workspace/ the workspace registry: named directories and where their sessions live
demo/      a tool-routing stand-in for a model, so the CLI can be demonstrated with no key
session/   JSONL message codec, FileSession, SessionStore
tool/      read write edit bash glob grep + approval-aware helpers
provider/  OpenAI-compatible and Anthropic providers over java.net.http, plus the SSE reader
core/      Message, Provider, Tool, ToolRegistry, AgentLoop, Session, Config, AppPaths
```

## Message model

`core.Message` is a sealed interface over four records, deliberately shaped like a conversation
rather than like any vendor payload:

```java
sealed interface Message
  record System(String text)
  record User(String text)
  record Assistant(String text, List<ToolCall> toolCalls, List<Thinking> thinking)
  record ToolResult(String toolCallId, String toolName, String content, boolean error)

  record ToolCall(String id, String name, String arguments)   // arguments: raw JSON text
  record Thinking(String text, String signature, String data) // one thinking block, verbatim
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
`Assistant.thinking()` exists for one protocol: extended thinking has to be handed back to the
Messages API verbatim, signatures included, or the next request is rejected — so the block is carried
through the session file even though nothing renders it. It is written only when there is one, which
keeps every other provider's records byte-identical to what they were before it existed.

## The loop

```
run(input):
  append User(input)
  step = 0
  while true:                                 # no step ceiling: the model or the user ends it
      if aborted: return                      # cooperative abort, checked between steps and calls
      onTurnStart(step)
      assistant = provider.complete(project(session, contextBudget), events -> listener)
      append assistant; onAssistant(assistant)
      if !assistant.hasToolCalls(): return    # model answered; the turn is over
      for each run of consecutive read-only calls, in order:
          if aborted: return
          run the run concurrently, append its results in the model's order
      for every other call, in order:
          if aborted: return
          onToolStart(call)
          result = tools.execute(call, context)   # never throws: failures become error results
          onToolEnd(call, result, elapsedMillis)
          append ToolResult(call, result)
      step++
```

Properties this buys:

- **Unbounded, and stoppable instead.** There is no ceiling on model turns per user input: a run
  ends when the model answers or when `abort()` cuts it short. A cap cannot tell a model stuck in a
  rut from one working through a long task, and cutting the second one off to punish the first fails
  a run that was going to succeed, at a step that has nothing to do with the work. Watching the turn
  and stopping it is the control that matches the problem. The prompt is projected onto the context
  budget by `ContextBudget` — elide old tool output, then drop whole older exchanges, then cut the
  biggest result — so a long conversation is a smaller request rather than an error.
- **Fault-isolated.** `ToolRegistry.execute` converts unknown tool names, invalid argument JSON and
  thrown exceptions into `ToolResult.error`, so the model gets to read its own mistake and retry.
  Provider failures are the exception: they abort the run as `AgentException`, because there is
  nothing for the model to react to when the model is what's unreachable.
- **Concurrent where it is safe.** A tool declares `readOnly()`, and only a run of read-only calls is
  executed in parallel (one virtual thread each, results appended in the model's order). Anything that
  writes or executes stays on the loop's thread, in order, because the transcript is a record of what
  happened and two writes in an unknown order is not a record.
- **Stoppable.** `abort()` sets a flag the loop checks between steps and calls; the flag is handed to
  the tools through `ToolContext.isCancelled()`, so a running `bash` command is killed within a
  heartbeat instead of being waited out for its own timeout. A call that was stopped before it ran is
  recorded as `not run`, so the turn it belongs to still has a result for every call it asked for.
- **Repairable.** Both wire formats reject a history where an assistant turn asked for a tool call and
  no result answers it, and reject a tool result the API cannot place. Since the assistant is appended
  *before* its calls run, an abort in that window — or a process killed mid-call — leaves a
  conversation that every later request is refused for: permanently, and with an error that says
  nothing about the cause. `SessionRepair` makes the projection that goes on the wire sendable again
  (a repair cannot rewrite an append-only file): it puts one answer behind every call, carrying a real
  result back into the turn that asked for it when something was written in the middle of its answers,
  and leaving out a result that answers no recorded call. The transcript says what it did.
- **Observable.** The listener receives deltas as they stream (so text renders live), the assembled
  message per turn, and tool start/end with timings. `AgentListener` defaults to no-ops, so a
  headless run needs no renderer at all. The two callbacks a parallel run can fire at once are
  serialised in the loop, because a renderer is a piece of state and handing it two threads is a bug
  waiting to happen.
- **Resumable.** Every message is appended to the session as it is created, not at the end of the
  run, so a killed process still leaves a conversation that can be reopened.

## Context budget

A session file grows without bound and a model's context does not, so every request is a projection
of the conversation (`ContextBudget`) rather than the conversation itself. `TokenEstimate` answers
"how big is this" with a heuristic — about four characters per token for ASCII, about one per
character for CJK and emoji — because a real count means a round trip to a tokeniser this runtime
deliberately does not ship, and the number only has to be good enough to decide what to leave out.

The projection has three stages, in order, and each one exists because the one before it is cheaper:

1. **Elide old tool output.** Tool results are the bulk of an agent's context and the least valuable
   part of it. Their *contents* are replaced with a marker, which keeps the call/result pairing that
   both wire formats validate while shrinking the prompt by orders of magnitude. Results belonging to
   the exchange being answered are left alone — that is the data the model is reasoning about.
2. **Drop whole older exchanges**, oldest first, one at a time and re-measuring after each. Never a
   partial one: an assistant turn separated from the results of the calls it made is a rejected
   request, not a smaller one, and there is a test that walks a range of budgets asserting exactly
   that invariant on every projection.
3. **Cut the biggest remaining result short**, with a marker in the text. This only happens when the
   exchange being answered does not fit by itself, which is the case a build log creates.

The session keeps everything it always kept. What was trimmed is reported to the listener, so the
transcript can say "20146 → 4820 tokens, 2 old tool results elided" instead of quietly answering a
question with half the evidence.

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
| Thinking | `reasoning_content` deltas, shown and then dropped | `thinking` blocks with a signature, shown *and* handed back on the next request |

The system prompt is one string built in `Prompts.system` from two parts, in this order: the
working directory's `CCJ.md`, and the configured (or built-in) rules. The project's file leads because it is the specific statement about this work; the
built-in rules follow rather than being replaced, since "inspect before you change" is how this agent
works rather than a preference a project can switch off by existing. The file is read from **one
directory only** — `ProjectPrompt` does not walk upwards, so what prompt a run uses is answerable by
looking at the directory it started in. It travels as a separate field in both protocols, which is
why it is the one part of a request `ContextBudget` never trims.

Both parsers face the same hostile reality: frames arrive split at arbitrary byte boundaries, so a
JSON payload can be delivered in pieces, and tool-call arguments arrive as a sequence of fragments
that must be concatenated before they are valid JSON. The OpenAI path assembles `tool_calls` by
`index` (first fragment carries `id` and `name`, later ones only `arguments`); the Anthropic path
concatenates `partial_json` per content block. The tests feed both parsers deliberately fragmented
input, because that is the failure mode that only shows up in production.

`Transport` (package-private) owns one retry policy for both: up to 3 attempts with exponential
backoff and jitter on connection-phase `IOException`, 408, 429 and 5xx, honouring a `Retry-After`
header when the server sends one (either form: seconds or an HTTP date, capped so a wrong header
cannot park a turn for an hour). Two things are deliberately *not* retried: 4xx other than 408/429
(the request is wrong, retrying it wastes time and money) and mid-stream failures (deltas already
reached the user; replaying would duplicate them). A 2xx body that is not an event stream is also an
error rather than an empty answer: a relay that fails upstream must not look like a model that had
nothing to say.

## Tools and approval

`Tool` is three strings, one method and one flag: `name`, `description`, `parametersJson` (a
hand-written JSON Schema), `execute(argumentsJson, ctx)`, and `readOnly()` — the flag that lets the
loop run a run of read-only calls concurrently and tells a reader that the tool never asks for
approval. `ToolRegistry` is the only caller and the only place where failures become results.

One tool does more than return: `restart` installs a rebuilt jar and calls `ToolContext.endRun()`,
which stops the loop where it stands and reports the tool's result as the run's final text. It is the
only way a tool ends a run that is neither failing nor being aborted, and it exists because that
process is about to be replaced — see [BOOTSTRAP.md](BOOTSTRAP.md).

`ToolContext` carries the working directory, the byte cap for bulk output, the `Approver`, and the
run's cancellation signal. `read`/`glob`/`grep` are read-only and never ask. `write`/`edit`/`bash`
call `ctx.approve(title, detail)` before touching anything, where the detail is what a human needs to
judge the call: the resolved path or the full command, the working directory, the timeout, and an
explicit marker when the path escapes the session cwd. "Escapes" is answered on real paths, not on
names: a symlink inside the workspace that points out of it is reported as outside, and a workspace
reached through a symlink does not make every path look foreign. Denial returns
`ToolResult.error("rejected by user")` and performs no I/O at all.

`ctx.isCancelled()` is the loop's abort flag, and a tool that can take minutes is expected to watch
it: `bash` polls it every 150 ms while waiting on the process, so aborting a turn kills the command
and its children instead of waiting out a ten-minute timeout.

The interactive approver denies by default when `System.console()` is null. A pipe, a cron job or a
CI runner therefore cannot accidentally approve a `rm`; `--yolo` is the explicit opt-in. Failing
closed is the whole point of having an approval step.

Output discipline: every tool that can produce bulk output respects `outputLimitBytes`. `bash`
keeps the head *and* tail with an explicit `... omitted N bytes ...` marker, because the interesting
part of a failing build is usually the end. `read` cuts a line that does not fit the budget rather
than skipping it: the answer to "resume with offset=N" has to be a *different* line next time, or the
reader is stuck on a page it can never turn.

## Sessions

One JSONL file per session, `~/.oh-my-ccj/sessions/<id>.jsonl`, appended and flushed per message, one
`write` call per line — a line that arrived in pieces would be a half-written JSON object to anything
reading the file at that moment, and the session list is read on every sidebar refresh:

```json
{"type":"user","text":"create proof.txt"}
{"type":"assistant","text":"","tool_calls":[{"id":"call_1","name":"bash","arguments":"{\"command\": \"touch proof.txt\"}"}]}
{"type":"tool_result","tool_call_id":"call_1","tool_name":"bash","content":"exit code 0\n","error":false}
{"type":"assistant","text":"Created proof.txt.","tool_calls":[]}
```

JSONL rather than a database because it is append-only, crash-safe without transactions, greppable,
and resumable by reading the file back — which is exactly `SessionStore.open`.

Listing is a read of what is on disk, with no separate index that could fall out of step with the
directory — which is what keeps `rm` on a session file a supported way to forget one. The expensive
half of that read is cached instead: a row's title is the first *user* message, so deriving it means
parsing the file part-way, and the sidebar asks for the whole list after every finished turn. That
cache (`SessionIndex`) is keyed on a file's modification time **and** size together — a message
appended in the same millisecond leaves the time where it was and only the size moves — and an entry
is dropped the moment either changes. So a file edited, appended to or deleted by hand is reported as
it is now, and the list is paid for once per session rather than once per refresh. The count shown
beside a workspace is taken from the directory rather than from a full listing, because it is one
integer and used to cost every session file on the machine. `MessageCodec` owns
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

`baseUrl`, `apiKey` and `apiKeyEnv` are provider-scoped: they mean something only next to the
provider they were entered for, which is recorded in `settingsFor`. A flag or a `CCJ_*` variable
naming one of them is an act for the provider that run ends up using, so `layered` marks it; a value
read from the file is left unmarked, because it may have been written for a provider the run is not
using. `settingsBelongTo` is the predicate the rest hangs off, and unmarked is never read as "yes,
this key is for that endpoint" — for a custom provider the definition is used instead
(`Providers.effectiveBaseUrl`, which is also what the status reports, so "where does this go" has one
answer).

`Config.remembered` is the other half: one `ProviderSettings` (endpoint, key, key variable) per
provider name, so a pair survives being switched away from. `changedBy` is the single transition a
settings change goes through — it remembers the pair being left under its own name, applies the
change, marks a pair the request names as the active provider's, and recalls the target provider's
pair back into the flat fields (`recalling` also drops it from the map, so a pair is never in two
places at once). `layered` ends in the same recall, which is what makes `ccj --provider X` pick up X's
saved credential, and `writeInto` writes only what was chosen: a defaulted endpoint or key variable is
left out and re-derived by `resolved()`.

## Testing strategy

| Level | What it pins |
|---|---|
| `SseTest` | frame splitting, CRLF, comments, `data: [DONE]`, multi-line payloads |
| `OpenAiProviderTest` / `AnthropicProviderTest` | the exact outgoing body shape, and fragmented stream reassembly into the right `Assistant` |
| `*ToolTest` | match semantics, ambiguity errors, truncation markers, timeout kill, denial performing no I/O |
| `AgentLoopTest` | turn counting, tool round-trips, error recovery, abort, a run past any fixed number of steps, provider failure wrapping |
| `ConfigTest` | precedence, provider-dependent defaults, redaction, malformed input |
| `ContextBudgetTest` | that no projection, at any budget, separates a call from its result |
| `SessionRepairTest` | that an interrupted history is filled in, in the right place, that a displaced answer is carried back into its turn, and that the file is left alone |
| `CliEndToEndTest` | CLI → HTTP → SSE → tool → real file on disk, both providers, denial path, exit codes |
| `Web*Test`（9 类，共用 `WebHarness`） | 按主题分开：页面与 SSE 流、审批握手、会话并发、会话与工作区、账本与压缩、设置表单、提供方目录、图片与视觉、跨源与 token。以前是一个 3942 行的 `WebApiTest`——一个类读不完，而「一个下午能读完」是它的卖点之一 |
| `WebApprovalTest` | that the status carries what a prompt needs and the page rebuilds one from it (the page half runs under node) |
| `WebReplayTest` | that a long history is split on a turn boundary, filled in during idle time, and that the reader's scroll position is held while it is (the page half runs under node) |

The end-to-end tests are the ones that matter: nothing below `Cli` is stubbed, and the mock server
uses chunked transfer encoding with deliberately fragmented frames so the client faces the same
boundaries a real relay produces. Everything runs offline with no API key.

## Concurrency model

Two different kinds of overlap, kept apart on purpose.

**Inside one turn**, only read-only tool calls run in parallel (`Tool.readOnly()`): a run of them is
executed one virtual thread each, and the results are appended in the order the model asked for them.
Anything that writes or executes stays on the loop's thread, in order, because a transcript whose
order depends on which file finished first is not a record of what happened.

**Between conversations**, turns run side by side. `AgentHub` keeps a `Conversation` per session —
its turn flag, its running `AgentLoop`, and its books — and each turn gets its own virtual thread.
The rule that protects a transcript is per session: a second message in a conversation that is
already working is refused, because two writers on one file is how it gets corrupted; a message in
any other conversation is accepted. *Displaying* a conversation is never refused — putting the busy
one back on screen hands over the `FileSession` its turn is already using, which is how a user can go
back and watch the job they left running. Operations are refused by what they touch: deleting a
session waits for that session, and changing the provider or the config file waits for all of them.

A `Conversation` also fixes its working directory when its turn starts, rather than reading the
active workspace per tool call. Two consequences, and both matter: switching to another workspace
while a turn is running cannot redirect that job's remaining tool calls into a different directory,
and adding a workspace or opening a session in one is therefore never refused. What the turn cannot
survive is having its file deleted or the model swapped under it, and those are exactly what still
waits.

Events carry the session they belong to, and that is what makes one stream serve several
conversations: the page renders the transcript it is showing and tracks the rest only as running
rows. `AgentLoop` itself is unchanged by any of this — it still runs one conversation, and the
concurrency lives in the layer that owns conversations.

## Extension points

- **New tool**: implement `Tool`, register it in `Tools.standard()` (or a custom `ToolRegistry`).
  Nothing else changes; the loop advertises whatever the registry contains.
- **New provider**: implement `Provider` (one method) and add a case to `Providers.create`. If it is
  OpenAI-shaped, `OpenAiProvider` already works — point `baseUrl` at the relay.
- **New front end**: implement `AgentListener`; the loop never references the renderer type.
- **New persistence**: implement `Session`; `MemorySession` shows the minimum.
