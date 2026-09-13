# Self-bootstrap

How ccj rebuilds itself, installs the result, and comes back on the new code — and the three things
that make it non-obvious enough to be worth writing down.

```bash
ccj                                     # or ./ccj, or the symlink on PATH
# ... the agent edits src/main/java/...
#     mvn -q -DskipTests -Djar.name=ccj-next package
#     restart {"built": "target/ccj-next.jar"}
# -> the process exits, the launcher starts the new jar, the session is on disk
```

Nothing extra is needed to try it: `ccj` is the launcher, and the `restart` tool is in the standard
tool set. `ccj --repl`, then ask for a change to ccj itself.

## Why this is not just `mvn package && exec java -jar`

Three separate facts, each of which turns the naive version into a crash.

**1. A build truncates the jar it writes, and truncating an executing jar kills the JVM.**
`mvn package` does not replace the jar atomically. The jar plugin truncates `target/ccj.jar` and
writes a new one; the shade plugin then replaces that in place. A JVM running from that file has
mmapped it, and the bytes underneath it change. Classes already loaded keep working — which is why
the failure looks arbitrary — and the next class that has to be *loaded* fails:

```
Exception in thread "main" java.lang.NoClassDefFoundError: com/ccj/agent/core/AgentLoop$Result
	at com.ccj.agent.core.AgentLoop.run(AgentLoop.java:138)
```

Nothing in that message says "the jar was rewritten". That is the whole reason this is an operation
in the tool set instead of a shell one-liner people are expected to get right.

**2. Renaming over it is fine.** Overwriting the bytes of an open file is not; replacing the
*directory entry* is. `mv new.jar target/ccj.jar` is an atomic rename on one filesystem, the running
process keeps the inode it already opened, and it can keep lazy-loading classes from it — verified
with a jar whose classes are loaded one per tick while it is renamed over. So a build writes a
different filename (`-Djar.name=ccj-next`) and the swap is a rename, and the process that did the
work survives long enough to say so.

**3. A process cannot replace its own code, so it asks to be restarted.** After the swap, this
process is running bytes no longer on disk. It exits with status 75 (`EX_TEMPFAIL`, chosen because
the CLI itself never returns it — 0, 1 and 2 are taken), and the launcher loop starts the installed
jar. The session is a file and the next process is told to open it (see the launcher's rules), so
the conversation continues; the tool ends the run rather than letting the model ask for one more turn
it will never get to send.

## The pieces

| Piece | What it does |
|---|---|
| `pom.xml` `-Djar.name` | the jar filename is a property, so a rebuild of this project can write `target/ccj-next.jar` and never touch the live jar |
| `RestartTool` (`restart`) | approval-gated; refuses anything but "install the scratch build over the installed jar"; renames it, marks the restart, ends the run |
| `ToolContext.endRun()` | how a tool ends a run that is not failing and not being aborted |
| `Cli` | turns the request into exit 75 from `-p`, the REPL and the web server (which releases its block instead of waiting for Ctrl-C) |
| `ResumePoint` | the note in `<home>/resume`: which conversation the next process should open, written before the exit and spent when it is read |
| the `ccj` launcher | re-runs the process on a restart, and knows when not to: a one-shot `-p` prompt is answered, not re-issued |

## The launcher's rules

A restart is only worth honouring once. A run that ends in a restart has *already* installed the jar
it wanted, so restarting the same request again would run identical code through an identical
request.

- With `--repl` or the web UI (the default), the new jar is started **on the conversation the old
  one was on**: the process that is ending writes that session id into `<home>/resume` before it
  returns `75`, and the next process reads it and opens it. The note is spent when it is read, so it
  resumes exactly one restart — a later start is a new session again, unless `--resume`/`--continue`
  says otherwise (an explicit choice always wins). Without this, a restart would hand the user an
  empty conversation at the moment they asked for their work to be updated, which is what made the
  handover feel like a crash even though nothing was lost.
- With `-p`, the prompt is **not** re-issued: the work it asked for is done, and the answer was the
  restart. The launcher says so and exits 0.
- A second restart in a row stops with the restart code, which is a state a human should look at —
  it means a run is installing and re-requesting itself in a loop.

## Costs and limits

- **The installed jar is only replaced between builds.** Anything already running keeps its old code
  until it restarts. That is inherent: two processes cannot share one filename across an upgrade.
- **The agent can brick this project.** It edits the source it runs from, and a bad change means the
  next start fails. The session files survive — they are JSONL under `--home` — and so does the
  previous jar if it is copied aside first (`cp target/ccj.jar target/ccj.jar.bak`), which is the
  cheap habit worth having before a self-modifying run.
- **A one-shot run cannot be continued.** Its session is on disk, but there is no live process, so
  the new jar is left installed and waiting.
- **A restart is not a hot reload.** It is a new process; nothing in memory survives it.

## Testing

`RestartToolTest` pins the decisions rather than the process lifecycle: the swap happens and ends the
run, a rejected restart changes nothing, and each refusal (missing jar, no installed jar, the
installed jar itself, an unrelated jar) comes back as an error. `ResumePointTest` pins the handover
note on its own — written, replaced, spent, and never fatal when it is missing or damaged.
`CliEndToEndTest` covers the pair: the process after a restart opens the conversation the previous
one was on, an explicit `--resume` still wins over the note, and a note naming a session that no
longer exists starts fresh instead of failing to start.

The end-to-end behaviour — that a build staged as `ccj-next` really does let the old process install
and hand over, and that the process which comes back is on the same conversation — is verified by
running it, which is the only way to test an exit that restarts a process. Both front ends were run
that way: `--repl` printed the same session id before and after the restart, and the web server came
back reporting the same session with its full history.
