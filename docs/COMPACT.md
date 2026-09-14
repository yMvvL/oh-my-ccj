# Compaction plan

`/compact` — the user asks for it, the model summarises the older part of a conversation, and the
session continues from the summary instead of from raw history.

## Why it exists next to ContextBudget

They answer the same question and must not be confused:

| | `ContextBudget` | `/compact` |
|---|---|---|
| Who triggers | automatic, silent | the user |
| What happens to old content | **dropped** (elided, or whole exchanges removed) | **summarised** by the model |
| Does the model know what it lost | no | yes — the summary says so |
| Cost | free | one request |
| Changes the file | no, only this request's projection | yes, permanently |

`ContextBudget` projects one request and leaves the file alone — "The session keeps everything; this
only decides what one request carries". Compaction is the first thing in this project that genuinely
rewrites conversation content, which is why it has to be explicit, reversible and honest.

## Reversible by construction: generational files

```
sessions/<id>.jsonl        generation 0 — the original, never rewritten
sessions/<id>.g1.jsonl     generation 1 — summary + the last few exchanges, verbatim
sessions/<id>.g2.jsonl     generation 2 — ...
```

- The session **id does not change**, so nothing that keys off an id moves: the sidebar, `--resume`,
  `openForDisplay`, `ResumePoint`, the workspace registry.
- `list()` and `open()` read the **newest** generation.
- Older generations stay on disk untouched, so what was compacted away is still there — that is a
  property of the layout, not a naming convention somebody has to trust.
- A compaction writes a *new* file rather than truncating one, so the append-only durability story
  survives: a compaction killed halfway leaves the old generation complete.

## The prompt is recoverable

The summary names the file holding the full history. `read` is read-only and needs no approval, so a
model that needs the exact error text or a file path it lost can go and read it. That turns
compaction from lossy into lossy-but-recoverable, and it is only possible because this project hands
its session files to the model as ordinary files.

## When it is refused

Compaction is a trade, and the trade is not automatically favourable: a summary carries a preamble,
names the file it came from, and is written to be complete — so a conversation whose early exchanges
were mostly tool-call *plumbing* summarises into something longer than the text it removed. Measured on
a real session of six short exchanges: 4239 → 4236 estimated tokens, i.e. nothing freed, while the
summary itself was 2225 characters against the 809 characters of actual content it replaced.

So the result is measured before it is written, against the messages the cut would consume rather than
against the whole conversation, and a summary that is not smaller is **refused** rather than saved:

```
POST /api/compact  ->  409  "nothing to gain: the summary is 70 tokens against the 39 it would
                             replace ... the conversation is not long enough for a summary to save
                             anything yet"
```

The same session, once it had grown to six substantial exchanges (a 53-line file read, tests written,
edge cases found and fixed), compacted at **30088 → 27885 tokens with 61% of the replaced part saved** —
and the summary kept the exact error strings, the line numbers, and the `take(-5)` bug the model had
found several turns earlier.

## What the summary must contain

Structure, not narrative — the point is to preserve the facts a model would otherwise have to
rediscover:

1. the original goal and any constraint stated up front
2. decisions made and why
3. every file touched, and what changed in it
4. what was verified, and how; what was tried and did not work
5. what is still open
6. verbatim technical facts: error messages, paths, commands, API shapes

## Keeping the model honest about it

`Message.Summary` is a distinct message kind, not a `User` message pretending to be history. It maps
onto the wire as one user turn with an explicit marker, so the model reads "this is a summary of
earlier work" rather than mistaking it for something it said. `Message` is sealed, so every place
that must handle it is a compile error until it does.

## What is kept verbatim

The most recent **5 user exchanges**, whole: the user turn, the assistant turns, and every
tool_result. Kept at exchange granularity because a `tool_result` without the assistant turn that
asked for it is a request both wire formats reject.

## Accounting

A compaction is a real request and costs real tokens, but it is not a model turn of the
conversation. It gets its own counter so the usage panel keeps meaning what it says.

## What you see when it happens

A compaction is visible in three places, and each answers a different question:

| Where | What it says |
|---|---|
| The transcript | A notice: how many messages became a summary, how many were kept verbatim, the token change, and the path of the file holding the full conversation |
| Below that notice | A **collapsed card** — `⤓ Compacted conversation · 13 messages summarised`. It opens to the summary itself, because "did it keep what mattered" is a question only the text can answer |
| The usage panel | A `compactions` row, shown only once there has been one. Its own row rather than folded into `steps`: it cost real tokens and it is not a turn |

The transcript is rebuilt from the server rather than patched: the conversation really has changed, and
guessing at the splice is how a page ends up showing history that never existed. Reopening the session
later — or reloading the page — shows the same card, because the summary is part of the conversation now
and is replayed like anything else.

## Where it can be triggered

- REPL: `/compact`
- Browser: a button, not a `/`-prefix intercepted in the composer — stealing a leading slash would
  break sending a message that legitimately begins with one.

Refused while a turn is running in that conversation: two writers on one transcript is the thing the
per-conversation turn flag exists to prevent.
