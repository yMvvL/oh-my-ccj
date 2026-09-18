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
| Real and working | The loop, both wire protocols, the eight tools, approval, sessions and resume, context projection, compaction with generations, the web UI, workspaces, cross-origin refusal, sub-agents with the conversation's approver |
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
- **2.1 An MCP client** — `todo`, **ordered as 2.5.6** — one item, one place in the order. The
  largest gap against the ecosystem: today the tool set is
  compiled in, so a user cannot bring their own. The shape matters more than the protocol — remote
  tools must enter through the same `ToolRegistry` and the same approver, and a server's tools must be
  visible in the approval prompt as coming from that server.
  *Acceptance:* a configured MCP server's tool runs through the existing approval path, and a refusal
  stops it.
- **2.2 More protocols** — `todo`. The OpenAI Responses API and Gemini, behind the existing `Provider`
  contract. Deliberately after 2.1: a new wire format adds less than a new tool source.
  *Acceptance:* the same scripted-provider tests pass against each mapping.
- **2.3 Better editing** — `todo`, **ordered as 2.5.4** — the same rule. A patch-style multi-edit
  (several hunks, one approval, one write),
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
| 2.5.1 | **A check runs itself after an edit.** `{"checks": [{"glob": "**/*.java", "command": "mvn -q -o -DskipTests compile"}]}` in the config file: the first check whose glob matches the edited path runs inside the `edit`/`write` call, and its verdict — one line when it passes, a bounded excerpt when it fails — is appended to the tool result | Same reason as above | `done` |
| 2.5.2 | **Permission rules, not one boolean.** `approvals.json` (keyed by project, in the application home) with allow and deny rules matching a tool, an exact command or one widened by a trailing ` *`, or a path glob — plus **allow for this session** on the prompt. The prompt used to have two answers: yes once, or yes-everything-for-the-session | Same reason as above | `done` |
| 2.5.3 | **Messages queue instead of 409.** A message sent while a turn runs waits behind it, the composer says how many are waiting, and each queued message runs as a turn of its own. Abort drops the queue and says how many | Same reason as above | `done` |
| 2.5.4 | **`edit` takes several hunks, and a failed match comes back with the file's neighbourhood** — 2.3, pulled forward to here. `edits: [...]` is matched, approved and written together: all or none, one diff, one write, overlapping hunks refused | The exact-match single hunk is the tool a model fails most often, and every failure used to be a whole round trip | `done` |
| 2.5.5 | **A networking tool.** `fetch`: `http`/`https` only, text types only, bounded while reading, redirects re-checked hop by hop, approved like everything else with the URL as the rule's subject | Without it the model guesses at APIs, which is when answers get confidently wrong | `done` |
| 2.5.6 | **An MCP client** — 2.1, pulled back to here. Servers declared in `<home>/mcp.json`, their tools registered as `mcp__<server>__<tool>` through the same `ToolRegistry` and the same approver | The ecosystem gap: the largest difference in what the agent can attempt at all. Behind the items above because those change every turn, and this changes some turns | `done` |
| 2.5.7 | **Prompt caching and automatic compaction.** Three `cache_control` breakpoints on the Anthropic prefix — system, tools, end of conversation — and a conversation that compacts itself when it grows past `maxContextTokens`, between turns, with a refusal remembered at double the size | Long sessions get slower and more expensive than they need to be, and the user is the one who has to notice | `done` |
| 2.5.8 | **Checkpoints.** A per-turn snapshot of the files a turn touched, `POST /api/undo` / `/undo` to put the last one back, twenty turns kept, oversized files skipped | Trust is what lets somebody leave auto-approve off *and* let the agent work | `done` |

**2.5.1 as built**, in `core/Checks` + `tool/PostEditCheck`, sharing the process plumbing with `bash`
through `tool/ProcessRunner` (extracted from `BashTool`, which is why its eleven tests were the
regression net for that refactor). Bounded four ways so it can run on every edit: one check per edit,
a 4 KiB report, a per-check timeout that kills the process tree, and nothing at all after a failed
edit or an aborted turn. A third thing came from running it against this repository rather than from
writing it: the glob decides when the check runs, not what the command looks at, so `mvn compile`
reported success about a file at the project root it never compiled. The passing line is `exit 0`
rather than "clean" for that reason, and the caveat is in `Checks`' javadoc, the README and
SECURITY.md. Two things the tests pinned that would otherwise have been bugs: a check
never runs for a file outside the session's working directory, and a pattern beginning `**/` matches
at the project root as well as inside it — `PathMatcher` alone does not do that, and a check that
silently never fires looks exactly like a check with nothing to report.

**2.5.2 as built.** `Approver` became `ApprovalAnswer approve(ApprovalRequest)` — four answers over a
structured request (tool, command, path, and the prose for the prompt) — because a boolean could not
say "this one, not everything", and two strings could not be matched by a rule without matching prose.
`RuleApprover` puts the rules in front of whoever is watching, and both front ends learned the four
answers: the browser's prompt has four buttons, the terminal's prompt takes `y`/`s`/`a`/n. Measured on
the way: two bugs the tests caught that no amount of reading would have — a rule remembered for a
command containing `>` could never match it (the metacharacter rule was being applied to exact
comparisons, where nothing can be widened), and the first rule a user ever grants threw
`UnsupportedOperationException` into the tool call because the empty rules file was an immutable map,
so "allow from now on" silently did nothing on the very first use. Both are pinned by tests now, and
the second one is why the third test of that path exists: the two before it started from a file that
already had a project in it.

**What is left, and what each one needs.** 2.5.2 is next (below). After it, in order: **2.5.3**
message queueing — the composer holds the next message instead of the server answering 409
(`HttpApi.message`), which needs the hub to keep a per-conversation queue and the page to say so;
**2.5.4** multi-hunk `edit` (2.3) — the tool one model in five misses, and each miss is a round trip;
**2.5.5** a `fetch` tool — the first tool that leaves the machine, so it needs its own rules about
schemes, size and what the model is told to trust; **2.5.6** the MCP client (2.1) — the largest change
to the tool registry since it was written, and the reason 2.5.2 has to land first, because a server's
tools must arrive already knowing which rules apply to them; **2.5.7** prompt caching and automatic
compaction — cheap to add, and it stops the user being the one who has to notice a session has grown;
**2.5.8** checkpoints — the last of the trust items, and the one that makes leaving the guard on
comfortable rather than merely possible.

**The decision that came with it, recorded rather than discovered later.** A check command is a
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
