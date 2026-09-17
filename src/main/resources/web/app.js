/* ccj --web front end.
 *
 * Vanilla ES2020 classic script: no modules, no bundler, no dependencies,
 * works offline straight out of the jar.
 *
 * Security: every string that originates from the server, the model or a tool
 * is written with textContent / createTextNode. This file contains no
 * innerHTML, no insertAdjacentHTML and no eval.
 */
'use strict';

(function () {
  // --------------------------------------------------------------- helpers

  function $(id) { return document.getElementById(id); }

  function el(tag, cls, text) {
    const node = document.createElement(tag);
    if (cls) { node.className = cls; }
    if (text !== undefined && text !== null) { node.textContent = String(text); }
    return node;
  }

  function str(v) { return v === undefined || v === null ? '' : String(v); }

  function firstLine(s) {
    const t = str(s);
    const i = t.indexOf('\n');
    return i < 0 ? t : t.slice(0, i);
  }

  function clip(s, max) {
    const t = str(s);
    return t.length > max ? t.slice(0, Math.max(0, max - 1)) + '…' : t;
  }

  /* Replayed tool results carry elapsedMs: null — the session file keeps the
   * conversation, not the timings — so an absent value prints nothing rather
   * than a fake "0 ms". A negative value is a call that never ran, which must
   * not read as a measurement either. */
  function msLabel(v) {
    if (v === null || v === undefined) { return ''; }
    const n = Number(v);
    return isFinite(n) && n >= 0 ? Math.round(n) + ' ms' : '';
  }

  function timeLabel(v) {
    const d = new Date(str(v));
    return isNaN(d.getTime()) ? str(v) : d.toLocaleString();
  }

  function hasContent(node) { return !!(node && node.firstChild); }

  /* The leading stamp of a session id ("20260913-001746-3377" → "20260913").
   * Rows are titled by what was asked, not by this; the stamp appears only when
   * two rows in the same list would otherwise read the same, because then the
   * title alone cannot say which one to click. */
  function idStamp(id) {
    const t = str(id);
    const i = t.indexOf('-');
    return i > 0 ? t.slice(0, i) : t;
  }

  /* cwd is the workspace path (the contract says so), so its last segment is
   * the workspace name — a fallback for a server that has not sent
   * status.workspace yet, never a replacement for it. */
  function baseName(path) {
    const parts = str(path).replace(/[\\/]+$/, '').split(/[\\/]/);
    return parts[parts.length - 1] || str(path);
  }

  // ------------------------------------------------------------- transport

  async function request(url, options) {
    const res = await fetch(url, options);
    const type = res.headers.get('content-type') || '';
    let body = null;
    try {
      body = type.indexOf('json') >= 0 ? await res.json() : await res.text();
    } catch (err) {
      body = null;
    }
    if (!res.ok) {
      let message = 'HTTP ' + res.status;
      if (body && typeof body === 'object' && body.error) {
        message = str(body.error);
      } else if (typeof body === 'string' && body.trim()) {
        message = clip(body.trim(), 300);
      }
      const error = new Error(message);
      error.status = res.status;
      throw error;
    }
    return body;
  }

  function postJSON(url, payload) {
    return request(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });
  }

  // ------------------------------------------------------------------ dom

  const dom = {
    connPill: $('conn-pill'),
    chipProvider: $('chip-provider'),
    chipModel: $('chip-model'),
    chipSession: $('chip-session'),
    live: $('live'),
    liveText: $('live-text'),
    btnNew: $('btn-new'),
    btnAuto: $('btn-auto'),
    autoState: $('auto-state'),
    btnSettings: $('btn-settings'),
    btnAbort: $('btn-abort'),
    btnCompact: $('btn-compact'),
    btnSidebar: $('btn-sidebar'),
    btnSide: $('btn-side'),
    btnTheme: $('btn-theme'),
    btnWallpaper: $('btn-wallpaper'),
    themeIcon: $('theme-icon'),
    themeLabel: $('theme-label'),
    transcript: $('transcript'),
    jump: $('jump'),
    composer: $('composer'),
    input: $('input'),
    send: $('send'),
    hint: $('composer-hint'),
    photoHint: $('photo-hint'),
    btnPhoto: $('btn-photo'),
    photoInput: $('photo-input'),
    side: $('side'),
    toolList: $('tool-list'),
    usage: $('usage'),
    uHit: $('u-hit'),
    uHitBar: $('u-hit-bar'),
    uHitFill: $('u-hit-fill'),
    uHint: $('u-hint'),
    uAlert: $('u-alert'),
    uTurns: $('u-turns'),
    uSteps: $('u-steps'),
    uIn: $('u-in'),
    uOut: $('u-out'),
    uCached: $('u-cached'),
    uErrorsRow: $('u-errors-row'),
    uErrors: $('u-errors'),
    uTools: $('u-tools'),
    uCompactions: $('u-compact'),
    uCompactionRow: $('u-compact-row'),
    uElapsed: $('u-elapsed'),
    uContext: $('u-context'),
    scrim: $('scrim'),
    sidebar: $('sidebar'),
    sidebarCollapse: $('sidebar-collapse'),
    sidebarAlert: $('sidebar-alert'),
    sidebarNote: $('sidebar-note'),
    wsTree: $('ws-tree'),
    wsDeleteAllHost: $('ws-delete-all-host'),
    workspaceAdd: $('workspace-add'),
    workspaceAddForm: $('workspace-add-form'),
    workspaceSave: $('workspace-save'),
    workspacePick: $('workspace-pick'),
    wsNewPath: $('ws-new-path'),
    wsBrowse: $('ws-browse'),
    wsBrowseHint: $('ws-browse-hint'),
    wsPathError: $('ws-path-error'),
    settingsOverlay: $('settings-overlay'),
    settingsForm: $('settings-form'),
    settingsClose: $('settings-close'),
    settingsError: $('settings-error'),
    settingsTest: $('settings-test'),
    settingsTestResult: $('settings-test-result'),
    settingsSave: $('settings-save'),
    settingsFoot: $('settings-foot'),
    composerPicker: $('composer-picker'),
    cfgPicker: $('cfg-picker'),
    cfgProviderHint: $('cfg-provider-hint'),
    cfgProviderError: $('cfg-provider-error'),
    cfgModelHint: $('cfg-model-hint'),
    cfgModelError: $('cfg-model-error'),
    cfgBaseUrl: $('cfg-baseurl'),
    cfgApiKey: $('cfg-apikey'),
    cfgApiKeyHint: $('cfg-apikey-hint'),
    cfgApiKeyError: $('cfg-apikey-error'),
    cfgClearKey: $('cfg-clearkey'),
    cfgClearKeyWrap: $('cfg-clearkey-wrap'),
    cfgApiKeyEnv: $('cfg-apikeyenv'),
    cfgApiKeyEnvHint: $('cfg-apikeyenv-hint'),
    cfgTemperature: $('cfg-temperature'),
    cfgLanguage: $('cfg-language'),
    cfgVisionBaseUrl: $('cfg-vision-baseurl'),
    cfgVisionModel: $('cfg-vision-model'),
    cfgVisionApiKey: $('cfg-vision-apikey'),
    cfgVisionApiKeyHint: $('cfg-vision-apikey-hint'),
    cfgVisionClearKey: $('cfg-vision-clearkey'),
    cfgVisionClearKeyWrap: $('cfg-vision-clearkey-wrap'),
    cfgVisionMaxTokens: $('cfg-vision-maxtokens'),
    cfgVisionOff: $('cfg-vision-off'),
    cfgVisionState: $('cfg-vision-state'),
    cfgVisionError: $('cfg-vision-error'),
    cfgProvidersNote: $('cfg-providers-note'),
    cfgProviderList: $('cfg-provider-list'),
    cfgBuiltInRow: $('cfg-builtin-row'),
    cfgBuiltIns: $('cfg-builtins'),
    cfgHiddenSection: $('cfg-hidden-section'),
    cfgHiddenList: $('cfg-hidden-list'),
    cfgProviderAddToggle: $('cfg-provider-add-toggle'),
    cfgProviderForm: $('cfg-provider-form'),
    cfgNewName: $('cfg-new-name'),
    cfgNewNameError: $('cfg-new-name-error'),
    cfgNewKind: $('cfg-new-kind'),
    cfgNewKindError: $('cfg-new-kind-error'),
    cfgNewBaseUrl: $('cfg-new-baseurl'),
    cfgNewBaseUrlError: $('cfg-new-baseurl-error'),
    cfgNewApiKeyEnv: $('cfg-new-apikeyenv'),
    cfgNewModels: $('cfg-new-models'),
    cfgProviderFormError: $('cfg-provider-form-error'),
    cfgProviderSave: $('cfg-provider-save')
  };

  // ---------------------------------------------------------------- state

  const state = {
    lastEventId: -1,       // SSE high-water mark; scope = current transcript
    source: null,
    everOpen: false,
    retryTimer: 0,
    busy: false,
    /* When the stream last delivered anything. A page cannot tell a quiet server
     * from a dead connection by looking at the socket, and a `busy` flag that
     * outlives its turn is a composer that never comes back. */
    lastEventAt: Date.now(),
    runningTools: 0,
    /* Sessions with a turn running somewhere on the server, by id. The page is
     * not rendering them — that is the point — but the tree marks their rows so
     * the user can find the turn again, and a turn left running in another
     * conversation is a fact this page has to be able to show. */
    runningSessions: new Set(),
    autoApprove: false,    // status.autoApprove, owned by the server
    block: null,           // current assistant block
    toolCards: new Map(),  // tool call id -> { root, ... }
    approvals: new Map(),  // approval id -> record
    /* Outstanding approvals the server reported while a replay was in flight: held
     * until the transcript is rebuilt, because drawing a prompt into a transcript
     * that is about to be cleared is how the prompt was lost in the first place. */
    pendingApprovalsSync: null,
    stick: true,           // transcript pinned to the bottom
    status: null,
    workspace: null,       // {name, path} of the active workspace
    cwdOverride: '',       // status.cwdOverride: -C pinned a directory, '' in the normal case
    configured: null,      // status.configured: null until known, then boolean
    sessionId: '',         // id of the session the transcript currently shows
    historyPromise: null,  // in-flight history load for the initial page
    historyInFlight: false,
    replaying: false,      // rendering a history snapshot, not the live stream
    pendingLive: [],       // SSE messages held back until a replay lands
    usage: null,           // last usage object seen (event or status)
    catalog: null,         // {providers, models} from GET /api/models, null until loaded
    catalogError: '',      // why the last catalogue refresh failed; '' when it worked
    reasoningLevels: null, // status.reasoningLevels: the effort tiers the provider accepts
    configProviders: [],    // provider names the server says are usable (GET /api/config)
    keyProvider: '',        // the provider the settings form's key field is currently about
    rememberedProviders: [] // provider names the server says have a key saved (GET /api/config)
  };

  // --------------------------------------------------------- text batching

  // Assistant deltas arrive a few characters at a time; each element keeps a
  // pending buffer that is flushed at most once per frame so a long turn costs
  // one text node per flush instead of thousands. A timer backs up rAF because
  // requestAnimationFrame does not fire in a hidden/background tab.
  //
  // Markdown goes through the same gate with a different unit of work: an answer
  // block's source is accumulated and re-rendered, rather than appended to.
  /* Where a replay pass appends. Normally the transcript; while the earlier part of a conversation is
   * being drawn it is a detached holder, so the exchange can be built off-screen and inserted above
   * the reader in one move. */
  let replayTarget = null;
  /** Bumped by every replay, so a superseded one stops instead of writing into a new transcript. */
  let replaySeq = 0;

  const queues = new Map();
  const mdQueues = new Map();   // markdown render state -> the node it renders into
  let rafId = 0;
  let flushTimer = 0;

  function scheduleFlush() {
    if (!rafId && !flushTimer) {
      rafId = requestAnimationFrame(flushText);
      flushTimer = setTimeout(flushText, 80);
    }
  }

  function queueText(node, delta) {
    if (!node || !delta) { return; }
    queues.set(node, (queues.get(node) || '') + delta);
    scheduleFlush();
  }

  function flushText() {
    if (rafId) { cancelAnimationFrame(rafId); rafId = 0; }
    if (flushTimer) { clearTimeout(flushTimer); flushTimer = 0; }
    if (!queues.size && !mdQueues.size) { return; }
    const atBottom = nearBottom();
    queues.forEach(function (text, node) {
      node.appendChild(document.createTextNode(text));
    });
    queues.clear();
    mdQueues.forEach(function (node, md) { renderMarkdown(md, node); });
    mdQueues.clear();
    settleScroll(atBottom);
  }

  // ---------------------------------------------------------------- scroll

  function nearBottom() {
    const gap = dom.transcript.scrollHeight - dom.transcript.scrollTop - dom.transcript.clientHeight;
    return gap < 48;
  }

  function scrollToBottom() {
    dom.transcript.scrollTop = dom.transcript.scrollHeight;
  }

  /* The server gives an unanswered approval two minutes and then denies it. A
   * card older than that cannot still be waiting for one, whatever this page
   * believes — a server that died, a stream that went quiet — and counting it as
   * live keeps the composer disabled forever. It is settled with the reason,
   * because a card that lies about waiting is worse than one that says what
   * happened. */
  const APPROVAL_MAX_AGE_MS = 150000;

  function expireStaleApprovals() {
    state.approvals.forEach(function (rec) {
      if (!rec.resolved && Date.now() - rec.at > APPROVAL_MAX_AGE_MS) {
        rec.settle(false, 'No answer from the server — treated as denied', 'bad');
      }
    });
  }

  function pendingApprovals() {
    let n = 0;
    state.approvals.forEach(function (rec) { if (!rec.resolved) { n += 1; } });
    return n;
  }

  function dropPlaceholder() {
    const node = dom.transcript.querySelector('.placeholder');
    if (node && node.parentNode) { node.parentNode.removeChild(node); }
  }

  function workspaceName() {
    return state.workspace && state.workspace.name ? state.workspace.name : '';
  }

  /* The empty transcript needs a line that is true in both states: before the
   * stream opens it is connecting, afterwards it is simply waiting for input.
   * The workspace name in front says *where* that input will land — switching
   * workspaces must be visible before the first message. */
  function placeholderText() {
    const ws = workspaceName();
    const connected = state.source && state.source.readyState === EventSource.OPEN;
    if (!ws) { return connected ? 'Connected — send a message to start.' : 'Connecting to ccj…'; }
    if (state.cwdOverride) {
      // -C pinned a directory that is not the active workspace's. Naming it here is the point: the
      // tree marks the workspace as active, and input lands somewhere else, so one of the two has
      // to say so out loud.
      return connected
        ? ws + ' · tools run in ' + state.cwdOverride + ' (-C)'
        : 'Connecting to ' + ws + '…';
    }
    return connected ? ws + ' · send a message to start' : 'Connecting to ' + ws + '…';
  }

  /* The placeholder is a plain text node the live stream also rewrites on
   * open; this keeps it in step when only the workspace changed. */
  function refreshPlaceholder() {
    const node = dom.transcript.querySelector('.placeholder');
    if (node) { node.textContent = placeholderText(); }
  }

  function showPlaceholder() {
    dropPlaceholder();
    const node = document.createElement('p');
    node.className = 'muted placeholder';
    node.textContent = placeholderText();
    dom.transcript.appendChild(node);
  }

  /* Scroll intent is measured immediately *before* a node is inserted, then
   * applied after it lands. Measuring after the insertion would compare the new
   * height against the old offset and wrongly conclude "the user scrolled up";
   * caching the flag from scroll events alone leans on events a hidden tab
   * never fires. */
  function appendToTranscript(node) {
    if (state.replaying) {
      // Restored from history: dim it and skip per-node scroll bookkeeping —
      // the replay ends with a single scroll to the bottom. While the earlier part of
      // a conversation is being drawn it goes into a detached holder first, so an exchange can be
      // built off-screen and inserted above the reader in one move.
      node.classList.add('replay');
      node.setAttribute('data-replay', '1');
      (replayTarget || dom.transcript).appendChild(node);
      return node;
    }
    const atBottom = nearBottom();
    dom.transcript.appendChild(node);
    settleScroll(atBottom);
    return node;
  }

  function settleScroll(atBottom) {
    if (state.replaying) {
      dropPlaceholder();
      state.stick = true;
      return;
    }
    dropPlaceholder();
    state.stick = atBottom;
    if (atBottom) {
      scrollToBottom();
      dom.jump.hidden = true;
    } else {
      updateJump();
    }
  }

  function updateJump() {
    const waiting = pendingApprovals() > 0;
    const show = !state.stick;
    dom.jump.hidden = !show;
    dom.jump.classList.toggle('alert', waiting);
    dom.jump.textContent = waiting ? 'Approval needed — jump to latest' : 'jump to latest';
  }

  dom.transcript.addEventListener('scroll', function () {
    state.stick = nearBottom();
    updateJump();
  });

  dom.jump.addEventListener('click', function () {
    state.stick = true;
    scrollToBottom();
    updateJump();
  });

  // ----------------------------------------------------------- header bits

  function setLive(kind, name) {
    dom.live.dataset.state = kind;
    dom.liveText.textContent =
      kind === 'running' ? 'running ' + str(name) :
      kind === 'approval' ? 'waiting for approval' :
      kind === 'offline' ? 'disconnected' : kind;
  }

  function refreshLive() {
    if (pendingApprovals() > 0) { setLive('approval'); }
    else if (state.runningTools > 0) { /* keep the current tool label */ }
    else if (state.busy) { setLive('thinking'); }
    else { setLive('idle'); }
  }

  /* The composer stays usable while nothing is configured — the server answers
   * 409 with a readable message — but the reason has to be on screen. */
  function composerHint() {
    if (state.busy) { return 'A turn is running — Send is disabled while the agent works.'; }
    if (state.configured === false) { return 'No model configured — open Settings to choose a provider and model.'; }
    return '';
  }

  function setBusy(busy) {
    state.busy = !!busy;
    dom.btnAbort.disabled = !state.busy;
    /* Abort belongs to the turn it stops: it appears next to Send while the
     * agent works, instead of sitting in the header greyed out all day. */
    dom.btnAbort.hidden = !state.busy;
    dom.send.disabled = state.busy;
    dom.hint.textContent = composerHint();
    refreshLive();
  }

  function paintAuto() {
    const on = state.autoApprove;
    dom.btnAuto.classList.toggle('on', on);
    dom.btnAuto.setAttribute('aria-pressed', on ? 'true' : 'false');
    dom.autoState.textContent = on ? 'on' : 'off';
    dom.btnAuto.title = on
      ? 'Approve all is ON: tool calls run without asking for this session.'
      : 'When on, every tool call runs without asking for this session.';
  }

  function autoApproveOn() { return state.autoApprove; }

  function setChips(status) {
    const configured = typeof status.configured === 'boolean' ? status.configured : state.configured;
    state.configured = configured;

    if (configured === false) {
      // Nothing is selected yet, so the provider/model/base URL chips would be
      // placeholders. One honest chip beats three empty ones.
      dom.chipProvider.textContent = 'not configured';
      dom.chipProvider.title = 'No model configured — open Settings.';
      dom.chipProvider.classList.add('chip-warn');
      dom.chipModel.hidden = true;
    } else {
      dom.chipProvider.classList.remove('chip-warn');
      dom.chipModel.hidden = false;
      if ('provider' in status) {
        dom.chipProvider.textContent = str(status.provider) || 'provider';
        dom.chipProvider.title = 'provider: ' + str(status.provider);
      }
      if ('model' in status || 'baseUrl' in status) {
        dom.chipModel.textContent = str(status.model) || 'model';
        // The base URL lives in this tooltip instead of a fourth chip: it is
        // long, rarely changes, and the settings panel shows it in full.
        dom.chipModel.title = 'model: ' + (str(status.model) || '—')
          + (status.baseUrl ? '\nbase URL: ' + str(status.baseUrl) : '');
      }
    }

    const id = str(status.sessionId);
    const count = Number(status.messageCount);
    if ('sessionId' in status || 'messageCount' in status) {
      const label = id ? clip(id, 26) : 'no session';
      dom.chipSession.textContent = label + (isFinite(count) ? ' · ' + count + ' msgs' : '');
      dom.chipSession.title = 'session: ' + (id || 'none') + (isFinite(count) ? ' (' + count + ' messages)' : '');
    }
  }

  /* The active workspace is one fact told in two places: the transcript's
   * placeholder and the tree's `active` mark. Both follow status.workspace —
   * the header used to hold the third copy, and it is gone. */
  function renderWorkspace(ws) {
    if (ws && typeof ws === 'object' && ('name' in ws || 'path' in ws)) {
      state.workspace = { name: str(ws.name), path: str(ws.path) };
    }
    refreshPlaceholder();
    noteActiveWorkspace();
  }

  /* status.workspace is authoritative; cwd is the same directory and only
   * stands in until that field arrives (or if an older server omits it). */
  function applyWorkspace(status) {
    // cwd and the workspace's own path differ only when -C overrode it: the page has to carry both
    // facts, because the placeholder is where the user finds out where the tools will run.
    state.cwdOverride = str(status.cwdOverride);
    if (status.workspace && typeof status.workspace === 'object') {
      renderWorkspace(status.workspace);
    } else if (!state.workspace && status.cwd) {
      renderWorkspace({ name: baseName(status.cwd), path: str(status.cwd) });
    } else {
      refreshPlaceholder();
    }
  }

  /* One line per tool: the panel is a legend, not the documentation. The full
   * first line of the description lives in the row's tooltip. */
  function renderTools(tools) {
    dom.toolList.textContent = '';
    if (!Array.isArray(tools) || !tools.length) {
      dom.toolList.appendChild(el('li', 'muted', 'No tools reported.'));
      return;
    }
    tools.forEach(function (tool) {
      const name = str(tool && tool.name);
      const desc = firstLine(str(tool && tool.description));
      const li = el('li');
      li.appendChild(el('span', 'name', name));
      if (desc) {
        li.appendChild(el('span', 'desc', desc));
        li.title = (name ? name + ' — ' : '') + desc;
      }
      dom.toolList.appendChild(li);
    });
  }

  // ------------------------------------------------------------ usage panel

  function fmtCount(v) {
    const n = Number(v);
    return v === null || v === undefined || !isFinite(n) ? '—' : n.toLocaleString();
  }

  /* null means "the provider reported nothing", which is not the same as zero:
   * a model that reports no cache figures must not look like a 0% hit rate. */
  function fmtCacheTokens(v) {
    return v === null || v === undefined ? 'n/a' : fmtCount(v);
  }

  function fmtHitRate(v) {
    if (v === null || v === undefined) { return 'n/a'; }
    const n = Number(v);
    if (!isFinite(n)) { return 'n/a'; }
    const pct = Math.max(0, n * 100);
    return (pct >= 10 ? pct.toFixed(0) : pct.toFixed(1)) + '%';
  }

  function fmtDuration(v) {
    const n = Number(v);
    if (!isFinite(n) || n < 0) { return '—'; }
    const s = n / 1000;
    if (s < 10) { return s.toFixed(1) + ' s'; }
    if (s < 60) { return Math.round(s) + ' s'; }
    /* Round once, then split: rounding the seconds independently of the
     * minutes printed "1m 60s" for anything just under a whole minute. */
    const total = Math.round(s);
    const m = Math.floor(total / 60);
    const rest = total % 60;
    return m + 'm ' + (rest < 10 ? '0' : '') + rest + 's';
  }

  function setUsageCell(node, text, bad) {
    node.textContent = text;
    node.classList.toggle('bad', !!bad);
  }

  /* Compact token counts: "12k / 200k" is a reading, six exact digits are not. Both numbers are
   * estimates and the row's label says so. */
  function fmtTokens(v) {
    const n = Number(v);
    if (!isFinite(n)) { return '—'; }
    if (n < 1000) { return String(Math.round(n)); }
    if (n < 1000000) { return (n / 1000).toFixed(n < 10000 ? 1 : 0) + 'k'; }
    return (n / 1000000).toFixed(1) + 'M';
  }

  /* The budget is only shown when one was configured; without it the row still answers "how big has
   * this conversation got", which is the question that precedes setting one. */
  function fmtContext(u) {
    const used = Number(u.contextTokens);
    if (!isFinite(used)) { return '—'; }
    const limit = Number(u.contextLimit);
    return isFinite(limit) && limit > 0 ? fmtTokens(used) + ' / ' + fmtTokens(limit) : fmtTokens(used);
  }

  /* The bar is a second reading of the same number, never a different one:
   * a reported 0% fills nothing (and still prints "0.0%"), an unreported rate
   * is an empty *unfilled* bar plus the hint below it — not a zero. */
  function setHitBar(rate) {
    const known = rate !== null && rate !== undefined && isFinite(Number(rate));
    const pct = known ? Math.min(100, Math.max(0, Number(rate) * 100)) : 0;
    dom.uHitFill.style.width = pct.toFixed(1) + '%';
    dom.uHitFill.classList.toggle('na', !known);
    dom.uHitBar.classList.toggle('na', !known);
    dom.uHitBar.setAttribute('aria-label', known
      ? 'cache hit rate ' + fmtHitRate(rate)
      : 'cache hit rate not reported');
  }

  function resetUsage() {
    state.usage = null;
    [dom.uTurns, dom.uSteps, dom.uIn, dom.uOut, dom.uCached, dom.uTools, dom.uErrors, dom.uElapsed,
      dom.uContext, dom.uCompactions]
      .forEach(function (node) { setUsageCell(node, '—', false); });
    dom.uHit.textContent = '—';
    dom.uHit.classList.add('na');
    setHitBar(null);
    dom.uHint.hidden = true;
    dom.uAlert.hidden = true;
    dom.uAlert.textContent = '';
    dom.uErrorsRow.classList.remove('bad');
  }

  /* One renderer for both sources — the `usage` event and status.usage. */
  function renderUsage(u) {
    if (!u || typeof u !== 'object') { resetUsage(); return; }
    state.usage = u;

    const noCache = u.cacheHitRate === null || u.cacheHitRate === undefined
      || u.cachedInputTokens === null || u.cachedInputTokens === undefined;
    const hit = fmtHitRate(u.cacheHitRate);
    dom.uHit.textContent = hit;
    dom.uHit.classList.toggle('na', hit === 'n/a');
    setHitBar(u.cacheHitRate);
    dom.uHint.hidden = !noCache;

    setUsageCell(dom.uTurns, fmtCount(u.turns), false);
    setUsageCell(dom.uSteps, fmtCount(u.steps), false);
    setUsageCell(dom.uIn, fmtCount(u.inputTokens), false);
    setUsageCell(dom.uOut, fmtCount(u.outputTokens), false);
    setUsageCell(dom.uCached, fmtCacheTokens(u.cachedInputTokens), false);
    setUsageCell(dom.uTools, fmtCount(u.toolCalls), false);
    // Its own row, and only when there has been one: a compaction costs real
    // tokens and is not a turn, so hiding it would make the token count look
    // wrong and folding it into steps would make "steps" mean two things.
    const compactions = Number(u.compactions);
    dom.uCompactionRow.hidden = !(isFinite(compactions) && compactions > 0);
    setUsageCell(dom.uCompactions, fmtCount(u.compactions), false);
    setUsageCell(dom.uElapsed, fmtDuration(u.elapsedMs), false);
    const limit = Number(u.contextLimit);
    const used = Number(u.contextTokens);
    setUsageCell(
      dom.uContext,
      fmtContext(u),
      isFinite(limit) && limit > 0 && isFinite(used) && used > limit);

    // A failing tool call is a signal, not a footnote: say it in words and in
    // colour instead of leaving it as a zero nobody looks at.
    const errors = Number(u.toolErrors);
    const bad = isFinite(errors) && errors > 0;
    setUsageCell(dom.uErrors, fmtCount(u.toolErrors), bad);
    dom.uErrorsRow.classList.toggle('bad', bad);
    dom.uAlert.hidden = !bad;
    dom.uAlert.textContent = bad
      ? errors + (errors === 1 ? ' tool call failed this session' : ' tool calls failed this session')
      : '';
  }

  /* A compaction replaced the conversation with its summary plus the newest
   * exchanges, so the transcript is rebuilt from the server rather than edited:
   * `loadHistory` is the one place that knows how to render a session. */
  async function onCompacted(ev) {
    const before = Number(ev.beforeTokens);
    const after = Number(ev.afterTokens);
    // The transcript is cleared first: the conversation really has changed, and `loadHistory` appends
    // (it is written for a session *switch*, where the id changed and noteSession cleared for it). Left
    // as-is, the kept exchanges would be drawn a second time underneath the ones already on screen.
    clearTranscript();
    const saved = Number(ev.savedPercent);
    // "? → ? tokens" would be worse than saying nothing: a missing number is
    // omitted rather than rendered as a placeholder that looks like data.
    const detail = (isFinite(before) && isFinite(after))
      ? ' — ' + fmtCount(before) + ' → ' + fmtCount(after) + ' tokens (estimated)'
      : '';
    appendNotice(
      'Compacted: ' + fmtCount(ev.summarised) + ' earlier message(s) became a summary, '
      + fmtCount(ev.kept) + ' kept verbatim'
      + (isFinite(saved) ? ' (' + saved + '% of the replaced part saved)' : '')
      + detail + '. The full conversation is still in ' + str(ev.source));
    await loadHistory(str(state.status && state.status.sessionId));
  }

  /* The button is a request like any other: the server decides whether the
   * conversation can be compacted, and a refusal is shown where a refusal
   * belongs — in the transcript, not in a dialog. */
  async function compactNow() {
    if (state.busy) { return; }
    // Disabled rather than relabelled: the button carries a glyph and a label
    // node, and writing textContent would throw both away. The busy state is
    // visible from the transcript the compaction is about to rewrite.
    dom.btnCompact.disabled = true;
    try {
      await request('/api/compact', { method: 'POST' });
      // The events carry the outcome; this only stops the button looking stuck.
    } catch (err) {
      appendError(str(err && err.message) || 'Compaction failed.');
    }
    dom.btnCompact.disabled = false;
  }

  function applyStatus(status) {
    if (!status || typeof status !== 'object') { return; }
    state.status = status;
    applyWorkspace(status);
    if ('sessionId' in status) { noteSession(str(status.sessionId)); }
    // The server says which conversations are working. The session on screen is
    // reported by `busy`; every *other* one is named in `running`, and together
    // they are what the tree marks.
    if (Array.isArray(status.running)) {
      const shown = str(status.sessionId);
      const next = new Set();
      status.running.forEach(function (id) {
        const owner = str(id);
        if (owner && owner !== shown) { next.add(owner); }
      });
      state.runningSessions = next;
    }
    setChips(status);
    if (Array.isArray(status.tools)) { renderTools(status.tools); }
    if (status.usage && typeof status.usage === 'object') { renderUsage(status.usage); }
    if (typeof status.autoApprove === 'boolean') {
      state.autoApprove = status.autoApprove;
      paintAuto();
    }
    // The picker above the composer is the same fact as the chips, told where
    // the user is about to type; both follow the server's status.
    if (composerPicker) { composerPicker.setStatus(status); }
    expireStaleApprovals();
    // Requests that are still outstanding are part of the server's answer about this conversation.
    // Applied only when no replay is in flight: a prompt added now would be wiped by the transcript
    // being rebuilt from history a moment later, which is the very loss this exists to prevent.
    if (!state.historyInFlight && !state.replaying) {
      syncApprovals(status.approvals);
    } else {
      state.pendingApprovalsSync = Array.isArray(status.approvals) ? status.approvals : null;
    }
    setBusy(!!status.busy || pendingApprovals() > 0);
    paintRunningRows();
  }

  /* ------------------------------------------------------------------ other conversations

   * One page, one stream, every conversation on the server. This page renders
   * exactly one of them — the one on screen — and the rest are tracked here so
   * the tree can mark the rows that are working. Nothing about a foreign turn is
   * rendered into the transcript: its prose belongs to another conversation, and
   * its `done` must not unstick this composer. */

  function isRunning(id) {
    const owner = str(id);
    return owner !== '' && state.runningSessions.has(owner);
  }

  function markRunning(id, running) {
    const owner = str(id);
    if (owner === '') { return; }
    if (running) {
      if (state.runningSessions.has(owner)) { return; }
      state.runningSessions.add(owner);
    } else {
      if (!state.runningSessions.delete(owner)) { return; }
    }
    paintRunningRows();
  }

  /* Re-marks the rows already in the tree rather than reloading the list: the
   * user's turn is what is running, and a full refresh would fight whatever they
   * are doing to the sidebar while it is. */
  function paintRunningRows() {
    const rows = document.querySelectorAll('.session-row');
    for (let i = 0; i < rows.length; i++) {
      const row = rows[i];
      const button = row.querySelector('.session-item');
      if (!button) { continue; }
      const label = button.querySelector('.session-title');
      const id = label ? str(label.dataset.sessionId) : '';
      if (!id) { continue; }
      const running = isRunning(id);
      row.classList.toggle('running', running);
      const existing = button.querySelector('.session-running');
      if (running && !existing) {
        const mark = el('span', 'session-running');
        mark.title = 'Running a turn — open it to watch, or stop it with the ■';
        mark.setAttribute('aria-label', 'running');
        button.querySelector('.session-name').appendChild(mark);
      } else if (!running && existing) {
        existing.remove();
      }
    }
  }

  /* Stops a turn running in a conversation this page is not showing. The id is
   * the server's own (the tree's rows carry it), so nothing is guessed. */
  async function stopSession(id) {
    const owner = str(id);
    if (owner === '') { return; }
    try {
      const res = await request('/api/abort?id=' + encodeURIComponent(owner), { method: 'POST' });
      if (res && res.aborted) {
        markRunning(owner, false);
      }
    } catch (err) {
      appendError('Could not stop ' + owner + ': ' + str(err && err.message));
    }
    // One refresh, not two. `reorderSessionsAfterTurn` re-reads the list itself when the workspace is
    // expanded, and invalidating first made the sidebar pay for that read twice — on a busy machine
    // that is two parses of every session file, which is exactly the cost this page can least afford
    // while a turn is streaming.
    invalidateSessions(owner);
    reorderSessionsAfterTurn();
  }

  async function refreshStatus() {
    try {
      const status = await request('/api/status');
      applyStatus(status);
    } catch (err) {
      appendError('Could not read status: ' + err.message);
    }
  }

  // -------------------------------------------------------------- markdown

  /* Assistant prose is markdown and arrives a few characters at a time, so the
   * renderer has to be exact *and* incremental.
   *
   * Exact: blocks are parsed from the whole source on every flush — string work
   * only — and a block is rendered from its own source alone. A half-arrived
   * message therefore renders as the message so far, and nothing that was drawn
   * early has to be taken back later.
   *
   * Incremental: blocks whose source did not change keep the nodes they already
   * have. An answer that grows by one word re-creates the one block it is still
   * writing, not the whole message, so the scroll position, a selection and the
   * paragraph being read all survive a long turn.
   *
   * This is a subset of CommonMark, chosen as what an agent actually writes:
   * headings, paragraphs, fenced code, lists (nested, with GFM task boxes),
   * blockquotes, tables, rules, and inline code, emphasis, strikethrough, links
   * and autolinks. Three refusals are decisions rather than omissions: raw HTML
   * is never interpreted — `<div>` in an answer is shown as the text it is,
   * because a renderer that passes HTML through is an HTML injection with extra
   * steps — a link's scheme is filtered, so `javascript:` in an answer stays
   * text instead of becoming something this page runs, and an image is rendered
   * as the link it also is, because fetching it would tell a third party that
   * this conversation is on screen.
   */

  // -------------------------------------------------------- markdown: parse

  const MD_ITEM_RE = /^([ \t]*)([-*+]|\d{1,9}[.)])([ \t]+)(.*)$/;
  const MD_HEADING_RE = /^ {0,3}(#{1,6})(?:[ \t]+(.*?))?[ \t]*$/;
  const MD_HR_RE = /^ {0,3}([-*_])[ \t]*(?:\1[ \t]*){2,}$/;
  const MD_QUOTE_RE = /^ {0,3}>/;
  const MD_FENCE_RE = /^ {0,3}(`{3,}|~{3,})[ \t]*([^ \t`]*)/;
  const MD_TABLE_DELIM_RE = /^ {0,3}\|?[ \t]*:?-+:?[ \t]*(\|[ \t]*:?-+:?[ \t]*)*\|?[ \t]*$/;
  const MD_PIPE_RE = /(?<!\\)\|/;

  /* Four columns to a tab, which is all an indentation check needs. */
  function mdWidth(text) { return str(text).replace(/\t/g, '    ').length; }

  /* How far a line is indented — its leading whitespace, not its whole width.
   * The distinction is the difference between "this line belongs to the item
   * above" and "this line happens to be long": measuring the width made the
   * quote, the code block and the table after a list become items of it. */
  function mdIndent(line) { return mdWidth(/^[ \t]*/.exec(str(line))[0]); }

  function mdDropIndent(line, width) {
    let used = 0;
    let i = 0;
    while (i < line.length && used < width) {
      const c = line.charAt(i);
      if (c === ' ') { used += 1; }
      else if (c === '\t') { used += 4; }
      else { break; }
      i += 1;
    }
    return line.slice(i);
  }

  function mdKey(lines, from, to) { return lines.slice(from, to).join('\n'); }

  function mdOrdered(marker) { return /^[0-9]/.test(str(marker)); }

  /* A line that opens a block, and so ends the paragraph or the quote above it.
   * An unclosed fenced block counts: its lines are code, not prose. */
  function mdStartsBlock(line) {
    return MD_FENCE_RE.test(line) || MD_HEADING_RE.test(line) || MD_HR_RE.test(line)
      || MD_QUOTE_RE.test(line) || MD_ITEM_RE.test(line);
  }

  /* Source → block list. Each block carries the raw source it was built from as
   * its `key`: the renderer compares keys to decide what still stands. */
  function mdBlocks(src) {
    const lines = str(src).replace(/\r\n?/g, '\n').split('\n');
    const out = [];
    let i = 0;
    while (i < lines.length) {
      const start = i;
      const line = lines[i];
      if (!line.trim()) { i += 1; continue; }

      const fence = MD_FENCE_RE.exec(line);
      if (fence) {
        const marker = fence[1].charAt(0);
        const closing = new RegExp('^ {0,3}' + marker + '{' + fence[1].length + ',}[ \\t]*$');
        const body = [];
        i += 1;
        while (i < lines.length && !closing.test(lines[i])) { body.push(lines[i]); i += 1; }
        if (i < lines.length) { i += 1; }   // the closing fence belongs to the block
        out.push({
          type: 'code', lang: str(fence[2]), text: body.join('\n'), key: mdKey(lines, start, i)
        });
        continue;
      }

      const heading = MD_HEADING_RE.exec(line);
      if (heading) {
        out.push({
          type: 'heading', level: heading[1].length, text: str(heading[2]),
          key: mdKey(lines, start, i + 1)
        });
        i += 1;
        continue;
      }

      if (MD_HR_RE.test(line)) {
        out.push({ type: 'hr', key: mdKey(lines, start, i + 1) });
        i += 1;
        continue;
      }

      if (MD_QUOTE_RE.test(line)) {
        const body = [];
        // `>`-prefixed lines, plus a plain line that continues the last one the
        // way markdown lets a lazy paragraph carry on.
        while (i < lines.length && lines[i].trim()
          && (MD_QUOTE_RE.test(lines[i]) || !mdStartsBlock(lines[i]))) {
          body.push(lines[i].replace(/^ {0,3}>[ \t]?/, ''));
          i += 1;
        }
        out.push({ type: 'quote', blocks: mdBlocks(body.join('\n')), key: mdKey(lines, start, i) });
        continue;
      }

      const table = mdTableAt(lines, i);
      if (table) { out.push(table.block); i = table.next; continue; }

      if (MD_ITEM_RE.test(line)) { const list = mdList(lines, i); out.push(list.block); i = list.next; continue; }

      const body = [line];
      i += 1;
      while (i < lines.length && lines[i].trim() && !mdStartsBlock(lines[i]) && !mdTableAt(lines, i)) {
        body.push(lines[i]);
        i += 1;
      }
      out.push({ type: 'para', text: body.join('\n'), key: mdKey(lines, start, i) });
    }
    return out;
  }

  function mdCells(line) {
    let s = str(line).trim();
    if (s.charAt(0) === '|') { s = s.slice(1); }
    if (s.charAt(s.length - 1) === '|') { s = s.slice(0, -1); }
    // An escaped pipe is content, not a column break.
    return s.split(MD_PIPE_RE).map(function (cell) { return cell.trim().replace(/\\\|/g, '|'); });
  }

  function mdAlign(cell) {
    const left = cell.charAt(0) === ':';
    const right = cell.charAt(cell.length - 1) === ':';
    if (left && right) { return 'center'; }
    if (right) { return 'right'; }
    if (left) { return 'left'; }
    return '';
  }

  /* A table needs a pipe in the header row and a delimiter row of the same
   * width — that width check is what keeps "a | b" over a line of dashes from
   * being read as a table nobody wrote. */
  function mdTableAt(lines, i) {
    if (i + 1 >= lines.length) { return null; }
    if (lines[i].indexOf('|') < 0) { return null; }
    if (!MD_TABLE_DELIM_RE.test(lines[i + 1]) || lines[i + 1].indexOf('-') < 0) { return null; }
    const header = mdCells(lines[i]);
    const align = mdCells(lines[i + 1]).map(mdAlign);
    if (!header.length || align.length !== header.length) { return null; }
    const rows = [];
    let j = i + 2;
    while (j < lines.length && lines[j].trim() && lines[j].indexOf('|') >= 0) {
      rows.push(mdCells(lines[j]));
      j += 1;
    }
    return { block: { type: 'table', header: header, align: align, rows: rows, key: mdKey(lines, i, j) }, next: j };
  }

  /* One list: the items at one indentation, each with the lines that belong to
   * it — a wrapped continuation, a nested list, a fenced block. Nesting is not
   * special-cased, because an item's own source is parsed recursively: for
   * "- a" over "  - b" the inner parse simply finds a list.
   *
   * A line belongs to the item above it when it is *indented* to that item's
   * content column. A lazily continued line at column 0 — markdown allows one —
   * is deliberately not joined: after a list, a column-0 line is far more often
   * the next block of the answer than a wrapped bullet, and guessing "wrapped"
   * once nests everything that follows into the last item. */
  function mdList(lines, start) {
    const open = MD_ITEM_RE.exec(lines[start]);
    const indent = mdWidth(open[1]);
    const contentIndent = indent + mdWidth(open[2]) + mdWidth(open[3]);
    const ordered = mdOrdered(open[2]);
    const startNumber = ordered ? (parseInt(open[2], 10) || 1) : 1;
    const items = [];
    let loose = false;
    let i = start;
    while (i < lines.length) {
      const line = lines[i];
      if (!line.trim()) {
        // A blank line either separates the items of one loose list or ends the
        // list; the next line that is not blank is what decides.
        let j = i;
        while (j < lines.length && !lines[j].trim()) { j += 1; }
        if (j >= lines.length) { i = j; break; }
        const nextItem = MD_ITEM_RE.exec(lines[j]);
        if ((nextItem && mdWidth(nextItem[1]) === indent && mdOrdered(nextItem[2]) === ordered)
            || mdIndent(lines[j]) >= contentIndent) {
          loose = true;
          i = j;
          continue;
        }
        break;
      }
      const item = MD_ITEM_RE.exec(line);
      // A different marker *kind* starts a new list: "- a" over "1. b" is a
      // bullet followed by a numbered item, not a two-item bullet list.
      if (item && mdWidth(item[1]) === indent && mdOrdered(item[2]) === ordered) {
        items.push(item[4]);
        i += 1;
        continue;
      }
      if (mdIndent(line) < contentIndent || !items.length) { break; }
      items[items.length - 1] += '\n' + mdDropIndent(line, contentIndent);
      i += 1;
    }
    return {
      block: {
        type: 'list', ordered: ordered, startNumber: startNumber, loose: loose,
        items: items.map(mdItem), key: mdKey(lines, start, i)
      },
      next: i
    };
  }

  function mdItem(src) {
    const task = /^\[([ xX])\][ \t]+/.exec(src);
    return {
      task: !!task,
      checked: !!task && task[1] !== ' ',
      blocks: mdBlocks(task ? src.slice(task[0].length) : src)
    };
  }

  // ------------------------------------------------------- markdown: render

  function mdAppend(node, text) { node.appendChild(mdInline(text)); }

  function mdRenderBlocks(blocks) {
    const out = [];
    blocks.forEach(function (block) { out.push(mdRenderBlock(block)); });
    return out;
  }

  function mdRenderBlock(block) {
    switch (block.type) {
      case 'heading': {
        const h = el('h' + block.level, 'md-h');
        mdAppend(h, block.text);
        return h;
      }
      case 'code': {
        const code = el('code', 'md-code');
        code.textContent = block.text;
        const pre = el('pre', 'md-pre');
        pre.appendChild(code);
        const wrap = el('div', 'md-code-wrap');
        // The info string is a label, not content: it is what tells a reader
        // whether this is a shell transcript or JSON.
        if (block.lang) { wrap.appendChild(el('div', 'md-code-lang', block.lang)); }
        wrap.appendChild(pre);
        return wrap;
      }
      case 'hr':
        return el('hr', 'md-hr');
      case 'quote': {
        const quote = el('blockquote', 'md-quote');
        mdRenderBlocks(block.blocks).forEach(function (child) { quote.appendChild(child); });
        return quote;
      }
      case 'list': {
        const list = el(block.ordered ? 'ol' : 'ul', 'md-list');
        if (block.ordered && block.startNumber !== 1) { list.start = block.startNumber; }
        block.items.forEach(function (item) { list.appendChild(mdRenderItem(item, block.loose)); });
        return list;
      }
      case 'table': {
        const table = el('table', 'md-table');
        const head = el('thead');
        head.appendChild(mdRenderRow('th', block.header, block.align));
        table.appendChild(head);
        if (block.rows.length) {
          const body = el('tbody');
          block.rows.forEach(function (row) { body.appendChild(mdRenderRow('td', row, block.align)); });
          table.appendChild(body);
        }
        // A wide table scrolls inside itself instead of stretching the column.
        const wrap = el('div', 'md-table-wrap');
        wrap.appendChild(table);
        return wrap;
      }
      default: {
        const p = el('p', 'md-p');
        mdAppend(p, block.text);
        return p;
      }
    }
  }

  function mdRenderRow(tag, cells, align) {
    const row = el('tr');
    for (let i = 0; i < cells.length; i++) {
      const cell = el(tag, align[i] ? 'md-align-' + align[i] : null);
      mdAppend(cell, cells[i]);
      row.appendChild(cell);
    }
    return row;
  }

  /* A tight item is inline content: wrapping it in <p> would give every bullet
   * the spacing of a paragraph. A loose one (blank lines between the items)
   * is written as paragraphs, because that is what the author asked for. */
  function mdRenderItem(item, loose) {
    const li = el('li', 'md-li');
    if (item.task) {
      const box = el('input', 'md-task');
      box.type = 'checkbox';
      box.checked = item.checked;
      box.disabled = true;   // the transcript is a record, not a form
      li.appendChild(box);
    }
    item.blocks.forEach(function (child) {
      if (child.type === 'para' && !loose) { mdAppend(li, child.text); }
      else { li.appendChild(mdRenderBlock(child)); }
    });
    return li;
  }

  // ------------------------------------------------------- markdown: inline

  const MD_PUNCT_RE = /[\\`*_{}[\]()#+\-.!>~|]/;
  const MD_MARKS = ['**', '__', '~~', '*', '_'];

  /* Text → a fragment of nodes. The buffer is what keeps this cheap: plain runs
   * are collected and emitted as one text node instead of one node per mark.
   *
   * `inLink` is on while rendering a label: a link's own text is not scanned for
   * links again, because an autolink there would be an anchor inside an anchor
   * (which a browser silently unpicks) and, for a bare URL used as its own
   * label, would recurse without end. */
  function mdInline(text, inLink) {
    const src = str(text);
    const frag = document.createDocumentFragment();
    let buf = '';
    let i = 0;

    function flush() {
      if (buf) { frag.appendChild(document.createTextNode(buf)); buf = ''; }
    }
    function take(node) { flush(); frag.appendChild(node); }

    while (i < src.length) {
      const c = src.charAt(i);

      if (c === '\\' && i + 1 < src.length) {
        const next = src.charAt(i + 1);
        if (next === '\n') { buf = buf.replace(/ +$/, ''); take(el('br')); i += 2; continue; }
        if (MD_PUNCT_RE.test(next)) { buf += next; i += 2; continue; }
        buf += c;
        i += 1;
        continue;
      }

      if (c === '\n') {
        // Two trailing spaces or a backslash is a hard break; a bare newline is
        // the soft break markdown renders as a space.
        if (/ {2,}$/.test(buf)) { buf = buf.replace(/ +$/, ''); take(el('br')); }
        else if (buf) { buf += ' '; }
        i += 1;
        continue;
      }

      if (c === '`') {
        const span = mdCodeSpan(src, i);
        if (span) { take(span.node); i = span.next; continue; }
      }

      if (!inLink && c === '<') {
        const auto = mdAutolink(src, i);
        if (auto) { take(auto.node); i = auto.next; continue; }
      }

      if (!inLink && (c === '[' || (c === '!' && src.charAt(i + 1) === '['))) {
        const link = mdLink(src, i);
        if (link) { take(link.node); i = link.next; continue; }
      }

      if (!inLink && c === 'h' && !/[0-9A-Za-z]/.test(src.charAt(i - 1))
        && /^https?:\/\//.test(src.slice(i, i + 8))) {
        const bare = mdBareUrl(src, i);
        if (bare) { take(bare.node); i = bare.next; continue; }
      }

      if (c === '*' || c === '_' || c === '~') {
        const marked = mdEmphasis(src, i, inLink);
        if (marked) { take(marked.node); i = marked.next; continue; }
      }

      buf += c;
      i += 1;
    }
    flush();
    return frag;
  }

  /* An unclosed backtick is literal text, which is what a stream mid-code-span
   * looks like for a few milliseconds. */
  function mdCodeSpan(text, i) {
    const open = /^`+/.exec(text.slice(i))[0];
    const end = text.indexOf(open, i + open.length);
    if (end < 0) { return null; }
    let body = text.slice(i + open.length, end).replace(/\n/g, ' ');
    // One space of padding on each side is not content, per the spec.
    if (body.length > 2 && body.charAt(0) === ' ' && body.charAt(body.length - 1) === ' ') {
      body = body.slice(1, -1);
    }
    return { node: el('code', 'md-code-inline', body), next: end + open.length };
  }

  function mdAutolink(text, i) {
    const end = text.indexOf('>', i + 1);
    if (end < 0) { return null; }
    const inner = text.slice(i + 1, end);
    if (!/^(https?:\/\/|mailto:)\S+$/i.test(inner)) { return null; }
    return { node: mdAnchor(inner, inner), next: end + 1 };
  }

  function mdLink(text, i) {
    const image = text.charAt(i) === '!';
    const open = image ? i + 1 : i;
    const close = text.indexOf(']', open + 1);
    if (close < 0 || text.charAt(close + 1) !== '(') { return null; }
    const end = mdLinkEnd(text, close + 2);
    if (end < 0) { return null; }
    let target = text.slice(close + 2, end).trim();
    const title = /^(\S+)\s+["'(].*["')]$/.exec(target);
    if (title) { target = title[1]; }
    let label = text.slice(open + 1, close);
    // An image would make the page fetch somebody else's URL the moment an
    // answer arrives — a beacon the reader never asked for — so it is rendered
    // as the link it also is.
    if (image && !label) { label = target; }
    return { node: mdAnchor(label, target), next: end + 1 };
  }

  /* The destination may contain balanced parentheses — `…/Foo_(bar)` is a URL a
   * model will paste — so the closing one is found by counting, not by taking
   * the first `)` seen. */
  function mdLinkEnd(text, from) {
    let depth = 0;
    for (let i = from; i < text.length; i += 1) {
      const c = text.charAt(i);
      if (c === '\\') { i += 1; continue; }
      if (c === '(') { depth += 1; continue; }
      if (c === ')') {
        if (depth === 0) { return i; }
        depth -= 1;
      }
    }
    return -1;
  }

  function mdBareUrl(text, i) {
    const match = /^https?:\/\/[^\s<>()[\]"'`]+/.exec(text.slice(i));
    if (!match) { return null; }
    let url = match[0];
    // Sentence punctuation is not part of the URL: a link ends before the dot
    // in "see https://example.dev."
    while (url.length && /[.,;:!?]$/.test(url)) { url = url.slice(0, -1); }
    if (!url) { return null; }
    return { node: mdAnchor(url, url), next: i + url.length };
  }

  function mdFindClose(text, from, mark) {
    let at = text.indexOf(mark, from);
    while (at >= 0) {
      const before = text.charAt(at - 1);
      // The closer cannot follow a space, cannot be a longer run of the same
      // character and cannot re-close an opener it just followed.
      if (before && !/\s/.test(before) && before !== mark && text.charAt(at + mark.length) !== mark.charAt(0)) {
        return at;
      }
      at = text.indexOf(mark, at + mark.length);
    }
    return -1;
  }

  function mdEmphasis(text, i, inLink) {
    for (let k = 0; k < MD_MARKS.length; k++) {
      const mark = MD_MARKS[k];
      if (text.slice(i, i + mark.length) !== mark) { continue; }
      const after = text.charAt(i + mark.length);
      // A delimiter never opens on a space, and a run of the same character is
      // not two delimiters.
      if (!after || after === mark.charAt(0) || /\s/.test(after)) { continue; }
      // An underscore inside a word is part of the word: `snake_case` is not
      // `snake` in italics, which matters in an answer full of identifiers.
      if (mark.charAt(0) === '_' && /[0-9A-Za-z]/.test(text.charAt(i - 1))) { continue; }
      const at = mdFindClose(text, i + mark.length, mark);
      if (at < 0) { continue; }
      const inner = text.slice(i + mark.length, at);
      if (!inner.trim()) { continue; }
      const tag = mark === '~~' ? 'del' : (mark.length === 2 ? 'strong' : 'em');
      const node = el(tag);
      node.appendChild(mdInline(inner, inLink));
      return { node: node, next: at + mark.length };
    }
    return null;
  }

  function mdSafeUrl(url) {
    // The scheme test has to see the string the browser will see, and `a.href =` goes through the
    // URL parser: it removes tab and newline *anywhere*, and leading/trailing C0 controls or spaces.
    // Testing "java\nscript:" as written would pass the filter and then be parsed as javascript:,
    // so the normalisation happens first and the cleaned target is what gets used.
    const raw = str(url)
      .replace(/[\t\n\r]/g, '')
      .replace(/^[\u0000-\u0020]+|[\u0000-\u0020]+$/g, '');
    // Only the schemes a link in an answer can mean. A scheme-less target is a
    // relative one, which cannot leave the page's own origin.
    if (/^[a-z][a-z0-9+.-]*:/i.test(raw) && !/^(https?|mailto):/i.test(raw)) { return ''; }
    return raw;
  }

  function mdAnchor(label, url) {
    const safe = mdSafeUrl(url);
    if (!safe) { return document.createTextNode(label); }
    const a = el('a', 'md-a');
    a.appendChild(mdInline(label, true));
    a.href = safe;
    a.target = '_blank';
    a.rel = 'noopener noreferrer';
    return a;
  }

  // ----------------------------------------------------- markdown: streaming

  /* How many leading blocks the previous render already produced. Identical
   * source means identical nodes, because a block is rendered from itself
   * alone — so the answer keeps everything above the block it is still writing. */
  function mdKeep(prev, next) {
    if (!prev) { return 0; }
    let n = 0;
    while (n < prev.length && n < next.length && prev[n].key === next[n].key) { n += 1; }
    return n;
  }

  function renderMarkdown(md, node) {
    const blocks = mdBlocks(md.source);
    const keep = mdKeep(md.blocks, blocks);
    for (let i = md.nodes.length - 1; i >= keep; i -= 1) {
      if (md.nodes[i].parentNode === node) { node.removeChild(md.nodes[i]); }
    }
    md.nodes.length = keep;
    for (let i = keep; i < blocks.length; i += 1) {
      const child = mdRenderBlock(blocks[i]);
      md.nodes.push(child);
      node.appendChild(child);
    }
    md.blocks = blocks;
  }

  /* Deltas are coalesced exactly like plain text: the parse runs once per frame
   * at most, and a message nobody is waiting on (a background tab) still gets
   * its timer flush. */
  function queueMarkdown(block, delta) {
    if (!block || !delta) { return; }
    const node = assistantNode(block, 'text');
    if (!block.md) { block.md = { source: '', blocks: null, nodes: [] }; }
    block.md.source += delta;
    mdQueues.set(block.md, node);
    scheduleFlush();
  }

  // ----------------------------------------------------------- transcript

  function appendLine(cls, text) {
    return appendToTranscript(el('p', cls, text));
  }

  function appendError(text) { return appendLine('ev ev-error', text); }

  /* Server notices (a refused New session, provider retries, a trimmed context) are
   * transcript lines. The Usage panel is owned by `usage` events and
   * status.usage, so a notice never writes there. */
  function appendNotice(text) { return appendLine('ev ev-notice', text); }

  /* A compaction's summary, drawn as a card of its own.
   *
   * It is not a notice: a notice is about something that happened at the edges, and this is what the
   * older part of the conversation now *is*. It is collapsed by default because it is long and it is
   * not what the reader is usually looking for — but it opens, because "did it keep what mattered"
   * is a question only the text can answer, and the line above it says where the full conversation
   * still is. */
  function appendSummary(ev) {
    const text = str(ev.text);
    if (!text) { return null; }
    const covers = Number(ev.covers);
    const details = el('details', 'ev ev-summary');
    details.open = false;
    const summaryLine = el('summary', 'ev-summary-head');
    summaryLine.appendChild(el('span', 'ev-summary-glyph', '⤓'));
    summaryLine.appendChild(el('span', 'ev-summary-title', 'Compacted conversation'));
    if (isFinite(covers) && covers > 0) {
      summaryLine.appendChild(el('span', 'ev-summary-count',
        fmtCount(covers) + (covers === 1 ? ' message summarised' : ' messages summarised')));
    }
    details.appendChild(summaryLine);
    const body = el('div', 'ev-summary-body');
    body.appendChild(el('div', 'ev-summary-note',
      'This is what the earlier turns came to. The full conversation is still on disk — ask the model '
      + 'to read it back if a detail is missing.'));
    const rendered = el('div', 'ev-summary-text');
    rendered.textContent = text;
    body.appendChild(rendered);
    details.appendChild(body);
    dom.transcript.appendChild(details);
    scrollToBottom();
    return details;
  }

  function assistantBlock() {
    if (state.block && state.block.root.parentNode === dom.transcript) { return state.block; }
    const root = el('div', 'ev ev-assistant');
    appendToTranscript(root);
    state.block = { root: root, reasoning: null, answer: null };
    return state.block;
  }

  function assistantNode(block, kind) {
    const key = kind === 'reasoning' ? 'reasoning' : 'answer';
    if (!block[key]) {
      // Reasoning is the model talking to itself and is shown as the plain text
      // it is; only the answer, the thing being handed to a reader, is markdown.
      const node = el('div', key === 'reasoning' ? 'msg-reasoning' : 'msg-answer md');
      block.root.appendChild(node);
      block[key] = node;
    }
    return block[key];
  }

  function appendUser(text) {
    const row = el('div', 'ev ev-user');
    row.appendChild(el('div', 'bubble', text));
    state.block = null;
    appendToTranscript(row);
  }

  function clearTranscript() {
    flushText();
    queues.clear();
    mdQueues.clear();
    dom.transcript.textContent = '';
    showPlaceholder();
    state.block = null;
    state.toolCards.clear();
    state.approvals.clear();
    // The pending sync belongs to the conversation being left; its requests are not this one's.
    state.pendingApprovalsSync = null;
    state.runningTools = 0;
    state.lastEventId = -1;   // dedupe scope is one transcript
    state.stick = true;
    dom.jump.hidden = true;
    resetUsage();
  }

  // -------------------------------------------------------- history replay

  /* The session id is the page's notion of "what the transcript shows". History
   * is fetched exactly once per id — on the transition to it — so the status
   * events that repeat the current id (turn end, reconnect, a refused New
   * session) can never fetch or render the same conversation twice. */
  function noteSession(id) {
    if (id === state.sessionId) { return; }
    state.sessionId = id;
    // A workspace switch hands out a brand-new, empty session; its history is
    // an empty screen, so the id is recorded and the pane left alone. The
    // resume that follows the switch is what fetches a conversation.
    if (tree.throwaway) { return; }
    clearTranscript();
    state.historyPromise = loadHistory(id);
  }

  const HISTORY_TIMEOUT_MS = 8000;
  let historySeq = 0;

  async function loadHistory(sessionId) {
    const seq = ++historySeq;
    state.historyInFlight = true;
    state.pendingLive = [];
    const safety = setTimeout(function () {
      // A hung history request must not hold the live stream hostage.
      if (seq === historySeq) {
        historySeq += 1;
        state.historyInFlight = false;
        flushPendingLive();
      }
    }, HISTORY_TIMEOUT_MS);
    try {
      const data = await request('/api/history');
      if (seq !== historySeq) { return; }          // superseded by a newer load
      if (data && typeof data === 'object' && 'sessionId' in data
        && str(data.sessionId) !== state.sessionId) {
        return;                                    // the server moved on
      }
      renderReplay(data && Array.isArray(data.events) ? data.events : []);
      // A fresh page gets its numbers from the same response that restored the
      // conversation, so the panel is populated before the first turn.
      if (data && data.usage && typeof data.usage === 'object') { renderUsage(data.usage); }
    } catch (err) {
      if (seq === historySeq) {
        appendError('Could not load history: ' + str(err && err.message));
      }
    } finally {
      clearTimeout(safety);
      if (seq === historySeq) {
        state.historyInFlight = false;
        flushPendingLive();
      }
    }
  }

  /* How much of a restored conversation is drawn before the page is usable.
   *
   * A long session is thousands of tool cards, and building them all at once blocks the thread for
   * long enough that a turn running behind the switch looks frozen — the cost is paid on the way in,
   * which is exactly when the user is watching. So a page older than this is drawn in two passes:
   * the most recent events now, the rest as soon as the browser is idle. Nothing is dropped, and the
   * reader can start scrolling immediately.
   *
   * The number is events, not exchanges, because that is what the cost is proportional to. It is
   * generous — a few hundred tool cards is a fast draw — and bounded so the first pass cannot itself
   * become the stall it exists to avoid. */
  const REPLAY_TAIL_EVENTS = 300;

  /* A history split into what to draw now and what to fill in behind it.
   *
   * The cut lands on a user message, which is the boundary the renderer already treats as one
   * (`appendUser` closes the open block). Splitting mid-turn would hand one assistant turn to both
   * passes, and the second pass would append its reasoning *after* the answer the first one drew.
   *
   * An exchange longer than the limit is kept whole: half an answer is worse than a slow screen, and
   * the events after that user message all belong to that turn. */
  function splitReplay(events) {
    const clean = (Array.isArray(events) ? events : []).filter(function (ev) {
      return !!ev && typeof ev === 'object';
    });
    // The last user message at or before the budget's end is where the tail starts. Walking back from
    // the end finds the *newest* such boundary, so the tail is as small as the limit allows and still
    // whole. When there is no budget to spend — a short history — the tail is everything.
    if (clean.length <= REPLAY_TAIL_EVENTS) {
      return { head: [], tail: clean, hidden: 0 };
    }
    let start = 0;
    for (let i = clean.length - REPLAY_TAIL_EVENTS; i >= 0; i--) {
      if (str(clean[i].type) === 'user') {
        start = i;
        break;
      }
    }
    return { head: clean.slice(0, start), tail: clean.slice(start), hidden: start };
  }

  /* Replay goes through the same dispatch() the live stream uses, so a restored
   * conversation renders exactly like a live one. Replay events carry no SSE
   * id, so they never touch state.lastEventId: the reconnect dedupe keeps
   * working, and live events always land *after* the snapshot they follow. */
  function renderReplay(events) {
    const split = splitReplay(events);

    state.replaying = true;
    try {
      if (!split.tail.length) { showPlaceholder(); return; }
      dropPlaceholder();
      replayPass(split.tail);
      flushText();
    } finally {
      state.replaying = false;
    }
    state.stick = true;
    scrollToBottom();
    dom.jump.hidden = true;
    refreshLive();

    if (split.head.length) {
      // The rest, once the browser has nothing better to do. The page is already usable and the
      // newest part is already on screen, so this cannot make the switch feel slower.
      loadOlderReplay(split.head, split.hidden);
    }
  }

  /** Runs one replay pass: the events through the renderer, skipping the live lifecycle ones. */
  function replayPass(events) {
    events.forEach(function (ev) {
      const type = str(ev.type);
      if (type === 'status' || type === 'done') { return; }   // live lifecycle, not content
      try {
        dispatch(ev);
      } catch (err) {
        appendError('UI error replaying ' + type + ': ' + str(err && err.message ? err.message : err));
      }
    });
  }

  /* Draws the earlier part of a conversation *above* what is already on screen, one exchange at a
   * time in the browser's idle time.
   *
   * Three things make it invisible, and each of them is the reason the machinery exists:
   *
   *  - it runs when the browser is idle, so a turn streaming behind it keeps painting;
   *  - it draws into a detached fragment and inserts it as one node, so nothing is ever half attached;
   *  - it holds the reader's place by the height it just added. Inserting above the viewport
   *    otherwise slides the text being read down the screen by however much was added — which is the
   *    whole reason "fill it in behind" needs care rather than a single insert.
   *
   * The chunk boundary is a user message: an assistant turn rendered by two chunks would end up with
   * its reasoning after its answer. */
  function loadOlderReplay(head, hidden) {
    const banner = el('div', 'ev ev-note replay-note',
      hidden + (hidden === 1 ? ' earlier event' : ' earlier events') + ' — loading…');
    dom.transcript.insertBefore(banner, dom.transcript.firstChild);

    const seq = ++replaySeq;
    let end = head.length;   // exclusive: everything from `end` on has been drawn

    const idle = window.requestIdleCallback
      ? function (fn) { return window.requestIdleCallback(fn, { timeout: 250 }); }
      : function (fn) { return setTimeout(function () { fn({ timeRemaining: function () { return 8; } }); }, 0); };

    const step = function (deadline) {
      // Another switch superseded this replay: its transcript is gone, and this one must stop rather
      // than append an old conversation into a new one.
      if (seq !== replaySeq) { return; }
      if (end <= 0) {
        if (banner.parentNode) { banner.parentNode.removeChild(banner); }
        return;
      }
      // Back to the previous boundary, so one exchange is drawn per pass.
      let from = end - 1;
      while (from > 0 && str(head[from].type) !== 'user') { from--; }

      const fragment = document.createDocumentFragment();
      const holder = el('div');
      const previousTranscript = dom.transcript;
      // The renderer appends to the transcript; for this pass it appends into a detached holder and
      // the result is moved up as one node.
      replayTarget = holder;
      const wasReplaying = state.replaying;
      state.replaying = true;
      try {
        replayPass(head.slice(from, end));
        flushText();
      } finally {
        state.replaying = wasReplaying;
        replayTarget = null;
      }
      void previousTranscript;
      while (holder.firstChild) { fragment.appendChild(holder.firstChild); }

      const anchor = dom.transcript.scrollHeight;
      const before = dom.transcript.scrollTop;
      dom.transcript.insertBefore(fragment, banner.nextSibling === null ? null : banner.nextSibling);
      const grew = dom.transcript.scrollHeight - anchor;
      if (grew > 0) { dom.transcript.scrollTop = before + grew; }

      end = from;
      if (end > 0 && deadline.timeRemaining() > 2) {
        step(deadline);            // still time in this frame
      } else {
        idle(step);                // or come back when the browser is free
      }
    };
    idle(step);
  }

  function flushPendingLive() {
    const queued = state.pendingLive;
    state.pendingLive = [];
    queued.forEach(processMessage);
    // A status that arrived while the transcript was being rebuilt carried the outstanding
    // approvals; now that the replay is done, drawing them cannot be undone by it.
    if (state.pendingApprovalsSync) {
      const waiting = state.pendingApprovalsSync;
      state.pendingApprovalsSync = null;
      syncApprovals(waiting);
    }
  }

  // ------------------------------------------------------------ tool cards

  function toolCard(id, name, phase) {
    const existing = state.toolCards.get(id);
    // Ids are stable per call, but a server may reuse them for a later call:
    // an `end` always belongs to the card on screen, a `start` only belongs to
    // it while that card is still running.
    if (existing && (phase !== 'start' || existing.running)) { return existing; }

    const root = el('div', 'ev ev-tool running');
    const head = el('div', 'tool-head');
    const spinner = el('span', 'tool-spinner');
    const mark = el('span', 'tool-mark');
    mark.hidden = true;
    const nameEl = el('span', 'tool-name', str(name));
    const summaryEl = el('span', 'tool-summary');
    const metaEl = el('span', 'tool-meta');
    head.appendChild(spinner);
    head.appendChild(mark);
    head.appendChild(nameEl);
    head.appendChild(summaryEl);
    head.appendChild(metaEl);
    root.appendChild(head);

    const card = {
      root: root, spinner: spinner, mark: mark, summary: summaryEl,
      meta: metaEl, output: null, moreRow: null, expanded: false, full: '', clipped: '',
      running: phase === 'start', counted: false
    };
    state.toolCards.set(id, card);
    appendToTranscript(root);
    return card;
  }

  function renderToolOutput(card, raw) {
    const text = str(raw).replace(/\s+$/, '');
    if (!text) { return; }
    const lines = text.split('\n');
    card.full = text;
    card.clipped = lines.slice(0, 6).join('\n');

    const atBottom = nearBottom();
    const pre = el('pre', 'tool-output', card.clipped);
    card.output = pre;
    card.root.appendChild(pre);

    if (lines.length > 6) {
      const row = el('div', 'tool-output-more');
      const toggle = el('button', 'link', 'show all');
      toggle.type = 'button';
      toggle.addEventListener('click', function () {
        const wasAtBottom = nearBottom();
        card.expanded = !card.expanded;
        pre.textContent = card.expanded ? card.full : card.clipped;
        toggle.textContent = card.expanded ? 'show less' : 'show all';
        settleScroll(wasAtBottom);
      });
      row.appendChild(toggle);
      row.appendChild(el('span', 'muted', '+' + (lines.length - 6) + ' more lines'));
      card.moreRow = row;
      card.root.appendChild(row);
    }
    settleScroll(atBottom);
  }

  function onTool(ev) {
    const id = str(ev.id) || 'tool-' + str(ev.name);
    const start = ev.state === 'start';
    const card = toolCard(id, ev.name, ev.state);
    if (ev.summary) { card.summary.textContent = clip(firstLine(ev.summary), 160); }

    if (start) {
      // A replayed start must not make the live indicator claim a tool is
      // running right now.
      if (!state.replaying && !card.counted) { card.counted = true; state.runningTools += 1; }
      card.running = true;
      card.root.classList.remove('failed', 'ok');
      card.spinner.hidden = false;
      card.mark.hidden = true;
      card.meta.textContent = 'running…';
      if (!state.replaying) { setLive('running', ev.name); }
    } else {
      if (card.counted) { card.counted = false; state.runningTools = Math.max(0, state.runningTools - 1); }
      const ok = ev.ok !== false;
      card.running = false;
      card.spinner.hidden = true;
      card.mark.hidden = false;
      card.mark.textContent = ok ? '✓' : '✗';
      card.mark.className = 'tool-mark ' + (ok ? 'ok' : 'fail');
      card.root.classList.remove('running');
      card.root.classList.toggle('failed', !ok);
      card.root.classList.toggle('ok', ok);
      const elapsed = msLabel(ev.elapsedMs);
      card.meta.textContent = (ok ? 'done' : 'failed') + (elapsed ? ' · ' + elapsed : '');
      if (!card.output) { renderToolOutput(card, ev.output); }
      if (!state.replaying) { refreshLive(); }
    }
  }

  // -------------------------------------------------------- approval cards

  function approvalRecord(id) { return state.approvals.get(id); }

  function onApproval(ev) {
    const id = str(ev.id);
    let rec = approvalRecord(id);
    // Ids may come back around for a later call; only an *open* record can
    // absorb a repeat, otherwise the loop would wait forever behind a card the
    // page never showed.
    if (rec && rec.resolved) { rec = null; }
    if (!rec) {
      rec = renderApproval(id, ev.title, ev.detail);
    }

    if (autoApproveOn() && !rec.resolved) {
      rec.remember.checked = true;
      rec.answer(true);
      rec.stateEl.textContent = 'Approved automatically (Approve all)';
    }
    refreshLive();
    updateJump();
  }

  /* Draws one outstanding request and registers it. Split out of onApproval because the status
   * carries the same three fields, and that is how a request survives the page looking at another
   * conversation: the answer lives on the server, the prompt lives here, and this is the one place
   * that knows how to build it. */
  function renderApproval(id, title, detail) {
    const root = el('div', 'ev ev-approval pending');
    const head = el('div', 'approval-head');
    head.appendChild(el('span', 'approval-flag', 'approval needed'));
    head.appendChild(el('span', 'approval-title', str(title) || 'Tool call'));
    root.appendChild(head);

    const detailNode = el('pre', 'approval-detail', str(detail) || '(no detail)');
    root.appendChild(detailNode);

    const actions = el('div', 'approval-actions');
    const rememberLabel = el('label', 'remember');
    const remember = el('input');
    remember.type = 'checkbox';
    rememberLabel.appendChild(remember);
    rememberLabel.appendChild(el('span', null, 'always allow'));
    const stateEl = el('span', 'approval-state', 'waiting for an answer');
    const deny = el('button', 'btn danger', 'Deny');
    deny.type = 'button';
    const approve = el('button', 'btn primary', 'Approve');
    approve.type = 'button';
    actions.appendChild(rememberLabel);
    actions.appendChild(deny);
    actions.appendChild(approve);
    actions.appendChild(stateEl);
    root.appendChild(actions);

    const rec = {
      id: id, root: root, approve: approve, deny: deny,
      remember: remember, stateEl: stateEl, resolved: false,
      at: Date.now()
    };

    rec.settle = function (allow, label, cls) {
      rec.resolved = true;
      rec.root.classList.remove('pending');
      rec.root.classList.add(allow ? 'approved' : 'denied');
      rec.approve.disabled = true;
      rec.deny.disabled = true;
      rec.remember.disabled = true;
      rec.stateEl.textContent = label;
      rec.stateEl.className = 'approval-state ' + cls;
      refreshLive();
      updateJump();
      setBusy(state.busy);
    };

    rec.answer = function (allow) {
      if (rec.resolved) { return; }
      const useRemember = allow && rec.remember.checked;
      rec.settle(allow, allow ? (useRemember ? 'Approved (always allow)' : 'Approved') : 'Denied',
        allow ? 'ok' : 'bad');
      answerApproval(id, allow, useRemember);
    };

    approve.addEventListener('click', function () { rec.answer(true); });
    deny.addEventListener('click', function () { rec.answer(false); });

    state.approvals.set(id, rec);
    appendToTranscript(root);
    return rec;
  }

  /* Requests the server says are still outstanding for the conversation on screen.
   *
   * This is what makes an approval survive looking away. The request is blocked in memory on the
   * server, not written to the conversation, so replaying the history cannot bring it back — and
   * without this the prompt vanished when the user switched away, leaving abort as the only way out
   * of a turn that was still perfectly answerable. */
  function syncApprovals(waiting) {
    if (!Array.isArray(waiting)) { return; }
    const shown = new Set();
    waiting.forEach(function (entry) {
      const id = str(entry.id);
      if (id === '') { return; }
      shown.add(id);
      const existing = approvalRecord(id);
      if (!existing || existing.resolved) {
        renderApproval(id, entry.title, entry.detail);
      }
    });
    // A record this page is holding that the server no longer knows about was answered elsewhere, or
    // timed out: it is closed here rather than left as a card that can never be answered.
    state.approvals.forEach(function (rec, id) {
      if (!rec.resolved && !shown.has(id)) {
        rec.settle(false, 'No longer waiting (answered elsewhere or timed out)', 'bad');
      }
    });
  }

  function onApprovalClosed(ev) {
    const rec = approvalRecord(str(ev.id));
    if (!rec || rec.resolved) { return; }
    const allow = ev.allow === true;
    rec.settle(allow, allow ? 'Approved' : 'Denied', allow ? 'ok' : 'bad');
  }

  async function answerApproval(id, allow, remember) {
    try {
      await postJSON('/api/approval', { id: id, allow: !!allow, remember: !!remember });
    } catch (err) {
      const rec = approvalRecord(id);
      if (rec) {
        rec.resolved = false;
        rec.root.classList.remove('approved', 'denied');
        rec.root.classList.add('pending');
        rec.approve.disabled = false;
        rec.deny.disabled = false;
        rec.remember.disabled = false;
        rec.stateEl.textContent = 'Could not send the answer — try again';
        rec.stateEl.className = 'approval-state bad';
      }
      appendError('approval: ' + err.message);
      refreshLive();
      updateJump();
    }
  }

  function approvePending() {
    state.approvals.forEach(function (rec) {
      if (!rec.resolved) {
        rec.remember.checked = true;
        rec.answer(true);
      }
    });
  }

  function onDone(ev) {
    flushText();
    const block = state.block;
    const finalText = str(ev && ev.finalText);
    if (block && !hasContent(block.answer) && finalText && block.root.parentNode === dom.transcript) {
      queueMarkdown(block, finalText);
      flushText();
    }
    state.block = null;
    const aborted = !!(ev && ev.aborted);
    appendToTranscript(el('div', 'ev ev-done', aborted ? 'turn aborted' : 'turn complete'));
    setBusy(false);
    setLive('idle');
    dom.input.focus();
    reorderSessionsAfterTurn();
  }


  // ------------------------------------------------------------- dispatch

  /* Anything that lands between two runs of prose closes the open assistant
   * block, so later deltas start a new block *below* that item instead of
   * silently appending to a block that is already higher up the transcript. */
  function breakBlock() { state.block = null; }

  function dispatch(ev) {
    switch (str(ev.type)) {
      case 'status': applyStatus(ev); break;
      case 'user': appendUser(str(ev.text)); break;
      case 'text': queueMarkdown(assistantBlock(), str(ev.delta)); break;
      case 'reasoning': queueText(assistantNode(assistantBlock(), 'reasoning'), str(ev.delta)); break;
      case 'tool': breakBlock(); onTool(ev); break;
      case 'approval': breakBlock(); onApproval(ev); break;
      case 'approval-closed': onApprovalClosed(ev); break;
      case 'notice': breakBlock(); appendNotice(str(ev.text)); break;
      case 'summary': breakBlock(); appendSummary(ev); break;
      case 'usage': renderUsage(ev); break;
      case 'compacted':
        // The transcript on screen is now a different conversation, so it is
        // re-read rather than patched: the summary and the kept exchanges are the
        // server's to describe, and guessing at the splice is how a page ends up
        // showing history that never existed.
        onCompacted(ev);
        break;
      case 'done': onDone(ev); break;
      case 'error':
        flushText();
        breakBlock();
        appendError(str(ev.message));
        setBusy(false);
        break;
      default: break;
    }
  }

  function handleMessage(msg) {
    state.lastEventAt = Date.now();
    // Live frames that arrive while a history snapshot is being rendered are
    // held back and replayed after it, so the snapshot is always the base and
    // nothing is lost or interleaved out of order.
    if (state.historyInFlight) { state.pendingLive.push(msg); return; }
    processMessage(msg);
  }

  function processMessage(msg) {
    // The SSE id is what makes the transcript idempotent across reconnects:
    // the server may replay frames the page has already rendered. Replayed
    // history never passes through here, so it cannot consume live ids.
    const rawId = str(msg.lastEventId) || str(msg.id);
    const idNum = parseInt(rawId, 10);
    const hasId = isFinite(idNum);
    if (hasId && idNum <= state.lastEventId) { return; }

    let ev = null;
    try { ev = JSON.parse(msg.data); } catch (err) { return; }
    if (!ev || typeof ev !== 'object') { return; }

    // The high-water mark advances for every event, including the ones this
    // page does not render: it is a position in one shared stream, and skipping
    // it for another conversation's turn would make the next reconnect replay
    // frames already delivered.
    if (hasId) { state.lastEventId = idNum; }

    // One stream carries every conversation on the server, so an event is
    // rendered only when it belongs to the transcript on screen. A turn running
    // in another session is exactly what the user is allowed to leave running:
    // its prose must not appear here, and its `done` must not unstick this
    // page's composer or claim this conversation just answered.
    const owner = str(ev.sessionId);
    if (owner && owner !== state.sessionId) {
      noteForeignTurn(ev);
      return;
    }

    try {
      dispatch(ev);
    } catch (err) {
      appendError('UI error handling ' + str(ev.type) + ': ' + (err && err.message ? err.message : err));
    }
  }

  /* A turn in another conversation, seen from here. It is not rendered — the
   * transcript belongs to one session — but three things about it are worth
   * acting on: the page is told which sessions are working, the tree marks the
   * rows so the user can find the turn again, and a finished turn refreshes the
   * list (that conversation's title and position just changed). */
  function noteForeignTurn(ev) {
    const owner = str(ev.sessionId);
    switch (str(ev.type)) {
      case 'user':
      case 'text':
      case 'reasoning':
      case 'tool':
      case 'notice':
        markRunning(owner, true);
        break;
      case 'done':
      case 'error':
        markRunning(owner, false);
        reorderSessionsAfterTurn();
        break;
      default:
        break;
    }
  }

  // ------------------------------------------------------------------ sse

  function showConnPill() {
    dom.connPill.hidden = false;
    if (!state.busy) { setLive('offline'); }
  }

  function hideConnPill() {
    dom.connPill.hidden = true;
    refreshLive();
  }

  function connect() {
    if (state.source) { state.source.close(); }
    const source = new EventSource('/api/events');
    state.source = source;

    source.onopen = function () {
      hideConnPill();
      state.lastEventAt = Date.now();
      const placeholder = dom.transcript.querySelector('.placeholder');
      if (placeholder) { placeholder.textContent = placeholderText(); }
      if (state.everOpen) {
        refreshStatus();
        // A restart can have added or forgotten workspaces, and the counts in
        // the tree follow the sessions that were written while we were away.
        loadWorkspaces();
      }
      state.everOpen = true;
    };

    source.onerror = function () {
      showConnPill();
      if (source.readyState === EventSource.CLOSED && !state.retryTimer) {
        // The browser only auto-retries while the stream is CONNECTING; a
        // fatal close (server restarted or bad response) needs a new source.
        state.retryTimer = setTimeout(function () {
          state.retryTimer = 0;
          connect();
        }, 3000);
      }
    };

    source.onmessage = handleMessage;
    // The server's keep-alive, which arrives as a named event because a bare `data` frame would be
    // dispatched to onmessage and looked like a real event of an unknown type. This is the only
    // signal that reaches the page while a turn is waiting on something — a model call, or an approval
    // nobody has answered — and without it the 20-second stale timer below fires on a healthy
    // connection: the page reconnects underneath a prompt that is still open, and the prompt appears
    // to flicker and then vanish. Measured, in a session waiting on an approval.
    source.addEventListener('ping', function () {
      state.lastEventAt = Date.now();
      // A ping proves the connection is alive, which is also the moment to take the reconnecting pill
      // down if it is up.
      hideConnPill();
    });
  }

  /* How long a stream may stay silent before the page stops trusting its own
   * idea of "a turn is running". A quiet server and a dead connection look
   * identical from here, and the cost of guessing wrong is a composer that never
   * comes back. */
  const STALE_EVENT_MS = 20000;

  function streamLooksStale() {
    return Date.now() - state.lastEventAt > STALE_EVENT_MS;
  }

  /* Re-reads the server's truth and, if the stream is gone, reopens it. Called
   * when the page comes back to the front — the moment a throttled tab's
   * timers start running again — and before a message is swallowed on the
   * strength of a stale `busy`. */
  function resync() {
    if (!state.source || state.source.readyState !== EventSource.OPEN) { connect(); }
    refreshStatus();
  }

  // ----------------------------------------------------------------- theme

  /* Three states on one control, cycled Light → Dark → System. The palette
   * itself is CSS, driven by data-theme on <html>; this only picks the value
   * and keeps the label honest. A manual choice always beats the OS: the OS is
   * consulted exactly while the choice is "System". */
  const THEME_KEY = 'ccj.theme';
  const THEME_CYCLE = ['light', 'dark', 'system'];
  const THEME_ICON = { light: '☀', dark: '☾', system: '◐' };
  const THEME_LABEL = { light: 'Light', dark: 'Dark', system: 'System' };
  const systemTheme = window.matchMedia('(prefers-color-scheme: light)');

  let themePref = 'system';

  function nextTheme(pref) {
    return THEME_CYCLE[(THEME_CYCLE.indexOf(pref) + 1) % THEME_CYCLE.length];
  }

  /* localStorage can be unavailable (private windows, blocked storage); the
   * page must still render in a theme, so a refusal falls back to System. */
  function storedTheme() {
    let value = '';
    try { value = str(localStorage.getItem(THEME_KEY)); } catch (err) { value = ''; }
    return THEME_CYCLE.indexOf(value) >= 0 ? value : 'system';
  }

  function visibleTheme(pref) {
    return pref === 'system' ? (systemTheme.matches ? 'light' : 'dark') : pref;
  }

  /* index.html resolves the same thing inline before the first paint; doing it
   * again here is idempotent and keeps one function as the source of truth.
   *
   * Two attributes, one decision: `data-theme` on <html> is what this page's own
   * rules and the pre-paint script read, and `data-ds-dark-theme` on <body> is
   * what the vendored DeepSeek Harness token sheet reads. They are set together
   * here and nowhere else. */
  function applyTheme() {
    const theme = visibleTheme(themePref);
    const root = document.documentElement;
    root.setAttribute('data-theme', theme);
    root.setAttribute('data-theme-pref', themePref);
    root.style.colorScheme = theme;
    if (theme === 'dark') {
      document.body.setAttribute('data-ds-dark-theme', '');
    } else {
      document.body.removeAttribute('data-ds-dark-theme');
    }
    dom.themeIcon.textContent = THEME_ICON[themePref];
    dom.themeLabel.textContent = THEME_LABEL[themePref];
    dom.btnTheme.title = 'Theme: ' + THEME_LABEL[themePref]
      + (themePref === 'system' ? ' (follows the desktop)' : '')
      + ' — click for ' + THEME_LABEL[nextTheme(themePref)];
    dom.btnTheme.setAttribute('aria-label',
      'Theme: ' + THEME_LABEL[themePref] + '. Switch to ' + THEME_LABEL[nextTheme(themePref)] + '.');
  }

  function setTheme(pref) {
    themePref = pref;
    try { localStorage.setItem(THEME_KEY, pref); } catch (err) { /* the choice still holds this visit */ }
    applyTheme();
  }

  function initTheme() {
    themePref = storedTheme();
    applyTheme();
    dom.btnTheme.addEventListener('click', function () { setTheme(nextTheme(themePref)); });
    // The desktop can change under a System choice — sunset, a schedule, a
    // different monitor — so follow the media query instead of snapshotting it.
    systemTheme.addEventListener('change', function () {
      if (themePref === 'system') { applyTheme(); }
    });
  }

  // ----------------------------------------------------------- wallpaper

  /* The pictures the server offers, rotated behind the page. The list comes from
   * the server's directory; the choice and the position come from this browser,
   * and a server with no pictures hides the control instead of offering a button
   * that does nothing.
   *
   * A rotation is a cut, not a fade: the next picture is fetched while the
   * current one is on screen, so switching never shows an empty frame. */
  const WALLPAPER_KEY = 'ccj.wallpaper';
  const WALLPAPER_INDEX_KEY = 'ccj.wallpaper.index';
  const WALLPAPER_EVERY_MS = 5 * 60 * 1000;

  const wallpapers = { names: [], index: 0, on: false };

  function wallpaperVisible() {
    return wallpapers.on && wallpapers.names.length > 0;
  }

  function wallpaperUrl(name) {
    return '/wallpaper/' + encodeURIComponent(name);
  }

  function paintWallpaper() {
    if (!wallpaperVisible()) {
      document.body.classList.remove('wallpaper');
      document.body.style.removeProperty('--ccj-wallpaper');
      return;
    }
    document.body.classList.add('wallpaper');
    document.body.style.setProperty(
      '--ccj-wallpaper', 'url("' + wallpaperUrl(wallpapers.names[wallpapers.index]) + '")');
    if (wallpapers.names.length > 1) {
      // Warm the next one up: a rotation should never show an empty frame.
      const next = new Image();
      next.src = wallpaperUrl(wallpapers.names[(wallpapers.index + 1) % wallpapers.names.length]);
    }
  }

  function setWallpaper(on) {
    wallpapers.on = !!on && wallpapers.names.length > 0;
    writeStored(WALLPAPER_KEY, wallpapers.on ? 'on' : 'off');
    dom.btnWallpaper.setAttribute('aria-pressed', wallpapers.on ? 'true' : 'false');
    dom.btnWallpaper.classList.toggle('on', wallpapers.on);
    paintWallpaper();
  }

  function nextWallpaper() {
    if (wallpapers.names.length === 0) { return; }
    wallpapers.index = (wallpapers.index + 1) % wallpapers.names.length;
    writeStored(WALLPAPER_INDEX_KEY, String(wallpapers.index));
    paintWallpaper();
  }

  async function initWallpaper() {
    let names = [];
    try {
      const body = await request('/api/wallpapers');
      if (body && Array.isArray(body.wallpapers)) {
        names = body.wallpapers.filter(function (name) { return typeof name === 'string'; });
      }
    } catch (err) {
      names = [];   // no directory, no control; that is not an error worth showing
    }
    wallpapers.names = names;
    if (names.length === 0) { return; }

    const stored = Math.floor(Number(readStored(WALLPAPER_INDEX_KEY)));
    wallpapers.index = isFinite(stored) && stored >= 0 && stored < names.length ? stored : 0;
    // On until this browser says otherwise: a directory of pictures is a decision somebody
    // already made, and the switch is one click away in the header.
    wallpapers.on = readStored(WALLPAPER_KEY) !== 'off';

    dom.btnWallpaper.hidden = false;
    dom.btnWallpaper.setAttribute('aria-pressed', wallpapers.on ? 'true' : 'false');
    dom.btnWallpaper.classList.toggle('on', wallpapers.on);
    dom.btnWallpaper.addEventListener('click', function (event) {
      // Shift-click is the manual step: rotation is the point, so a plain click
      // is the switch and this is how to see the next one now.
      if (event.shiftKey) { nextWallpaper(); return; }
      setWallpaper(!wallpapers.on);
    });
    paintWallpaper();
    setInterval(function () {
      if (wallpaperVisible()) { nextWallpaper(); }
    }, WALLPAPER_EVERY_MS);
  }

  // -------------------------------------------------------------- sidebar

  /* The sidebar is the workspace tree: one node per workspace, its sessions
   * folded open underneath. Two rules make it safe to browse:
   *
   *   - folding a node only *reads* — `GET /api/sessions?workspace=<name>`,
   *     which the server answers without switching anything — so looking at
   *     another workspace can never move the conversation;
   *   - switching is its own explicit gesture: `Use` on a row, or clicking a
   *     session, which means "go there and open that".
   *
   * The collapsed state and the open nodes are both remembered, so a reload
   * comes back to the same view. The right panel collapses by `display: none`
   * on a flex item; this one uses the same rule from the other side, so the
   * two toggles read as a pair.
   *
   * Deleting names the workspace it happens in — `DELETE /api/session` and
   * `DELETE /api/sessions` take a `workspace` parameter — so a row under a
   * folded node is cleared where it lives, and the page stays in whatever
   * workspace it was working in. */
  const SIDEBAR_KEY = 'ccj.sidebar.collapsed';
  const EXPANDED_KEY = 'ccj.tree.expanded';

  /* Why the transcript is empty when we emptied it: the server publishes a
   * notice for a delete, but it is published *before* the new session's status
   * clears the pane, so the user is left looking at a blank transcript with no
   * reason for it. */
  const FRESH_SESSION = ' ccj started a fresh session, so the transcript is empty.';

  function readStored(key) {
    try { return str(localStorage.getItem(key)); } catch (err) { return ''; }
  }

  function writeStored(key, value) {
    try { localStorage.setItem(key, value); } catch (err) { /* still true for this visit */ }
  }

  function storedExpanded() {
    const set = new Set();
    let parsed = null;
    try { parsed = JSON.parse(readStored(EXPANDED_KEY)); } catch (err) { parsed = null; }
    if (Array.isArray(parsed)) {
      parsed.forEach(function (name) {
        const clean = str(name);
        if (clean) { set.add(clean); }
      });
    }
    return set;
  }

  const tree = {
    payload: null,        // the last GET /api/workspaces answer
    expanded: storedExpanded(),
    sessions: new Map(),  // workspace name -> {status, items, error}
    selected: '',         // the row the sidebar's own actions point at
    activeName: '',       // the workspace the page is actually working in
    error: '',            // why the workspace list could not be read
    busy: false,          // a delete is in flight
    throwaway: false,     // a switch's empty session: record its id, skip its history
    deletes: []           // the armed two-step controls of the current render
  };

  function saveExpanded() {
    const names = [];
    tree.expanded.forEach(function (name) { names.push(name); });
    writeStored(EXPANDED_KEY, JSON.stringify(names));
  }

  function workspaceItems() {
    return workspaceItemsOf(tree.payload);
  }

  function workspaceItemsOf(payload) {
    const list = payload && Array.isArray(payload.workspaces) ? payload.workspaces : [];
    return list.filter(function (item) { return !!item && typeof item === 'object'; });
  }

  function workspaceItem(name) {
    const wanted = str(name);
    let found = null;
    workspaceItems().forEach(function (item) {
      if (!found && str(item.name) === wanted) { found = item; }
    });
    return found;
  }

  function sessionsIn(payload) {
    return payload && Array.isArray(payload.sessions) ? payload.sessions : [];
  }

  function isStatusPayload(res) {
    return !!res && typeof res === 'object'
      && ('sessionId' in res || 'busy' in res || 'workspace' in res);
  }

  function activeSessionId() { return str(state.status && state.status.sessionId); }

  /* Only the placeholder counts as empty: anything else in the pane is content
   * the user can already read. */
  function transcriptEmpty() {
    return !dom.transcript.querySelector(':not(.placeholder)');
  }

  // ------------------------------------------------------ sidebar: messages

  /* A refusal and its outcome are shown inside the sidebar, next to the control
   * that produced them — the dialogs that used to own these lines are gone. */
  function sidebarMessages(note, error) {
    dom.sidebarNote.textContent = str(note);
    dom.sidebarNote.hidden = !note;
    dom.sidebarAlert.textContent = str(error);
    dom.sidebarAlert.hidden = !error;
  }

  function sidebarNote(text) { sidebarMessages(text, ''); }
  function sidebarError(text) { sidebarMessages('', text); }
  function clearSidebarMessages() { sidebarMessages('', ''); }

  // ----------------------------------------------------- sidebar: collapse

  /* Below 720px both panels are sheets over the transcript rather than columns
   * beside it, and the scrim is what makes "tap outside" mean something. A
   * sheet is never left open behind a wider window: the layouts are different
   * enough that the state does not carry across. */
  const SHEET_WIDTH = '(max-width: 720px)';

  function sheetsAreSheets() { return window.matchMedia(SHEET_WIDTH).matches; }

  function paintScrim() {
    dom.scrim.hidden = !(dom.sidebar.classList.contains('open') || dom.side.classList.contains('open'));
  }

  function closeSheets() {
    if (!dom.sidebar.classList.contains('open') && !dom.side.classList.contains('open')) {
      return;
    }
    dom.sidebar.classList.remove('open');
    dom.side.classList.remove('open');
    dom.btnSide.setAttribute('aria-expanded', 'false');
    dom.btnSidebar.setAttribute('aria-expanded', 'false');
    paintScrim();
  }

  /* Choosing something in a sheet is what the sheet was opened for, so it goes
   * away by itself: leaving it up would hide the conversation it was used to
   * choose. Nothing on a wide window: the panels there are columns. */
  function closeSheetsWhenNarrow() {
    if (sheetsAreSheets()) { closeSheets(); }
  }

  /* Below 900px the transcript needs the width more than the tree does, so a
   * narrow window starts collapsed; a stored choice still wins on a wide one. */
  function initialSidebarCollapsed() {
    if (window.matchMedia('(max-width: 900px)').matches) { return true; }
    return readStored(SIDEBAR_KEY) === '1';
  }

  function setSidebarCollapsed(collapsed) {
    dom.sidebar.classList.toggle('collapsed', collapsed);
    const expanded = collapsed ? 'false' : 'true';
    dom.btnSidebar.setAttribute('aria-expanded', expanded);
    dom.sidebarCollapse.setAttribute('aria-expanded', expanded);
    writeStored(SIDEBAR_KEY, collapsed ? '1' : '0');
  }

  /* One control, two layouts: a column that folds away on a wide window, a sheet
   * that comes and goes on a phone. The two classes are kept exclusive so the
   * state is readable from either one. */
  function toggleSidebar() {
    if (sheetsAreSheets()) {
      const open = !dom.sidebar.classList.contains('open');
      dom.sidebar.classList.toggle('open', open);
      dom.sidebar.classList.toggle('collapsed', !open);
      // One sheet at a time: the details pane is the other one.
      if (open) { dom.side.classList.remove('open'); }
      dom.btnSidebar.setAttribute('aria-expanded', open ? 'true' : 'false');
      dom.sidebarCollapse.setAttribute('aria-expanded', open ? 'true' : 'false');
      paintScrim();
      return;
    }
    setSidebarCollapsed(!dom.sidebar.classList.contains('collapsed'));
  }

  function initSidebar() {
    setSidebarCollapsed(initialSidebarCollapsed());
    dom.btnSidebar.addEventListener('click', toggleSidebar);
    dom.sidebarCollapse.addEventListener('click', toggleSidebar);
    dom.scrim.addEventListener('click', closeSheets);
    paintScrim();
  }

  // --------------------------------------------------------- sidebar: tree

  /* Focus is inside the tree whenever a user acts on it, and every render
   * rebuilds these nodes from the server's answer. Each control therefore
   * carries the key it can be found by again, so folding a node or deleting a
   * row never drops the keyboard onto <body> mid-task. */
  function focusKey() {
    const node = document.activeElement;
    return node && node.dataset ? str(node.dataset.focusKey) : '';
  }

  function focusByKey(key) {
    const wanted = str(key);
    if (!wanted) { return false; }
    const nodes = dom.wsTree.querySelectorAll('[data-focus-key]');
    for (let i = 0; i < nodes.length; i += 1) {
      if (str(nodes[i].dataset.focusKey) === wanted) { nodes[i].focus(); return true; }
    }
    return false;
  }

  function cachedSessions(name) { return tree.sessions.get(str(name)); }

  /* The selection is what `Delete all` acts on, so changing it also disarms
   * that question: an armed control must never be retargeted at a workspace
   * the user picked after asking. */
  function selectWorkspace(name) {
    if (tree.selected === name) { return; }
    tree.selected = name;
    deleteAllControl.reset();
  }

  /* One session: what was asked, when, and the same two-step delete the dialogs
   * used, so the gesture did not change when the list moved.
   *
   * The row is read, not scanned: the first user message is the label, because a
   * list of `20260912-030245-7b27` says nothing about which task a session was.
   * A session with nothing asked yet has no title to show, so it falls back to
   * its id — the same string the chip in the header carries.
   *
   * Clicking *anywhere* on the row opens it. The delete controls sit inside the
   * li and not inside the button, so the gap between them would otherwise be a
   * dead zone that looks exactly like the rest of the row; `rowOpensOn` gives
   * that area the same destination, while leaving room for a modifier. */
  function sessionRow(name, item, index, activeId, stamp) {
    const id = str(item.id);
    const current = id !== '' && id === activeId;
    // The row's own truth about running: the server's list says which sessions
    // are working, and `paintRunningRows` merges in what this page learned from
    // the stream (a turn started or aborted since that list was fetched).
    const running = !!item.running;
    const li = el('li', 'session-row' + (current ? ' current' : '') + (running ? ' running' : ''));
    if (current) { li.setAttribute('aria-current', 'true'); }

    const btn = el('button', 'session-item');
    btn.type = 'button';
    btn.dataset.focusKey = 's:' + name + ':' + id;
    btn.title = id + (current ? ' — the session on screen' : '')
      + (running ? '\nthis session is running a turn' : '')
      + '\n' + str(name) + ' · ' + timeLabel(item.lastModified);
    const label = firstLine(str(item.title)) || firstLine(str(item.preview));
    const name_ = el('span', 'session-name');
    if (stamp) { name_.appendChild(el('span', 'session-id', stamp)); }
    const titleNode = el('span', 'session-title', clip(label || id, 200));
    // The id the row stands for, read back by the running mark: the tree is
    // re-rendered from the server's list, and the mark has to be able to find
    // the row again without re-fetching anything.
    titleNode.dataset.sessionId = id;
    name_.appendChild(titleNode);
    if (running) {
      // A dot, not a spinner: the turn is happening in another conversation, and
      // the mark is what leads the user back to it rather than a decoration.
      const mark = el('span', 'session-running');
      mark.title = 'Running a turn — open it to watch, or stop it with the ×';
      mark.setAttribute('aria-label', 'running');
      name_.appendChild(mark);
    }
    btn.appendChild(name_);
    btn.addEventListener('click', function () { openWorkspaceSession(name, id, btn); });

    // The button is what gets disabled while the resume is in flight, so the
    // click that lands on the row's empty strip reports the same control.
    rowOpensOn(li, function () { openWorkspaceSession(name, id, btn); });

    const actions = el('span', 'session-actions');
    if (running) {
      // Stopping the turn is the one action worth having on a row the user is
      // not looking at: without it a background turn could only be stopped by
      // opening it first.
      const stop = el('button', 'session-stop');
      stop.type = 'button';
      stop.textContent = '■';
      stop.title = 'Stop the turn running in ' + id;
      stop.dataset.focusKey = 'stop:' + name + ':' + id;
      stop.addEventListener('click', function (event) {
        event.stopPropagation();
        stopSession(id, name);
      });
      actions.appendChild(stop);
    }
    const remove = deleteControl(actions, 'Delete', function (control) {
      deleteTreeSession(name, id, index, control, btn);
    });
    remove.idle.dataset.focusKey = 'del:' + name + ':' + id;
    remove.yes.dataset.focusKey = 'del2:' + name + ':' + id;
    remove.idle.title = 'Delete ' + id + ' from disk';
    tree.deletes.push(remove);

    li.appendChild(btn);
    li.appendChild(actions);
    return li;
  }

  /* The whole row is the target: a click anywhere on the li that the row's own
   * controls did not already answer opens the session. That is what turns the
   * strip between the title and Delete — visually part of the row, previously
   * inert — into a hit. The delete controls are buttons, so a click on them
   * arrives with a target that is not the li itself and is left alone; a
   * modified click (a new tab, a text selection) is not a plain open either.
   */
  function rowOpensOn(host, open) {
    host.addEventListener('click', function (event) {
      if (event.target !== host) { return; }
      if (event.defaultPrevented) { return; }
      if (event.button !== 0) { return; }
      if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) { return; }
      const selection = typeof window !== 'undefined' && window.getSelection
        ? window.getSelection() : null;
      if (selection && str(selection.toString()) !== '') { return; }
      open(event);
    });
  }

  /* The sessions of one node, or the reason there are none to show yet. The
   * rows are read from the node's own workspace, whether or not it is active. */
  function sessionsList(name, listId) {
    const list = el('ul', 'ws-sessions');
    list.id = listId;
    list.setAttribute('aria-label', 'Sessions in ' + name);
    const cached = cachedSessions(name);
    if (!cached || cached.status === 'loading') {
      list.appendChild(el('li', 'ws-note muted', 'Loading…'));
    } else if (cached.status === 'error') {
      list.appendChild(el('li', 'ws-note err', 'Could not load sessions: ' + cached.error));
    } else if (!cached.items.length) {
      list.appendChild(el('li', 'ws-note muted',
        'No saved sessions — a session gets a file once it has messages.'));
    } else {
      const activeId = activeSessionId();
      const items = cached.items;
      // Only rows that would read the same get a stamp; a list where nothing
      // collides is titles all the way down, which is the point of the change.
      const stamps = repeatedStamps(items);
      items.forEach(function (item, index) {
        list.appendChild(sessionRow(name, item, index, activeId, stamps.get(str(item.id))));
      });
    }
    return list;
  }

  /* Session id → stamp, for the ids whose title another row in the same list
   * already shows. Keyed by id, so duplicate titles are answered in one pass. */
  function repeatedStamps(items) {
    const seen = new Map();
    const stamps = new Map();
    items.forEach(function (item) {
      const id = str(item.id);
      const key = firstLine(str(item.title)) || firstLine(str(item.preview)) || id;
      if (seen.has(key)) {
        stamps.set(id, idStamp(id));
        stamps.set(seen.get(key), idStamp(seen.get(key)));
      } else {
        seen.set(key, id);
      }
    });
    return stamps;
  }

  /* A workspace is a folder: its one control selects the node and folds its
   * sessions, which is a read. `Use` is the separate, explicit switch. */
  function nodeElement(item, index) {
    const name = str(item.name);
    const active = item.active === true || (name !== '' && name === workspaceName());
    const open = tree.expanded.has(name);
    const listId = 'ws-sessions-' + index;
    const li = el('li', 'ws-node' + (active ? ' active' : '')
      + (name !== '' && name === tree.selected ? ' selected' : ''));
    li.dataset.workspace = name;

    const row = el('div', 'ws-row');
    const nameBtn = el('button', 'ws-name');
    nameBtn.type = 'button';
    nameBtn.dataset.focusKey = 'ws:' + name;
    nameBtn.setAttribute('aria-expanded', open ? 'true' : 'false');
    nameBtn.setAttribute('aria-controls', listId);
    if (active) { nameBtn.setAttribute('aria-current', 'true'); }
    nameBtn.title = (str(item.path) || name) + (active
      ? '\nThe workspace this page is working in — click to fold its sessions'
      : '\nClick to fold its sessions open — this does not switch workspace');
    nameBtn.appendChild(el('span', 'ws-chevron', open ? '▾' : '▸'));
    nameBtn.appendChild(el('span', 'ws-folder', '▣'));
    nameBtn.appendChild(el('span', 'ws-label', name));
    const count = Number(item.sessions);
    if (isFinite(count)) {
      const badge = el('span', 'ws-count', count.toLocaleString());
      badge.title = count === 1 ? '1 session' : count.toLocaleString() + ' sessions';
      nameBtn.appendChild(badge);
    }
    nameBtn.addEventListener('click', function () { toggleNode(name); });
    row.appendChild(nameBtn);
    li.appendChild(row);

    // State on the left, the row's own actions on the right: one line of the
    // card, so the name above it never has to be squeezed to make room.
    const foot = el('div', 'ws-foot');
    if (active) {
      const badge = el('span', 'ws-badge', 'active');
      badge.title = 'The active workspace — nothing to switch to';
      foot.appendChild(badge);
    } else {
      const use = el('button', 'btn ghost sm ws-use', 'Use');
      use.type = 'button';
      use.dataset.focusKey = 'use:' + name;
      use.title = 'Switch to ' + name + ' — starts a new session there';
      use.addEventListener('click', function () { activateWorkspace(name, use); });
      foot.appendChild(use);
    }
    li.appendChild(foot);

    const remove = deleteControl(foot, 'Remove', function (control) {
      removeWorkspace(name, control);
    }, { confirm: 'Remove?', busy: 'Removing…' });
    remove.idle.dataset.focusKey = 'rm:' + name;
    remove.idle.classList.add('ws-remove');
    remove.idle.title = active
      ? 'The active workspace cannot be removed — switch to another one first'
      : 'Forget ' + name + ' — its session files stay on disk';
    remove.idle.disabled = active;
    tree.deletes.push(remove);

    if (open) { li.appendChild(sessionsList(name, listId)); }
    return li;
  }

  function renderTree(key) {
    const keep = key === undefined ? focusKey() : str(key);
    tree.deletes = [deleteAllControl];
    // The tree is rebuilt from the server's answer, which takes the scroll
    // position with it — and the rebuild now also happens on its own, when a
    // turn ends. Reading one workspace's history while another's is long must
    // not snap the sidebar back to the top, so the position is carried across
    // the rebuild the same way focus is.
    const scrolled = treeScroll();
    dom.wsTree.textContent = '';
    if (!tree.payload) {
      dom.wsTree.appendChild(el('li', 'ws-note ' + (tree.error ? 'err' : 'muted'),
        tree.error || 'Loading…'));
      return;
    }
    const items = workspaceItems();
    if (!items.length) {
      dom.wsTree.appendChild(el('li', 'ws-note muted', 'No workspaces reported.'));
      return;
    }
    items.forEach(function (item, index) { dom.wsTree.appendChild(nodeElement(item, index)); });
    treeScroll(scrolled);
    // The control the keyboard was on may be gone (a deleted row, a forgotten
    // workspace); the selected node is where it lands instead.
    if (keep && !focusByKey(keep)) { focusByKey('ws:' + tree.selected); }
  }

  /* The sidebar body scrolls, not the tree inside it: the notes and the action
   * row are part of the same column. Reading the position from the container
   * that owns it is what makes it survive a rebuild. */
  function treeScroll(to) {
    const body = dom.wsTree.parentNode;
    if (!body) { return 0; }
    if (to === undefined) { return body.scrollTop || 0; }
    // Only restore a position there is room for: a shorter list must not be
    // left scrolled past its own end, which shows a blank pane.
    body.scrollTop = Math.min(to, Math.max(0, body.scrollHeight - body.clientHeight));
    return body.scrollTop;
  }

  /* The server owns the list, so every render starts from its answer. `keep`
   * reuses the cached session lists when the caller already holds newer data
   * (a delete's answer *is* the list it belongs to). */
  function acceptWorkspaces(payload, opts) {
    const options = opts || {};
    tree.payload = payload && typeof payload === 'object' ? payload : { active: '', workspaces: [] };
    tree.error = '';
    if (options.keep !== true) { invalidateSessions(); }
    pruneExpanded();
    if (options.selected) { selectWorkspace(str(options.selected)); }
    if (!tree.selected || !workspaceItem(tree.selected)) { selectWorkspace(workspaceName()); }
    renderTree(options.focusKey);
    return loadExpanded();
  }

  async function loadWorkspaces(opts) {
    try {
      return await acceptWorkspaces(await request('/api/workspaces'), opts);
    } catch (err) {
      // Stale beats nothing: the tree keeps its last answer and the alert says
      // why the refresh failed. With no answer at all it says that instead of
      // "Loading…" forever.
      tree.error = 'Could not load workspaces: ' + str(err && err.message);
      sidebarError(tree.error);
      renderTree();
      return undefined;
    }
  }

  function pruneExpanded() {
    const live = new Set();
    workspaceItems().forEach(function (item) { live.add(str(item.name)); });
    let changed = false;
    tree.expanded.forEach(function (name) {
      if (!live.has(name)) { tree.expanded.delete(name); changed = true; }
    });
    if (changed) { saveExpanded(); }
  }

  /* Folding a node is the read that keeps this list honest: the sessions of an
   * open node are fetched once and cached until something invalidates them. */
  function loadExpanded() {
    const pending = [];
    workspaceItems().forEach(function (item) {
      const name = str(item.name);
      if (tree.expanded.has(name) && !tree.sessions.has(name)) { pending.push(name); }
    });
    if (!pending.length) { return Promise.resolve(); }
    pending.forEach(function (name) {
      tree.sessions.set(name, { status: 'loading', items: [], error: '' });
    });
    renderTree();
    return Promise.all(pending.map(fetchSessions)).then(function () { renderTree(); });
  }

  async function fetchSessions(name) {
    try {
      const res = await request('/api/sessions?workspace=' + encodeURIComponent(name));
      tree.sessions.set(name, { status: 'ready', items: sessionsIn(res), error: '' });
    } catch (err) {
      tree.sessions.set(name, {
        status: 'error', items: [], error: str(err && err.message) || 'request failed'
      });
    }
  }

  /* Deleting a session, starting one, switching workspaces and re-reading the
   * workspace list all invalidate the cached lists. */
  function invalidateSessions(name) {
    if (name === undefined || name === null || name === '') { tree.sessions.clear(); }
    else { tree.sessions.delete(str(name)); }
  }

  /* A turn just ended, so the session it ran in has moved: the server orders the
   * list by each file's modification time, and the turn wrote to that file. The
   * page is still holding the list it fetched before the turn, which is why a
   * session used again did not rise, and a session whose first message was this
   * turn was not on the list at all. Re-asking is what puts the conversation you
   * just had at the top.
   *
   * Only the active workspace is re-read — nothing was appended in another one,
   * so its order cannot have changed — and a failure leaves the last order on
   * screen: a background refresh must never replace rows with an error, because
   * the conversation next to it is already correct.
   */
  async function reorderSessionsAfterTurn() {
    const active = workspaceName();
    if (active === '') { return; }
    try {
      if (tree.expanded.has(active)) {
        const res = await request('/api/sessions?workspace=' + encodeURIComponent(active));
        tree.sessions.set(active, { status: 'ready', items: sessionsIn(res), error: '' });
        renderTree();
      } else {
        invalidateSessions(active);   // nothing on screen to reorder, but the cache is stale
      }
      await loadWorkspaces({ keep: true });
    } catch (err) {
      // The last order stays; the next action that re-reads the list will fix it.
    }
  }

  function toggleNode(name) {
    clearSidebarMessages();
    selectWorkspace(name);
    if (tree.expanded.has(name)) { tree.expanded.delete(name); }
    else { tree.expanded.add(name); }
    saveExpanded();
    renderTree();
    loadExpanded();
  }

  /* The page's own workspace and the tree's `active` mark are the same fact.
   * When the server says the active one changed, the tree follows it. */
  function noteActiveWorkspace() {
    const name = workspaceName();
    if (name === tree.activeName) { return; }
    tree.activeName = name;
    if (name) { selectWorkspace(name); }
    renderTree();
    loadExpanded();
  }

  // ------------------------------------------------------- sidebar: actions

  /* Switching is a two-step move: the request changes the server, and the
   * status that answers it says which session the new workspace is on. The
   * transcript is emptied *here*, before the answer lands, so the old
   * workspace's messages are never on screen next to the new workspace's name
   * — and never under a session id that belongs to neither. */
  async function switchWorkspaceTo(name) {
    const res = await postJSON('/api/workspace', { name: name });
    selectWorkspace(name);
    state.sessionId = '';          // the next status must clear and re-fetch
    clearTranscript();
    // The status that answers a switch describes a brand-new, empty session.
    // Fetching *its* history is a round trip for an empty screen, so the id is
    // recorded and the transcript left alone; the resume that follows (when the
    // user picked a session) is what fetches.
    tree.throwaway = true;
    try {
      if (isStatusPayload(res)) { applyStatus(res); } else { await refreshStatus(); }
    } finally {
      tree.throwaway = false;
    }
  }

  /* `Use` is the only control that moves the page, and what it costs is on its
   * tooltip: a switch starts a new session in the target workspace. */
  async function activateWorkspace(name, button) {
    if (!name || name === workspaceName()) {
      closeSheetsWhenNarrow();
      return;
    }
    clearSidebarMessages();
    if (button) { button.disabled = true; }
    try {
      await switchWorkspaceTo(name);
      // A switch invalidates every cached list, and the counts move with the
      // active mark, so the list is re-read rather than patched.
      await loadWorkspaces({ focusKey: 'ws:' + name });
      // Only once it worked: a failure is reported in this sheet, so closing it
      // would hide the reason the tap did nothing.
      closeSheetsWhenNarrow();
    } catch (err) {
      sidebarError('Could not switch to ' + name + ': ' + str(err && err.message));
      renderTree('use:' + name);
    }
  }

  /* A session in another workspace means two things at once: go there, then
   * open it — in that order. Between them the transcript holds the switch's
   * placeholder, so the wrong workspace's history never flashes up. */
  async function openWorkspaceSession(name, id, button) {
    clearSidebarMessages();
    if (button) { button.disabled = true; }
    try {
      if (name !== workspaceName()) {
        await switchWorkspaceTo(name);
        await loadWorkspaces({ keep: true, focusKey: 's:' + name + ':' + id });
      }
      await switchSession({ action: 'resume', id: id }, button);
      // The conversation is on screen now, which is what the sheet was opened
      // for; a failure below leaves it up, because that is where it is reported.
      closeSheetsWhenNarrow();
    } catch (err) {
      if (button) { button.disabled = false; }
      sidebarError('Could not open ' + clip(id, 44) + ': ' + str(err && err.message));
      renderTree('s:' + name + ':' + id);
    }
  }

  /* Resuming or starting a session is the server's decision; the answer says
   * which session the page is on, and that — never the button that was pressed
   * — decides whether the transcript is cleared and its history fetched. */
  async function switchSession(body, button) {
    if (button) { button.disabled = true; }
    try {
      const res = await postJSON('/api/session', body);
      if (isStatusPayload(res)) { applyStatus(res); } else { await refreshStatus(); }
      if (body.action === 'new') {
        // A new session is what invalidates the active workspace's list; a
        // resume only changes which row carries the mark.
        invalidateSessions(workspaceName());
        renderTree();
        loadExpanded();
      } else {
        renderTree();
      }
    } catch (err) {
      if (button) { button.disabled = false; }
      const message = 'session ' + body.action + ' failed: ' + str(err && err.message);
      appendError(message);
      sidebarError('Session ' + body.action + ' failed: ' + str(err && err.message));
      renderTree();
    }
  }

  /* Deleting one session. The endpoint names the workspace, so a row under a
   * folded node is deleted where it lives — the page does not have to switch,
   * and deleting elsewhere never touches the session on screen. */
  async function deleteTreeSession(name, id, index, control, rowButton) {
    if (tree.busy) { return; }
    tree.busy = true;
    control.busy();
    rowButton.disabled = true;
    clearSidebarMessages();
    const active = name === workspaceName() && id !== '' && id === activeSessionId();
    try {
      const res = await request('/api/session?workspace=' + encodeURIComponent(name)
        + '&id=' + encodeURIComponent(id), { method: 'DELETE' });
      // The answer *is* the refreshed list for this workspace, so it is stored
      // and kept: re-asking would be a round trip for information in hand.
      tree.sessions.set(name, { status: 'ready', items: sessionsIn(res), error: '' });
      const items = cachedSessions(name).items;
      const next = items.length ? items[Math.min(index, items.length - 1)] : null;
      const focus = next ? 's:' + name + ':' + str(next.id) : 'ws:' + name;
      renderTree(focus);
      sidebarNote('Deleted ' + clip(id, 44) + '.' + (active ? FRESH_SESSION : ''));
      await loadWorkspaces({ keep: true, focusKey: focus });
      await afterSessionDelete(active);
    } catch (err) {
      sidebarError('Could not delete ' + clip(id, 44) + ': ' + str(err && err.message));
      invalidateSessions(name);   // the list on screen is not trusted any more
      renderTree('ws:' + name);
      loadExpanded();
    } finally {
      tree.busy = false;
    }
  }

  /* `Delete all` clears the workspace the sidebar has selected, named in the
   * request so it works in any node — not only the active one. */
  async function deleteAllSelectedWorkspace(control) {
    if (tree.busy) { return; }
    const name = tree.selected || workspaceName();
    if (!name) {
      control.reset();
      sidebarError('Select a workspace first.');
      return;
    }
    tree.busy = true;
    control.busy();
    clearSidebarMessages();
    const inActive = name === workspaceName();
    const before = cachedCount(name);
    try {
      const res = await request('/api/sessions?workspace=' + encodeURIComponent(name),
        { method: 'DELETE' });
      control.reset();
      const items = sessionsIn(res);
      tree.sessions.set(name, { status: 'ready', items: items, error: '' });
      const gone = Math.max(0, before - items.length);
      renderTree('delall');
      sidebarNote((gone === 1 ? 'Deleted 1 session.' : 'Deleted ' + gone + ' sessions.')
        + (inActive ? FRESH_SESSION : ''));
      await loadWorkspaces({ keep: true, focusKey: 'delall' });
      await afterSessionDelete(inActive);
    } catch (err) {
      control.reset();
      sidebarError('Could not delete the sessions: ' + str(err && err.message));
      renderTree('delall');
    } finally {
      tree.busy = false;
    }
  }

  /* How many rows the delete cleared: the count in hand when the list has been
   * read, otherwise the workspace's own count. */
  function cachedCount(name) {
    const cached = cachedSessions(name);
    if (cached && cached.status === 'ready') { return cached.items.length; }
    const item = workspaceItem(name);
    const count = Number(item && item.sessions);
    return isFinite(count) ? count : 0;
  }

  /* Deleting the active session makes the server hand out a new id, and the
   * page follows it by clearing the transcript — which is right, but looks like
   * something broke unless the pane says why. Reconcile from the server first
   * (so the tree is not waiting on the stream), then explain the empty pane,
   * but only if nobody else has. */
  async function afterSessionDelete(active) {
    if (!active) { return; }
    await refreshStatus();
    if (state.historyPromise) { await state.historyPromise; }
    if (transcriptEmpty()) {
      appendNotice('The session you were in was deleted — ccj started a fresh one, so this transcript is empty.');
    }
  }

  /* Deleting is destructive and there is no undo, so one click only *arms* it:
   * the button is replaced in place by the question and a way out, the question
   * takes focus, and the second click is what spends the file.
   *
   * `words` lets a caller say "Remove" instead of "Delete" without a second
   * implementation of the same gesture. */
  function deleteControl(host, label, onConfirm, words) {
    const askLabel = (words && words.confirm) || 'Delete?';
    const busyLabel = (words && words.busy) || 'Deleting…';
    const idle = el('button', 'btn danger sm', label);
    const ask = el('span', 'confirm-row');
    const yes = el('button', 'btn danger sm', askLabel);
    const no = el('button', 'btn ghost sm', 'Cancel');
    idle.type = 'button'; yes.type = 'button'; no.type = 'button';
    ask.hidden = true;
    ask.appendChild(yes);
    ask.appendChild(no);
    host.appendChild(idle);
    host.appendChild(ask);

    const control = { idle: idle, ask: ask, yes: yes, no: no, armed: false };

    control.reset = function () {
      control.armed = false;
      idle.hidden = false;
      ask.hidden = true;
      yes.disabled = false;
      no.disabled = false;
      yes.textContent = askLabel;
    };
    control.arm = function () {
      control.armed = true;
      idle.hidden = true;
      ask.hidden = false;
      yes.focus();
    };
    control.busy = function () {
      yes.disabled = true;
      no.disabled = true;
      yes.textContent = busyLabel;
    };

    idle.addEventListener('click', control.arm);
    no.addEventListener('click', function () { control.reset(); idle.focus(); });
    yes.addEventListener('click', function () { onConfirm(control); });
    return control;
  }

  // ------------------------------------------- sidebar: adding a workspace

  function clearWorkspaceErrors() {
    dom.wsPathError.textContent = '';
    dom.wsPathError.hidden = true;
    dom.wsBrowseHint.textContent = '';
    dom.wsBrowseHint.hidden = true;
  }

  /* One 400 message per refusal, decided here: with the form open, an unusable
   * directory belongs under the path field; with the form closed — a pick the
   * server refused — there is no field on screen, so the message goes above the
   * tree, where the note about the add would have been. */
  function showWorkspaceError(message) {
    const text = str(message) || 'Workspace request failed.';
    const underField = !dom.workspaceAddForm.hidden
      && /path|director|folder|\bdir\b|absolute|usable|permission|denied|creat|exist/i.test(text);
    if (underField) {
      fieldError(dom.wsPathError, text);
      dom.wsNewPath.focus();
    } else {
      sidebarError(text);
    }
  }

  function setAddFormOpen(open) {
    dom.workspaceAddForm.hidden = !open;
    dom.workspaceAdd.setAttribute('aria-expanded', open ? 'true' : 'false');
    if (!open) { dom.wsNewPath.value = ''; }
  }

  /* The chooser runs on the machine that serves the page, so this request *is*
   * the desktop dialog: it blocks until the user answers, or the server gives
   * up after two minutes and reports a cancel. `picked` is called with the path
   * only when a folder really came back, so cancelling is not an error and not
   * an add. A 400 (no desktop, no chooser) is shown next to the field, which
   * keeps working as the fallback. */
  async function pickWorkspaceFolder(picked) {
    clearWorkspaceErrors();
    dom.workspacePick.disabled = true;
    dom.wsBrowse.disabled = true;
    const label = dom.workspacePick.textContent;
    dom.workspacePick.textContent = 'Waiting…';
    dom.wsBrowseHint.textContent =
      'A folder chooser was opened on the desktop — choose a folder in that window.';
    dom.wsBrowseHint.hidden = false;
    try {
      const res = await request('/api/workspaces/browse', { method: 'POST' });
      if (res && typeof res === 'object' && res.path && !res.cancelled) {
        dom.wsBrowseHint.textContent = '';
        dom.wsBrowseHint.hidden = true;
        picked(str(res.path));
      } else {
        dom.wsBrowseHint.textContent = 'No folder chosen — the chooser was dismissed.';
      }
    } catch (err) {
      dom.wsBrowseHint.hidden = true;
      fieldError(dom.wsPathError, str(err && err.message) || 'Could not open a folder chooser.');
    }
    dom.workspacePick.disabled = false;
    dom.wsBrowse.disabled = false;
    dom.workspacePick.textContent = label;
  }

  /* The name is the folder's, so there is nothing to ask for: the request carries
   * the directory and the server derives the name (and a free suffix when that
   * name is taken). */
  async function addWorkspace(path) {
    clearWorkspaceErrors();
    const directory = str(path).trim();
    if (!directory) {
      fieldError(dom.wsPathError, 'Enter a directory, or choose a folder.');
      dom.wsNewPath.focus();
      return;
    }
    dom.workspaceSave.disabled = true;
    try {
      const res = await postJSON('/api/workspaces', { path: directory });
      const added = addedWorkspace(res, directory);
      setAddFormOpen(false);
      // Adding is not switching: the new node is opened so its (empty) list is
      // visible, and the page stays in the workspace it was working in.
      if (added) {
        tree.expanded.add(added);
        saveExpanded();
        sidebarNote('Workspace ' + added + ' added — press Use to work there.');
      } else {
        sidebarNote('Workspace added — press Use to work there.');
      }
      acceptWorkspaces(res, added ? { selected: added, focusKey: 'ws:' + added } : undefined);
    } catch (err) {
      showWorkspaceError(err.message);
    }
    dom.workspaceSave.disabled = false;
  }

  /* Which entry the server just created: the one holding the directory that was
   * asked for. The name is the server's to choose, so it is read back rather
   * than guessed from the path. */
  function addedWorkspace(payload, directory) {
    const wanted = str(directory).replace(/\/+$/, '');
    let found = '';
    workspaceItemsOf(payload).forEach(function (item) {
      if (!found && str(item.path).replace(/\/+$/, '') === wanted) { found = str(item.name); }
    });
    return found;
  }

  /* The gesture a click on "Add workspace" is: the desktop's own chooser, and the
   * workspace appears as soon as a folder is chosen. A machine whose chooser
   * cannot run reports that under the path field, so the form is opened then —
   * typing a path stays one click away instead of hidden behind a refusal. */
  async function addWorkspaceByPicking() {
    await pickWorkspaceFolder(function (path) { addWorkspace(path); });
    if (!dom.wsPathError.hidden) { openAddForm(); }
  }

  function openAddForm() {
    setAddFormOpen(true);
    dom.wsNewPath.focus();
  }

  /* Forgetting is not deleting: the registry entry goes, the session files
   * stay where they are. The active workspace is refused, and the button says
   * so before the server has to. */
  async function removeWorkspace(name, control) {
    clearSidebarMessages();
    control.busy();
    try {
      const res = await request('/api/workspace?name=' + encodeURIComponent(name), { method: 'DELETE' });
      sidebarNote('Workspace ' + name + ' forgotten — its session files stay on disk.');
      await acceptWorkspaces(res, { focusKey: 'ws:' + name });
    } catch (err) {
      control.reset();
      sidebarError('Could not remove ' + name + ': ' + str(err && err.message));
      renderTree();
    }
  }

  // --------------------------------------- provider / model / effort picker

  /* One control, two mount points: the composer (directly above the input) and
   * the settings form. Both read the same catalogue — GET /api/models merged
   * with the names GET /api/config reports — so they can never drift apart.
   * What a choice *does* is what differs, and that is the caller's callback,
   * not the picker: the composer commits through POST /api/config at once, the
   * settings form only fills its fields in until Save.
   *
   * The panel is two columns (providers left, that provider's models right)
   * over a row of effort tiers. Choosing a provider only swaps the right
   * column; choosing a model or a tier is what commits. A refused request is
   * shown inside the panel and the previous selection is kept, so the screen
   * never claims a change the server rejected. */

  const EFFORT_HINT = {
    default: 'Default — the provider decides how much to think.',
    low: 'Low — a little more thinking, a few more tokens.',
    high: 'High — more thinking, more tokens.',
    max: 'Max — the most thinking, the most tokens.'
  };
  const REASONING_FALLBACK = ['low', 'high', 'max'];

  /* The tiers the server accepts (status.reasoningLevels), with the implicit
   * "default" first. A server that predates the field still gets all three. */
  function reasoningLevels() {
    const levels = Array.isArray(state.reasoningLevels) && state.reasoningLevels.length
      ? state.reasoningLevels : REASONING_FALLBACK;
    const all = ['default'];
    levels.forEach(function (level) {
      const clean = str(level).trim().toLowerCase();
      if (clean && clean !== 'default' && all.indexOf(clean) < 0) { all.push(clean); }
    });
    return all;
  }

  function tierLabel(tier) { return str(tier) || 'default'; }

  /* The provider list is the catalogue first, then any name GET /api/config
   * knows that the catalogue does not: the server accepts a provider this
   * build has never heard of, and the picker must offer it too. */
  function pickerProviders() {
    const out = [];
    const seen = new Set();
    function add(record) {
      const name = str(record && record.name).trim();
      if (!name || seen.has(name.toLowerCase())) { return; }
      seen.add(name.toLowerCase());
      out.push({
        name: name,
        kind: str(record.kind),
        baseUrl: str(record.baseUrl),
        builtIn: record.builtIn === true,
        known: record.known !== false,
        models: Array.isArray(record.models) ? record.models : []
      });
    }
    if (state.catalog) { state.catalog.providers.forEach(function (p) { add(p); }); }
    state.configProviders.forEach(function (name) { add({ name: name, known: false }); });
    return out;
  }

  function pickerProviderKnown(name) {
    const wanted = str(name).trim().toLowerCase();
    return pickerProviders().some(function (p) { return p.name.toLowerCase() === wanted; });
  }

  function createPicker(config) {
    const root = config.root;
    const apply = config.apply || null;            // async (kind, next); throws on refusal
    const onSelect = config.onSelect || null;      // local side effects; never a request
    const providerSelects = config.providerSelects === true;

    const nodes = {
      trigger: root.querySelector('[data-role="trigger"]'),
      value: root.querySelector('[data-role="value"]'),
      panel: root.querySelector('[data-role="panel"]'),
      providers: root.querySelector('[data-role="providers"]'),
      models: root.querySelector('[data-role="models"]'),
      modelInput: root.querySelector('[data-role="modelInput"]'),
      modelUse: root.querySelector('[data-role="modelAdd"]'),
      addProvider: root.querySelector('[data-role="addProvider"]'),
      effort: root.querySelector('[data-role="effort"]'),
      effortHint: root.querySelector('[data-role="effortHint"]'),
      error: root.querySelector('[data-role="error"]')
    };

    const picker = {
      selection: { provider: '', model: '', reasoning: 'default' },
      browsed: '',
      open: false
    };

    function showError(message) {
      nodes.error.textContent = str(message);
      nodes.error.hidden = !message;
    }

    function clearError() {
      nodes.error.textContent = '';
      nodes.error.hidden = true;
    }

    function renderTrigger() {
      const sel = picker.selection;
      const empty = !sel.provider && !sel.model;
      nodes.value.textContent = empty
        ? 'not configured'
        : [sel.provider, sel.model, tierLabel(sel.reasoning)].filter(Boolean).join(' · ');
      nodes.value.classList.toggle('picker-value-warn', !sel.provider || !sel.model);
      nodes.trigger.title = empty
        ? 'No model configured — choose a provider and model'
        : 'Provider ' + (sel.provider || '—') + ', model ' + (sel.model || '—')
          + ', effort ' + tierLabel(sel.reasoning);
    }

    function optionMeta(parts) {
      const meta = el('span', 'picker-option-meta');
      parts.forEach(function (part) {
        if (part) { meta.appendChild(el('span', 'picker-badge', part)); }
      });
      return meta;
    }

    /* The provider column. A failed catalogue fetch never becomes a one-row
     * list: the last good answer, if there is one, is kept and the reason is
     * shown above it; with no answer at all only the reason is shown. The
     * selected provider is an extra row on top of a real list, never a
     * substitute for one — which is what made every other provider look gone. */
    function renderProviders() {
      nodes.providers.textContent = '';
      const loaded = state.catalog !== null;
      const list = loaded ? pickerProviders() : [];
      const selected = picker.selection.provider;
      const browsed = picker.browsed || selected;
      if (loaded && selected && !pickerProviderKnown(selected)) {
        list.unshift({ name: selected, kind: '', baseUrl: '', builtIn: false, known: false, models: [] });
      }
      if (state.catalogError) {
        nodes.providers.appendChild(el('li', 'picker-empty picker-warn',
          'Provider list unavailable: ' + state.catalogError
          + (loaded ? ' Showing the last list that loaded.' : '')));
      }
      if (!list.length) {
        if (!state.catalogError) {
          nodes.providers.appendChild(el('li', 'picker-empty',
            loaded ? 'No providers reported.' : 'Loading providers…'));
        }
        return;
      }
      list.forEach(function (p) {
        const li = el('li');
        const btn = el('button', 'picker-option');
        btn.type = 'button';
        btn.dataset.provider = p.name;
        const isSelected = selected !== '' && p.name.toLowerCase() === selected.toLowerCase();
        const isBrowsed = browsed !== '' && p.name.toLowerCase() === browsed.toLowerCase();
        btn.classList.toggle('active', isSelected);
        btn.classList.toggle('browsed', isBrowsed && !isSelected);
        btn.setAttribute('aria-pressed', isSelected ? 'true' : 'false');
        btn.appendChild(el('span', 'picker-option-name', p.name));
        const meta = [];
        if (p.kind) { meta.push(p.kind); }
        // Without the catalogue (a build that does not serve it) the kind and
        // the built-in/own split are simply unknown; the name alone is honest.
        if (p.known) {
          meta.push(p.builtIn ? 'built-in' : 'your provider');
          meta.push(p.models.length === 1 ? '1 model' : p.models.length + ' models');
        }
        btn.appendChild(optionMeta(meta));
        btn.addEventListener('click', function () { chooseProvider(p.name); });
        li.appendChild(btn);
        // Providers can be removed from where they are chosen: a definition is deleted, a built-in
        // alias is hidden (it cannot be deleted — it is code) and can be restored in Settings.
        const actions = el('span', 'picker-option-actions');
        const drop = deleteControl(actions, 'Remove', function (control) {
          removeProvider(p.name, control);
        }, { confirm: 'Remove?', busy: 'Removing…' });
        drop.idle.title = 'Remove ' + p.name + ' from this list'
          + (p.builtIn ? ' (a built-in is hidden, not deleted)' : ' — its definition is deleted');
        li.appendChild(actions);
        nodes.providers.appendChild(li);
      });
    }

    /* The model column is the catalogue's list for the provider being browsed
     * — the selected provider unless the user is deliberately browsing another
     * row in the left column, and openPanel and setStatus put it back in step
     * with the selection. The model in use, and the picker's own choice, are
     * added when the catalogue does not list them, so neither can look lost. */
    function renderModels() {
      nodes.models.textContent = '';
      const provider = picker.browsed || picker.selection.provider;
      if (!provider) {
        nodes.models.appendChild(el('li', 'picker-empty', 'Choose a provider first.'));
        return;
      }
      const wanted = provider.toLowerCase();
      const entries = catalogModelsFor(provider).slice();
      const chosen = str(picker.selection.provider).toLowerCase() === wanted
        ? str(picker.selection.model).trim() : '';
      const inUse = str(state.status && state.status.provider).toLowerCase() === wanted
        ? str(state.status && state.status.model).trim() : '';
      // Only what the provider actually offers: a removed model that is still in use stays visible
      // in the trigger above the composer, but it must not keep a row here or removing it looks
      // like it did nothing.
      if (!entries.length) {
        // "lists no models" is only true when the catalogue answered; without
        // one, the honest thing is that the list is simply not available.
        nodes.models.appendChild(el('li', 'picker-empty', state.catalog
          ? 'No models listed for ' + provider + ' — type one below, or define it under'
            + ' Your providers in Settings.'
          : 'Model list unavailable' + (state.catalogError ? ' — ' + state.catalogError : '')
            + '; type a model name below.'));
        return;
      }
      entries.forEach(function (entry) {
        const model = str(entry.model);
        const li = el('li', 'picker-model');
        const btn = el('button', 'picker-option');
        btn.type = 'button';
        btn.dataset.model = model;
        const isCurrent = chosen !== '' && model.toLowerCase() === chosen.toLowerCase();
        const isInUse = inUse !== '' && model.toLowerCase() === inUse.toLowerCase();
        btn.classList.toggle('active', isCurrent);
        btn.setAttribute('aria-pressed', isCurrent ? 'true' : 'false');
        btn.appendChild(el('span', 'picker-option-name', model));
        btn.appendChild(optionMeta(isInUse ? ['in use'] : (str(entry.source) ? [str(entry.source)] : [])));
        btn.addEventListener('click', function () {
          choose('model', { provider: provider, model: model });
        });
        li.appendChild(btn);
        // Forget this model: the same two-step control as every other remove.
        const actions = el('span', 'picker-option-actions');
        const forget = deleteControl(actions, 'Remove', function (control) {
          removeModel(provider, model, control);
        }, { confirm: 'Remove?', busy: 'Removing…' });
        forget.idle.title = 'Forget ' + model + ' for ' + provider + ' — it stops being offered';
        li.appendChild(actions);
        nodes.models.appendChild(li);
      });
    }

    function renderEffort() {
      nodes.effort.textContent = '';
      nodes.effort.appendChild(el('span', 'picker-effort-label', 'Effort'));
      const current = picker.selection.reasoning || 'default';
      reasoningLevels().forEach(function (level) {
        const btn = el('button', 'picker-tier', tierLabel(level));
        btn.type = 'button';
        btn.dataset.tier = level;
        const on = level === current;
        btn.classList.toggle('active', on);
        btn.setAttribute('aria-pressed', on ? 'true' : 'false');
        btn.addEventListener('click', function () { choose('reasoning', { reasoning: level }); });
        nodes.effort.appendChild(btn);
      });
      nodes.effortHint.textContent = EFFORT_HINT[current] || EFFORT_HINT.default;
    }

    /* Picking a provider has to be enough to switch to it. Browsing alone
     * made another provider unreachable unless it also had a model row to
     * click, so a provider whose list is empty could not be selected at all.
     * The composer commits, pairing the provider with the model already in use
     * when it is offered and with the first offered model otherwise; the
     * settings form still only drafts the change until Save. */
    function chooseProvider(name) {
      const clean = str(name).trim();
      if (!clean) { return; }
      clearError();
      picker.browsed = clean;
      if (providerSelects) {
        picker.selection.provider = clean;
        picker.render();
        if (onSelect) { onSelect('provider', picker.selection); }
        return;
      }
      const offered = catalogModelsFor(clean).map(function (entry) { return str(entry.model); });
      const current = str(picker.selection.model).trim();
      const keeps = current !== '' && offered.some(function (model) {
        return model.toLowerCase() === current.toLowerCase();
      });
      choose('model', {
        provider: clean,
        model: keeps ? current : (offered.length ? offered[0] : current)
      });
    }

    /* The one commit path: a model pick changes provider + model, a tier pick
     * changes only the tier. `apply` may reject (a provider without a key, a
     * turn in flight); then the error is shown and the selection untouched. */
    function choose(kind, patch) {
      const next = {
        provider: kind === 'model' ? str(patch.provider) : picker.selection.provider,
        model: kind === 'model' ? str(patch.model) : picker.selection.model,
        reasoning: kind === 'reasoning' ? str(patch.reasoning) : picker.selection.reasoning
      };
      clearError();
      const commit = function () {
        picker.selection = next;
        picker.browsed = next.provider;
        nodes.modelInput.value = '';
        picker.render();
        if (onSelect) { onSelect(kind, next); }
      };
      if (!apply) { commit(); return; }
      Promise.resolve().then(function () { return apply(kind, next); }).then(commit, function (err) {
        showError(str(err && err.message) || 'The change was refused.');
        // The column follows the click even when the switch is refused, otherwise the panel keeps
        // showing the previous provider's models and an added model looks like it was replaced.
        if (kind === 'model' && str(patch.provider)) {
          picker.browsed = str(patch.provider);
          picker.render();
        }
      });
    }

    /* A 400 from /api/models is the server saying why: the model in use can
     * never be forgotten, and a name that is not this provider's cannot be
     * either. The panel says it; nothing is changed. */
    function modelError(err) {
      if (err && err.status === 404) {
        return 'This server build does not remember models yet, so nothing was changed'
          + ' (POST/DELETE /api/models answered 404).';
      }
      return str(err && err.message) || 'The model could not be saved.';
    }

    async function removeModel(provider, model, control) {
      clearError();
      control.busy();
      try {
        const res = await request('/api/models?provider=' + encodeURIComponent(provider)
          + '&model=' + encodeURIComponent(model), { method: 'DELETE' });
        acceptModelCatalogue(res);
      } catch (err) {
        control.reset();
        showError(modelError(err));
      }
    }

    /* The model column keeps a text field next to the list so a provider whose
     * list is empty — or a model the catalogue does not know — can still be
     * described. `Add` only ever appends to the list: switching is what
     * clicking a model row does, and conflating the two is how a second model
     * looked like it had replaced the first. Re-renders from the server's
     * answer and leaves the panel open. */
    async function useManualModel() {
      const model = nodes.modelInput.value.trim();
      const provider = picker.browsed || picker.selection.provider;
      if (!provider) { showError('Choose a provider first.'); return; }
      if (!model) { showError('Type a model name first.'); return; }
      clearError();
      nodes.modelUse.disabled = true;
      try {
        const res = await postJSON('/api/models', { provider: provider, model: model });
        acceptModelCatalogue(res);
        nodes.modelInput.value = '';
      } catch (err) {
        showError(modelError(err));
      } finally {
        nodes.modelUse.disabled = false;
      }
    }

    picker.render = function () {
      renderTrigger();
      if (!picker.open) { return; }
      renderProviders();
      renderModels();
      renderEffort();
    };

    picker.setSelection = function (sel) {
      picker.selection = {
        provider: str(sel && sel.provider),
        model: str(sel && sel.model),
        reasoning: str(sel && sel.reasoning) || 'default'
      };
      picker.browsed = picker.selection.provider;
      nodes.modelInput.value = '';
      picker.render();
    };

    /* Follows the server's status. Re-renders only on a real change, so the
     * repeated status frames of an idle page never steal focus inside the
     * open panel. */
    picker.setStatus = function (status) {
      const beforeProvider = picker.selection.provider;
      const before = picker.selection.provider + '\u0000' + picker.selection.model
        + '\u0000' + picker.selection.reasoning;
      if ('provider' in status) { picker.selection.provider = str(status.provider); }
      if ('model' in status) { picker.selection.model = str(status.model); }
      if ('reasoning' in status) { picker.selection.reasoning = str(status.reasoning) || 'default'; }
      if (Array.isArray(status.reasoningLevels) && status.reasoningLevels.length) {
        state.reasoningLevels = status.reasoningLevels.map(str);
      }
      // The model column belongs to the selected provider; a provider change
      // from anywhere (another tab, a Save) moves the column with it.
      if (!picker.browsed || picker.selection.provider !== beforeProvider) {
        picker.browsed = picker.selection.provider;
      }
      const after = picker.selection.provider + '\u0000' + picker.selection.model
        + '\u0000' + picker.selection.reasoning;
      if (before !== after) { picker.render(); }
    };

    picker.openPanel = function () {
      if (picker.open) { return; }
      picker.open = true;
      // The column follows the selection: a browse left over from the last
      // time the panel was open must not put another provider's models there.
      picker.browsed = picker.selection.provider;
      nodes.modelInput.value = '';
      nodes.panel.hidden = false;
      nodes.trigger.setAttribute('aria-expanded', 'true');
      clearError();
      picker.render();
      // A provider defined since the last look (another window, the terminal, a
      // script) must appear here rather than being invisible until something
      // else happens to refresh. The render above already shows what we have.
      if (typeof refreshCatalog === 'function') { refreshCatalog(); }
    };

    picker.closePanel = function (focusTrigger) {
      if (!picker.open) { return; }
      picker.open = false;
      nodes.panel.hidden = true;
      nodes.trigger.setAttribute('aria-expanded', 'false');
      clearError();
      if (focusTrigger) { nodes.trigger.focus(); }
    };

    picker.isOpen = function () { return picker.open; };
    picker.contains = function (node) { return !!node && root.contains(node); };
    picker.focus = function () { nodes.trigger.focus(); };

    nodes.trigger.addEventListener('click', function (event) {
      event.preventDefault();
      if (picker.open) { picker.closePanel(true); } else { picker.openPanel(); }
    });
    nodes.modelUse.addEventListener('click', function () { useManualModel(); });
    nodes.addProvider.addEventListener('click', function () { openAddProviderForm(); });
    /* Enter in the model field applies it — and must not reach the composer,
     * where the same key sends the message. */
    nodes.modelInput.addEventListener('keydown', function (event) {
      if (event.key !== 'Enter') { return; }
      event.preventDefault();
      event.stopPropagation();
      useManualModel(false);
    });

    return picker;
  }

  let composerPicker = null;
  let settingsPicker = null;
  const pickers = [];

  function initPickers() {
    composerPicker = createPicker({ root: dom.composerPicker, apply: applyComposerChoice });
    settingsPicker = createPicker({
      root: dom.cfgPicker,
      providerSelects: true,
      onSelect: settingsPicked
    });
    pickers.push(composerPicker, settingsPicker);
    composerPicker.setSelection({ provider: '', model: '', reasoning: 'default' });
    settingsPicker.setSelection({ provider: '', model: '', reasoning: 'default' });
  }

  /* The composer commits every choice; the settings form keeps its picks as a
   * draft until Save, so its callback only settles the credential fields. */
  async function applyComposerChoice(kind, next) {
    const payload = kind === 'reasoning'
      ? { reasoning: next.reasoning }
      : { provider: next.provider, model: next.model };
    const res = await postJSON('/api/config', payload);
    if (res && typeof res === 'object') { applyStatus(res); }   // chips now, status event later
  }

  function settingsPicked(kind, next) {
    if (kind === 'provider') { settingsProviderChanged(next.provider); }
    else { renderProviderHint(); renderModelHint(); }
  }

  function refreshPickers() {
    pickers.forEach(function (p) { p.render(); });
    if (settingsPicker) {
      renderProviderHint();
      renderModelHint();
    }
  }

  function openPicker() {
    let found = null;
    pickers.forEach(function (p) { if (p.isOpen()) { found = p; } });
    return found;
  }

  // ------------------------------------------------------------ settings

  /* The stored key never reaches the DOM: renderSettings() empties the field
   * and only its placeholder reports that something is saved. */
  const KEY_PLACEHOLDER = {
    config: '•••••••• saved',
    env: 'from the environment',
    none: 'paste your API key'
  };

  function fieldError(node, message) {
    node.textContent = message;
    node.hidden = false;
  }

  function clearSettingsErrors() {
    dom.settingsError.textContent = '';
    dom.settingsError.hidden = true;
    [dom.cfgProviderError, dom.cfgModelError, dom.cfgApiKeyError, dom.cfgVisionError].forEach(function (node) {
      node.textContent = '';
      node.hidden = true;
    });
    dom.settingsTestResult.textContent = '';
    dom.settingsTestResult.className = 'settings-test-result';
    dom.settingsTestResult.hidden = true;
  }

  function numField(node) {
    const raw = node.value.trim();
    if (raw === '') { return undefined; }
    const n = Number(raw);
    return isFinite(n) ? n : undefined;
  }

  // --------------------------------------------------- provider catalogue

  /* The catalogue (GET /api/models) is what the settings form offers: which
   * providers exist, which of them are the user's own, and which models each
   * one serves. It is optional — a typed name and a typed model still work —
   * so a failed refresh is recorded and shown, never thrown. */
  async function refreshCatalog() {
    try {
      setCatalog(await request('/api/models'));
    } catch (err) {
      // Keep the previous catalogue: stale suggestions beat none.
      state.catalogError = err && err.status === 404
        ? 'this server build does not serve a provider catalogue (GET /api/models answered 404)'
        : (str(err && err.message) || 'the catalogue could not be loaded');
    }
    refreshPickers();
    renderProviderSection();
  }

  /* Only object entries are kept: a catalogue is a list of provider records
   * with a name, a kind, an endpoint and models. */
  function setCatalog(payload) {
    const records = function (list) {
      return (Array.isArray(list) ? list : []).filter(function (entry) {
        return entry && typeof entry === 'object';
      });
    };
    state.catalog = {
      providers: records(payload && payload.providers),
      models: records(payload && payload.models),
      builtIns: (Array.isArray(payload && payload.builtIns) ? payload.builtIns : [])
        .map(function (name) { return str(name); })
        .filter(Boolean)
    };
    state.catalogError = '';
  }

  /* Names are matched case-insensitively, the way the server matches them. */
  function providerInfo(name) {
    const wanted = str(name).trim().toLowerCase();
    if (!wanted || !state.catalog) { return null; }
    let found = null;
    state.catalog.providers.forEach(function (entry) {
      if (!found && str(entry.name).toLowerCase() === wanted) { found = entry; }
    });
    return found;
  }

  function catalogModelsFor(provider) {
    const wanted = str(provider).trim().toLowerCase();
    if (!wanted || !state.catalog) { return []; }
    return state.catalog.models.filter(function (entry) {
      return str(entry.provider).toLowerCase() === wanted;
    });
  }

  function customProviders() {
    if (!state.catalog) { return []; }
    return state.catalog.providers.filter(function (entry) { return entry.builtIn !== true; });
  }

  /* The summary under the settings picker: how many models the chosen provider
   * lists and where they came from. The panel itself says the same thing per
   * row, on demand. */
  function renderModelHint() {
    if (!settingsPicker) { return; }
    const name = settingsPicker.selection.provider.trim();
    const entries = catalogModelsFor(name);
    let text = '';
    if (name && state.catalog) {
      if (entries.length) {
        const sources = [];
        entries.forEach(function (entry) {
          const source = str(entry.source);
          if (source && sources.indexOf(source) < 0) { sources.push(source); }
        });
        text = (entries.length === 1 ? '1 model' : entries.length + ' models') + ' for ' + name
          + (sources.length ? ' · source: ' + sources.join(', ') : '');
      } else if (providerInfo(name)) {
        text = name + ' lists no models — type the model name in the picker.';
      }
    }
    dom.cfgModelHint.textContent = text;
    dom.cfgModelHint.hidden = !text;
  }

  /* Says what the selected provider is: compiled in, the user's own, or not
   * defined yet — the last one is a real state, not a typo to hide. The
   * settings form still accepts any name, because the server does. */
  function renderProviderHint() {
    if (!settingsPicker) { return; }
    const name = settingsPicker.selection.provider.trim();
    const info = providerInfo(name);
    let text = '';
    if (info) {
      const parts = [info.builtIn === true ? 'built-in provider' : 'your own provider'];
      if (str(info.kind)) { parts.push('protocol ' + str(info.kind)); }
      const count = Array.isArray(info.models) ? info.models.length : 0;
      parts.push(count === 1 ? '1 model' : count + ' models');
      text = parts.join(' · ');
    } else if (name && state.catalog) {
      text = '"' + name + '" is not defined on this server yet — add it under Your providers below,'
        + ' or save it as typed.';
    }
    dom.cfgProviderHint.textContent = text;
    dom.cfgProviderHint.hidden = !text;
  }

  /* A provider's endpoint and key variable belong to the provider, so choosing
   * a known one fills them in. This also matters for correctness: the server
   * treats a base URL that differs from the provider's default as an explicit
   * override, so a previous provider's URL left in the field would silently win
   * over the definition just selected. */
  /* Whether the server says a key is saved for this provider. Names are matched the way the server
   * matches them, and the list carries names only — a key never reaches the page. */
  function providerHasSavedKey(name) {
    const wanted = str(name).trim().toLowerCase();
    return wanted !== '' && state.rememberedProviders.some(function (entry) {
      return str(entry).trim().toLowerCase() === wanted;
    });
  }

  function settingsProviderChanged(name) {
    renderProviderHint();
    renderModelHint();
    const info = providerInfo(name);
    if (!info) { return; }
    if (str(info.baseUrl)) { dom.cfgBaseUrl.value = str(info.baseUrl); }
    // The variable comes from the definition when it names one: a relay that reads its key from
    // MYRELAY_KEY must not be told to read OPENAI_API_KEY, which is another provider's key.
    dom.cfgApiKeyEnv.value = str(info.apiKeyEnv)
      || (str(info.kind) === 'anthropic' ? 'ANTHROPIC_API_KEY' : 'OPENAI_API_KEY');
    // Keys are saved per provider, so whether this one has one is a question the server answers
    // (by name, never the key): "saved" here and "sent" by the server are the same fact, and a form
    // that says "saved" for a key that will not be sent is how the wrong one gets written down.
    if (str(name).toLowerCase() !== str(state.keyProvider).toLowerCase()) {
      dom.cfgApiKey.value = '';
      const remembered = providerHasSavedKey(name);
      dom.cfgApiKey.placeholder = remembered ? KEY_PLACEHOLDER.config : KEY_PLACEHOLDER.none;
      dom.cfgApiKeyHint.textContent = remembered
        ? 'A key is saved for ' + str(name) + '. Leave this empty to keep it.'
        : 'No key saved for this provider — paste one to switch to it.';
      dom.cfgApiKeyHint.hidden = false;
      // Clearing is offered only where there is something to clear, and it clears this provider's.
      dom.cfgClearKey.checked = false;
      dom.cfgClearKeyWrap.hidden = !remembered;
    }
    const models = Array.isArray(info.models) ? info.models.map(str) : [];
    if (models.length && models.indexOf(settingsPicker.selection.model) < 0) {
      // The provider's first model is the sane default; nothing is sent until Save.
      settingsPicker.setSelection({
        provider: settingsPicker.selection.provider,
        model: models[0],
        reasoning: settingsPicker.selection.reasoning
      });
    }
  }

  // ------------------------------------------- your providers (custom ones)

  function clearProviderFormErrors() {
    [dom.cfgNewNameError, dom.cfgNewKindError, dom.cfgNewBaseUrlError, dom.cfgProviderFormError]
      .forEach(function (node) {
        node.textContent = '';
        node.hidden = true;
      });
  }

  function providerNote(text) {
    dom.cfgProvidersNote.textContent = str(text);
    dom.cfgProvidersNote.hidden = !text;
  }

  /* A 404 here is not a failure of the request but of the build: the two
   * provider endpoints are newer than the rest of the API. Saying so is the
   * difference between "nothing happened" and "it worked, trust me". */
  function providersUnsupported(action, method) {
    return 'This server build does not support ' + action + ' yet, so nothing was changed'
      + ' (' + method + ' /api/providers answered 404).';
  }

  function activeProviderName() {
    return str(state.status && state.status.provider).trim();
  }

  function providerRow(info) {
    const name = str(info.name);
    const li = el('li', 'provider-item');
    li.setAttribute('data-provider', name);

    const top = el('div', 'provider-top');
    top.appendChild(el('span', 'provider-name', name));
    top.appendChild(el('span', 'provider-badge', str(info.kind) || 'openai'));
    if (name && name.toLowerCase() === activeProviderName().toLowerCase()) {
      top.appendChild(el('span', 'provider-badge in-use', 'in use'));
    }
    const actions = el('span', 'provider-actions');
    const remove = deleteControl(actions, 'Remove', function (control) {
      removeProvider(name, control);
    }, { confirm: 'Remove?', busy: 'Removing…' });
    remove.idle.title = 'Remove the definition of ' + name + ' — its models stop being offered';
    top.appendChild(actions);

    const count = Array.isArray(info.models) ? info.models.length : 0;
    li.appendChild(top);
    li.appendChild(el('span', 'provider-url', str(info.baseUrl) || '—'));
    li.appendChild(el('span', 'provider-meta',
      count === 0 ? 'no models listed' : count === 1 ? '1 model' : count + ' models'));
    return li;
  }

  /* Built-ins that are not in the list right now, offered as a choice inside the
   * add form. They are not "hidden" and there is nothing to restore: the list
   * simply does not contain them, and adding one is an ordinary add. */
  function renderBuiltInChoices() {
    const names = Array.isArray(state.catalog && state.catalog.builtIns)
      ? state.catalog.builtIns : [];
    dom.cfgBuiltIns.textContent = '';
    dom.cfgBuiltInRow.hidden = names.length === 0;
    names.forEach(function (name) {
      const add = el('button', 'btn ghost sm', name);
      add.type = 'button';
      add.title = 'Add the built-in provider ' + name + ' to your list';
      add.addEventListener('click', function () { addBuiltInProvider(name, add); });
      dom.cfgBuiltIns.appendChild(add);
    });
  }

  /* Adding a built-in back to the list: the same request the picker uses to delete
   * one, in reverse. PUT because it is the list being amended, not a definition
   * being created. */
  async function addBuiltInProvider(name, button) {
    providerNote('');
    clearSettingsErrors();
    button.disabled = true;
    try {
      const res = await request('/api/providers', {
        method: 'PUT',
        body: JSON.stringify({ name: name })
      });
      acceptCatalogue(res, 'Provider ' + name + ' added.');
    } catch (err) {
      providerNote(err && err.status === 404
        ? providersUnsupported('adding built-in providers', 'PUT')
        : 'Could not add ' + name + ': ' + str(err && err.message));
    } finally {
      button.disabled = false;
    }
  }

  /* Built-ins are code, so this list is only ever the user's own definitions —
   * and it says so out loud when there are none, rather than leaving an empty
   * box whose meaning the user has to guess. */
  function renderProviderSection() {
    renderBuiltInChoices();
    dom.cfgProviderList.textContent = '';
    if (state.catalogError) {
      dom.cfgProviderList.appendChild(el('li', 'muted',
        'Providers could not be listed: ' + state.catalogError));
      return;
    }
    const custom = customProviders();
    if (!custom.length) {
      dom.cfgProviderList.appendChild(el('li', 'muted provider-empty', 'No custom providers yet.'));
      return;
    }
    custom.forEach(function (info) { dom.cfgProviderList.appendChild(providerRow(info)); });
  }

  function setProviderFormOpen(open) {
    dom.cfgProviderForm.hidden = !open;
    dom.cfgProviderAddToggle.setAttribute('aria-expanded', open ? 'true' : 'false');
  }

  /* The picker's Add provider… entry is not a second form: it opens the one
   * under Your providers in Settings and puts the caret in its name field,
   * which is all the gesture needs. The panel closes first — two surfaces
   * asking about the same provider would compete for the answer. */
  async function openAddProviderForm() {
    pickers.forEach(function (p) { p.closePanel(false); });
    if (dom.settingsOverlay.hidden) { await openSettings(); }
    setProviderFormOpen(true);
    dom.cfgNewName.focus();
    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    dom.cfgProviderForm.scrollIntoView({ block: 'nearest', behavior: reduce ? 'auto' : 'smooth' });
  }

  /* Both provider endpoints answer with the whole catalogue, so the tree, the
   * pickers and the model counts all come from the server's answer — never
   * from the form's own idea of what it just sent. */
  function acceptCatalogue(payload, note) {
    if (payload && typeof payload === 'object' && Array.isArray(payload.providers)) {
      setCatalog(payload);
    }
    refreshPickers();
    renderProviderSection();
    providerNote(note);
  }

  /* POST and DELETE /api/models answer with the whole catalogue too, so what
   * the panel shows after a model is remembered or forgotten is the server's
   * list, not a local guess. The note area belongs to the provider form, so it
   * is left alone: these two say what happened through their own notice. */
  function acceptModelCatalogue(payload) {
    if (payload && typeof payload === 'object' && Array.isArray(payload.providers)) {
      setCatalog(payload);
    }
    refreshPickers();
    renderProviderSection();
  }

  function parseModelList(raw) {
    const models = [];
    str(raw).split(',').forEach(function (part) {
      const name = part.trim();
      if (name && models.indexOf(name) < 0) { models.push(name); }
    });
    return models;
  }

  /* One 400 message per refusal, and the field it belongs to is decided here:
   * a bad name lands under Name, the protocol under Protocol, the endpoint
   * under Base URL — the same rule the workspaces form uses. */
  function showProviderFormError(err) {
    const message = str(err && err.message) || 'Could not add the provider.';
    if (err && err.status === 404) {
      providerNote(providersUnsupported('adding providers', 'POST'));
      return;
    }
    if (/kind|protocol/i.test(message)) {
      fieldError(dom.cfgNewKindError, message);
      dom.cfgNewKind.focus();
    } else if (/base\s*url|url|endpoint/i.test(message)) {
      fieldError(dom.cfgNewBaseUrlError, message);
      dom.cfgNewBaseUrl.focus();
    } else if (/name|duplicate|exist|taken|reserved/i.test(message)) {
      fieldError(dom.cfgNewNameError, message);
      dom.cfgNewName.focus();
    } else {
      dom.cfgProviderFormError.textContent = message;
      dom.cfgProviderFormError.hidden = false;
    }
  }

  async function addProvider() {
    providerNote('');
    clearProviderFormErrors();
    const name = dom.cfgNewName.value.trim();
    const baseUrl = dom.cfgNewBaseUrl.value.trim();
    if (!name) {
      fieldError(dom.cfgNewNameError, 'Enter a name for the provider.');
      dom.cfgNewName.focus();
      return;
    }
    if (!baseUrl) {
      fieldError(dom.cfgNewBaseUrlError, 'Enter the base URL this provider is reached at.');
      dom.cfgNewBaseUrl.focus();
      return;
    }
    const payload = {
      name: name,
      kind: dom.cfgNewKind.value,
      baseUrl: baseUrl,
      apiKeyEnv: dom.cfgNewApiKeyEnv.value.trim(),
      models: parseModelList(dom.cfgNewModels.value)
    };
    dom.cfgProviderSave.disabled = true;
    try {
      const res = await postJSON('/api/providers', payload);
      dom.cfgNewName.value = '';
      dom.cfgNewBaseUrl.value = '';
      dom.cfgNewApiKeyEnv.value = '';
      dom.cfgNewModels.value = '';
      acceptCatalogue(res, 'Provider ' + name + ' saved — select it above and press Save to use it.');
      // Defining the provider the picker already has selected settles its credential fields.
      if (settingsPicker.selection.provider.toLowerCase() === name.toLowerCase()) {
        settingsProviderChanged(settingsPicker.selection.provider);
      }
    } catch (err) {
      showProviderFormError(err);
    }
    dom.cfgProviderSave.disabled = false;
  }

  async function removeProvider(name, control) {
    providerNote('');
    clearProviderFormErrors();
    control.busy();
    try {
      const res = await request('/api/providers?name=' + encodeURIComponent(name), { method: 'DELETE' });
      // The answer is the new catalogue; the /api/config name list is older, so
      // the removed name is dropped here rather than offered until the next open.
      const gone = str(name).trim().toLowerCase();
      state.configProviders = state.configProviders.filter(function (entry) {
        return str(entry).trim().toLowerCase() !== gone;
      });
      acceptCatalogue(res, 'Provider ' + name + ' removed.');
    } catch (err) {
      control.reset();
      providerNote(err && err.status === 404
        ? providersUnsupported('removing providers', 'DELETE')
        : 'Could not remove ' + name + ': ' + str(err && err.message));
    }
  }

  function renderSettings(cfg) {
    // The names the server says are usable. The current one stays in the
    // dropdown even if this build no longer lists it, and the field keeps
    // whatever it holds: provider names are configuration, not a closed set.
    const providers = Array.isArray(cfg.providers) ? cfg.providers.map(str).filter(Boolean) : [];
    const current = str(cfg.provider);
    if (current && providers.indexOf(current) < 0) { providers.push(current); }
    state.configProviders = providers;

    if (Array.isArray(cfg.reasoningLevels) && cfg.reasoningLevels.length) {
      state.reasoningLevels = cfg.reasoningLevels.map(str);
    }
    settingsPicker.setSelection({
      provider: current,
      model: str(cfg.model),
      reasoning: 'reasoning' in cfg ? str(cfg.reasoning) || 'default' : settingsPicker.selection.reasoning
    });
    renderProviderHint();
    renderModelHint();

    // An endpoint that was entered for another provider is not this one's: a custom provider uses
    // its definition's, so showing the stored value would offer to save the wrong address under
    // this provider's name. The catalogue already knows what this provider's own endpoint is.
    const info = providerInfo(current);
    const storedIsOurs = cfg.usesStoredSettings !== false;
    dom.cfgBaseUrl.value = storedIsOurs ? str(cfg.baseUrl) : str(info && info.baseUrl);
    dom.cfgApiKeyEnv.value =
      (storedIsOurs ? str(cfg.apiKeyEnv) : str(info && info.apiKeyEnv)) || 'OPENAI_API_KEY';
    dom.cfgTemperature.value =
      cfg.temperature === null || cfg.temperature === undefined ? '' : str(cfg.temperature);
    fillLanguageOptions(cfg);

    const source = str(cfg.apiKeySource);
    dom.cfgApiKey.value = '';
    dom.cfgApiKey.disabled = false;
    // Which provider this key field is about, and which providers have a key saved at all — per
    // provider, so switching to one that was used before shows as saved rather than empty.
    state.keyProvider = current;
    state.rememberedProviders = Array.isArray(cfg.rememberedProviders)
      ? cfg.rememberedProviders.map(str).filter(Boolean)
      : [];
    dom.cfgApiKey.placeholder = KEY_PLACEHOLDER[source] || KEY_PLACEHOLDER.none;
    if (source === 'env') {
      dom.cfgApiKeyHint.textContent =
        'using ' + (str(cfg.apiKeyEnv) || 'the environment variable') + ' from the environment';
      dom.cfgApiKeyHint.hidden = false;
    } else if (source === 'config') {
      dom.cfgApiKeyHint.textContent = 'A key is saved in the config file. Leave this empty to keep it.';
      dom.cfgApiKeyHint.hidden = false;
    } else {
      dom.cfgApiKeyHint.textContent = '';
      dom.cfgApiKeyHint.hidden = true;
    }

    dom.cfgClearKey.checked = false;
    dom.cfgClearKeyWrap.hidden = source !== 'config';

    renderVisionSettings(cfg);

    dom.cfgApiKeyEnvHint.textContent = source === 'env' ? 'the key is read from this variable' : '';
    dom.cfgApiKeyEnvHint.hidden = source !== 'env';

    dom.settingsFoot.textContent = str(cfg.configFile)
      ? 'Settings are stored in ' + str(cfg.configFile)
      : 'No config file path reported by the server.';

    clearSettingsErrors();
    clearProviderFormErrors();
    providerNote('');
    setProviderFormOpen(false);
  }

  /* The languages the server offers, with "auto" first: the list belongs to the
   * server because the sentence it appends to the prompt is the server's, and a
   * page that invented its own list would offer choices nothing acts on. Rebuilt
   * only when the list changed, so opening Settings never resets a selection the
   * user is in the middle of making. */
  function fillLanguageOptions(cfg) {
    const offered = Array.isArray(cfg.languages) ? cfg.languages : [];
    const wanted = str(cfg.language) || 'auto';
    const signature = wanted + '|' + offered.map(function (item) { return str(item.value); }).join(',');
    if (dom.cfgLanguage.dataset.signature !== signature) {
      dom.cfgLanguage.textContent = '';
      const auto = document.createElement('option');
      auto.value = 'auto';
      auto.textContent = 'Auto — whatever the model picks';
      dom.cfgLanguage.appendChild(auto);
      offered.forEach(function (item) {
        const value = str(item.value);
        if (!value || value === 'auto') { return; }
        const option = document.createElement('option');
        option.value = value;
        option.textContent = str(item.label) || value;
        dom.cfgLanguage.appendChild(option);
      });
      dom.cfgLanguage.dataset.signature = signature;
    }
    dom.cfgLanguage.value = wanted;
    if (dom.cfgLanguage.value !== wanted) { dom.cfgLanguage.value = 'auto'; }
  }

  /* The model that describes pictures, which is configured separately from the provider above: its
   * own endpoint, key and budget. The key follows the same rule as every other key on this page —
   * the server says whether one exists and where it comes from, never what it is, so the field is
   * always blank and blank means "keep what is there". */
  function renderVisionSettings(cfg) {
    const vision = cfg && cfg.vision && typeof cfg.vision === 'object' ? cfg.vision : {};
    const source = str(vision.apiKeySource);
    dom.cfgVisionBaseUrl.value = str(vision.baseUrl);
    dom.cfgVisionModel.value = str(vision.model);
    dom.cfgVisionMaxTokens.value =
      vision.maxTokens === null || vision.maxTokens === undefined ? '' : str(vision.maxTokens);
    dom.cfgVisionMaxTokens.placeholder = str(vision.defaultMaxTokens) || '8192';
    dom.cfgVisionApiKey.value = '';
    dom.cfgVisionApiKey.placeholder = KEY_PLACEHOLDER[source] || KEY_PLACEHOLDER.none;
    dom.cfgVisionClearKey.checked = false;
    dom.cfgVisionClearKeyWrap.hidden = source !== 'config';
    dom.cfgVisionOff.checked = false;
    if (source === 'config') {
      dom.cfgVisionApiKeyHint.textContent = 'A vision key is saved in the config file. Leave this empty to keep it.';
      dom.cfgVisionApiKeyHint.hidden = false;
    } else if (source === 'env') {
      dom.cfgVisionApiKeyHint.textContent = 'using ' + (str(vision.apiKeyEnv) || 'an environment variable') + ' from the environment';
      dom.cfgVisionApiKeyHint.hidden = false;
    } else {
      dom.cfgVisionApiKeyHint.textContent = '';
      dom.cfgVisionApiKeyHint.hidden = true;
    }
    // What the picture button will actually do, which is not the same as "the block is filled in":
    // the feature needs an endpoint, a model and a key, and a form that called a half-filled block
    // "on" would promise a description that then gets refused.
    if (vision.on) {
      dom.cfgVisionState.textContent = 'on — ' + str(vision.baseUrl) + ' · ' + str(vision.model);
    } else if (vision.configured) {
      dom.cfgVisionState.textContent = 'incomplete — a key is missing, so pictures are refused';
    } else {
      dom.cfgVisionState.textContent = 'off — a picture is refused with what to set';
    }
  }

  /* Only the fields this form manages are sent; apiKey is omitted when the
   * field is empty so the stored key survives an unrelated change. */
  function settingsPayload() {
    const chosen = settingsPicker.selection;
    const payload = {
      provider: chosen.provider,
      model: chosen.model,
      baseUrl: dom.cfgBaseUrl.value.trim(),
      apiKeyEnv: dom.cfgApiKeyEnv.value.trim() || 'OPENAI_API_KEY',
      reasoning: chosen.reasoning || 'default',
      language: dom.cfgLanguage.value || 'auto'
    };
    const clearing = dom.cfgClearKey.checked;
    if (clearing) {
      payload.clearApiKey = true;
    } else if (dom.cfgApiKey.value) {
      payload.apiKey = dom.cfgApiKey.value;
    }
    const temperature = numField(dom.cfgTemperature);
    if (temperature !== undefined) { payload.temperature = temperature; }

    // Pictures. Empty means unchanged, like every other text field here, so the two things that are
    // not a value — forgetting the key, turning the feature off — are sent as the flags they are.
    const visionBaseUrl = dom.cfgVisionBaseUrl.value.trim();
    const visionModel = dom.cfgVisionModel.value.trim();
    if (visionBaseUrl) { payload.visionBaseUrl = visionBaseUrl; }
    if (visionModel) { payload.visionModel = visionModel; }
    const budget = numField(dom.cfgVisionMaxTokens);
    if (budget !== undefined) { payload.visionMaxTokens = budget; }
    if (dom.cfgVisionClearKey.checked) {
      payload.clearVisionApiKey = true;
    } else if (dom.cfgVisionApiKey.value) {
      payload.visionApiKey = dom.cfgVisionApiKey.value;
    }
    if (dom.cfgVisionOff.checked) { payload.clearVision = true; }
    return payload;
  }

  function configSummary(status, prefix) {
    if (!status || typeof status !== 'object') { return prefix; }
    const bits = [];
    if (status.provider) { bits.push('provider ' + str(status.provider)); }
    if (status.model) { bits.push('model ' + str(status.model)); }
    return bits.length ? prefix + ' — ' + bits.join(', ') : prefix;
  }

  function showSaveError(err) {
    const message = str(err && err.message) || 'Save failed.';
    const status = err && err.status;
    if (/vision|picture/i.test(message)) {
      // Before the key branch on purpose: "the vision key is missing" is about the picture field,
      // and the generic apiKey rule below would match it and blame the provider's key.
      fieldError(dom.cfgVisionError, message);
    } else if (status === 401 || status === 403 || /api[- ]?key|unauthor|forbidden|invalid key|401|403/i.test(message)) {
      fieldError(dom.cfgApiKeyError, message);
    } else if (/model/i.test(message)) {
      // "no model configured" also names the provider, so the model branch wins.
      fieldError(dom.cfgModelError, message);
    } else if (/provider/i.test(message)) {
      fieldError(dom.cfgProviderError, message);
      settingsPicker.focus();
    } else {
      dom.settingsError.textContent = message;
      dom.settingsError.hidden = false;
    }
  }

  async function saveSettings() {
    clearSettingsErrors();
    dom.settingsSave.disabled = true;
    try {
      const res = await postJSON('/api/config', settingsPayload());
      closeSettings();
      if (res && typeof res === 'object') { applyStatus(res); }   // chips now, status event later
      appendNotice(configSummary(res, 'Settings saved'));
      // A save can change which provider is active, and the list marks that.
      await refreshCatalog();
    } catch (err) {
      showSaveError(err);
    }
    dom.settingsSave.disabled = false;
  }

  function showTestResult(text, kind) {
    dom.settingsTestResult.textContent = text;
    dom.settingsTestResult.className = 'settings-test-result ' + kind;
    dom.settingsTestResult.hidden = false;
  }

  async function testSettings() {
    clearSettingsErrors();
    dom.settingsTest.disabled = true;
    dom.settingsTest.textContent = 'Testing…';
    try {
      const res = await postJSON('/api/config/test', settingsPayload());
      const reply = clip(firstLine(str(res && res.reply)), 200) || '(empty reply)';
      const elapsed = msLabel(res && res.elapsedMs);
      showTestResult('ok — reply: “' + reply + '”' + (elapsed ? ' (' + elapsed + ')' : ''), 'ok');
    } catch (err) {
      showTestResult(str(err && err.message) || 'Test failed.', 'bad');
    }
    dom.settingsTest.textContent = 'Test connection';
    dom.settingsTest.disabled = false;
  }

  function focusSettingsForm() { settingsPicker.focus(); }

  function closeSettings() {
    if (dom.settingsOverlay.hidden) { return; }
    if (settingsPicker) { settingsPicker.closePanel(false); }
    dom.settingsOverlay.hidden = true;
    // The panel is opened from the button (or automatically on a first run);
    // either way the button is where focus belongs when it closes.
    dom.btnSettings.focus();
  }

  /* `preloaded` skips the round trip when the caller already has GET /api/config. */
  async function openSettings(preloaded) {
    dom.settingsOverlay.hidden = false;

    // The catalogue is fetched on every open, in parallel with the config: the
    // panel offers what this server has *now*, not what it had last time.
    const catalog = refreshCatalog();

    let cfg = preloaded && typeof preloaded === 'object' ? preloaded : null;
    if (!cfg) {
      dom.settingsError.textContent = 'Loading configuration…';
      dom.settingsError.hidden = false;
      try {
        cfg = await request('/api/config');
      } catch (err) {
        dom.settingsError.textContent = 'Could not load settings: ' + str(err && err.message);
        dom.settingsError.hidden = false;
        return;
      }
    }
    await catalog;   // renderSettings labels the provider list from the catalogue
    renderSettings(cfg && typeof cfg === 'object' ? cfg : {});
    focusSettingsForm();
  }

  /* A first run has no model, and the page cannot do anything useful until one
   * is picked, so the panel opens itself. `preloaded` spares the round trip
   * when init() has already read GET /api/config. */
  async function maybeOpenSettingsOnFirstLoad(preloaded) {
    let cfg = preloaded && typeof preloaded === 'object' ? preloaded : null;
    if (!cfg) {
      try {
        cfg = await request('/api/config');
      } catch (err) {
        return false;
      }
    }
    if (!cfg || typeof cfg !== 'object' || cfg.configured !== false) { return false; }
    await openSettings(cfg);
    return true;
  }

  /* The provider names the server says are usable, read once at start so the
   * composer's picker offers providers before Settings is ever opened. */
  async function readSettingsConfig() {
    try {
      const cfg = await request('/api/config');
      if (cfg && typeof cfg === 'object' && Array.isArray(cfg.providers)) {
        state.configProviders = cfg.providers.map(str).filter(Boolean);
      }
      return cfg && typeof cfg === 'object' ? cfg : null;
    } catch (err) {
      return null;
    }
  }

  // ------------------------------------------------------------ composer

  async function sendMessage() {
    if (state.busy) {
      // The page's idea of "busy" can outlive the turn it came from: a stream
      // that died, a throttled timer, a server that restarted. Swallowing the
      // message silently would be the worst answer, so the server is asked
      // before anything is dropped.
      if (!streamLooksStale()) { return; }
      const status = await request('/api/status').catch(function () { return null; });
      if (!status || status.busy) {
        if (status) { applyStatus(status); }
        return;
      }
      applyStatus(status);
    }
    const text = dom.input.value.trim();
    if (!text) { return; }
    dom.input.value = '';
    state.stick = true;
    scrollToBottom();
    // Set before the request, not after it. A fast turn can finish — and have its
    // `done` rendered from the event stream — before this response arrives, and
    // setting the flag afterwards leaves the composer stuck on a turn that is
    // already over. The stream is the authority and corrects this either way.
    setBusy(true);
    try {
      await postJSON('/api/message', { text: text });
    } catch (err) {
      if (err.status === 409) {
        // 409 means "a turn is already running" or, on a fresh install, "no
        // model configured". Whether a turn is running is the server's fact,
        // not this page's guess: a refusal that collided with the end of the
        // previous turn must not leave the composer disabled.
        appendError(err.message);
        refreshStatus();
      } else {
        appendError('send failed: ' + err.message);
        setBusy(false);
      }
      dom.input.value = text;
    }
    dom.input.focus();
  }

  dom.btnCompact.addEventListener('click', compactNow);

  /* A picture does not travel as a message body. The server saves it under the
   * session's own directory, asks the vision model to describe it, and starts a
   * turn on the description — so what runs is an ordinary turn on ordinary text,
   * and what is stored is readable text beside a file on disk.
   *
   * The description is asked for inside this request, which is why the composer
   * is not put into the busy state here: the server's own status event says when
   * a turn is really running. A vision model that is unreachable refuses the
   * picture with a reason instead of leaving the page waiting on a turn that was
   * never started, and nothing was sent to the main model in the meantime.
   */
  async function sendPhoto(file) {
    if (!file) { return; }
    state.stick = true;
    setPhotoHint('describing ' + file.name + '…');
    try {
      await request('/api/attachment?name=' + encodeURIComponent(file.name), {
        method: 'POST',
        headers: { 'Content-Type': file.type || 'application/octet-stream' },
        body: file
      });
    } catch (err) {
      appendError('picture refused: ' + err.message);
    } finally {
      setPhotoHint('');
      refreshStatus();
    }
  }

  function setPhotoHint(text) {
    dom.photoHint.textContent = text;
    dom.photoHint.hidden = !text;
  }

  dom.btnPhoto.addEventListener('click', function () { dom.photoInput.click(); });

  dom.photoInput.addEventListener('change', function () {
    const file = dom.photoInput.files && dom.photoInput.files[0];
    /* Cleared before the upload, so choosing the same picture twice in a row is
     * two uploads rather than one upload and one silence. */
    dom.photoInput.value = '';
    sendPhoto(file);
  });

  dom.composer.addEventListener('submit', function (event) {
    event.preventDefault();
    sendMessage();
  });

  /* A background tab has its timers throttled, so a stream that died while the
   * page was hidden may never have been retried. Coming back to the front is the
   * one moment that can be noticed cheaply, and it is also the moment the user
   * is about to type again. */
  document.addEventListener('visibilitychange', function () {
    if (document.visibilityState === 'visible') { resync(); }
  });

  window.addEventListener('focus', function () {
    if (streamLooksStale()) { resync(); }
  });

  dom.input.addEventListener('keydown', function (event) {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
      event.preventDefault();
      if (!state.busy) { sendMessage(); }
    }
  });

  dom.btnAbort.addEventListener('click', async function () {
    dom.btnAbort.disabled = true;
    try {
      await postJSON('/api/abort', {});
    } catch (err) {
      appendError('abort failed: ' + err.message);
      dom.btnAbort.disabled = !state.busy;
    }
  });

  /* Starting a new session is the one session action that stays in the header;
   * it happens in the workspace the page is already in, so the tree has nothing
   * to switch. Focus goes to the composer, because that is what a new session
   * is for. */
  dom.btnNew.addEventListener('click', function () {
    switchSession({ action: 'new' }, null).then(function () { dom.input.focus(); });
  });

  /* The sidebar carries the same two-step control the rows do, so "delete
   * everything" is never a single click either. */
  const deleteAllControl = deleteControl(dom.wsDeleteAllHost, 'Delete all',
    deleteAllSelectedWorkspace);
  deleteAllControl.idle.dataset.focusKey = 'delall';
  deleteAllControl.idle.title = 'Delete every session in the selected workspace';

  /* Add workspace is the chooser, not a form: the click opens the desktop's own
   * dialog, and the folder's name is the workspace's. The trigger opens the form
   * instead on a machine where the chooser cannot run, which is why it is a
   * toggle as well — both gestures stay one click from the sidebar. */
  dom.workspaceAdd.addEventListener('click', function () {
    if (!dom.workspaceAddForm.hidden) {
      setAddFormOpen(false);
      return;
    }
    addWorkspaceByPicking();
  });
  dom.workspaceAddForm.addEventListener('submit', function (event) {
    event.preventDefault();
    addWorkspace(dom.wsNewPath.value);
  });
  /* Browse is the same chooser on its own: it fills the field rather than
   * adding, because a typed path is edited before it is sent. */
  dom.wsBrowse.addEventListener('click', function () {
    pickWorkspaceFolder(function (path) {
      dom.wsNewPath.value = str(path);
      dom.wsNewPath.focus();
    });
  });
  dom.workspacePick.addEventListener('click', function () {
    pickWorkspaceFolder(function (path) {
      addWorkspace(path);
    });
  });

  dom.btnSettings.addEventListener('click', function () { openSettings(); });
  dom.settingsClose.addEventListener('click', closeSettings);
  dom.settingsOverlay.addEventListener('click', function (event) {
    if (event.target === dom.settingsOverlay) { closeSettings(); }
  });
  dom.settingsForm.addEventListener('submit', function (event) {
    event.preventDefault();
    saveSettings();
  });
  dom.settingsTest.addEventListener('click', testSettings);
  dom.cfgProviderAddToggle.addEventListener('click', function () {
    const open = dom.cfgProviderForm.hidden;
    setProviderFormOpen(open);
    if (open) { dom.cfgNewName.focus(); }
  });
  dom.cfgProviderForm.addEventListener('submit', function (event) {
    event.preventDefault();
    addProvider();
  });
  dom.cfgClearKey.addEventListener('change', function () {
    const clearing = dom.cfgClearKey.checked;
    dom.cfgApiKey.disabled = clearing;
    if (clearing) { dom.cfgApiKey.value = ''; }
  });

  /* Escape closes what is open, innermost first: an open picker, then the
   * settings dialog, then the Add-workspace form, then an armed delete. The
   * sidebar itself is a region, not a popup — it is closed with its own
   * toggle, on purpose. */  function cancelArmedDeletes() {
    let armed = false;
    tree.deletes.forEach(function (control) {
      if (control.armed && control.idle.isConnected) {
        armed = true;
        control.reset();
        control.idle.focus();
      }
    });
    return armed;
  }

  document.addEventListener('keydown', function (event) {
    if (event.key !== 'Escape') { return; }
    const open = openPicker();
    if (open) { open.closePanel(true); }
    else if (!dom.settingsOverlay.hidden) { closeSettings(); }
    else if (dom.sidebar.classList.contains('open') || dom.side.classList.contains('open')) {
      closeSheets();
    } else if (!dom.workspaceAddForm.hidden) {
      setAddFormOpen(false);
      dom.workspaceAdd.focus();
    } else { cancelArmedDeletes(); }
  });

  /* A picker closes when the click lands outside it. The trigger toggles
   * itself, so a click on it is inside the root and never double-toggles. The
   * check runs in the capture phase because choosing an option re-renders the
   * list, and by the time the event bubbles the clicked node is detached —
   * which would look exactly like a click outside. */
  document.addEventListener('click', function (event) {
    pickers.forEach(function (picker) {
      if (picker.isOpen() && !picker.contains(event.target)) { picker.closePanel(false); }
    });
  }, true);

  dom.btnAuto.addEventListener('click', async function () {
    const next = !autoApproveOn();
    dom.btnAuto.disabled = true;
    // Optimistic paint; the response and the following status event reconcile it.
    state.autoApprove = next;
    paintAuto();
    try {
      const res = await postJSON('/api/auto-approve', { enabled: next });
      if (res && typeof res.autoApprove === 'boolean') { state.autoApprove = res.autoApprove; }
    } catch (err) {
      state.autoApprove = !next;
      appendError('auto-approve failed: ' + err.message);
    }
    paintAuto();
    dom.btnAuto.disabled = false;
    if (autoApproveOn()) { approvePending(); }
    refreshLive();
  });

  dom.btnSide.addEventListener('click', function () {
    const narrow = window.matchMedia('(max-width: 900px)').matches;
    if (narrow) {
      const open = dom.side.classList.toggle('open');
      dom.btnSide.setAttribute('aria-expanded', open ? 'true' : 'false');
      // One sheet at a time: the sidebar is the other one, and leaving it up
      // would put the panel behind a panel.
      if (open) { dom.sidebar.classList.remove('open'); }
    } else {
      const collapsed = dom.side.classList.toggle('collapsed');
      dom.btnSide.setAttribute('aria-expanded', collapsed ? 'false' : 'true');
    }
    paintScrim();
  });

  window.addEventListener('resize', function () {
    if (!window.matchMedia('(max-width: 900px)').matches) {
      dom.side.classList.remove('open');
    }
    if (!sheetsAreSheets()) {
      // Back to columns: the sheets were a phone's layout, and the panels have
      // their own state (collapsed or not) for this one.
      dom.sidebar.classList.remove('open');
      dom.side.classList.remove('open');
    }
    paintScrim();
  });

  // --------------------------------------------------------------- start

  async function init() {
    initTheme();
    initWallpaper();
    initSidebar();
    initPickers();
    paintAuto();
    setBusy(false);
    // The first status tells us which session the page is showing; its history
    // is rendered before the stream opens, so the initial replay has no live
    // events to race with. The tree loads alongside, from whatever the stored
    // expanded state says to open.
    await refreshStatus();
    loadWorkspaces();
    if (state.historyPromise) { await state.historyPromise; }
    connect();
    // The picker above the composer must offer the catalogue before Settings is
    // ever opened, so both are read (once) at start.
    const cfg = await readSettingsConfig();
    await refreshCatalog();
    const settingsOpen = await maybeOpenSettingsOnFirstLoad(cfg);
    if (!settingsOpen) { dom.input.focus(); }
  }

  init();
})();
