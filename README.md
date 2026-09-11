# oh-my-ccj

A coding agent runtime written from scratch in plain Java 21. No agent framework, no HTTP client
library, no CLI library — `java.net.http` for transport, `com.sun.net.httpserver` for the web UI and
the test doubles, 7.4k lines of hand-written Java plus a 2.7k-line vanilla page, 184 tests.

`ccj` streams a conversation with a model, lets the model call tools that touch your filesystem,
feeds the results back, and repeats until the model answers. It is a small, readable implementation
of the loop that every coding agent is built around.

```
$ ccj -p "add a null check to Parser.java and run the tests"
⚙ read Parser.java
✔ read (3 ms)
⚙ edit Parser.java
✔ edit (1 ms)
⚙ bash mvn -q test
✔ bash (8420 ms)
    exit code 0
Added the check on line 42 and the suite passes.
```

## Quick start

```bash
mvn package                      # builds target/ccj.jar (shaded, no classpath juggling)

./ccj                            # opens the web UI; configure your model there
./ccj --demo                     # the same, with a local stand-in model: no key, no network
./ccj --repl                     # terminal REPL instead of the browser
./ccj -p "what does src/Main.java do?"   # one-shot
```

The first run has no model configured, so the web UI opens its settings panel and asks for one —
provider, model, base URL, API key. Saving writes `~/.oh-my-ccj/config.json` (0600) and switches the
running session over immediately: no restart, no JSON editing. Flags and environment variables still
work for scripted use.

Requires JDK 21+ and Maven. The only runtime dependency is `jackson-databind`.

## Install

Put the launcher on your `PATH` once and use `ccj` from any directory, like any other CLI:

```bash
mvn -DskipTests package                       # or let the launcher build it on first use
ln -sfn "$PWD/ccj" ~/.local/bin/ccj           # ~/.local/bin is on PATH by default
cd ~/some/other/project && ccj -p "explain this repo"
```

The launcher runs `java -jar target/ccj.jar` and rebuilds when a file under `src/main` is newer than
the jar, so the symlink can never run stale code. Tools resolve relative paths against the directory
you started `ccj` in (`-C` overrides it); sessions live under `~/.oh-my-ccj` and are shared across
projects (`--home` isolates them). The key lives in the environment or the config file — `ccj`
is a small program, not a service.

### Try it without an API key

```bash
ccj --demo                        # web UI with the stand-in model
ccj --demo --repl                 # the same in the terminal
ccj --demo -p "read pom.xml"      # one-shot
```

`--demo` swaps the model for a local tool router: `read <path>`, `run <command>`, `list [glob]` and
`search <regex>` become **real** tool calls, and anything else is answered with that vocabulary.
Everything else stays real — same loop, same tools, same approval prompts, same sessions — and it
needs no key, no network and no second process. It is the fastest way to watch the agent work.

## Web UI

```bash
ccj                       # the default: serve http://127.0.0.1:8787 and open it
ccj --no-open             # for scripts: serve it without launching a browser
ccj --repl                # the terminal front end instead
```

The page streams the assistant's prose, shows every tool call as a card with its arguments, timing and
output, and renders an approval request as a blocking card with Approve / Deny — because that is
exactly what it is: the loop thread is parked until someone answers, and a timeout denies. Sessions
are the same JSONL files the CLI uses, so a conversation started in the terminal can be resumed in
the browser and vice versa. `--port` moves it, `--host` binds another interface, and binding
anything but loopback **requires** `--web-token` — the UI can run shell commands, so an unauthenticated
network bind is a remote code execution surface. With a token, the printed URL carries it and the
server stores it in an HttpOnly cookie, so the browser never needs token plumbing.

Model settings — provider, model, base URL, API key, step and temperature limits — live in the UI
itself (`Settings`), and `Test connection` sends one tiny request to check them before saving. The
key is never sent back to the browser, only whether one exists and where it comes from. See
[docs/WEBUI.md](docs/WEBUI.md).

## What it does

| Capability | Detail |
|---|---|
| Providers | Any OpenAI-compatible endpoint (`/chat/completions`, SSE) and Anthropic (`/v1/messages`, SSE). Streaming with incremental tool-call assembly, retry with backoff on 408/429/5xx. |
| Tools | `read`, `write`, `edit`, `bash`, `glob`, `grep` — each with a hand-written JSON Schema and self-describing errors. |
| Loop | One model turn at a time; every requested tool runs, its result goes back, and the model is asked again. Bounded by `--max-steps`. |
| Usage | Prompt/output tokens, steps, tool calls and the **cache hit rate** per session, parsed from both protocols (`prompt_tokens_details.cached_tokens`, `prompt_cache_hit_tokens`, `cache_read_input_tokens`), shown live in the side panel and in each turn's token line, and persisted with the session so resuming continues the count. |
| Sessions | Append-only JSONL under `~/.oh-my-ccj/sessions/`, created on the first message (an empty session leaves no file), resumable with `--resume` / `--continue`, and replayed into the transcript when the UI opens one. |
| Approval | Anything that writes or executes asks first; without a terminal it is denied, not silently allowed. |
| Rendering | Streaming prose, tool cards with argument summaries, per-call timing, dimmed reasoning, a spinner that yields the terminal to prompts. |
| Workspaces | Named directories, each with its own session history. The workspace `ccj` starts in keeps the original sessions directory; added ones get their own under `<home>/workspaces/<name>/`. Switching changes the working directory *and* the history in one move. |
| Web UI | `ccj` serves the same loop as a single page: streaming transcript, tool cards, blocking approval prompts, session switching, and a settings panel that configures the model at runtime. No framework, no build step. |

## Usage

```
Usage: ccj [options]

Modes:
      (no flags)               serve the web UI, opening it in a browser
          --repl               interactive REPL in this terminal
          --web                the default, stated explicitly (for scripts)
          --no-open            serve the web UI without opening a browser
      -p, --print <prompt>     run a single turn, print the answer, and exit

Demo:
      --demo                   no model and no key: read/run/list/search become real tool calls,
                               so the loop, the tools and the approval prompts can be tried out

Model:
          --provider <name>    provider to use (openai, anthropic, ...)
          --model <name>       model identifier
          --base-url <url>     override the provider base URL
          --api-key <key>      API key literal
          --api-key-env <var>  environment variable holding the API key
          --temperature <n>    sampling temperature
          --max-tokens <n>     response token cap
          --max-steps <n>      model turns per user input (default 25)
          --system <text>      system prompt for this run

Sessions:
          --resume <id>        reopen a session by id
          --continue           reopen the most recent session
          --list-sessions      print sessions and exit

Web:
          --port <n>           web UI port (default 8787)
          --host <addr>        bind address (default 127.0.0.1; anything else needs --web-token)
          --web-token <token>  require this token from every web request

Runtime:
          --config <file>      config file (default: <home>/config.json)
          --home <dir>         application home (default: ~/.oh-my-ccj)
      -C, --cwd <dir>          working directory tools resolve relative paths against
          --tools              print available tools and exit
          --yolo               approve every tool call, alias --auto-approve
      -h, --help               print this help
      -v, --version            print the version

The web UI is the default front end; --repl gives the terminal one. In the REPL, type
/help for commands. Model settings live in the web UI and can be changed there at runtime.
```

REPL commands: `/help`, `/exit`, `/clear`, `/new`, `/resume <id>`, `/sessions`, `/model <name>`,
`/tools`, `/config`, `/yolo`.

### Configuration

Precedence, lowest first: built-in defaults → `~/.oh-my-ccj/config.json` → `CCJ_*` environment →
command-line flags.

```json
{
  "provider": "openai",
  "model": "gpt-4o-mini",
  "baseUrl": "https://api.openai.com/v1",
  "apiKeyEnv": "OPENAI_API_KEY",
  "temperature": 0.2,
  "maxTokens": 4096,
  "maxSteps": 25,
  "autoApprove": false,
  "outputLimitBytes": 32768,
  "systemPrompt": "optional override"
}
```

| Environment variable | Meaning |
|---|---|
| `CCJ_PROVIDER`, `CCJ_MODEL`, `CCJ_BASE_URL`, `CCJ_API_KEY`, `CCJ_API_KEY_ENV` | model selection and credentials |
| `CCJ_TEMPERATURE`, `CCJ_MAX_TOKENS`, `CCJ_MAX_STEPS`, `CCJ_OUTPUT_LIMIT_BYTES` | sampling and limits |
| `CCJ_AUTO_APPROVE`, `CCJ_SYSTEM_PROMPT` | approval mode and prompt override |
| `CCJ_HOME` | application home directory |

`model` can be omitted for `openai` and `anthropic`: the defaults are `gpt-4o-mini` and
`claude-sonnet-4-5`. They are deliberately **not** applied when you point `baseUrl` at your own
endpoint — relays and local servers name models freely, so guessing there would replace a clear
configuration error with an obscure 404. In that case `--model` / `CCJ_MODEL` is required and the
error says so.

The key comes from `apiKey` in the config file or from the environment variable named by
`apiKeyEnv` (default `OPENAI_API_KEY` for OpenAI-compatible, `ANTHROPIC_API_KEY` for Anthropic);
keeping it in the environment means it never lands in a file. Keys are never printed — `/config` and
error output show at most `***1234`.

### Tools

| Tool | Arguments | Notes |
|---|---|---|
| `read` | `path`, `offset?`, `limit?` | numbered lines, resumes with `offset`, rejects binary files |
| `write` | `path`, `content` | creates parents, reports bytes; approval shows an overwrite preview |
| `edit` | `path`, `old_string`, `new_string`, `replace_all?` | exact match; ambiguity is an error with the match count, denial previews the diff |
| `bash` | `command`, `cwd?`, `timeout_seconds?` | `/bin/bash -lc`, stderr merged, exit code reported, output capped with head+tail |
| `glob` | `pattern`, `path?` | relative-path globbing incl. `**`, newest first, skips `target/`, `.git/`, `node_modules/`, `.idea/` |
| `grep` | `pattern`, `path?`, `glob?`, `ignore_case?`, `max_results?` | Java regex, skips binaries and files over 2 MiB |

Tool failures never kill the session: bad arguments, unknown tool names, missing files and thrown
exceptions all come back as error results the model can read and correct.

### Safety model

- `read`, `glob` and `grep` run freely; `write`, `edit` and `bash` require approval.
- With a terminal, approval is a `y/N` prompt that shows the tool, the full command, the resolved
  working directory and the timeout — and pauses the spinner while it waits.
- Without a terminal (pipes, CI), side-effecting calls are **denied** with an explanation unless
  `--yolo` was passed. Failing closed is the point.
- Relative paths resolve against `--cwd`; paths outside it are labelled in the approval prompt.

## Workspaces

A workspace is a directory plus the sessions that belong to it, because a conversation only makes
sense next to the files it was about:

```bash
ccj --workspace api            # this run works in api's directory, with api's sessions
```

In the browser the workspace switcher does the same thing, and adding one from there creates the
directory if it does not exist. The registry is `~/.oh-my-ccj/workspaces.json`; the workspace you
first started `ccj` in stays mapped to the top-level `~/.oh-my-ccj/sessions`, so sessions from before
workspaces existed are still there. Model settings are deliberately **not** per workspace — a key and
a model belong to the account, not to a folder. Forgetting a workspace never deletes conversation
files.

## Architecture

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

`core` is the contract; everything else plugs into it. The loop sees a `Provider` and a
`ToolRegistry` and knows nothing about HTTP or the terminal, which is why the same loop runs headless
in tests, behind a scripted provider, or against a mock HTTP server. See
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the message model, the loop algorithm, the wire
mappings and the reasoning behind the design.

## Development

```bash
mvn test                                  # 184 tests, no network, no API key
mvn -Dtest=CliEndToEndTest test           # end-to-end through the CLI only
mvn -Dtest=WebApiTest test                # HTTP + SSE + approval handshake only
mvn -DskipTests package                   # fat jar
```

All tests are offline. The provider tests and the end-to-end tests stand up a scripted HTTP server
(`com.sun.net.httpserver`) that speaks both wire formats with chunked transfer encoding and
deliberately fragmented frames, so SSE assembly is tested against the same boundaries a real relay
produces. The end-to-end tests drive the real CLI: request out over HTTP, SSE parsed, tool executed,
file on disk, session written.

| Area | Tests |
|---|---|
| providers (SSE parsing, both wire mappings, retry policy) | 29 |
| tools (matching, truncation, timeouts, denial paths) | 34 |
| core (loop behaviour, config precedence, config persistence) | 26 |
| session (codec round-trips, append/reopen, listing) | 19 |
| CLI (argument parsing, mode selection) | 10 |
| end-to-end (CLI → HTTP → tool → disk) | 9 |
| web API (HTTP, SSE, approvals, token gate, settings, workspaces) | 22 |
| workspaces (registry rules, persistence, isolation) | 7 |
| demo provider (routing, termination) | 8 |

## Limitations

- One turn at a time: no parallel tool execution, no sub-agents.
- Text only: no image or file attachments on the wire.
- No MCP, no plugins, no sandboxing — approval is the only guard, and `--yolo` removes it.
- Bash runs as the current user with your full environment.
- Sessions grow without bound; there is no compaction or summarisation.
- `cacheHitRate` is `n/a` unless the provider reports cache figures — a local model has no cache to
  hit and is not reported as 0%. Usage totals cover one session (they are stored beside the
  conversation, one record per turn), not a whole project or a billing period.
- The OpenAI path targets the chat-completions API, not the newer Responses API.
- The API key is stored in plaintext when you let the UI save it (the file is `0600`); keeping it
  in the environment instead is one dropdown away.
- The web UI is a local console: one turn at a time, one session, no accounts. It binds loopback by
  default and refuses a public bind without a token, because the agent can run shell commands.
