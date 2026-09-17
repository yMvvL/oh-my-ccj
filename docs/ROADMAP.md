# Roadmap

Where this project is going, in the order the work actually has to happen.

Two rules decide that order. **A claim without a way to check it is a liability**, so verification
comes before features — including features that already exist on paper. And **the guard is the
product**: this is a tool that runs commands as you, so anything that widens what it may do without
asking is a bigger decision than anything that makes it faster.

Status markers used below: `todo`, `doing`, `done`, `blocked`.

## Where it stands

Honest inventory as of September 2026, because a roadmap that starts from an inflated picture is
fiction.

| | |
|---|---|
| Real and working | The loop, both wire protocols, the seven tools, approval, sessions and resume, context projection, compaction with generations, the web UI, workspaces, cross-origin refusal, sub-agents with the conversation's approver |
| Checked by CI | `./mvnw -B -ntp verify` on Linux and macOS with JDK 21 and Node 20, then packaging the jar and starting it. The Node case files report as skipped when they cannot run, rather than passing |
| Real but unverified *here* | A change made on the maintainer's Windows machine. There is no JDK, no Maven and no `~/.m2` there, so nothing in this list can be run locally — CI is the only evidence, and a change that never reaches a push has no evidence at all |
| Missing | MCP, native Windows support, any sandbox |
| Recently landed | Pictures: one image per turn, described by a **separate** vision model whose endpoint, key and model are configured on their own; the description joins the conversation as ordinary text and the file is stored beside the session. The main model never receives an image, which is why neither wire format, nor `Message`, nor compaction changed. See [VISION.md](VISION.md) |
| Recently removed | The sub-agent staging directory (`<cwd>/.ccj-work`), and with it the promote step. Approval replaced it — see [SUBAGENTS.md](SUBAGENTS.md) |

## Phase 0 — Make the claims checkable — `done`, with three loose ends

**What landed, and what it bought.** The Maven Wrapper pins the Maven version, so a contributor's build
is the build CI runs. A GitHub Actions workflow runs the suite on two platforms, packages the jar and
starts it — which is the only thing that turns "542 tests pass" from a claim into evidence. And the
Node case files now report as *skipped* when they cannot run instead of passing, with a test that pins
that behaviour through `TestAbortedException`.

Loose ends, each small and each the same kind of defect the phase existed to remove. The first one is
closed since this was written: neither the README nor the workflow names a test count any more, and
the workflow's comment says why — the number drifts with every test added, and the badge is the live
answer.

- **The compiler plugin is still implicit.** The wrapper pins Maven, so the default binding is stable
  in practice, but no version is written down for a reader to check.
- **Two cases still return early when `app.js` is missing** (`theAnswerIsRenderedAsMarkdown`,
  `theRendererBuildsNodesAndNeverHtmlSource`), which JUnit records as a pass — the same defect the Node
  runner just had, in the narrower case of the file rather than the runtime being absent.

## Phase 1 — Close the correctness gaps that are still open

**Why before new capability.** Each of these is a case where the code is right in the common path and
wrong at the edge, and the edge is where a coding agent gets used: long sessions, several
conversations, a user editing the same file in their editor.

**Recently closed**, listed so the list below is not read as a backlog that never moves: the sub-agent
staging escape and the shared-directory cleanup that went with it (both removed with the directory),
unapprovable sub-agent writes, `edit` and `write` refusing a file that changed or appeared while the
approval waited, both writes staged-and-renamed instead of written in place, cross-origin state
changes, an error body read to the end before being truncated, and a Node case file that reported a
missing runtime as a pass. All of it verified by reading the source — none of it by a run on the
machine this list was written on.

- **1.1 Two conversations, one file** — `todo`. The README calls this deliberate ("the same exposure
  as two terminals in one directory"). That is defensible for `bash`, less so for a tool that shows you
  a diff and then writes something else: `write` now refuses a file that *appeared* during the
  approval, but nothing refuses a file that *changed* during it.
  *Acceptance:* either the overwrite case is detected and refused the way the appearance case is, or
  the approval prompt says plainly that another turn may be writing the same path.
- **1.2 The deadline that fires mid-run** — `todo`. Cancellation is well covered (a streaming turn, a
  running tool, an abort between a turn and its tools, an approval answered by an abort). What is not
  covered is the case the sub-agent deadline exists for: a run that keeps working past its ten minutes.
  The queueing half is tested; the firing half is asserted only at the level of the message it
  produces.
  *Acceptance:* a scripted sub-agent that never stops is cut off at the deadline, and its report says
  so.
- **1.3 Usage accounting** — `doing`. A sub-agent's tokens now reach the conversation that paid
  (`SubAgentRunner.UsageTally` → `SubAgentReport.withUsage` → the session's books), with tests added
  alongside this roadmap. Still open: whether a compacted session double-counts, and whether the
  estimate in the panel can be checked against a provider's own figures.
  *Acceptance:* the panel's total equals the sum of the provider's per-turn numbers for a session that
  used sub-agents and was compacted once.
- **1.4 Session files grow without bound** — `todo`. Known and documented; the question is whether a
  long-lived session should be pruned, archived, or left alone with the disk as the user's problem.
  *Acceptance:* a written decision, and a `--max-session-bytes`-shaped option if the answer is "prune".
- **1.5 A test that drives the page** — `todo`. The markdown cases run the shipped source against a
  small DOM, which covers parsing and not layout, focus, or the approval handshake in a real browser.
  *Acceptance:* one browser-level smoke test that drives a turn end to end, marked optional in CI so a
  machine without a browser still passes.

## Phase 2 — Capability, in the order it earns its keep

**Why this order.** Each item is judged by how much it expands what the agent can do *without*
weakening the approval story. Anything that would need a sandbox to be safe does not belong here yet.

- **2.0 Pictures** — `done`. The first capability in this list to land: one image per turn, described
  by a vision model configured separately from the main provider, with the description — not the image
  — joining the conversation, and the file stored beside the session rather than in the project.
  *Acceptance, met:* `POST /api/attachment` with a real PNG produces a turn whose message is the
  description, the picture is on disk under `<id>.attachments/`, and every refusal (not a picture,
  over 8 MB, busy conversation, no vision model configured) leaves the vision endpoint uncalled. The
  design, the measurements and what was not verified are in [VISION.md](VISION.md).
- **2.1 An MCP client** — `todo`. The largest gap against the ecosystem: today the tool set is
  compiled in, so a user cannot bring their own. The shape matters more than the protocol — remote
  tools must enter through the same `ToolRegistry` and the same approver, and a server's tools must be
  visible in the approval prompt as coming from that server.
  *Acceptance:* a configured MCP server's tool runs through the existing approval path, and a refusal
  stops it.
- **2.2 More protocols** — `todo`. The OpenAI Responses API and Gemini, behind the existing `Provider`
  contract. Deliberately after 2.1: a new wire format adds less than a new tool source.
  *Acceptance:* the same scripted-provider tests pass against each mapping.
- **2.3 Better editing** — `todo`. A patch-style multi-edit (several hunks, one approval, one write),
  because a model fixing five places currently produces five prompts.
  *Acceptance:* one approval shows all hunks, and a partially-applied patch is impossible.
- **2.4 Sub-agent tuning** — `todo`. Per-role model and effort: a cheap model for `explore`, the main
  one for `verify`. Cost is already counted into the conversation's books, so what is missing is
  per-run visibility — the transcript says a task ran, not what it spent. Not nesting: recursion is
  still refused by construction.
  *Acceptance:* a role can be pinned to a model, and a delegated run's own cost is readable next to
  the task card.
- **2.5 Native Windows** — `todo`. A configurable shell instead of `/bin/bash`, and a launcher that is
  not a bash script. Today WSL is the documented answer, which is honest but limits the audience.
  *Acceptance:* a turn runs on Windows without WSL, with the shell named in the config.

## Phase 2.5 — The loop around the loop

**Why here.** Phase 2 answers "what can it do"; this answers "why is using it worse than it should
be". Every item below came out of using the tool and finding the friction, and they are ordered by
what a person feels first, not by what is most interesting to build.

| # | What | Why it is first | Status |
|---|---|---|---|
| 2.5.1 | **A check runs itself after an edit.** A command declared in the config (`checks`: a command, an optional file pattern) runs after a successful `edit`/`write` that matched it, and a bounded tail of its output is appended to the tool result — so the model is told what it broke without having to decide to go and look | The single largest difference from an IDE-backed agent: a compiler error arrives in the same step as the edit instead of three turns later, and a weak model converges in one pass instead of four | `doing` |
| 2.5.2 | **Permission rules, not one boolean.** `approvals.json` with allow rules (tool name, command prefix, path glob), plus "allow this one for the session" on the prompt. The prompt's only current answers are "yes, this once" and "yes, everything, all session" | This is the root of the felt friction: the choice today is being interrupted or turning the guard off, and people turn the guard off | `todo` |
| 2.5.3 | **Messages queue instead of 409.** Typing while a turn runs queues the next message, with the composer saying so | A 409 turns a thinking pause into a dead stop, and "it feels slow" is partly this rather than token speed | `todo` |
| 2.5.4 | **`edit` takes several hunks, and retries once with the error.** One approval, one write, and a failed match comes back with the file's neighbourhood attached | The exact-match single hunk is the tool a model fails most often, and every failure is a whole round trip | `todo` |
| 2.5.5 | **A networking tool.** `fetch` (URL → text, size- and scheme-checked) so "what changed in this library" is answerable | Without it the model guesses at APIs, which is when answers get confidently wrong | `todo` |
| 2.5.6 | **An MCP client** — see 2.1, which this depends on being honest about: remote tools enter through `ToolRegistry` and the same approver | The ecosystem gap; it is 2.5.6 rather than 2.1 because the four items above change the daily experience more | `todo` |
| 2.5.7 | **Prompt caching and automatic compaction.** Anthropic's `cache_control` breakpoint on the stable prefix; compact when the projection crosses a threshold rather than only when asked | Long sessions get slower and more expensive than they need to be, and the user is the one who has to notice | `todo` |
| 2.5.8 | **Checkpoints.** A per-turn snapshot of the files a turn touched, and a way back to it | Trust is what lets somebody leave auto-approve off *and* let the agent work | `todo` |

**2.5.1 needs a decision, and it is recorded here rather than discovered later.** A check command is a
command, and [CONVENTIONS](CONVENTIONS.md) says approval is the only guard for anything that executes.
The command is one the user wrote into their own config file, which is the same act as writing it in
`CCJ.md` or typing it — so it runs without a prompt, and that is a deliberate widening of the approval
model, written down in [SECURITY.md](../SECURITY.md) as one. What it may not do is become a general
escape: the hook runs the configured command, with the session's working directory, and nothing else.
When 2.5.2 lands, the check becomes an ordinary allow rule and this exemption goes away.

## Phase 3 — Distribution

- **3.1 Releases** — `todo`. Versioned tags, notes that say what changed and what it cost, and a
  statement of which JDK the jar was built for.
- **3.2 Packaging** — `todo`. Decide between a fat jar (today), `jpackage`, and a native image. The
  last one conflicts with reflection-based Jackson configuration unless it is configured for it.
- **3.3 Documentation that cannot drift** — `todo`. The README's feature table is the project's
  selling document and its biggest maintenance cost; the pieces that can be generated from code
  (tool schemas, the usage table) should be.

## Not planned

Written down so it stops being re-litigated:

- **A sandbox.** Approval is the model, and the README says so. A container-based sandbox is a
  different product with a different trust story.
- **A multi-user server.** No accounts, no tenancy, no per-user sessions. The web UI is a console for
  the person who started it.
- **A plugin marketplace.** MCP (2.1) is the extensibility story.
- **Cost in currency.** A rate card is an assumption that changes without telling this project; token
  counts and cache hit rate are facts.
- **Sub-agents talking to each other.** They report to the main agent, which is the only participant
  with the whole picture.

## How an item gets done

One item at a time, in the order above, with the shape the existing work already has:

1. **The test first.** If the item is a bug, the test that fails without the fix is the deliverable's
   other half. If it is a feature, the acceptance line above is the test.
2. **The smallest change that satisfies it.** This project's value is that it is readable in an
   afternoon; a clever fix that costs that is a bad trade.
3. **Docs in the same change.** README, `docs/`, and the comment next to the code. When a mechanism is
   removed, its description goes with it — a stale paragraph claiming a guard that no longer exists is
   worse than no paragraph, because the next reader trusts it.
4. **Say what was measured.** "Measured: a second conversation deleted the first one's files" is worth
   ten paragraphs of reasoning, and it is how the existing docs are written.
5. **Report what did not run.** A change that could not be verified on this machine says so, in the
   commit message and in the reply to whoever asked for it.

See [CONVENTIONS.md](CONVENTIONS.md) for the code-level rules that go with this.
