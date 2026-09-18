# MCP servers

A Model Context Protocol server is a program that offers tools over a small JSON-RPC conversation. ccj
can start one and give the model its tools, which is how a capability this program does not have —
a database, a browser, an internal API — becomes something the agent can call.

## Configuring one

`<home>/mcp.json`, beside the approvals file and the config file:

```json
{
  "servers": [
    {"name": "fs", "command": "npx", "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"]},
    {"name": "notes", "command": "python3", "args": ["-m", "my_notes_server"],
     "env": {"NOTES_HOME": "/home/you/notes"}}
  ]
}
```

| Field | Meaning |
|---|---|
| `name` | what the server is called, and the middle of every tool name it contributes. Letters, digits, `-` and `_`; `__` is refused because it is the separator |
| `command` | the program to run |
| `args` | its arguments |
| `env` | extra environment variables, on top of this process's |

**In the application home, never in the project.** A server is a command that gets executed and a set
of tools the model may then call, so it is not a decision a repository should be able to make on
somebody's behalf by being cloned.

## How its tools appear

Every tool is named `mcp__<server>__<tool>` — `mcp__fs__read_file`. Three parts, all load-bearing: the
model can see where a capability came from, the transcript does not pretend a remote tool is a
built-in, and an approval rule can name one server's tools (`mcp__fs__*`) or one of them exactly
(`mcp__fs__read_file`).

**Every call goes through the same approver as `bash`.** A server's own idea of what it may do is not
this program's idea, so a tool it offers is treated as a program that does things: it asks, the prompt
names the tool and its arguments, and a rule can answer instead. A server cannot run unasked.

## What this client does and does not implement

Implements, over the server's standard input and output:

- `initialize` (with a retry when a server only speaks the older protocol revision),
  `notifications/initialized`, `tools/list`, `tools/call`.
- One JSON object per line, answered by id, with a reader thread per server and a deadline per request.
- Server stderr is drained and its tail is kept for error messages: a server that logs a lot would
  otherwise block on a full pipe, and the symptom is a hang with nothing to read.
- A server is started the first time one of its tools is called, and kept for the run. Discovery starts
  each server once, asks what it has, and closes it again, so an agent that never uses a tool pays
  nothing for it.

Not implemented, deliberately:

- **HTTP/SSE transport.** A local process needs no ports, tokens or network, and half-supporting a
  second transport is worse than saying plainly which one this is.
- **Resources, prompts, sampling, notifications from the server.** This client is a tool client: the
  loop gives the model tools, and everything else in the protocol would need somewhere to go that this
  agent does not have.
- **OAuth and the other authentication flows.** A server that needs a token gets it from `env`.

## What was verified

`McpClientTest` runs a real server process out of the test classpath and checks discovery, a call, a
tool that reports failure, a server one protocol revision behind, a server that logs until its pipe
fills, one that never answers, one that is gone, and one that cannot start at all. Then, live: a
server configured in `mcp.json`, its tool discovered at startup, called by the model through an
approval prompt, and allowed by a rule that names the server.
