# Plugging a router into ccj

Notes for the API router that will sit in front of the model providers. The short version: **most of
the integration already exists and needs no code**, and the one piece that does need code is a single
interface that already has a working implementation to copy.

## 1. A router is just a provider

Anything that speaks the OpenAI chat-completions API is already usable, today, with no changes:

```bash
# from the settings panel, or:
curl -X POST localhost:6767/api/providers -H 'content-type: application/json' -d '{
  "name": "router",
  "kind": "openai",
  "baseUrl": "http://localhost:9090/v1",
  "apiKeyEnv": "ROUTER_KEY",
  "models": ["deepseek-v4-flash", "gpt-5.5"]
}'
```

After that the provider is selectable in the settings panel, `ccj --provider router --model …` works
in the terminal, and the model list is offered in the model field. Definitions live in
`~/.oh-my-ccj/providers.json`; `kind: "anthropic"` is available for a router that prefers the messages
API. Keys are read from the named environment variable, so nothing secret is written down.

**This is the whole integration for a router that only proxies.** No agent code, no UI code.

## 2. A dynamic catalogue needs one interface

`ModelCatalog` answers "which providers and models exist, and where did that answer come from?":

```java
public interface ModelCatalog {
  List<ProviderInfo> providers();   // name, kind, baseUrl, builtIn, models
  List<Model> models();             // provider, model, source   ("config" today, "router" later)
}
```

`ConfigModelCatalog` is the shipped implementation (built-ins plus `providers.json`). A
router-backed one is a second implementation of the same two methods, for example:

```java
final class RouterCatalog implements ModelCatalog {
  private final String baseUrl;                 // http://localhost:9090
  @Override public List<Model> models() {
    // GET {baseUrl}/v1/models, or the router's own endpoint — whichever it exposes.
    // Map into Model(provider, model, "router"); cache for a few seconds.
  }
}
```

Two things make this a real seam rather than a plan:

- The web layer never learns where the catalogue came from. `GET /api/models` serialises whatever the
  catalogue returns, `source` included, so a router-provided entry shows up in the model field with
  no front-end change. The page even labels the source already.
- Nothing else in the agent reads the catalogue. The provider actually used is still chosen by
  `config.provider` + `config.model`, i.e. the router can be selected without the catalogue agreeing
  with it — and if the catalogue is slow or down, the settings form still renders from configuration
  (that is why the shipped implementation is synchronous and does no I/O).

Wiring it up is one line, where the CLI builds the hub:

```java
new AgentHub.Settings(…, new RouterCatalog(baseUrl), providerStore, …);
```

## 3. What the router should expose

Only one endpoint is needed for the catalogue, and the boring choice is best:

| Endpoint | Used for | Notes |
|---|---|---|
| `GET /v1/models` | the model field | OpenAI-shaped `{"data":[{"id":…}]}` keeps the adapter trivial |
| `POST /v1/chat/completions` | the agent's turns | already required, already works |
| `GET /health` (optional) | a future provider-status panel | not needed for anything today |

## 4. What deliberately does not belong in the agent

The agent talks to exactly one endpoint and does not try to be a router: no cross-provider failover,
no cost accounting, no model selection heuristics. Those are the router's job, and duplicating them
would put two disagreeing policies on the same path. What the agent *does* report is the accounting it
can see — tokens, cache hit rate, tool calls, per session (`/api/status.usage`) — which is what a
router panel would need to show savings rather than guess them.

## 5. If a router panel is wanted later

The pattern is the workspace panel: a `GET /api/router/*` endpoint that proxies the router's own API
(a local router has no CORS story worth relaying), plus a pane in the page. The pieces that already
exist and would be reused: the loopback-only server with the token gate, the SSE event stream, the
notice channel, and the settings form's provider list. Nothing above `AgentHub` needs to change; the
hub would gain a thin client for the router's metadata, exactly as it gained a folder chooser.
