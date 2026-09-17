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

### Names

The name is the documentation that cannot go stale, so it carries the meaning and the comment does not
have to. One pattern per kind of thing, and it is the pattern the rest of the tree already uses:

| Kind | Shape | Examples |
|---|---|---|
| Class, record, enum | `PascalCase`, a noun — what the thing *is*, never `Manager`/`Helper`/`Util` | `ContextBudget`, `AttachmentStore`, `VisionConfig`, `SubAgentRole` |
| Method | `camelCase`, a verb phrase — what it *does*, no `get`/`set` prefix | `resolved()`, `forgetProviderSettings()`, `describePicture()`, `readBounded()` |
| Boolean-returning method / boolean field | `is`/`has`/`can` + the property, phrased as the question it answers | `isConfigured()`, `hasKey()`, `settingsBelongTo(provider)`, `isEmpty()` |
| Constant | `SCREAMING_SNAKE_CASE` with the unit or the bound in the name | `DEFAULT_MAX_TOKENS`, `MAX_REPLY_BYTES`, `HEARTBEAT_MILLIS` |
| Field | the noun it holds, no prefix, no `m_` | `vision`, `remembered`, `maxTokens` |
| Local, parameter | the name a reader would say out loud; short only where the scope is one screen | `provider`, `budget`, `settings` — `i`, `e`, `p` inside a loop or a `catch` |
| Test | the claim, as a sentence in the present tense, so a failure reads as the bug | `aPictureOverTheLimitIsRefusedWithoutBeingHeld` |
| JSON key on the wire | `camelCase`, and the same word on both sides | `visionMaxTokens`, `apiKeySource`, `sessionId` |
| DOM id, CSS class | `kebab-case` for ids, `kebab-case` for classes; a `cfg-` prefix for anything inside the settings form | `cfg-vision-maxtokens`, `photo-hint`, `ev-user` |

Three rules that come out of the table, because they are the ones that get broken:

- **A name that lies is worse than a long name.** `normalizeLanguage` returning null for "auto" is a
  method that does more than the name says; `withoutVisionKey` says exactly what it did.
- **The same thing keeps the same word everywhere** — code, javadoc, HTTP field, config key, docs. A
  second word for one concept is how the docs and the code drift apart.
- **Do not name a thing after its implementation.** `remembered` is what the map means to the reader;
  a `hashMapOfKeys` would name today's data structure.

### Comments

- **A comment earns its place by explaining a *why* the code cannot show**: a choice made against the
  obvious alternative, a failure that was measured, a boundary that is load-bearing, a word about what
  a caller must not do. Everything else is noise, and noise is what hides the comments that matter.
- **Restating the signature is forbidden.** `/** Reads the file. */` on `readFile` adds nothing; the
  paragraph about *bounded* reading — that the limit only limits if it applies to the read — is the
  part a future reader needs.
- **Measured means measured.** Give the number and what produced it: "measured: 1500 of 1500 tokens on
  reasoning, `content` empty", not "may be too small".
- **Comments go with the code they describe**, in the same change, including when the code is removed.
  A paragraph describing a guard that no longer exists is trusted by the next reader, which is worse
  than no paragraph.
- **No commented-out code, no `TODO` without a home.** Something not being done now is a
  [ROADMAP](ROADMAP.md) item with an acceptance line, not a comment that will outlive its context.
- **Section banners are for the browser files only.** `app.js` is one 5k-line classic script whose
  cases lift functions out of the shipped source by name, so `/* ---- transcript */` markers are part
  of the test harness. Java files get package, class and method javadoc instead.

### Errors

- **The message says what to do next.** `EditTool`: "`old_string` matched three times; pass a longer
  snippet or `replace_all`". `VisionClient`: "the model ran out of room while still reasoning (1500
  tokens, 1500 of them spent thinking) … Raise it with `maxTokens` …". The reader is a person pressing
  a button, or a model with one more attempt in it.
- **Name the thing that failed, with its identity**: the file and line, the endpoint and status, the
  provider and model, the variable or flag that sets it. "invalid input" is a bug report about the
  error message.
- **Never swallow a failure to keep a path simple.** A refusal that a caller cannot see, or a
  placeholder that looks like a success, is worse than a stopped turn: the empty vision reply is
  exactly this, and it is why `describe` throws instead of returning "".
- **Throw the type the caller maps to a status.** `IllegalArgumentException` → 400,
  `IllegalStateException` → 409, `PayloadTooLargeException` → 413, anything else → 500. An endpoint that
  wants a different answer returns it itself.

### The front end

- **One classic script, no build step, no framework.** `app.js` is served as written, so it must parse
  in a browser without a bundler and use no modules.
- **The page reads the server's vocabulary, not its own.** Provider names, reasoning levels and
  languages come from the API; a list invented in the page is a list of choices nothing acts on.
- **Model output is rendered as text** — DOM nodes, never HTML source — and a link only gets an `href`
  after its scheme is filtered.
- **A control that hides its label keeps an accessible name.** Below 720px the composer's buttons drop
  their words and keep their glyphs, so each of them carries an `aria-label`; the visible text is not
  the only name a screen reader can read.
- **Empty means "leave it as it is"** for every text field in the settings form, so anything that is a
  *removal* — clearing a key, turning a feature off — is sent as a flag of its own rather than as an
  empty string.

### Spelling and voice

- **British English**, in prose, comments, identifiers and messages: `normalise`, `summarise`,
  `serialise`, `behaviour`, `colour`. The codebase is written that way and a second spelling of one word
  is drift with no benefit.
- **Except where a platform owns the spelling**: CSS properties (`color`, `text-align: center`),
  browser APIs (`overscroll-behavior`, `behavior: 'smooth'`), JDK/Maven/vendor names (`Path.normalize`,
  `maven-shade-plugin`, a vendor's JSON field). Those are quoted, not translated.
- **Voice: terse, concrete, second person in docs, no marketing.** Say what was measured and what was
  not. A bare number ("542 tests pass") is true only until somebody adds one, which makes it a claim
  about a run nobody can reproduce; a number that names its run and its conditions — which command,
  on which platform, and whether the browser cases ran or skipped — stays a fact.

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

## How a rule here is checked

A rule nobody can check is a preference. Where a rule can be made mechanical it is, and the mechanism
is named next to it:

| Rule | Checked by |
|---|---|
| Names, comments, spelling | `mvn test`: `core/ConventionsTest` scans `src/main/java` for the American spellings this house does not use and fails with the file and the line |
| The page never builds HTML from a string | `WebMarkdownTest`, over the shipped `app.js` between its banners |
| The browser cases run the shipped source | `src/test/js/*.test.mjs`, run by `Web*Test` through node, reported as *skipped* when node is absent |
| Approval is the only guard | `SECURITY.md` says it, and the tests that remove a guard fail when the guard goes |
| Docs agree with the code | Review, and the entries in [CHANGELOG.md](CHANGELOG.md) and [ROADMAP.md](ROADMAP.md) that name the version — a README row that no longer matches is a bug |

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
