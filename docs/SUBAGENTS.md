# Sub-agents

A main agent that hands work to helpers, reads their results, and shows the user none of it.

## Why

The expensive thing in a long session is not time, it is context. An agent that reads thirty files to
find the three that matter has spent thirty files' worth of tokens in the conversation, and every
later request re-sends them — which is why sessions end up needing `/compact` at all. A sub-agent
reads the thirty and reports the three. The main conversation grows by a conclusion instead of by a
search.

That is the whole value, and it is worth being precise about because the other one people expect —
speed — is mostly not there. Real task graphs are deep chains (statement → solution → data → verify),
and a chain does not parallelise. Parallelism pays at the two ends: the exploration before the work,
and the verification after it. Quoting a ~40% saving would be optimistic; the context saving is the
one that shows up every turn.

## The shape

```
main agent
  ├── task(...) ──> sub-agent ──> returns a report      (read-only, parallel-safe)
  ├── task(...) ──> sub-agent ──> returns a report
  └── decides what to keep, deletes the rest
```

A sub-agent runs a real `AgentLoop` against a `MemorySession`: same loop, same tools, same approval
machinery — but no file, no id in the sidebar, and no events on the wire. The user sees the main
agent's summary and nothing else. That invisibility is the point, not a side effect.

## Three roles, three sets of tools

The role decides the tools, and the tools decide what can go wrong.

| Role | Tools | Why |
|---|---|---|
| `explore` | `read`, `glob`, `grep` | Nothing it does can change anything, so nothing it does needs asking. The default. |
| `verify` | `read`, `glob`, `grep` | A verifier that can fix what it finds is not verifying. It reports; the main agent acts. |
| `build` | all of the above plus `write`, `edit` | The only role that changes files. It works in the session's own directory and asks before it writes, like the main agent. |

`explore` and `verify` take the same tools on purpose — they differ in what the prompt asks for, not
in what they may touch. Splitting them by capability would be inventing a constraint the work does
not have.

No role gets `task`. Recursion is refused by construction rather than by a depth counter: a
sub-agent's registry simply does not contain the tool, so it cannot ask for one, and there is no
depth limit to get wrong.

## Where a sub-agent writes

In the session's own working directory, like the main agent — with no staging area and no promote step.

An earlier version staged a writing sub-agent's files in `<cwd>/.ccj-work/rN/` for the main agent to
promote. The reasoning was sound and the result was worse. Staging was only defensible while
sub-agents ran with `Approver.ALWAYS` — it kept an unapprovable writer out of the project — but it
bought that at a price paid on every run:

- the main agent had to promote each file by hand, in the same turn;
- a promotion that did not happen in that turn was discarded at the end of it, with a notice the user
  had to be watching for;
- the report had to explain which paths were finished work and which were by-products, and a main
  agent that read that list wrong lost the file;
- and none of it was needed once the approval travels to the user.

So a sub-agent asks. Its request goes through the conversation that started it — it runs on that
turn's thread, so the prompt appears in the transcript the user is already watching, attributed to
that conversation — and the same refusal stops the same write. Aborting the turn answers any pending
request. The sub-agent is invisible in what it reads; it is not invisible in what it does.

## Reports, not transcripts

A sub-agent returns a structured report, not its conversation:

```
STATUS: done | blocked | failed
SUMMARY: one paragraph, what was found or produced
FILES:
  path        final | disposable    one line on what it is
FINDINGS:   (verify/explore) the actual answer, with paths and line numbers
```

The `FILES` block is what lets the main agent decide what to keep **without reading the files**. If it
had to open each one to judge it, the context saving this feature exists for would be spent on the
judgement instead.

An oversized report is truncated with a marker, the way tool output already is. A verbose sub-agent
must not be able to blow the budget of the conversation it was meant to protect.

## Rules the implementation enforces

Each of these is a claim the code holds. Most of them began as a measured failure rather than a
design intention, which is why they are written down here.

- **Approval reaches the user, and it is the guard.** A `build` sub-agent runs with the
  conversation's own approver, so a write or a shell command is put to the same person the main agent
  asks — in the transcript they are already watching, because the sub-agent runs on that turn's
  thread — and a refusal stops the write. The sub-agent is invisible in what it *reads*, never in what
  it *does*. The staging directory that once made an unapprovable writer tolerable is gone (see
  above): the approval is what replaced it.
- **One writer at a time.** Two writing runs cannot overlap. Deciding whether two tasks will touch the
  same file needs to understand what they mean, which this cannot do — so it does not try. Readers are
  unaffected and still run in parallel.
- **The deadline covers the queue.** A writer waiting for the lock is already consuming the caller's
  patience, so the wait is a timed `tryLock` bounded by the same deadline rather than starting the
  clock afterwards. Waiting on a lock is not a state `abort()` can reach — the worker thread is not
  set and no model call is in flight — which is why the wait ends on the deadline rather than on an
  interrupt.
- **Cancellation propagates, and is checked at the boundaries.** Aborting the main turn aborts the
  sub-agents it started, through the same flag the tools already honour. A 100 ms poller alone is a
  race against a fast provider, so the flag is also read before the work starts and again after it
  finishes.
- **A deadline, always.** A sub-agent that runs away cannot hold the main conversation open. On
  expiry it is cancelled and reports what it had.
- **No `task` in a sub-registry.** Recursion is refused by construction rather than by a depth
  counter: the tool is simply absent, so a sub-agent cannot ask for one and there is no limit to get
  wrong.
- **Token accounting stays honest.** A sub-agent's usage is added to the conversation that started it,
  because that is who paid. Hiding the reading is the feature; hiding the cost would be a token count
  that quietly omits half of what was spent.

## What is deliberately not here

- **Sub-agents talking to each other.** They report to the main agent, which is the only thing that
  sees the whole picture. A mesh of agents that can address each other is a different design with a
  different failure mode.
- **A depth limit.** Not needed once `task` is absent from every sub-registry, and a number that
  cannot be reached is a number nobody maintains.
- **Parallel writers.** See above: it is not a matter of being careful, it is that the failure is
  invisible.
