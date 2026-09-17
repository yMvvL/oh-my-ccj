# Web UI

`ccj` — no arguments — runs the same agent loop behind a local HTTP server with a single-page UI, and
that page is where the model gets configured (`--repl` gives the terminal front end instead). No
framework, no build step, no new dependency: the server is `com.sun.net.httpserver` and the page is
three files packed into the jar under `/web/`.

It exists because the loop is not terminal-shaped: streaming text, tool cards and approvals are all
easier to look at as a page. Design constraint that shaped everything below — **the browser is an
untrusted client**: it gets no shell, no filesystem, and every side effect still goes through
`Approver`.

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/` | the page (`index.html`) |
| `GET` | `/app.js`, `/style.css` | assets, served from the jar |
| `GET` | `/api/status` | provider, model, base URL, cwd (and whether `-C` overrode it), session, tools, `busy` for the session on screen, and `running` — the ids of the other sessions working right now |
| `GET` | `/api/events` | SSE stream of everything the loop reports |
| `POST` | `/api/message` | `{"text": "..."}` — start a turn in the session on screen; `409` when *that* session is already running (other conversations are unaffected) |
| `POST` | `/api/abort` | stop the running turn at the next safe point; add `?id=<session>` to stop a turn in a conversation you are not looking at |
| `POST` | `/api/compact` | compact the session on screen: the older turns become a summary, written to the next generation file. `409` while a turn is running or when the summary would not be smaller than what it replaces |
| `POST` | `/api/attachment` | one picture as the raw body, `?name=<file name>` — the vision model describes it and the description starts a turn; `400` when the bytes are not a PNG/JPEG/WebP/GIF, `413` over 8 MB, `409` while that conversation is running or when no vision model is configured |
| `POST` | `/api/approval` | `{"id": "...", "allow": true, "remember": false}` — answer a pending approval |
| `POST` | `/api/auto-approve` | `{"enabled": true}` — flip the whole session to auto-approve |
| `POST` | `/api/session` | `{"action": "new"}` or `{"action": "resume", "id": "..."}` |
| `GET` | `/api/sessions` | session summaries, newest first; each row reports `running` |
| `DELETE` | `/api/sessions` | delete every session in the active workspace |
| `DELETE` | `/api/session?id=...` | delete one session |
| `POST` | `/api/workspaces/browse` | open the desktop's folder chooser and return the path |
| `GET` | `/api/history` | the current session replayed as render events (see below) |
| `GET` | `/api/workspaces` | every workspace, with the active one, its path and its session count |
| `POST` | `/api/workspaces` | register a workspace: `{"path": "..."}`, or `{"name": "...", "path": "..."}` to override the name (the directory is created if needed) |
| `POST` | `/api/workspace` | `{"name": "..."}` — switch to a workspace |
| `DELETE` | `/api/workspace?name=...` | forget a workspace; its session files stay on disk |
| `GET` | `/api/models` | the provider catalogue: providers, their protocol and endpoint, their models |
| `GET`/`POST` | `/api/providers` | list or define a provider (`{name, kind, baseUrl, apiKeyEnv, models}`) |
| `POST` | `/api/models` | remember a model for a provider (`{provider, model}`), built-ins included |
| `DELETE` | `/api/models?provider=&model=` | forget a model (the one in use included — see below) |
| `DELETE` | `/api/providers?name=...` | remove a provider: a definition is deleted, a built-in leaves the list |
| `PUT` | `/api/providers` | `{"name": "..."}` — add a built-in back to the list |
| `GET` | `/api/config` | what the settings form needs: current values, whether a key exists, where it is stored |
| `POST` | `/api/config` | save provider/model/key settings and switch to them immediately |
| `POST` | `/api/config/test` | send one tiny request with the posted settings **without saving** |

`GET /api/config` also reports `language` and `languages` (see *Thinking language* below), and
`POST /api/config` accepts `language` among its fields.

### Thinking language

`config.json` keeps a `language` alongside `reasoning`, and the settings form offers it: `auto` (the
default, which adds nothing to the prompt) or one of the languages in `Prompts`. The setting is a
*sentence appended to the system prompt*, not a protocol parameter — nothing on either wire can ask for
it — so what it can promise is bounded by the model:

- The **answer** language follows reliably, including when the user writes in another language.
- The **reasoning** language follows on some models and not others. Reasoning models pick the language
  of their thinking from the whole context and tend to stay in the one they started in; measured here
  through an OpenAI-shaped relay, a `deepseek-v4.1` thinking stream stayed in English with an English
  prompt, with a Chinese system prompt that says 必须用中文思考, with a Chinese user message, and with
  all three at once. The same sentences move other models. The settings hint is worded for that, rather
  than claiming a guarantee the wire cannot give.

The sentence is written twice on purpose — once in English, where the agent's other rules live and
where a model reads it as an instruction, and once in the target language, because the text in front of
a model is the stronger cue for the text it writes next. `GET /api/config` returns `language` and the
`languages` list the form renders (`{value, label}`, the label being the language's own name), and a
`POST` carries `language` back; `auto` normalises to "no sentence".

### Workspaces

A workspace is a working directory plus the sessions that belong to it. Switching one changes where
tools resolve relative paths **and** which conversation history the page shows — the two are the same
decision, because a session only makes sense next to the files it was talking about.

```json
{"active": "oh-my-ccj",
 "workspaces": [
   {"name": "oh-my-ccj", "path": "/home/you/oh-my-ccj", "sessions": 12, "active": true},
   {"name": "api", "path": "/home/you/api", "sessions": 3, "active": false}
 ]}
```

- The registry is `<home>/workspaces.json`. The workspace `ccj` started in is seeded from the starting
  directory and is the active one; it keeps using the top-level `<home>/sessions` directory, so
  sessions from before workspaces existed stay where they were.
- A workspace is named after its folder: `POST /api/workspaces` takes a path and derives the name
  from the last segment. If that name is taken — the same project twice, or two directories that
  share a last segment — the next free suffix is used (`api`, `api-2`, …) rather than refusing, since
  the user picked a folder and not a name. A folder whose last segment could not be one path segment —
  a separator in it, or a leading dash, which would read as a flag — comes back as `400` with the rule
  that was broken.
- A directory that is already a workspace is refused, naming the entry that owns it: two names for
  one directory would give one working directory two competing histories.
- Workspaces added later store their sessions under `<home>/workspaces/<name>/sessions/`, and the
  directory is created if it does not exist.
- Model settings (`config.json`) are **not** per workspace: a key and a model are a property of the
  account, not of the folder. Everything else — working directory, sessions, usage totals — is.
- Switching starts a **new** session in the target workspace (an empty one leaves no file); the
  sessions list then shows that workspace's history to resume from.
- `DELETE` only forgets the registry entry. Deleting conversation files because a folder was removed
  from a list would be a surprising thing for a tool to do.
- `ccj --workspace <name>` does the same thing for the terminal front end.

## Deleting sessions

Deleting is the cleanup path for a pile of experiment sessions, so it is explicit and reversible only
in the sense that a deleted file is gone:

- `DELETE /api/session?id=<id>` removes one file. Ids are validated against the same pattern used for
  file names, so `../` can never reach the filesystem.
- `DELETE /api/sessions` clears the **active workspace**, and only session files inside it — other
  files in that directory are left alone.
- Deleting the active session starts a fresh one and publishes a `status` event, which is how the
  page learns it should clear and start over. The response is the refreshed session list.
- Empty sessions are not a problem to clean up: they were never written to disk in the first place.

## Adding a workspace

**Add workspace** is the chooser itself, not a form: one click opens the desktop's dialog, and the
folder that comes back is registered under its own name — no name field, because the folder already
has one. The form still exists behind the same trigger for machines where no chooser can run: there
its **Choose folder…** button opens the chooser and adds what it returns, and its path field is the
fallback that always works, with **Browse…** filling the field rather than adding it.

A browser cannot supply an absolute path — the File System Access API returns a directory handle with
a name and nothing else, by design — so both buttons ask the **server**, which is running on the
machine the user is sitting at, to open the desktop's own chooser. Preference order is `zenity`, then
`kdialog` (both are native dialogs needing no toolkit), then Swing's `JFileChooser`. A headless
machine, or one with none of those, gets `400` with a message telling the user to type the path — the
page then opens the form so the field is visible rather than hidden behind the refusal. Only one
chooser can be open at a time, and one that is never answered is dismissed after two minutes and
reported as `cancelled` (which is not an error, and not an add) — a modal dialog must never be able to
wedge the server.

## Theme

Three states — **System**, **Light**, **Dark** — cycled from one control in the header, with the
choice kept in `localStorage` and System following `prefers-color-scheme` live. The theme variable is
set on `<html>` by an inline script in `<head>` before the stylesheet paints, so a reload never shows
the wrong theme first, and a one-line script at the top of `<body>` repeats the answer as the
attribute the token sheet reads. A manual choice has to be able to override the operating system,
which is why the OS is never a media query here — only a value the scripts resolve.

The look is the DeepSeek Harness's, and it is copied rather than imitated: `theme.css` is the
harness's own token sheets (`base.css`, `design-platform.css`, `gradient-shadow-text.css`,
`scrollbar.css` from `@deepseek-ai/dsh-client-ui-theme`) verbatim, and `style.css` is the component
sheet that consumes them. That gives one palette in two layers — a static scale and the semantic
aliases over it — with the dark theme as the harness's own switch (`body[data-ds-dark-theme]`), so a
dark-mode fix upstream is a re-copy rather than a re-derivation.

`style.css` defines no colours: every surface, border, text tone and shadow names a `--dsw-*` value.
What stays local is the geometry the harness writes as literals per component — the radius ladder
(cards 12px, inputs and rows 8px, the composer and a user bubble 22px, dialogs 24px, pills round),
the spacing scale, and the 748px chat column. `shiki.css` is the one sheet left out: the transcript
renders plain code, with no highlighter to name its tokens.

## Wallpapers

The page can rotate a background picture behind itself. The pictures are files in one directory —
`~/Pictures/ccj-backgrounds` unless `--wallpapers <dir>` or `CCJ_WALLPAPERS` says otherwise — and
the server offers them read-only through two endpoints: `GET /api/wallpapers` lists the names in the
order a person reads a numbered set (`1.png`, `2.png`, … `10.jpg`), and `GET /wallpaper/<name>`
streams one back.

A name is input, so it is treated like one: no separators, no absolute paths, nothing that resolves
out of the directory through a link, and nothing whose own bytes are not a raster image — the bytes
decide the media type, so a file named `.jpg` that really holds a PNG is served as a PNG. SVG is
left out on purpose: it is a document that can carry script, and a background is not worth that
hole.

The page keeps the switch and the position in `localStorage`, rotates every five minutes, and
fetches the next picture while the current one is on screen so a rotation is a cut rather than a
blank frame. It starts on the first time a browser sees a directory with pictures in it — the
control is one click away in the header, and `⇧`-click steps to the next picture immediately. A server with no pictures reports an empty list, and the control hides itself instead
of offering a button that does nothing.

While a wallpaper is on, the surfaces above it turn semi-transparent — the page, the sidebar, the
details pane and the composer card — and the three panes get a `backdrop-filter` blur. Tool output,
code cards, menus and dialogs stay opaque: those are the things that get read line by line, and a
photograph behind a stack trace is decoration fighting content.

## On a phone, over a tailnet

The page is one console, meant to be reachable from a phone as well as from the machine it runs on,
and that shapes the bind, the gate and the layout.

**The bind.** By default the server is bound to *named addresses*, one server each, sharing the same
hub and the same conversation: loopback for the machine the user is sitting at, and — when this machine
is on a tailnet — its tailnet address for their phone.

```
$ ccj
oh-my-ccj 0.1.0 — web UI: http://127.0.0.1:6767/
                          also on http://100.72.92.41:6767/?token=…
```

`--host tailscale` means the tailnet address *alone* (for a machine whose loopback nobody wants), and
`--host <addr>` still means exactly one address. A wildcard is never used: binding `0.0.0.0` would put
the port on the café wifi, the office LAN and the docker bridge, and the point of naming addresses is
that the set of devices able to reach the port is a set the user chose. The tailnet address is found
through the `tailscale0` interface, or by asking `tailscale ip -4` where the tailnet rides a
differently named one; anything outside `100.64.0.0/10` is refused, and a machine without a tailnet is
served on loopback alone rather than bound to something else.

**The gate.** Two ways in, and they answer different questions:

| Request | Answer |
|---|---|
| arrived on loopback **and** names a loopback `Host` | served — this is the machine itself, and a secret to type in order to use one's own command line is a password nobody asked for |
| carries the token (query, `Authorization: Bearer`, or the cookie) | served, from any address |
| anything else | `401` when the server has a token; `403` when it does not and the `Host` is not a loopback name |

The `Host` half is not decoration: a tokenless server is reachable from any page the user visits, and
DNS rebinding is what turns that into a page that can *read* the answers, so a request naming another
host is refused. The token is what makes a network address safe; loopback is not a network. A caller
who has proved the token is asked nothing else — the secret is the whole price of reaching this server
from somewhere else.

The token comes from `--web-token`, or `CCJ_WEB_TOKEN`, or — generated on first use — `web-token` in
the application home, mode `0600`. The file is what makes "just run `ccj`" work without a secret on the
command line (which `ps` and shell history read back) and without a new token every restart (which
would log the phone out each time). It is deliberately **not** a key in `config.json`: a home directory
gets backed up far more casually than a shell profile. `HttpApi.urls()` marks each address with the
token only where one is needed — loopback is printed bare, so opening a browser on this machine never
puts a secret in an address bar.

Opening the tailnet URL once puts the token in the HttpOnly cookie, after which the bookmark needs
nothing.

**Narrowing it to one device** is Tailscale's job rather than the page's: the default tailnet rule
already lets every device reach every other one, so the token is what separates "on my tailnet" from
"mine". An access rule restricting `dst: <this-machine>:6767` to one `src` device narrows it further
(Tailscale's own ACL syntax, applied in the admin console). `tailscale serve` in front of a loopback
bind is the alternative when HTTPS with a real certificate is wanted — it still needs a token, for the
reason above.

**The layout** below 720px, because a phone is not a small desktop window:

| Wide | Phone |
|---|---|
| sidebar and details are columns beside the transcript | both are sheets **over** it, one at a time, opened from the header, closed by tapping the scrim, Escape, or by choosing something in them — because picking a session is what the sheet was opened for |
| rows revealed by hover (session and workspace actions) | revealed always, since there is no hover to reveal them |
| 32px tree rows, 28px `.btn.sm`, 12px form fields | 42px rows, larger buttons, and 16px fields — the last one because Safari zooms the page when a focused field is under 16px |
| `show all` is a bare 12px word | padded into a real target |
| the composer's own buttons carry their words (`Photo`, `Compact`) | the words go and the glyphs stay: the picker, the picture, compact and send do not fit one 390px row with their labels attached. Each of those buttons has an `aria-label`, so the name a screen reader reads is not the label that was hidden |
| composer padding is fixed | `env(safe-area-inset-bottom)`, so the home bar does not sit on the send button |

The scrim is one element below both sheets and below every dialog, so a dialog opened from a sheet
still lands on top. The sheets do not survive a resize back to a wide window: they are a phone's
layout, and the columns keep their own state.

## Pictures

The composer's **Photo** button sends one picture, and what joins the conversation is a *description*
of it written by a separate vision model — the main model never receives an image. The full design,
including why it is shaped that way, is in [VISION.md](VISION.md); this is what the page and the
endpoint do.

```js
// The file itself is the request body; the name travels in the query, because only the bytes can
// say what the file is.
fetch('/api/attachment?name=' + encodeURIComponent(file.name), {
  method: 'POST',
  headers: { 'Content-Type': file.type },
  body: file
});
```

The server answers `202` with `{accepted, attachment, mediaType, description}` and starts a turn whose
first message is:

```
[picture whiteboard.png] a whiteboard with a red arrow and the words 'ship it'

(The picture this describes is saved at /home/you/.oh-my-ccj/…/20260918-101500-ab12.attachments/whiteboard.png; read it if a detail the description dropped matters.)
```

Three things about that text are deliberate:

- **The description is ordinary text**, so it compacts, replays and renders like anything else typed.
  Nothing downstream of the composer knows a picture was involved.
- **The marker says a picture was described** rather than passed off as typing, and the path is what
  makes the description checkable: `read` needs no approval, so asking about a detail the description
  dropped costs one tool call rather than another upload.
- **Nothing is written before the turn can start.** The bytes are sniffed first, the conversation is
  claimed second, and only then is the vision model asked — so a picture sent into a conversation that
  is already running is refused without spending an upload and a description on a turn that cannot
  happen, and a vision model that cannot be reached refuses the picture instead of starting a turn
  about a picture nobody looked at.

| Refusal | When | Body |
|---|---|---|
| `400` | the bytes are not PNG, JPEG, WebP or GIF (the type is read from the magic number, never the name or the `Content-Type`) | what the first bytes actually were |
| `413` | the body is over 8 MB, refused on its declared length and bounded again while reading | the limit and the declared size |
| `409` | the conversation on screen already has a turn running, or no vision model is configured | what to abort, or which block/flag to set |

The picture is stored under `<sessions>/<session-id>.attachments/`, beside the session and never in the
project — a photo of a receipt is not project content, and a file that shows up in `git status` because
somebody photographed something is a surprise nobody asked for. Deleting the session deletes them, and
so does deleting every session in a workspace.

## Custom providers

The built-in names are code; anything else is a definition the user owns, kept in
`<home>/providers.json`:

```json
{"providers": {
   "myrelay": {"kind": "openai", "baseUrl": "https://relay.example.com/v1",
               "apiKeyEnv": "MY_KEY", "models": ["deepseek-v4-flash", "gpt-5.5"]}}}
```

- `kind` is the wire protocol (`openai` covers every `/chat/completions` endpoint, `anthropic` the
  messages API), which is what makes a relay, a gateway, a local vLLM or a personal router usable
  without a release. Only two protocols exist, and anything else is rejected as a typo.
- A definition's `baseUrl` is the endpoint that definition is served at; it is also what the status
  reports, so "where will this go" has one answer. A `baseUrl` stored in `config.json` is used only
  when it was entered for this provider (a `--base-url` flag, a `CCJ_BASE_URL`, or the settings form
  while this provider was selected) — a value left behind by the provider you used a minute ago is
  somebody else's address, and sending this provider's traffic there with that provider's key is what
  `settingsFor` in the config file exists to prevent. `apiKeyEnv` is a *variable name*
  (`MY_KEY`), never the key itself; a definition whose field holds something that looks like a key is
  refused, with the value redacted.
- Definitions are validated before they are stored, and a definition that could not work is never
  selectable. Removing one is allowed even while it is in use, because removal is a list decision
  rather than a capability removal (see below).
- **A definition is intent**: saving one also puts it in the list when the list has been narrowed,
  and a definition found on disk but missing from the list is folded back in on load. Without that,
  a provider the user just saved is invisible — the bug that made Save look broken.
- **A base URL is a prefix**: an endpoint path in it (`/chat/completions`, `/v1/messages`) is
  stripped wherever a base URL enters — a provider definition or the settings form — because ccj
  appends that path itself, and sending it twice is a 404 that teaches nothing. A trailing slash is
  removed first, so `…/v1/chat/completions/` is normalised exactly like `…/v1/chat/completions`.
- **An `apiKeyEnv` that holds a key** rather than the *name* of one is reported as exactly that
  mistake, with the key redacted, instead of the bare "no API key" message.
- **The key variable of a definition is the one that holds its key.** `apiKeyEnv` in `config.json`
  belongs to the provider configured there, and `Config.resolved()` fills it with the provider-kind
  default — `OPENAI_API_KEY` for every name that is not `anthropic`. Reading that variable first
  would put a globally exported OpenAI key in the `Authorization` header of a request to somebody
  else's relay, so a definition's own variable is consulted before the config's, and an `apiKey`
  literal in the config still wins over both.
- **Removing a provider**: a definition the user made is deleted from `providers.json`. A built-in
  is an alias compiled into the agent, so there is nothing to delete — it simply leaves the list,
  and the list becomes **explicit**: `providers.json` records `"shown": [...]`, the providers that
  exist, rather than anything about what was removed. There is no "hidden" state to explain and
  nothing to restore; `GET /api/models` reports the built-ins that are *not* in the list as
  `"builtIns": [...]`, and `PUT /api/providers` adds one back, which is an ordinary add. An
  *emptied* list is explicit too: removing the last provider leaves the picker empty — no built-in
  returns — and `builtIns` is how one comes back.
- Removal is a list decision, never a capability removal: a config naming a removed provider keeps
  working, which is why removing the provider in use is allowed. Deleting a definition that is in
  use is also allowed — the running session keeps the provider it built — but the notice warns to
  choose another before the next restart.
- Model lists are editable **per provider, built-ins included**: a hand-typed model is not a
  one-off, and the list a picker offers must survive the next render. The first edit records the
  whole list, after which it is authoritative — that is what makes a removal stick, and what makes
  an addition survive a restart. An *empty* recorded list is authoritative too, which is the only
  reason removing the last model does not resurrect the provider's default.
- Removing a model **never edits the configuration**: the model in use stays in use, and stays
  visible to the picker, marked `in use · not offered`. Refusing the removal instead would deadlock
  the user whenever the model in use was also the only one offered — the exact case that prompted
  this rule.
- The catalogue endpoint (`/api/models`) is what the settings form reads. It is deliberately
  synchronous and offline: a settings form must render instantly, and a router-backed catalogue can
  cache whatever it fetches. See [ROUTER.md](ROUTER.md).

## Choosing a model (the composer picker)

The model is chosen where it matters — in a control inside the composer, in its footer row beside the
send button, the way the harness composes it. It opens a two-column panel: providers on the left, the
models that provider offers on the right, and, once a model is picked, an effort tier. The tiers
travel with every request:

| Tier | OpenAI-compatible | Anthropic |
|---|---|---|
| `default` | nothing sent — the provider decides | nothing sent |
| `low` | `reasoning_effort: "low"` | extended thinking, budget 2048 |
| `high` | `reasoning_effort: "high"` | extended thinking, budget 8192 |
| `max` | `reasoning_effort: "high"` plus `max_completion_tokens: 32768` | extended thinking, budget 32768 (and `max_tokens` raised to exceed it) |

Anthropic's `thinking_delta` events are streamed to the client as `reasoning`, like OpenAI's
`reasoning_content`: a turn that spends thousands of tokens thinking must not look like a stalled
spinner. The thinking text is not part of the assistant turn — it is not what the transcript shows as
an answer — but it *is* kept, with its signature, because the Messages API wants it back on the next
request and rejects a turn that drops it. It is handed back only while a reasoning tier is set: the
blocks belong to a setting the request must be asking for. A block whose signature never arrived (a
stream cut mid-thought) is dropped rather than sent, since the signature is what the API verifies; a
`redacted_thinking` block is opaque and is kept verbatim.

Two details are protocol facts rather than choices: the OpenAI-shaped API has no effort above `high`,
so `max` buys room to think instead; and Anthropic rejects a custom `temperature` while thinking is
enabled, so the tier and a temperature are mutually exclusive there. A tier that is never chosen sends
nothing at all, which is what keeps every non-reasoning model working unchanged.

The value is stored as `reasoning` in `config.json`, reported in `status` (with the list of levels so
the picker needs no second source), accepted by `POST /api/config`, and cleared by posting
`"reasoning": "default"`.

In the composer, **picking a provider is enough to switch to it**: the pick applies immediately,
paired with the model already in use when that provider offers it and with the first offered model
otherwise. Browsing alone (swapping the model column without applying) was worse than useless — a
provider whose list was empty could not be selected at all. The settings form keeps the drafting
behaviour, because there a pick is an edit to the form until Save.

## Settings

`ccj` with no arguments serves the web UI, and the UI is where a model gets configured — no JSON
editing, no restart. `GET /api/config` returns:

```json
{
  "configured": true,
  "provider": "openai",
  "model": "gpt-4o-mini",
  "baseUrl": "https://api.openai.com/v1",
  "apiKeyEnv": "OPENAI_API_KEY",
  "apiKeySource": "env",
  "usesStoredSettings": true,
  "rememberedProviders": ["deepseek", "myrelay"],
  "reasoning": null,
  "reasoningLevels": ["low", "high", "max"],
  "temperature": null,
  "maxTokens": null,
  "configFile": "/home/you/.oh-my-ccj/config.json",
  "providers": ["openai", "openai-compatible", "deepseek", "groq", "ollama", "custom", "anthropic"]
}
```

`apiKeySource` is `config`, `env` or `none`; the key itself is never sent to the browser.
`baseUrl`, `apiKeyEnv` and `apiKeySource` describe the **active provider**,
`usesStoredSettings` is false when what the file holds was entered for a different one (a custom
provider is served by its definition in that case) — the form then shows the catalogue's endpoint
rather than offering to save a value that will not be used — and `rememberedProviders` lists, by
name, the providers that have a key saved, so the form can say "saved" or "not saved" per provider
before anything is pasted. `POST
/api/config` takes any subset of
`{provider, model, baseUrl, apiKey, apiKeyEnv, temperature, maxTokens, reasoning}` and returns the new
`status` payload. Rules:

- An omitted or empty `apiKey` keeps the stored one; `"clearApiKey": true` deletes it — and forgets it
  for that provider, so it cannot come back on the next switch. Other providers' keys are untouched.
- Keys are kept **per provider** in `config.json`: the pair in effect at the top level, marked with
  `settingsFor`, and a `remembered` map for the rest. Changing `provider` moves the pair being left
  into `remembered` and takes the new provider's pair back out, so switching back needs no key and no
  key is ever sent to a provider it was not entered for.
- The provider is built and validated **before** anything is written, so a typo cannot leave a broken
  config behind; a rejected change returns `400` with `{"error": "..."}` and changes nothing.
- Only what was chosen is written: a defaulted endpoint or key variable is left out of the file and
  re-derived on load, so the file never accumulates values nobody set.
- Fields the form does not manage (system prompt, output caps, auto-approve, the context budget) are
  preserved in the file. The context budget has no form field on purpose: it is a ceiling you set
  once for a model, not a knob to nudge while sending messages.
- The file is written with `0600` permissions because it may hold a key.

`POST /api/config/test` runs one minimal turn with the posted values (no save) and answers
`{"ok": true, "reply": "...", "elapsedMs": 420}` or `400 {"error": "HTTP 401: bad key"}`. It is the
only endpoint that spends the user's tokens, and only when they ask for it.

While no provider is configured the UI still works: the status reports `configured: false`, sending a
turn returns `409 {"error": "no model configured — open Settings"}`, and the settings form opens on
first load.

All JSON is UTF-8. Errors are `{"error": "..."}` with a non-2xx status; a body over 1 MiB is refused
with `413` rather than read into memory. The server binds loopback; binding another interface
requires an explicit token, and every request must then carry it as `?token=` or
`Authorization: Bearer`.

Without a token the server additionally requires a **loopback `Host` header**: any page the user
visits can reach `127.0.0.1` with a simple cross-origin POST (no preflight) and DNS rebinding lets it
read the answers too, so a request addressed to another name is `403`. A name is never resolved to
check this — only `localhost` and loopback literals are accepted — because resolving an
attacker-chosen name is the whole point of a rebinding attack. A token makes the check unnecessary,
which is why it only applies to the tokenless default.

## SSE events

One JSON object per `data:` line, each with a `type`, and an `id:` line so a reconnecting browser can
resume with `Last-Event-ID` (the server keeps a small replay buffer).

| `type` | Fields | Meaning |
|---|---|---|
| `status` | as `/api/status`, plus `busy`, `running` and `approvals` | sent on connect, after session switches, when a turn ends, and when a tool asks for approval |

Every event also carries **`sessionId`** — the conversation it belongs to. One stream carries every
conversation on the server, so this is what lets a page render exactly the transcript it is showing
and leave a background turn's prose alone. Events that are about the server rather than a
conversation (a notice from the settings form) carry an empty id and are shown by every page.


| `user` | `text` | the turn's input, echoed so the transcript is server-authoritative |
| `text` | `delta` | assistant prose, streamed |
| `reasoning` | `delta` | reasoning/thinking content, streamed |
| `tool` | `id`, `name`, `summary`, `state` (`start`\|`end`), `ok`, `elapsedMs`, `output` | tool call lifecycle; `summary` is a one-line argument digest. `elapsedMs` is `-1` for a call an abort stopped before it ran: there is no duration to report, and a `0` would read as a measurement |
| `approval` | `id`, `title`, `detail` | the loop is blocked waiting for an answer |
| `approval-closed` | `id`, `allow` | answered (by a browser or by timeout) |
| `notice` | `text` | token usage, retries, a trimmed context budget, a repaired history |
| `done` | `finalText`, `aborted` | the turn finished |
| `error` | `message` | the turn failed |
| `usage` | `turns`, `steps`, `inputTokens`, `outputTokens`, `cachedInputTokens`, `cacheHitRate`, `toolCalls`, `toolErrors`, `elapsedMs`, `contextTokens`, `contextLimit` | running totals for the current session; also sent when a tool call starts or ends, so the panel never contradicts the cards on screen |

A subscriber is a queue, not a socket write: the thread that publishes an event never touches a
client's socket, so a browser that stops reading (a suspended tab, a stalled network) fills only its
own bounded queue and is dropped if it falls too far behind — where writing straight from `publish`
would wedge the turn that is streaming, and the replay monitor with it. The page reconnects and
re-reads `/api/history` after any disconnect, so a dropped client loses nothing but its place.

## History and usage

The side panel ends with a number that is an estimate and labelled that way. **context** is what
this conversation would cost the model's window, against the budget when one is configured
(`--max-context-tokens`, `CCJ_MAX_CONTEXT_TOKENS`, or `"maxContextTokens"` in `config.json`), and it
is what tells you a session is about to be trimmed before the transcript says it was. Token counts
come from the provider and are exact; the context figure is a heuristic over them, and the panel says
so. There is no money in the panel: a price is an assumption about a rate card that changes without
telling ccj, and a plausible-looking number nobody can check is worse than no number.

A page that loads, reloads, or switches sessions must show the conversation that already exists —
otherwise resuming a session looks like it worked and then shows an empty screen. `GET /api/history`
returns the current session encoded as **the same event objects the stream emits**, so the page
renders history and live turns with one code path:

```json
{"sessionId": "20260911-233115-94cf",
 "usage": {"turns": 1, "steps": 2, "inputTokens": 1200, "outputTokens": 340,
           "cachedInputTokens": 900, "cacheHitRate": 0.75, "toolCalls": 1,
           "toolErrors": 0, "elapsedMs": 480},
 "events": [
   {"type": "user", "text": "read pom.xml", "replay": true},
   {"type": "tool", "id": "call_1", "name": "read", "summary": "pom.xml",
    "state": "start", "replay": true},
   {"type": "tool", "id": "call_1", "name": "read", "state": "end", "ok": true,
    "elapsedMs": null, "output": "…", "replay": true},
   {"type": "text", "delta": "Reading it now.", "replay": true}
 ]}
```

Replay events carry `replay: true` and have no SSE id (they are not part of the live stream, and the
dedupe that protects reconnects does not apply to them). A tool call and its result are paired by
`id`, so the renderer produces the same card it would have produced live. Two fields differ from the
live stream: replayed `tool` end events have `elapsedMs: null` because the session file stores the
conversation and not timings, and no `notice` events are replayed at all — token lines are transient,
and the usage panel is fed by `usage` instead.

`usage` totals belong to the session, not to the process: every turn end appends one accounting line
to the session file (`{"type":"usage","input_tokens":…}`) and reopening a session restores it, so a
resumed conversation continues its cache hit rate instead of starting at zero. They arrive as an
event during and after every turn and are also embedded in the `status` payload as a `usage` object,
so a fresh page has numbers before the first turn. `turns` counts user turns, `steps` counts model
turns (a turn that calls three tools is three steps plus the answer), and `elapsedMs` is time spent
inside turns. `cachedInputTokens` and `cacheHitRate` are `null` when the
provider reported no cache figures — "no information" and "nothing was cached" are different things,
and reporting 0% for a local model would be a lie.

Cache accounting per protocol: OpenAI-compatible endpoints report
`prompt_tokens_details.cached_tokens` (DeepSeek and others use `prompt_cache_hit_tokens`), which is
already included in `prompt_tokens`; Anthropic reports `cache_read_input_tokens` and
`cache_creation_input_tokens` **in addition to** `input_tokens`, so the prompt size is their sum and
the hit rate is `cache_read / prompt size`.

### A long conversation is drawn in two passes

A session of a few thousand messages is thousands of tool cards, and building them all at once blocks
the page for long enough that a turn streaming behind the switch looks frozen. So a history longer
than **300 events** is drawn in two passes: the newest 300 now, the rest as soon as the browser is
idle. Nothing is dropped, and the reader can scroll immediately.

Three details make the second pass invisible, and each is the reason it exists:

- **The cut lands on a user message**, which is the boundary the renderer already treats as one
  (`appendUser` closes the open assistant block). Splitting mid-turn would hand one turn to both
  passes, and the second would append its reasoning *after* the answer the first one drew. An
  exchange longer than the budget is kept whole: half an answer is worse than a slow screen.
- **Each chunk is built off-screen and inserted as one node**, above what is already drawn.
- **The reader's scroll position is held** by the height that was added above them; without that,
  filling in the past would slide the text being read down the screen.

An earlier chunk that is still loading shows a "N earlier events — loading…" line at the top, which is
removed when the pass finishes; switching sessions again cancels the pass rather than appending one
conversation's history into another's transcript. Measured on a real 1,320-message session (1,740
events): the page became usable in 29 ms with 194 nodes drawn, against 64 ms and 1,069 nodes for the
single pass — and the finished transcript is identical either way.

## Rendering the answer

Assistant prose is markdown, and the page renders it as markdown — live, from the same deltas the
stream already carries, and identically when the conversation is restored from `/api/history`.

Streaming is what shapes the design. A delta can leave a fence unclosed, a table half-written or an
asterisk unmatched, and a renderer that could not survive that would either flicker or have to guess
where the message was going. So the renderer re-parses the **whole source** on every flush — string
work only — and renders each block **from its own source alone**. A half-arrived message is therefore
a faithful rendering of the message so far, and nothing drawn early ever has to be taken back. What
makes it cheap is that a block *is* its source: blocks whose source did not change keep the nodes
they already have, so an answer growing by one word re-creates the one block it is still writing and
leaves everything above it untouched — the scroll position, a text selection and the paragraph being
read all survive a long turn.

| Supported | Notes |
|---|---|
| Headings, paragraphs, hard (two-space) and soft breaks | the sizes step down by level |
| Fenced code, ``` or `~~~` | the info string is shown as a label above the block; the block scrolls in place |
| Lists, nested, ordered with a start number | GFM task boxes are rendered as *disabled* checkboxes — a transcript is a record, not a form |
| Blockquotes, thematic breaks | |
| Tables with `:--`, `--:` and `:--:` alignment | a wide table scrolls inside itself |
| Inline code, `**bold**`, `*italic*`, `~~struck~~`, links, autolinks | |

Three refusals are security decisions rather than omissions:

- **Raw HTML is never interpreted.** `<script>` in an answer is shown as the text it is, not parsed
  as markup. The page's rule — every string that comes from the server, the model or a tool is
  written as a text node, with no `innerHTML` anywhere in `app.js` — is what the renderer is built
  from, and a markdown parser that passes HTML through would be an HTML injection with extra steps.
- **A link's scheme is filtered.** Only `http`, `https`, `mailto` and scheme-less (relative) targets
  become an `href`; `javascript:` and `data:` stay text. Every link opens in a new tab with
  `rel="noopener noreferrer"`.
- **Images are not fetched.** `![alt](url)` renders as the link it also is, because rendering an
  image makes the browser request a URL the moment an answer arrives — a beacon the reader never
  asked for, telling a third party that this conversation is on screen. Inlining would need a proxy
  in the server, which is a different feature and a different risk.

Reasoning (`msg-reasoning`) stays plain text: it is the model talking to itself, and it is not what
the reader is here for. The markdown cases live in `src/test/js/markdown.test.mjs`, which lifts the
renderer's own source out of `web/app.js` and runs it against a small DOM — no browser, no npm
package, nothing to install but a `node` binary (`WebMarkdownTest` runs it from `mvn test`, and skips
it when there is no node).

Deliberate deviations from CommonMark, both of them about not guessing: a line that continues a list
item must be **indented** to that item's content column — a column-0 line after a list is far more
often the next block of the answer than a wrapped bullet, and guessing "wrapped" once nests the whole
rest of the answer inside the last item — and HTML entities are not decoded, so `&amp;` stays the
five characters the model wrote.

## Several conversations at once

A turn running in one session does not lock the server. That is the point of a sidebar full of
conversations: start a long job in one, switch to another, and deploy the next piece of work while
the first is still going.

What the server keeps per session, and what it still refuses:

- **The unit that takes one turn at a time is the session.** `AgentHub.Conversation` holds that
  session's turn flag, its running loop and its books. A second `POST /api/message` for a *busy*
  session is refused with `409`; a message for any other session is accepted. Two writers appending
  to one transcript is the thing that corrupts it, and that is still impossible.
- **Looking at a conversation is always allowed — including the busy one.** Opening the session that
  is running is the main thing a user wants to do with it, and it is answered by putting the *same*
  `FileSession` its turn is appending to on screen, not by opening a second writer on the file. The
  first version refused this, which made a running turn impossible to watch: clicking its row in the
  sidebar bounced with `409`.
- **Operations are refused by what they touch, not by a global flag.** Deleting the session a turn is
  writing is refused (`409`) — that would pull the transcript out from under it. Changing the
  provider or the config file is refused while *anything* is running, because those are what every
  conversation is built on. Adding a workspace, or opening a conversation in another one, is not
  refused at all: a turn holds the working directory it started in, so switching the namespace on
  screen cannot redirect work that is already under way.
- **Every event names its session.** `sessionId` travels on the event, and the page renders only
  what belongs to the transcript on screen. A finished turn in another session does not unstick this
  page's composer, and its prose never lands in the wrong conversation. The page's `lastEventId`
  still advances for those foreign events — it is a position in one shared stream, and skipping it
  would make the next reconnect replay frames already delivered.
- **Running sessions are visible from anywhere.** `GET /api/status` reports `running` (the ids of the
  other sessions working now), each row of `GET /api/sessions` reports `running`, and the tree marks
  those rows with a pulsing dot plus a ■ control. That control posts `/api/abort?id=<session>`, so a
  background turn can be stopped without opening it first.
- **An approval in another session is still answerable, and comes back when you return.** The prompt
  is published with its session and `POST /api/approval` answers by id from anywhere. It is also part
  of that conversation's `status`: an approval is a request blocked in memory, not a message, so
  replaying a conversation cannot restore it — without reporting it, looking away and back lost the
  question and left abort as the only way out. Each conversation's status names only its own requests,
  a request reported while a replay is in flight is held until the transcript stops being rebuilt, and
  one the server no longer knows about (answered elsewhere, or timed out) is closed rather than left
  as a card that can never be answered. Aborting a session also denies its pending requests, so abort
  works on a turn that is blocked on a human.
- **Two conversations in one workspace can write the same files.** Nothing serialises that: it is
  the same exposure as two terminals in one directory, and serialising it would defeat the feature.

What abort is and is not: it stops the turn between steps and before each tool call (`AgentLoop`),
and a running `bash` command is killed through `ToolContext.isCancelled()`. A turn blocked inside the
model's own network call finishes that call first — there is no cancellation point in the middle of
reading a stream — so behind an approval it stops at once, and in the middle of a reply it stops at
the next step boundary.

## Session lifecycle

`GET /api/sessions` labels each row with a `title`: the first user message, flattened to one line
and bounded, so the sidebar reads as a list of tasks instead of a list of `20260913-001746-3377`.
`preview` is the same message at the shorter length `--list-sessions` prints, and both are derived
on read — a session's file is still just the conversation, with no index and no title field to go
stale. The id stays in the payload and in the row's tooltip: a title is what a session is *about*,
an id is what it *is*, and two sessions can start with the same sentence.

The order is the server's, not the page's: sessions come back newest first, ordered by the file's
modification time, which a turn advances. So a turn ends by re-reading the active workspace's list
— that is what puts the conversation you just had at the top, and what makes a session whose first
message was this turn appear at all. It is a background refresh of one endpoint, and a failure
leaves the previous order on screen rather than replacing rows with an error.

A session file is created on the **first message**, not when a session id is minted: opening the
CLI, or pressing `New session`, must not leave an empty file behind that pollutes the session list.
`POST /api/session {"action": "new"}` on an already-empty session is a no-op and says so through a
`notice` event rather than minting yet another id.

Accounting records share the file but are not messages: `FileSession` filters them out of the
conversation, and the last one wins when a session is reopened.

## Approval

`AgentHub.askApproval` publishes an `approval` event and blocks the loop thread, so a tool call that
needs a human cannot proceed on its own. Timeout is **deny** — the safe default, and the same rule
the CLI uses when stdin is not a terminal. `remember: true` flips the session to auto-approve, which
the UI shows as a visible, revertible state.

## Abort and the conversation it leaves behind

`POST /api/abort` asks the running turn to stop, and the transcript shows what that meant. An abort
lands in one of two places, and both are visible:

- **Between calls** — a call that had not started is recorded as `not run`, so its card appears and
  is closed rather than left spinning on something that will never finish. It carries
  `elapsedMs: -1`, which the page renders as `failed` with its reason in the output and no timing,
  and the same text the model will read.
- **Mid-call** — the tool's own cancellation path runs (`bash` kills the process tree), and the
  result says it was aborted.

Either way the assistant turn was already in the session file before its calls ran, and both wire
formats reject a history where a call has no result. Rather than leave a conversation that every
later request is refused for — permanently, and with an error that says nothing about why —
`SessionRepair` makes the projection that goes out sendable: it supplies the missing results, and it
also handles the case where a message landed in the middle of a turn's answers (a second writer on
one session, a stray append) — that turn's real result is carried back into the turn, because a
`tool` message that answers no call the API can see is the same rejected request from the other side
(`Messages with role 'tool' must be a response to a preceding message with 'tool_calls'`). A result
that answers no recorded call at all cannot be sent, so it is left out and named in the notice. The
page announces all of it in a `notice`. The file is not rewritten: append-only is the property that
makes `--resume` trustworthy, and the repair is a statement about what to *send*, not about what
happened.

## Restarting from the page

`POST /api/message` can lead to the `restart` tool, which installs a rebuilt jar and ends the
process. The server does not wait for Ctrl-C in that case: its loop watches for the request and
returns, printing the session id, so the launcher can start the new jar. The page is unaffected
until the connection drops — then the browser reconnects, `/api/history` replays the session, and
the turn ends with the tool card rather than an error. See [BOOTSTRAP.md](BOOTSTRAP.md).

The conversation comes back with it. Before returning, the process writes the session it was on into
`<home>/resume`, and the next process opens that session instead of a new one — so the reload lands
on the conversation whose turn installed the jar, not on an empty transcript. The note is spent when
it is read: it resumes exactly the one restart that wrote it, and a later start is a new session
again. `--resume` and `--continue` still win, since an explicit choice outranks a memory.

## Reuse

The web layer is a front end like any other: it builds the same `Provider`, `ToolRegistry`,
`FileSession` and `AgentLoop` the CLI does, and implements `AgentListener` to translate callbacks
into events. Sessions are shared with the CLI, so `ccj --web` can pick up a conversation started in
the terminal.

## Room for a router

Nothing here assumes the model is a first-party endpoint: it is one `baseUrl` + `model`, which is
exactly what an OpenAI-compatible router needs. A router panel (model catalogue, key health, request
logs, cost) adds `GET /api/router/*` endpoints plus a pane in the page; the status payload already
carries `provider`, `model` and `baseUrl`, so the pane has somewhere to start without redesigning
anything above.
