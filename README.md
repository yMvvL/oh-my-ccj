# oh-my-ccj

A coding agent runtime written from scratch in plain Java 21. No agent framework, no HTTP client
library, no CLI library — `java.net.http` for transport, `com.sun.net.httpserver` for the test
doubles, ~5k lines of hand-written code, 119 tests.

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
./ccj --help                     # launcher; builds the jar on first use

mkdir -p ~/.oh-my-ccj
cat > ~/.oh-my-ccj/config.json <<'JSON'
{
  "provider": "openai",
  "model": "gpt-4o-mini",
  "apiKeyEnv": "OPENAI_API_KEY"
}
JSON
export OPENAI_API_KEY=sk-...

ccj                              # interactive REPL
ccj -p "what does src/Main.java do?"   # one-shot
```

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
scripts/playground.sh                              # REPL against a local fake model
scripts/playground.sh -p "read README.md"          # one-shot
```

The playground starts a throwaway OpenAI-compatible endpoint on localhost that turns `read <path>`,
`run <command>`, `list [glob]` and `search <regex>` into **real** tool calls, then hands you the
normal CLI: same approval prompts, same tool cards, same session handling. Nothing leaves the
machine, no key is involved, and the working directory is this repository — the fastest way to watch
the loop work end to end.

## What it does

| Capability | Detail |
|---|---|
| Providers | Any OpenAI-compatible endpoint (`/chat/completions`, SSE) and Anthropic (`/v1/messages`, SSE). Streaming with incremental tool-call assembly, retry with backoff on 408/429/5xx. |
| Tools | `read`, `write`, `edit`, `bash`, `glob`, `grep` — each with a hand-written JSON Schema and self-describing errors. |
| Loop | One model turn at a time; every requested tool runs, its result goes back, and the model is asked again. Bounded by `--max-steps`. |
| Sessions | Append-only JSONL under `~/.oh-my-ccj/sessions/`, resumable in a later process with `--resume` / `--continue`. |
| Approval | Anything that writes or executes asks first; without a terminal it is denied, not silently allowed. |
| Rendering | Streaming prose, tool cards with argument summaries, per-call timing, dimmed reasoning, a spinner that yields the terminal to prompts. |

## Usage

```
One-shot:
  -p, --print <prompt>     run a single turn, print the answer, and exit

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

Runtime:
      --config <file>      config file (default: <home>/config.json)
      --home <dir>         application home (default: ~/.oh-my-ccj)
  -C, --cwd <dir>          working directory tools resolve relative paths against
      --tools              print available tools and exit
      --yolo               approve every tool call, alias --auto-approve
  -h, --help               print this help
  -v, --version            print the version
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

## Architecture

```
cli/       argument parsing, wiring, REPL, exit codes   (the only place that calls System.exit)
ui/        AgentListener implementation: streaming text, tool cards, spinner, ANSI
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
mvn test                                  # 119 tests, no network, no API key
mvn -Dtest=CliEndToEndTest test           # end-to-end through the CLI only
mvn -DskipTests package                   # fat jar
```

All tests are offline. The provider tests and the end-to-end tests stand up a scripted HTTP server
(`com.sun.net.httpserver`) that speaks both wire formats with chunked transfer encoding and
deliberately fragmented frames, so SSE assembly is tested against the same boundaries a real relay
produces. The end-to-end tests drive the real CLI: request out over HTTP, SSE parsed, tool executed,
file on disk, session written.

| Area | Tests |
|---|---|
| providers (SSE parsing, both wire mappings, retry policy) | 28 |
| tools (matching, truncation, timeouts, denial paths) | 34 |
| core (loop behaviour, config precedence) | 21 |
| session (codec round-trips, append/reopen, listing) | 19 |
| CLI (argument parsing) | 8 |
| end-to-end (CLI → HTTP → tool → disk) | 9 |

## Limitations

- One turn at a time: no parallel tool execution, no sub-agents.
- Text only: no image or file attachments on the wire.
- No MCP, no plugins, no sandboxing — approval is the only guard, and `--yolo` removes it.
- Bash runs as the current user with your full environment.
- Sessions grow without bound; there is no compaction or summarisation.
- The OpenAI path targets the chat-completions API, not the newer Responses API.
