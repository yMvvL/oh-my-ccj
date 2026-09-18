# Changelog

What changed, newest first, in the words a reader would use — not a dump of commit subjects. The
reasoning for each entry is in the commit that made it and in the document the entry links to; this
file is the index.

There are no version numbers yet: [ROADMAP](ROADMAP.md) 3.1 is where tags, notes and a JDK statement
land. Until then an entry is a date and what it did, and `0.1.0` is what the launcher prints.

## 2026-09-18 — pictures

**A picture arrives as a description.** Photograph a screenshot, send it from the phone, and the
*description* — not the image — joins the conversation. The main model never receives an image, which
is why no wire format, renderer or compaction step grew an image branch, and why a session file stays
readable text with a picture beside it. `POST /api/attachment`, the composer's picture button, and a
vision model configured on its own: its own endpoint, key, model and completion budget, in Settings,
the config file's `vision` block, or `--vision-*`/`CCJ_VISION_*`. Pictures live in
`<sessions>/<id>.attachments/` and go when their session goes. Designed in [VISION.md](VISION.md).

**The description budget was wrong the first time, and the failure was silent.** A reasoning model
spends the completion budget before it writes a word, so too small a budget does not shorten a
description, it deletes it: a phone screenshot at `max_tokens: 1500` returned HTTP 200 with
`finish_reason: length`, all 1500 tokens spent thinking, and empty `content`. Measured again at 4096
(2882 used) and 8192 (1084 used); the default is now 8192, the number is configurable, and the refusal
says which setting fixes it instead of printing a wall of JSON.

**A page on the tailnet may change state, and it could not.** The cross-origin guard accepted only
loopback origins, so *every* state-changing request from a phone — a message, an abort, a settings
save, an approval, a picture — was refused as another site. The rule is now "a loopback address, or
the host the request was aimed at", which is still a guard: another site's origin is its own host, and
a page reaching the server under a rebinding name has no token. See [SECURITY.md](../SECURITY.md).

**The vision model is configurable from Settings**, with the two things a form cannot say with an
empty field — forget the saved key, turn pictures off — sent as the flags they are.

Also: `edit` and `write` re-read before they write and refuse a file that changed while the approval
waited, both write through a temp file and an atomic rename, the SSE keep-alive is a real event the
page can hear, and an error body is read bounded rather than truncated after the fact.

**A message sent while a turn is running waits instead of being refused.** The old answer was `409`,
which disabled the composer until the turn ended — so a thinking pause was a dead stop and whatever
you thought of while waiting was gone by the time it finished. Now the message queues, the composer
says how many are waiting, and each one runs as a turn of its own. The queue is per conversation and
bounded at sixteen; abort stops the turn **and drops what was behind it**, because stopping is what
that button is for, and the count is published so a dropped message is visible rather than silent.

**Approval has four answers, and one of them can be written down.** Deny, allow once, allow for this
session, allow from now on. The last writes a rule into `<home>/approvals.json` keyed by project, and
that rule answers the next identical request without a prompt. This removes the friction that made
people run `--yolo`: "stop asking me about this" used to mean "stop asking me about anything", because
the only other answer the gate had was a session-wide switch. Rules match a command exactly, or widen
it by one trailing ` *`, and a widened rule never covers a command containing `;`, `&&`, `|`, a
substitution or a redirect. Deny wins over everything, a path rule cannot leave the project, an
unreadable rules file asks instead of allowing, and every automatic answer is in the transcript as
`allowed by rule — …`. See [SECURITY.md](../SECURITY.md).

**A check runs itself after an edit, and the conventions became a document.** `checks` in the config
file names commands that run inside the `edit`/`write` call that matches their glob, so the compiler's
verdict arrives with the change rather than three turns later — see the README row and
[SECURITY.md](../SECURITY.md) for the one place this deliberately runs something without asking. The
process plumbing `bash` already had (closed stdin, bounded capture, deadline, process-tree kill) moved
to `tool/ProcessRunner` so both callers share it, with `BashTool`'s eleven tests as the net. Two things
came out of trying it on this repository rather than from writing it: a check's glob decides *when* it
runs and not what the command looks at, so `mvn compile` reported `exit 0` for a broken file at the
root it never compiled — the passing line now says `exit 0` instead of "clean", because "clean" is a
claim the command did not make — and a pattern written `**/*.java` has to match a file at the project
root, which `PathMatcher` alone does not do. Alongside:
[docs/CONVENTIONS.md](CONVENTIONS.md) gained the naming table, the comment rules, error shape, the
front end's own rules and spelling; [ROADMAP](ROADMAP.md) gained Phase 2.5, the experience work, with
2.5.1 done; and the spelling pass that came out of writing the rules down is enforced by
`core/ConventionsTest`.

## 2026-09-14 — the second front end gets serious

**`/compact`.** The older turns are replaced by a summary the model writes, as the next *generation*
of the same session (`<id>.g1.jsonl` beside the untouched file), so the conversation it replaced is
still on disk and the summary names it. Refused when the summary would not come out smaller than what
it replaces — measured on a real session at 4239 → 4236 tokens. See [COMPACT.md](COMPACT.md).

**Tailnet reach.** The server binds named addresses — loopback plus this machine's tailnet address —
never a wildcard, with a generated token for everything that is not loopback. See
[WEBUI.md](WEBUI.md).

**Project rules.** A `CCJ.md` in the working directory leads the prompt, read from that directory only,
so "which prompt is this run using" is answerable by looking at the folder you started in.

**Several conversations at once**, each with its own busy flag, its own working directory from the
moment the turn started, and events that name their session so a background turn's prose cannot land
in the transcript you are reading.

## 2026-09-12 — the page becomes the product

The web UI becomes the default front end and can configure the model at runtime: a layered
provider → model → effort picker, custom providers defined in the form, per-provider endpoint and key
that travel together (switching provider loads that provider's pair rather than passing the old one
on), themes, desktop folder picker, workspaces with a VS Code-style sidebar, session deletion, resume
fixes, and per-session usage with a cache hit rate. Workspaces keep their own directories and their own
history; switching moves both.

## 2026-09-11 — the first working agent

A coding agent runtime in plain Java 21: the loop, one model turn at a time with every requested tool
run and fed back, the tool set (`read`, `write`, `edit`, `bash`, `glob`, `grep`, `restart`), approval
before anything writes or executes, sessions as append-only JSONL under `~/.oh-my-ccj/sessions/`,
both wire protocols (OpenAI-shaped and Anthropic) with streaming and retry, an offline playground, a
launcher that survives being symlinked onto `PATH`, and `--demo`: no model and no key, so the loop,
the tools and the approval prompts can be tried before anything is configured.

## What is not in it yet

MCP, plugins, a sandbox, multi-user accounts — and the experience work, which is where the next
changes go: [ROADMAP](ROADMAP.md) Phase 2.5.
