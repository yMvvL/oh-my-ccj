# Security

`ccj` runs shell commands on your machine, and the web UI is a shell prompt behind HTTP. That is the
design, not a defect — a coding agent that cannot run commands cannot do the job. What follows is the
model, so you can decide whether it fits your situation.

## The security model in one paragraph

A process you started, listening on addresses you named, reachable by devices you control, running as
your user with your environment. `bash` is **not** sandboxed: it is `/bin/bash -lc` with your PATH,
your `$HOME` and your credentials, and it can read and write anything you can. Approval is the only
guard before it acts, and `--yolo` (or auto-approve) removes that guard by design. Treat a running ccj
exactly as you would treat an open terminal.

## What is defended

- **Never a wildcard bind.** The server binds loopback plus, when the machine has one, its tailnet
  address — never `0.0.0.0`. A wildcard would put the port on every network the machine is on.
- **A token on every non-loopback address.** 32 random bytes in `~/.oh-my-ccj/web-token`, mode `0600`,
  generated on first need. Loopback needs none: a password to use your own command line protects
  nothing.
- **Loopback `Host` checking**, so a DNS name that resolves to `127.0.0.1` (a rebinding attack) is not
  treated as local, and dotted names are never resolved.
- **Cross-origin state-changing requests are refused.** A browser page on another site shares your
  machine's loopback address and sets a loopback `Host`, so the two checks above cannot tell it apart
  from the real UI. This one can, and it was a real hole: a POST to `/api/auto-approve` carrying
  `Origin: https://evil.example` was served and did switch auto-approval on — after which the agent
  stops asking before it runs commands.
- **A sub-agent asks before it changes anything.** It reads in a conversation you never see, but its
  writes and shell commands go through the same approver as the main agent's, so they arrive as a
  prompt in the transcript you are already watching, and a refusal stops the write. It cannot delegate
  further either: the `task` tool is absent from its registry. Note what this does *not* claim — a
  sub-agent is not confined to a directory. There is no path sandbox; approval is the guard, exactly
  as it is for the agent that sent it.
- **The page renders model output as text.** Markdown is built from DOM nodes, raw HTML is shown as
  text, link schemes are filtered, and images are not fetched.

## What is not defended

- **The agent can do anything you can**, including `rm -rf`, `git push`, and reading your SSH keys.
  Approval is the guard; read the diff before you approve.
- **`--yolo` removes the guard entirely.** That is what it is for. Do not use it in a session that is
  also reachable over a network.
- **A stolen token or URL is a shell.** The startup URL carries `?token=…`; treat it as a password.
- **Nothing is audited or rate-limited.** There are no accounts, no logs beyond the session files, and
  no way to revoke one device without rotating the token file.
- **Not for exposure to the public internet.** A tailnet is a set of devices you administer; a VPS, a
  forwarded port or a café network is not, and the token does not change that. It filters callers; it
  does not make the service safe to publish.

## Supported versions

The latest release, on Linux and macOS. Windows is not supported (`/bin/bash`); WSL is.

## Reporting a vulnerability

**Do not open a public issue.** Use GitHub's private reporting on the repository page (*Security* →
*Report a vulnerability*), or contact the maintainer directly.

Include what you did, what happened, and what you expected. A reproduction is worth more than a
description — the cross-origin hole above was found by sending one header and watching the setting
change, which is what made it worth fixing rather than debating.

You will get an acknowledgement within a few days. This is a personal project with one maintainer, so
please allow time; a fix may take longer than the reply.

## If you think you have been compromised

1. Rotate the token: delete `~/.oh-my-ccj/web-token` and restart — a new one is generated.
2. Rotate any credential the agent could have read: `~/.oh-my-ccj/config.json`, your shell environment,
   any key in a file the session touched.
3. Read the session files in `~/.oh-my-ccj/sessions/`. Every command the agent ran is recorded there
   verbatim, which is the fastest way to see what happened.
