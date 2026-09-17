# Contributing

Small project, one maintainer. Most useful contributions are small: a bug report with the output in
it, a fix to something that misbehaved, a clearer sentence in a comment that misled you.

**Nothing here is a gate.** If the requirements below do not fit what you have time for, open the
issue or the pull request anyway and say so. A reported bug with no fix is worth more than a fix
nobody was told about, and an honest "I could not test this" is more useful than silence.

## Before you open a pull request

```bash
./mvnw test
```

That is the whole requirement. No network, no API key, about a minute. CI runs the same command on
Linux and macOS, so you will find out quickly either way — and if your change is platform-specific,
not running it yourself is fine as long as you say so.

The browser cases under `src/test/js/` run under node and are wired into the JUnit suite. Without node
they are reported as **skipped** rather than passing, so if the summary says `Skipped: 6`, the page was
not tested. CI installs node, so this only affects your local run.

### One trap worth knowing

`./mvnw package` and `./mvnw verify` write `target/ccj.jar` — **the jar a running ccj executes from**.
On Linux and macOS a running process keeps the inode it opened, so overwriting the file usually does
not kill it. This repository is stricter than the platform, deliberately:

```bash
./mvnw -Djar.name=ccj-next package      # writes target/ccj-next.jar, touches nothing live
```

The `jar.name` property exists for exactly this, and the launcher uses it when it rebuilds for you.

## If you are fixing a bug

A test that fails before the fix is the most valuable thing in the pull request — it is the evidence
that the bug was the bug you thought it was, and it stops the same bug coming back. If you are not sure
how to write one, describe what you did to reproduce it instead; that is nearly as good, and I can
usually turn it into a test.

If it is a one-character fix and the test would be longer than the fix, say so and skip it.

## If you are adding a feature

Please open an issue first, briefly. Not for permission — to save you work. Several capabilities are
deliberately absent (MCP, plugins, sandboxing, multi-user, images) and a few have been considered and
rejected for reasons that are not obvious from the code. A sentence of back-and-forth is cheaper than a
weekend spent on something that cannot land.

Small things — a flag, a better error message, a missing case — do not need this. Open the pull request.

## Writing style

**Comments explain why, not what.** The code says what it does. What is worth writing down is the
alternative that was rejected and the reason for choosing this one. Not every method needs it: one
where the choice was real does, and the existing files show the pattern.

**Say what you verified.** "Should work" is not a result. If you ran something, quote the command and
what it printed. If you did not, say so — that is fine, and an honest gap is worth more than a
confident claim that turns out to be false.

**Prefer a measured number to an argued one.** Ten minutes with a stopwatch or a counter beats a
paragraph of reasoning. A few existing examples, to show the tone rather than to set a bar:

- the sub-agent approval path exists because a test showed a sub-agent writing over a real project file
  with no prompt, since it ran with an always-allow approver;
- `/compact` refuses to run when its summary would not be smaller, after a real session compacted from
  4239 to 4236 tokens — a cost with no benefit;
- the SSE keep-alive is a real event rather than a `: ping` comment because a comment reaches no
  handler, so a page waiting on an approval saw the prompt flicker while it reconnected underneath;
- the session-list cache test was shown to be load-bearing by changing the cache condition to
  `if (false)` and watching it fail.

None of these were required of anyone. They are what happens when a claim is worth checking.

## Style

- Java 21, no framework. `javac` warnings are the lint.
- Two-space indent, 100-column lines. The existing files are consistent; match them.
- Public methods get a javadoc sentence. Private ones get a comment only where the *why* is not obvious.
- No new runtime dependency without asking first — the current set is `jackson-databind` and nothing
  else, and keeping it that way is part of the point.
- Test names say what behaviour they pin: `aParentDirectoryEscapeIsRefused`, not `testWriteToolRefuses`.

## Reporting a bug

What you ran, what you expected, what happened, and the exact output. If a model is involved, say which
provider and model — behaviour differs enough between them to matter. Do not paste an API key; if one
appears in output you were about to send, rotate it first.

Security issues go through [SECURITY.md](SECURITY.md) rather than a public issue, so a fix can exist
before the details do.

## Out of scope

Not because the ideas are bad — because the project is deliberately small, and each of these would turn
it into something else:

- MCP, a plugin system, a marketplace
- Sandboxing, or any claim that `bash` is confined (it is not, on purpose)
- Multi-user accounts, a hosted service, a shared instance
- Image and file attachments
- Any platform beyond Linux, macOS and WSL (the shell tool is `/bin/bash`)

If you think one of these is wrong, open an issue and argue for it. The list has changed before.
