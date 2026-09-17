# Conventions

How code, tests and documentation are written in this repository. It exists because the project's
main asset is that one person can read it in an afternoon and know what it does — every rule below is
in service of that, and a rule that stops serving it should be changed here rather than worked around
in a file.

## The shape of the thing

`core/` is the contract. `cli/`, `ui/`, `web/`, `provider/`, `session/`, `tool/` and `demo/` plug into
it, and none of them knows about each other. The loop sees a `Provider` and a `ToolRegistry` and has
never heard of HTTP or a terminal, which is why the same loop runs headless in a test, behind a
scripted provider, and behind the page.

Rules that keep it that way:

- **Nothing in `core/` imports from a front end.** If a change needs `core` to know about a socket, the
  seam is wrong.
- **Only `cli/` calls `System.exit`.** Everything else returns a value or throws.
- **A tool never throws at the model.** `ToolRegistry.execute` turns a bad argument, an unknown tool
  name and a thrown exception into a `ToolResult.error` the model can read and correct. A tool that
  throws where it could report is a bug in the tool.
- **A front end never re-implements the loop.** It implements `AgentListener` and gets the events.

## Toolchain and dependencies

- **Java 21.** Records, sealed interfaces, pattern matching in `switch`, virtual threads. No preview
  features.
- **One runtime dependency:** `jackson-databind`. A second one is a decision to argue for in a commit
  message, not a convenience. Tests use JUnit 5 only.
- **No build step for the front end.** `web/app.js` is a classic script with no modules, no bundler and
  no npm dependency. It is served out of the jar as written.
- **Tests are offline.** No network, no API key, no live model. A test that needs one of those is a
  test that cannot run in CI, which means it is not a test.
- **Supported platforms:** Linux and macOS. Windows through WSL. `BashTool` hard-codes `/bin/bash`; do
  not claim Windows support until that is configurable.

## Code

- **Two-space indent, ~100 columns.** Continuation lines are indented to read as a unit, not to satisfy
  a formatter.
- **`final class` for anything not designed for inheritance.** Records for value types. Enums for
  closed sets of choices (`SubAgentRole`).
- **Javadoc explains *why*, never *what*.** `/** Runs the command. */` on a method called `run` is
  noise. A paragraph on why the interrupt is aimed only at the model call, and not at a running tool,
  is the kind of comment that earns its place.
- **Record the measurement.** When a comment says a design was chosen because of a failure, name the
  failure — "measured: a sub-agent wrote `../../真实文件.txt` over a real file, with no prompt". That
  sentence is why the next person does not delete the check.
- **No comment describing behaviour that no longer exists.** When a mechanism is removed, its
  paragraph goes in the same change. A stale comment is worse than none: the reader trusts it.
- **Names say what the thing is.** `SubAgentRunner`, `ContextBudget`, `SessionRepair`. Short names are
  fine inside a small scope (`i`, `cwd`, `ctx`), not for anything a reader has to hold across a screen.
- **Errors carry what the caller needs to act.** `EditTool` reports that `old_string` matched three
  times and suggests `replace_all`, rather than "invalid edit". `FileSession` reports the file and the
  line number of a malformed record.
- **No magic numbers without a name.** `CANCEL_POLL_MILLIS = 150` with a sentence about why 150 is
  enough, not `Thread.sleep(150)`.

## Security

The product runs commands as the user. Everything here is load-bearing.

- **Approval is the guard, and it is the only one.** Do not add a code path that writes or executes
  without going through `Approver`. Do not widen a default (`Approver.ALWAYS`) to make a feature
  convenient — that is how the sub-agent staging escape happened.
- **A sub-agent asks the same person the main agent asks.** It is invisible in what it *reads*, never
  in what it *does*.
- **No wildcard bind, ever.** Named addresses only, and any non-loopback address requires the token.
- **State-changing endpoints check `Origin`.** The loopback and `Host` checks cannot tell one browser
  page from another; that check is the only one that can.
- **Keys are never printed or returned to the browser.** `/config` shows at most `***1234`. The settings
  response says whether a key exists, not what it is.
- **The page renders model output as text.** No `innerHTML`, no `insertAdjacentHTML`, no `eval`. Link
  schemes are filtered after normalisation. A test asserts this on the shipped source.
- **A claim of safety must be one the code makes.** If a paragraph in `SECURITY.md` describes a
  boundary, there is a test that fails when the boundary is removed.

## Sub-agents

- **No `task` in a sub-registry.** Recursion is refused by construction; there is no depth counter to
  get wrong.
- **The role decides the tools.** A verifier that can write is not a verifier.
- **One writer at a time**, process-wide. Two writers on one path lose one result silently.
- **The deadline covers the queue**, not just the work.
- **Usage is added to the conversation that paid.** Hiding the reading is the feature; hiding the cost
  would be a number that is quietly wrong.

## Tests

- **The name is the claim.** `aCrossOriginRequestCannotChangeState`,
  `aQueuedWriterGivesUpRatherThanRunningPastItsDeadline`. A test whose name does not state a property
  is a test that will be deleted the first time it fails.
- **Test behaviour through the real thing.** A real `AgentLoop` with a scripted `Provider`, a real file
  system under `@TempDir`, the real CLI over a real HTTP server. No mocks of the class under test.
- **A test that cannot run must not look like one that passed.** Skipping is `Assumptions` or an
  explicit failure, never an early `return`.
- **Deliberately awkward inputs are the point.** SSE frames split mid-line, tool output cut mid-UTF-8
  character, a session file interrupted between a call and its result. The mock server fragments frames
  on purpose, because a relay does.
- **The front end's cases run the shipped source.** `src/test/js/*.test.mjs` lifts the renderer out of
  `web/app.js` between its banner comments, so the assertions are about what is served, not a copy of
  it.
- **Every fixed bug gets a test that fails without the fix.** Written in the same change, not after.

## Documentation

- **Docs ship with the code.** README, `docs/`, `SECURITY.md` and the comment next to the change, in one
  commit. A feature nobody can find is a feature nobody has.
- **The README feature table is a claim per row.** A row that no longer matches the code is a bug.
- **Say what was measured, and what was not.** "472 tests" belongs in the README only while a run can
  reproduce it. When something was not verified on the machine doing the work, the change says so.
- **`Limitations` is not an apology section.** Every entry is a trade the reader needs in order to
  decide whether to trust the tool, written as the trade it is.

## Making a change

1. **Read the file first.** Evaluate what is on disk, not what it was last time.
2. **Write the failing test**, or name the acceptance check for a feature.
3. **Make the smallest change that satisfies it.** Readability is the budget being spent.
4. **Run what can be run.** If the toolchain is not available on this machine, say so explicitly rather
   than implying a green run.
5. **Update the docs and the comments** in the same change, including removing what is no longer true.
6. **Commit messages:** `<type>: <what changed, in the imperative>`, e.g.
   `feat: a CCJ.md leads the prompt, read from one directory`. The body says why, and names anything
   that was measured. `fix:` for a defect, `docs:` for documentation only, `refactor:` for a change
   that alters no behaviour.

### Definition of done

- [ ] A test fails without this change, and passes with it
- [ ] `mvn -q test` green, or the reason it could not run is stated
- [ ] The JS cases run, or CI reports them as skipped rather than passed
- [ ] README / `docs/` / `SECURITY.md` agree with the code, including anything removed
- [ ] No comment left describing behaviour that is gone
- [ ] No new dependency, or the commit message argues for it
- [ ] The approval path is unchanged, or the change says how it was kept honest

See [ROADMAP.md](ROADMAP.md) for what is being worked on, in what order.
