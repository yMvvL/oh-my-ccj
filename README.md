# oh-my-ccj

[![build](https://github.com/ArchCCJ/oh-my-ccj/actions/workflows/build.yml/badge.svg)](https://github.com/ArchCCJ/oh-my-ccj/actions/workflows/build.yml)

A coding agent runtime written from scratch in plain Java 21. No agent framework, no HTTP client
library, no CLI library — `java.net.http` for transport, `com.sun.net.httpserver` for the web UI and
the test doubles — 16.6k lines of Java plus an 8.0k-line vanilla page, with 14.8k more lines of tests
alongside them.

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

## Why this one

There are other coding agents. These are the things this one does that they mostly do not, and each is
a place where a long session or an interrupted one goes wrong:

- **An interrupted turn is repaired, not fatal.** Kill the process between an assistant turn and the
  tool calls it asked for and you have a history no API will accept again — every later request is
  refused, and the conversation is dead. ccj supplies the missing results on the projection it sends
  ("not run" is what actually happened) and carries displaced answers back into the turn that asked
  for them, so the session continues.
- **`/compact` is reversible.** Compaction is usually a one-way loss: the old turns are gone and the
  summary is all that is left. Here it writes a *new generation* of the same session and leaves the
  original untouched, and the summary names that file so the model can `read` a dropped detail back.
  It also refuses to run when the summary would not come out smaller than what it replaces — measured
  on a real session at 4239 → 4236 tokens, which is a cost with no benefit.
- **The agent can read its own history.** Sessions are plain JSONL in a directory the `read` tool can
  reach, which is what makes the point above work at all.
- **Context trimming keeps the protocol valid.** Old tool output is elided, then whole exchanges are
  dropped — never splitting an assistant turn from the results of the calls it made.
- **A sub-agent's changes go through your approval.** It reads in a conversation you never see, but a
  write is put to you like any other, and aborting the turn answers it. It cannot delegate further
  either: the tool is simply absent from its registry.
- **The web UI is reachable from your phone without being on the open internet.** It binds named
  addresses — loopback plus your tailnet — never a wildcard, and every non-loopback address needs a
  token. Cross-origin requests that would change state are refused.

What it deliberately does not have: MCP, plugins, a sandbox, or multi-user accounts. Pictures arrive
as a description rather than as an image — see the `Picture` row below — and everything else is in
[Limitations](#limitations).

## Read this before you run it

`ccj` executes shell commands on your machine. That is the whole point of it, and it means the
boundaries below are not fine print — they are the design.

- **The agent runs as you, with your environment.** `bash` is not sandboxed, not limited to a
  directory, and not restricted to a command list. Anything you could type, it can type.
- **Approval is the only guard.** Tools that write or execute ask first, and without a terminal to
  ask on they are *denied* rather than assumed. `--yolo` (or auto-approve in the UI) removes that
  guard entirely; on a session that can also be reached over a network, that combination is remote
  code execution by design.
- **The web UI is a shell prompt reachable over HTTP.** It is served on addresses you name and never
  on a wildcard, and every address except loopback requires a token (`~/.oh-my-ccj/web-token`, 32
  random bytes, `0600`, generated on first need). The token is the only thing between a device that
  can reach the port and the ability to run commands. Treat the URL that carries it as a password:
  it is printed once at startup and appears in your shell history only if you paste it there.
- **Do not expose it to a network you do not control.** A tailnet address is reasonable because the
  set of devices that can reach it is one you administer. A public interface — a VPS, a forwarded
  port, a café network — is not, and the token does not change that: it filters callers, it does not
  make the service safe to publish.

None of this is a defect to be fixed later; a coding agent that cannot run commands cannot do the
job. It is the reason `ccj` is built as a personal tool for a machine you own.

## Quick start

```bash
./mvnw package                   # builds target/ccj.jar (shaded, no classpath juggling)

./ccj                            # opens the web UI; configure your model there
./ccj --demo                     # the same, with a local stand-in model: no key, no network
./ccj --repl                     # terminal REPL instead of the browser
./ccj -p "what does src/Main.java do?"   # one-shot
```

The first run has no model configured, so the web UI opens its settings panel and asks for one —
provider, model, base URL, API key. Saving writes `~/.oh-my-ccj/config.json` (0600) and switches the
running session over immediately: no restart, no JSON editing. Flags and environment variables still
work for scripted use.

### What it runs on

**Linux and macOS, with JDK 21+.** The wrapper (`./mvnw`) brings its own Maven, so Maven does not have
to be installed — but a POSIX shell is required, and that is a real dependency rather than an
incidental one:

| Needs | Why |
|---|---|
| `/bin/bash` | The `bash` tool runs commands through it, and it is the tool the whole design is built around. |
| POSIX shell utilities | The launcher (`ccj`) uses `readlink`; the tools expect `grep`, `find` and friends to behave the usual way. |
| JDK 21+ | `./mvnw` and `java -jar` both need it. |

Windows is not supported. Java itself runs there, but the shell tool would have nothing to run: a
`cmd.exe` or PowerShell path is a different tool with different quoting rules, not a configuration
change. **WSL works** and is the way to use this on Windows — inside it, everything above is true.

Nothing else is required: no Maven, no Node (the browser cases are skipped, and *reported* as skipped,
when it is absent — CI installs it so that cannot pass unnoticed), no API key (`--demo` runs against a
local stand-in model).

## Install

Put the launcher on your `PATH` once and use `ccj` from any directory, like any other CLI:

```bash
./mvnw -DskipTests package                    # or let the launcher build it on first use
ln -sfn "$PWD/ccj" ~/.local/bin/ccj           # ~/.local/bin is on PATH by default
cd ~/some/other/project && ccj -p "explain this repo"
```

The launcher runs `java -jar target/ccj.jar` and rebuilds when any file under `src/main` — the page
included, not just the sources — or `pom.xml` is newer than the jar, so the symlink cannot run stale
code. Tools resolve relative paths against the active workspace, which is the directory you started
`ccj` in until you pick another one (`-C` overrides it for one run, and the session says so);
sessions live under `~/.oh-my-ccj` and are shared across projects (`--home` isolates them). The key
lives in the environment or the config file — `ccj` is a small program, not a service.

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
ccj                       # the default: serve on 127.0.0.1 and on this machine's tailnet address,
                          # then open it
ccj --no-open             # for scripts: serve it without launching a browser
ccj --repl                # the terminal front end instead
```

The page streams the assistant's prose, shows every tool call as a card with its arguments, timing and
output, and renders an approval request as a blocking card with Approve / Deny — because that is
exactly what it is: the loop thread is parked until someone answers, and a timeout denies. Answers
arrive as markdown and are rendered as markdown (headings, lists, tables, fenced code, links) while
they stream. Sessions are the same JSONL files the CLI uses, so a conversation started in the
terminal can be resumed in the browser and vice versa.

**One command, two ways in.** Serving is bound to named addresses rather than to a wildcard:

```
$ ccj
oh-my-ccj 0.1.0 — web UI: http://127.0.0.1:6767/
                          also on http://100.72.92.41:6767/?token=…
  the first address is this machine (no token needed there); the others are the tailnet, and ask for
  the token in the URL
```

- **This machine** reaches it at `127.0.0.1` with nothing attached. Loopback is not a network, and a
  secret to type in order to use your own command line would be a password nobody asked for.
- **Your other devices** reach it at the tailnet address, and have to carry a token. It is generated
  on first use, kept at `~/.oh-my-ccj/web-token` (0600) so a restart does not log the phone out, and
  printed in the URL — open that URL once and the token lands in an HttpOnly cookie.
- **Nothing else** reaches it: no wildcard bind, so the café wifi, the office LAN and the docker
  bridge have no listener to talk to. `--host <addr>` binds exactly one address instead, `--host
  tailscale` the tailnet address alone, and anything but loopback then needs a token — one from
  `--web-token`, `CCJ_WEB_TOKEN`, or the file.
- A tokenless server (a machine with no tailnet) additionally insists on a loopback `Host` header:
  any page you open can POST to `127.0.0.1` without a preflight, and DNS rebinding would let it read
  the answers, so a request naming another host is refused rather than served.

`--port` moves the port. The agent can run shell commands, so the address it is reachable on is a
security decision, not a convenience one: a token is the price of reaching it from the network.

Model settings — provider, model, base URL, API key and temperature — live in the UI itself
(`Settings`), and `Test connection` sends one tiny request to check them before saving. The effort
tier is picked above the message box instead. The context budget is deliberately not in the form: it
is `--max-context-tokens`, `CCJ_MAX_CONTEXT_TOKENS` or `"maxContextTokens"` in the config file. The
key is never sent back to the browser, only whether one exists and where it comes from. See
[docs/WEBUI.md](docs/WEBUI.md).

### From your phone, over Tailscale

Nothing extra to type: if this machine is on a tailnet, `ccj` already serves your phone.

```bash
ccj                    # this machine at http://127.0.0.1:6767/
                       # and the phone at http://100.72.92.41:6767/?token=…
```

Open the second URL on the phone once; the token goes into an HttpOnly cookie and the bookmark needs
nothing after that. The token is generated on first use and kept at `~/.oh-my-ccj/web-token` (0600,
readable only by you) so restarts do not log the phone out; `--web-token` and `CCJ_WEB_TOKEN` override
it, and a blank value in either place counts as "no token" rather than as an empty password.

Two things make it *yours* rather than merely "on the tailnet":

Open the printed URL once on the phone; the token goes into an HttpOnly cookie and the bookmark needs
nothing after that. Two things make it *yours* rather than merely "on the tailnet":

- **The bind is the address, not every interface.** `ss -ltn | grep 6767` shows two listeners:
  `127.0.0.1:6767` and `100.72.92.41:6767`. The tailnet one is what the phone uses, and it is the only
  one that can be reached from off this machine. `--host tailscale` serves *only* the tailnet address
  (then this machine must use it too, or the MagicDNS name `archymwl.taile88351.ts.net`).
- **A tailnet is not the same as one device.** Every device in it can reach that port; the token is
  what stops the others. To pin it to one device as well, add to the tailnet's access rules:

  ```json
  { "acls": [ { "action": "accept", "src": ["ffdancer"], "dst": ["archymwl:6767"] },
              { "action": "accept", "src": ["archymwl"], "dst": ["archymwl:6767"] } ] }
  ```

  (Tailscale's default rule already allows everything within a tailnet — the pair above is what
  *narrows* it. `tailscale status` prints the device names.)

The tailnet address is resolved through the `tailscale0` interface, or by asking `tailscale ip -4` on
systems that carry the tailnet over a differently named one; an address outside `100.64.0.0/10` is
never accepted, and a machine without a tailnet simply serves loopback and is not bound to anything
else. `--host <addr>` still takes any address, and any non-loopback one **requires** a token.
For HTTPS with a real certificate and no open port at all, `tailscale serve --bg 6767` in front of a
loopback bind works too — it still needs the token, because the `Host` the server sees is then a
hostname rather than loopback.

## What it does

| Capability | Detail |
|---|---|
| Providers | Any OpenAI-compatible endpoint (`/chat/completions`, SSE) and Anthropic (`/v1/messages`, SSE). Streaming with incremental tool-call assembly, retry with backoff on 408/429/5xx. |
| Custom providers | Define your own provider in the settings panel or over HTTP: a name, a protocol, an endpoint, an optional key variable and the models it serves. A relay, a gateway, a local vLLM or your own API router is a definition, not a release. The model list of **any** provider — built-ins included — is editable and persisted, so a hand-typed model is a remembered choice rather than a one-off. Providers you do not use can be removed from the picker outright: your own definitions are deleted and the compiled-in aliases leave the list (the list becomes explicit, and adding one back is an ordinary add). |
| Endpoint and key | They belong to the provider they were entered for, and the config file keeps one pair per provider: the one in effect plus a `remembered` entry for each other provider you have configured. Switching provider loads that provider's pair instead of passing the old one on, so a session cannot end up billing the provider it just left — and switching back does not ask you to paste the key again. |
| Tools | `read`, `write`, `edit`, `bash`, `glob`, `grep` — each with a hand-written JSON Schema and self-describing errors. `bash` runs the command with its stdin already closed, so `cat`, `sort` or a script's `read` sees end-of-input instead of waiting for a terminal that is not there. The read-only three declare themselves as such, and a run of them in one turn executes concurrently. |
| Loop | One model turn at a time per conversation; every requested tool runs, its result goes back, and the model is asked again. No step ceiling — a turn ends when the model answers or when you abort it, because a cap cannot tell a model stuck in a rut from one working through a long task. Abort is real: a running shell command is killed rather than waited out. |
| Sub-agents | The agent can delegate a self-contained task to a sub-agent that works in its own conversation and returns only a report: a conclusion with file paths instead of the reading that produced it, so a search across thirty files costs the main conversation one paragraph. Three roles — `explore` and `verify` read only, `build` writes, and its changes ask for the same approval yours do. It cannot delegate further: `task` is absent from its registry, so recursion is impossible rather than limited. Off unless `--subagents`. |
| Project rules | Drop a `CCJ.md` in a working directory and its contents lead the prompt for every request made there: the project's own statement about how work is done here, with the built-in rules following it rather than being replaced by it. Read from **that directory only** — a file higher up the tree is not consulted, so "what prompt is this run using" is answerable by looking at the folder you started in. `--system` still overrides the built-in part. Missing, empty and unreadable all mean "no project rules". |
| Context budget | `--max-context-tokens` (or `CCJ_MAX_CONTEXT_TOKENS`, or the config file) caps what one request carries, in estimated tokens. Over it, old tool output is elided first, then whole older exchanges are dropped — never splitting an assistant turn from the results of the calls it made — and if the exchange being answered still does not fit, its largest result is cut short with an explicit marker. The session file keeps everything; only the request is a projection, and the transcript says what was trimmed. |
| Context | The side panel reports the estimated prompt size against the budget (`--max-context-tokens`), so you can see a session about to be trimmed before the transcript says it was. It is an estimate and labelled as such: token counting is a heuristic. No money is shown — a price is an assumption about a rate card that changes without telling ccj. |
| Compaction | `/compact` (or the Compact button) replaces the older turns with a summary the model writes, so a long session keeps going without re-sending everything it has ever read. Written as the next **generation** of the same session — `<id>.g1.jsonl` beside the untouched `<id>.jsonl` — so the conversation it replaced is still on disk and still readable, and the summary tells the model where to `read` it back. The newest five exchanges stay verbatim; a summary that would not come out smaller than what it replaces is refused instead of written. See [docs/COMPACT.md](docs/COMPACT.md). |
| Usage | Prompt/output tokens, steps, tool calls and the **cache hit rate** per session, parsed from both protocols (`prompt_tokens_details.cached_tokens`, `prompt_cache_hit_tokens`, `cache_read_input_tokens`), shown live in the side panel and in each turn's token line, and persisted with the session so resuming continues the count. |
| Sessions | Append-only JSONL under `~/.oh-my-ccj/sessions/`, created on the first message (an empty session leaves no file), resumable with `--resume` / `--continue`, replayed into the transcript when the UI opens one, and deletable one at a time or all at once from the sidebar — including in a workspace you are not currently in. The sidebar labels each one with the first thing you asked it, since a list of timestamp ids says nothing about which task it was. A turn that an interruption left without its tool results (`Ctrl-C` between the call and its execution, or a killed process) is repaired for the next request instead of being refused forever, and so is a turn whose answers ended up displaced by a message written in the middle of them — the answers are carried back into the turn that asked, because a `tool` message the API cannot place is a rejected request. |
| Approval | Anything that writes or executes asks first; without a terminal it is denied, not silently allowed. |
| Rendering | Streaming prose, tool cards with argument summaries, per-call timing, dimmed reasoning, a spinner that yields the terminal to prompts. |
| Markdown | An answer is markdown and is rendered as such in the browser: headings, lists (nested, with task boxes), fenced code with its language, tables with alignment, quotes, inline code, emphasis and links — parsed from the streaming deltas, so a half-arrived answer is readable and the blocks above the one still being written are never re-drawn. Raw HTML in an answer is shown as text, images are not fetched, and a link's scheme is filtered. |
| Themes | Light/dark/system, remembered per browser, applied before the first paint. |
| Thinking language | One setting in Settings, kept in `config.json` (or `--language` / `CCJ_LANGUAGE` for a run). The answer always comes back in the chosen language, whatever language you wrote in, and the prompt asks for the *thinking* in it too — which some models honour and some do not; the settings hint says so rather than promising it. `auto` says nothing at all. |
| Queued messages | Typing while a turn runs queues the message instead of being refused: the composer stays usable, and the hint says how many are waiting. When the turn ends the next one starts on its own, as a turn of its own with its own `done`. Abort stops the turn **and drops what was queued behind it**, saying how many — stopping is what that button is for. A conversation holds sixteen; beyond that the server refuses with the reason rather than growing without limit. |
| Approval rules | Four answers instead of two: deny, allow once, **allow for this session**, and **allow from now on**. The last writes a rule into `<home>/approvals.json` keyed by project, and the rule answers the next identical request without a prompt: `{"tool": "bash", "command": "mvn -q -o test"}`, or `git diff *` for the same command with further arguments, or `{"tool": "edit", "path": "src/**"}`. Deny still wins, a wildcard never covers a command containing `;`, `&&`, `|`, a substitution or a redirect, a path rule cannot walk out of the project, and every automatic answer appears in the transcript as `allowed by rule — …`. The point is the switch nobody dared touch: "stop asking me about this" used to mean "stop asking me about anything". See [SECURITY.md](SECURITY.md). |
| Checks | Commands that run by themselves after an edit, declared in the config file: `"checks": [{"glob": "**/*.java", "command": "mvn -q -o -DskipTests compile"}]`. The first check whose glob matches the file just written runs inside the same `edit`/`write` call and its verdict rides back with the result — one line when it passes (`exit 0`, not "clean": see below), a bounded excerpt when it fails. **The glob decides when the check runs, not what the command looks at** — measured: with `**/*.java` and `mvn -q -o -DskipTests compile`, a broken file at the repository root comes back `exit 0`, because Maven compiles `src/main/java` and never saw it. Pair the glob with a command that covers what the glob selects. This is the difference between a compiler error arriving in the step that caused it and arriving three turns later if the model thinks to go looking, and it costs one command per edit: one check per edit, a 4 KiB report, a per-check timeout, nothing for a failed edit, and nothing for a file outside the session's directory. See [SECURITY.md](SECURITY.md) for what running a configured command without a prompt means. |
| Pictures | The composer's picture button (or `POST /api/attachment`) sends one image, up to 8 MB, PNG/JPEG/WebP/GIF — the type is read from the bytes, not from the file name. A **separate vision model** describes it, and the description becomes your message: `[picture photo.jpg] …` plus the path, so a detail the description dropped can be read back. The main model never receives an image and no session file ever holds one, which is why no wire format, renderer or compaction step grew an image branch. Own endpoint, own key, own model and its own completion budget (`maxTokens`, default 8192), set in **Settings** (the *Pictures* section), in the config file's `vision` block, or with `--vision-base-url`, `--vision-model`, `--vision-api-key`/`--vision-api-key-env`, `--vision-max-tokens`: absent means the feature is off, and a picture sent while it is off is refused with what to set. The budget is a ceiling and not a spend — a description costs what the model writes — but a reasoning model spends it before it writes a word, which is why it is generous by default: at 1500 a phone screenshot came back empty, with every token spent thinking. The picture itself is stored under `<sessions>/<session-id>.attachments/` — beside the session, so deleting the conversation deletes it — and never in your project. See [docs/VISION.md](docs/VISION.md). |
| Effort tiers | A picker above the message box: provider → model → `default`/`low`/`high`/`max`, translated per protocol (`reasoning_effort` for OpenAI-shaped APIs, extended-thinking budgets for Anthropic). `default` sends nothing, so ordinary models are unaffected. |
| Folder picker | "Add workspace" *is* the folder picker: one click opens the desktop's own chooser (`zenity`, `kdialog` or Swing) and the folder that comes back becomes the workspace under its own name, with a suffix if that name is taken. A machine with no chooser falls back to the form the same click opens, so typing a path — or browsing to one with the same chooser — always works. |
| Workspaces | A VS Code-style sidebar: every workspace is a folder that expands to its own sessions, with lazy loading, per-row delete and per-node remove. A row is labelled by the first thing it was asked and is one click target end to end, and a finished turn re-reads the list so the conversation you just had is at the top. Named directories with their own session history — the workspace `ccj` starts in keeps the original sessions directory, added ones get their own under `<home>/workspaces/<name>/`. Switching changes the working directory *and* the history in one move, the active workspace is where the tools run no matter where the process was started, and reading a folded folder never moves the session you are in. |
| Web UI | `ccj` serves the same loop as a single page: streaming transcript, tool cards, blocking approval prompts, session switching, and a settings panel that configures the model at runtime. No framework, no build step. On a phone the two panels become sheets over the transcript. It is served on loopback and, when the machine is on a tailnet, on its tailnet address as well — so the same page is one command away from the laptop it runs on and from the phone in your pocket, with a generated token the only thing the network side needs. |
| Several conversations at once | A turn running in one session does not lock the server: start a second task in another conversation while the first is still working, and the sidebar marks the sessions that are running (■ stops one from anywhere, without opening it). What each session still takes is *one* turn at a time — two writers on one transcript is how it gets corrupted — and the operations that change what every conversation is built on (the model, the config file, the workspace) wait until nothing is running. Payloads of messages carry their session id, so a delta from a background turn can never be rendered into the transcript you are reading. |
| Self-bootstrap | The agent can rebuild this project and install the result: `mvn -q -DskipTests -Djar.name=ccj-next package` writes a scratch jar without touching the one in use (truncating a jar a JVM is executing is how the process dies mid-build), then `restart` renames it into place and the launcher starts the new code — **on the conversation you were on**: the ending process writes that session id down and the next one opens it, so a restart no longer hands you an empty transcript. See [docs/BOOTSTRAP.md](docs/BOOTSTRAP.md). |

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
          --max-context-tokens <n>
                               prompt budget: above it, old tool results are elided and older
                               exchanges dropped before the request is sent
          --reasoning <level>  how much the model should think: low, high or max
          --system <text>      system prompt for this run

Sessions:
          --resume <id>        reopen a session by id
          --continue           reopen the most recent session
          --list-sessions      print sessions and exit
          --workspace <name>   use a workspace: its directory and its own sessions
          --language <name>    think and answer in this language (auto: let the model decide)

Web:
          --port <n>           web UI port (default 6767)
          --host <addr>        bind address (default 127.0.0.1; 'tailscale' uses this machine's
                               tailnet address; anything else needs --web-token)
          --web-token <token>  require this token from every web request
                               (or set CCJ_WEB_TOKEN; the flag wins)
          --wallpapers <dir>   pictures the page rotates as its background
                               (default: ~/Pictures/ccj-backgrounds, or CCJ_WALLPAPERS)

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
`/compact`, `/tools`, `/config`, `/yolo`.

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
  "autoApprove": false,
  "outputLimitBytes": 32768,
  "systemPrompt": "optional override"
}
```

| Environment variable | Meaning |
|---|---|
| `CCJ_PROVIDER`, `CCJ_MODEL`, `CCJ_BASE_URL`, `CCJ_API_KEY`, `CCJ_API_KEY_ENV` | model selection and credentials |
| `CCJ_TEMPERATURE`, `CCJ_MAX_TOKENS`, `CCJ_OUTPUT_LIMIT_BYTES` | sampling and output limits |
| `CCJ_REASONING`, `CCJ_MAX_CONTEXT_TOKENS` | reasoning effort, prompt budget |
| `CCJ_AUTO_APPROVE`, `CCJ_SYSTEM_PROMPT` | approval mode and prompt override |
| `CCJ_VISION_BASE_URL`, `CCJ_VISION_MODEL`, `CCJ_VISION_API_KEY`, `CCJ_VISION_API_KEY_ENV`, `CCJ_VISION_MAX_TOKENS` | the model that describes pictures (its own endpoint, key and completion budget; unset means the feature is off — except the budget, which has a default) |
| `CCJ_WALLPAPERS` | pictures the page rotates as its background |
| `CCJ_WEB_TOKEN` | the token the network addresses must carry (without `--web-token`, which wins; otherwise `~/.oh-my-ccj/web-token`) |
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
| `edit` | `path`, `old_string`, `new_string`, `replace_all?` — or `edits: [{old_string, new_string, replace_all?}]` | exact match; ambiguity is an error with the match count, and a failed hunk comes back with the file's lines around where it was expected. The `edits` form changes several places in one file **together**: one approval, one write, and nothing written unless every hunk matches (a hunk that overlaps another, or that does not match, refuses the whole change). One diff of the whole file is what the approval shows |
| `bash` | `command`, `cwd?`, `timeout_seconds?` | `/bin/bash -lc`, stderr merged, exit code reported, output capped with head+tail |
| `glob` | `pattern`, `path?` | relative-path globbing incl. `**`, newest first, skips `target/`, `.git/`, `node_modules/`, `.idea/` |
| `grep` | `pattern`, `path?`, `glob?`, `ignore_case?`, `max_results?` | Java regex, skips binaries and files over 2 MiB |
| `restart` | `built` | installs a scratch build over the jar in use and restarts on it; ends the run. See [docs/BOOTSTRAP.md](docs/BOOTSTRAP.md) |

Tool failures never kill the session: bad arguments, unknown tool names, missing files and thrown
exceptions all come back as error results the model can read and correct. `restart` is the one tool
that ends the run on purpose — and only when it worked.

### Safety model

- `read`, `glob` and `grep` run freely; `write`, `edit` and `bash` require approval.
- With a terminal, approval is a `y/N` prompt that shows the tool, the full command, the resolved
  working directory and the timeout — and pauses the spinner while it waits.
- Without a terminal (pipes, CI), side-effecting calls are **denied** with an explanation unless
  `--yolo` was passed. Failing closed is the point.
- Relative paths resolve against `--cwd`; paths outside it are labelled in the approval prompt.

## Custom providers

Built-in names are compiled in; everything else is yours:

```bash
curl -X POST localhost:6767/api/providers -H 'content-type: application/json' -d '{
  "name": "myrelay", "kind": "openai",
  "baseUrl": "https://relay.example.com/v1",
  "apiKeyEnv": "MY_KEY", "models": ["deepseek-v4-flash"]}'
ccj --provider myrelay --model deepseek-v4-flash
```

`kind` is the wire protocol — `openai` for anything that speaks `/chat/completions`, `anthropic` for
the messages API. Definitions live in `~/.oh-my-ccj/providers.json`, are validated before they are
stored, and can be managed from the settings panel. The catalogue the UI reads comes from a
`ModelCatalog` interface rather than from the configuration directly, so a gateway that knows its own
model list can answer for itself — see [docs/ROUTER.md](docs/ROUTER.md).

## Workspaces

A workspace is a directory plus the sessions that belong to it, because a conversation only makes
sense next to the files it was about:

```bash
ccj --workspace api            # this run works in api's directory, with api's sessions
```

The active workspace is where the tools run, whichever directory `ccj` was started from — the one it
started in is only used to pick a workspace when the registry is empty, never to move the working
directory. `-C <dir>` is the one exception, a per-run override, and a session using it says so
instead of letting the workspace tree imply otherwise.

In the browser the workspace switcher does the same thing, and adding one from there is a single
click: **Add workspace** opens the desktop's own folder chooser, and the folder that comes back
becomes the workspace — its name is the folder's, with a numeric suffix if that name is taken, and a
directory already in the list is refused rather than given a second history. The directory is created
if it does not exist. The registry is `~/.oh-my-ccj/workspaces.json`; the workspace you
first started `ccj` in stays mapped to the top-level `~/.oh-my-ccj/sessions`, so sessions from before
workspaces existed are still there. Model settings are deliberately **not** per workspace — a key and
a model belong to the account, not to a folder. Forgetting a workspace never deletes conversation
files.

## Providers, endpoints and keys

`baseUrl`, `apiKey` and `apiKeyEnv` describe a *provider*, not the session, so `config.json` keeps one
pair per provider: the fields in effect at the top level (marked with `settingsFor`) and a `remembered`
entry for every other provider you have entered one for.

```json
{
  "provider": "CommandCode",
  "model": "deepseek/deepseek-v4.1-flash",
  "apiKey": "…",
  "settingsFor": "CommandCode",
  "remembered": { "deepseek": { "apiKey": "…", "baseUrl": "https://api.deepseek.com" } }
}
```

Switching provider moves the pair being left into `remembered` and takes the new provider's pair back
out, so switching is free in both directions and a key never migrates: it is sent only to the provider
it was entered for, with that provider's endpoint. A provider with no remembered pair is served by its
own definition (`providers.json`) or by the built-in defaults. Clearing the key field with a provider
selected forgets that provider's key; every other provider's is untouched. The settings form says
"saved" per provider (`rememberedProviders` carries names only, never a key), because a form that
promises a key it will not send is how the wrong endpoint gets written down again. The file only
records what you chose — a defaulted endpoint or key variable is left out and re-derived on load.

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
./mvnw test                               # the whole suite: no network, no API key
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
| providers (SSE parsing, both wire mappings, retry policy, custom definitions, effort tiers, catalogue) | 75 |
| tools (matching, truncation, timeouts, cancellation, denial paths) | 41 |
| core (loop behaviour, tool overlap, abort reaching a streaming model call, a project's own CCJ.md rules leading the prompt, context budget, token estimate, config precedence, per-provider settings, interrupted-history repair, the thinking-language prompt, compaction) | 108 |
| session (codec round-trips including thinking, append/reopen, the listing cache, generation files, the restart handover note) | 49 |
| CLI (argument parsing, mode selection, port suggestion) | 13 |
| end-to-end (CLI → HTTP → tool → disk, reasoning, budget, where tools run, CCJ.md reaching the request, a restart landing on the same conversation) | 26 |
| web API (HTTP, SSE, approvals surviving a session switch, refusal by session, sessions running side by side, opening a busy session, token gate, host guard, settings, workspaces, deletion, models, providers, compaction) | 88 |
| web rendering (the markdown, session-row, approval, replay, add-workspace and compaction cases, run under node) | 20 |
| restart (the swap, its refusals, and that nothing happens without approval) | 8 |
| folder chooser (subprocess plumbing, timeout, single-dialog guard) | 5 |
| wallpapers (numeric ordering, type from the bytes, containment, no directory) | 8 |
| tailnet (the address `--host tailscale` resolves, and what is refused) | 5 |
| workspaces (registry rules, persistence, isolation, naming a picked folder) | 11 |
| demo provider (routing, termination) | 8 |

The counts above are from the last run in the maintainer's environment. What CI actually runs, on Linux
and macOS, is `./mvnw -B -ntp verify` — see [.github/workflows/build.yml](.github/workflows/build.yml).
Where the work is going is in [docs/ROADMAP.md](docs/ROADMAP.md), and how code, tests and docs are
written here is in [docs/CONVENTIONS.md](docs/CONVENTIONS.md).

## Limitations

- One conversation at a time per session. Several conversations run side by side, and the agent can
  delegate to **sub-agents** that read in their own conversation and report back only a summary — off
  unless `--subagents` is passed, because a delegated run spends tokens the user did not type a
  message for. Inside a conversation, only read-only tools run concurrently: they are the only ones
  whose overlap cannot change what the transcript means. See [docs/SUBAGENTS.md](docs/SUBAGENTS.md).
- Pictures go one way and one step removed: an image is described by a vision model and the
  *description* joins the conversation. The main model never receives the image, so "what does this
  error say" and "transcribe this whiteboard" work while "is this pixel the right shade of blue" does
  not — ask again on a detail the description dropped, or `read` the file, which is where it is told
  to look. One picture per turn, up to 8 MB, and a second upload starts a second turn.
- No MCP, no plugins, no sandboxing — approval is the only guard, and `--yolo` removes it.
- Bash runs as the current user with your full environment.
- Sessions grow without bound on disk. What does not fit the context budget is elided or dropped;
  what you *ask* for with `/compact` is summarised, and only then is it a summary you can see and undo —
  the turns it replaced are still in the generation file beside it, and the summary names that file so
  the model can read a detail back rather than trusting its own paraphrase.
- A turn has no step ceiling, so a model that gets stuck will keep calling tools until you abort it.
  That is the trade: a long task is never cut off at a number somebody guessed, and stopping a stuck
  one is a decision you make while watching it rather than a guess made in advance.
- `cacheHitRate` is `n/a` unless the provider reports cache figures — a local model has no cache to
  hit and is not reported as 0%. Usage totals cover one session (they are stored beside the
  conversation, one record per turn), not a whole project or a billing period.
- The OpenAI path targets the chat-completions API, not the newer Responses API.
- The API key is stored in plaintext when you let the UI save it (the file is `0600`); keeping it
  in the environment instead is one dropdown away.
- The web UI is a local console: several conversations can work at once, but one browser page shows
  one transcript, and there are no accounts. It is served on named addresses — loopback, plus this
  machine's tailnet address when it has one — and never on a wildcard, so the port does not exist on
  any network the user did not name. Loopback needs nothing; every other address needs the token,
  because the agent can run shell commands. Inside a tailnet the token is what tells devices apart:
  the network decides who is nearby, the token decides who is let in.
- Several conversations can be running in the same workspace, which means their tools can write the
  same files with no ordering between them. That is the same exposure as two terminals in one
  directory, and it is deliberately not serialised — the point is to start a task and keep working.
- `abort` stops a turn between steps and before each tool call, so a turn blocked on the model's own
  network call finishes that call before it stops. A turn waiting for an approval stops immediately,
  in the conversation it belongs to.
