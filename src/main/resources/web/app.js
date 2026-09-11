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
   * than a fake "0 ms". */
  function msLabel(v) {
    if (v === null || v === undefined) { return ''; }
    const n = Number(v);
    return isFinite(n) ? Math.max(0, Math.round(n)) + ' ms' : '';
  }

  function timeLabel(v) {
    const d = new Date(str(v));
    return isNaN(d.getTime()) ? str(v) : d.toLocaleString();
  }

  /* A session's age is the question the list answers ("which one was I just
   * in?"), and an absolute timestamp makes the reader do the subtraction. The
   * exact time is still one hover away, in the row's tooltip. */
  function relativeTime(v) {
    const d = new Date(str(v));
    if (isNaN(d.getTime())) { return str(v); }
    const seconds = Math.round((Date.now() - d.getTime()) / 1000);
    if (seconds < 90) { return 'just now'; }
    const minutes = Math.round(seconds / 60);
    if (minutes < 90) { return minutes + 'm ago'; }
    const hours = Math.round(minutes / 60);
    if (hours < 36) { return hours + 'h ago'; }
    const days = Math.round(hours / 24);
    return days < 8 ? days + 'd ago' : d.toLocaleDateString();
  }

  function hasContent(node) { return !!(node && node.firstChild); }

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
    btnSidebar: $('btn-sidebar'),
    btnSide: $('btn-side'),
    btnTheme: $('btn-theme'),
    themeIcon: $('theme-icon'),
    themeLabel: $('theme-label'),
    transcript: $('transcript'),
    jump: $('jump'),
    composer: $('composer'),
    input: $('input'),
    send: $('send'),
    hint: $('composer-hint'),
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
    uElapsed: $('u-elapsed'),
    sidebar: $('sidebar'),
    sidebarCollapse: $('sidebar-collapse'),
    sidebarAlert: $('sidebar-alert'),
    sidebarNote: $('sidebar-note'),
    wsTree: $('ws-tree'),
    wsDeleteAllHost: $('ws-delete-all-host'),
    workspaceAddToggle: $('workspace-add-toggle'),
    workspaceAddForm: $('workspace-add-form'),
    workspaceSave: $('workspace-save'),
    wsNewName: $('ws-new-name'),
    wsNewPath: $('ws-new-path'),
    wsBrowse: $('ws-browse'),
    wsBrowseHint: $('ws-browse-hint'),
    wsNameError: $('ws-name-error'),
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
    cfgMaxSteps: $('cfg-maxsteps'),
    cfgTemperature: $('cfg-temperature'),
    cfgProvidersNote: $('cfg-providers-note'),
    cfgProviderList: $('cfg-provider-list'),
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
    runningTools: 0,
    autoApprove: false,    // status.autoApprove, owned by the server
    block: null,           // current assistant block
    toolCards: new Map(),  // tool call id -> { root, ... }
    approvals: new Map(),  // approval id -> record
    stick: true,           // transcript pinned to the bottom
    status: null,
    workspace: null,       // {name, path} of the active workspace
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
    configProviders: []    // provider names the server says are usable (GET /api/config)
  };

  // --------------------------------------------------------- text batching

  // Assistant deltas arrive a few characters at a time; each element keeps a
  // pending buffer that is flushed at most once per frame so a long turn costs
  // one text node per flush instead of thousands. A timer backs up rAF because
  // requestAnimationFrame does not fire in a hidden/background tab.
  const queues = new Map();
  let rafId = 0;
  let flushTimer = 0;

  function queueText(node, delta) {
    if (!node || !delta) { return; }
    queues.set(node, (queues.get(node) || '') + delta);
    if (!rafId && !flushTimer) {
      rafId = requestAnimationFrame(flushText);
      flushTimer = setTimeout(flushText, 80);
    }
  }

  function flushText() {
    if (rafId) { cancelAnimationFrame(rafId); rafId = 0; }
    if (flushTimer) { clearTimeout(flushTimer); flushTimer = 0; }
    if (!queues.size) { return; }
    const atBottom = nearBottom();
    queues.forEach(function (text, node) {
      node.appendChild(document.createTextNode(text));
    });
    queues.clear();
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
      // the replay ends with a single scroll to the bottom.
      node.classList.add('replay');
      node.setAttribute('data-replay', '1');
      dom.transcript.appendChild(node);
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
    if (status.workspace && typeof status.workspace === 'object') {
      renderWorkspace(status.workspace);
    } else if (!state.workspace && status.cwd) {
      renderWorkspace({ name: baseName(status.cwd), path: str(status.cwd) });
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
    const m = Math.floor(s / 60);
    const rest = Math.round(s % 60);
    return m + 'm ' + (rest < 10 ? '0' : '') + rest + 's';
  }

  function setUsageCell(node, text, bad) {
    node.textContent = text;
    node.classList.toggle('bad', !!bad);
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
    [dom.uTurns, dom.uSteps, dom.uIn, dom.uOut, dom.uCached, dom.uTools, dom.uErrors, dom.uElapsed]
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
    setUsageCell(dom.uElapsed, fmtDuration(u.elapsedMs), false);

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

  function applyStatus(status) {
    if (!status || typeof status !== 'object') { return; }
    state.status = status;
    applyWorkspace(status);
    if ('sessionId' in status) { noteSession(str(status.sessionId)); }
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
    setBusy(!!status.busy || pendingApprovals() > 0);
  }

  async function refreshStatus() {
    try {
      const status = await request('/api/status');
      applyStatus(status);
    } catch (err) {
      appendError('Could not read status: ' + err.message);
    }
  }

  // ----------------------------------------------------------- transcript

  function appendLine(cls, text) {
    return appendToTranscript(el('p', cls, text));
  }

  function appendError(text) { return appendLine('ev ev-error', text); }

  /* Server notices (refused New session, retries, step-limit warnings) are
   * transcript lines. The Usage panel is owned by `usage` events and
   * status.usage, so a notice never writes there. */
  function appendNotice(text) { return appendLine('ev ev-notice', text); }

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
      const node = el('div', key === 'reasoning' ? 'msg-reasoning' : 'msg-answer');
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
    dom.transcript.textContent = '';
    showPlaceholder();
    state.block = null;
    state.toolCards.clear();
    state.approvals.clear();
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

  /* Replay goes through the same dispatch() the live stream uses, so a restored
   * conversation renders exactly like a live one. Replay events carry no SSE
   * id, so they never touch state.lastEventId: the reconnect dedupe keeps
   * working, and live events always land *after* the snapshot they follow. */
  function renderReplay(events) {
    state.replaying = true;
    try {
      if (!events.length) { showPlaceholder(); return; }
      dropPlaceholder();
      events.forEach(function (ev) {
        if (!ev || typeof ev !== 'object') { return; }
        const type = str(ev.type);
        if (type === 'status' || type === 'done') { return; }   // live lifecycle, not content
        try {
          dispatch(ev);
        } catch (err) {
          appendError('UI error replaying ' + type + ': ' + str(err && err.message ? err.message : err));
        }
      });
      flushText();
    } finally {
      state.replaying = false;
    }
    state.stick = true;
    scrollToBottom();
    dom.jump.hidden = true;
    refreshLive();
  }

  function flushPendingLive() {
    const queued = state.pendingLive;
    state.pendingLive = [];
    queued.forEach(processMessage);
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
      const root = el('div', 'ev ev-approval pending');
      const head = el('div', 'approval-head');
      head.appendChild(el('span', 'approval-flag', 'approval needed'));
      head.appendChild(el('span', 'approval-title', str(ev.title) || 'Tool call'));
      root.appendChild(head);

      const detail = el('pre', 'approval-detail', str(ev.detail) || '(no detail)');
      root.appendChild(detail);

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

      rec = {
        id: id, root: root, approve: approve, deny: deny,
        remember: remember, stateEl: stateEl, resolved: false
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
        const remember = allow && rec.remember.checked;
        rec.settle(allow, allow ? (remember ? 'Approved (always allow)' : 'Approved') : 'Denied',
          allow ? 'ok' : 'bad');
        answerApproval(id, allow, remember);
      };

      approve.addEventListener('click', function () { rec.answer(true); });
      deny.addEventListener('click', function () { rec.answer(false); });

      state.approvals.set(id, rec);
      appendToTranscript(root);
    }

    if (autoApproveOn() && !rec.resolved) {
      rec.remember.checked = true;
      rec.answer(true);
      rec.stateEl.textContent = 'Approved automatically (Approve all)';
    }
    refreshLive();
    updateJump();
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
      assistantNode(block, 'text').appendChild(document.createTextNode(finalText));
    }
    state.block = null;
    const aborted = !!(ev && ev.aborted);
    appendToTranscript(el('div', 'ev ev-done', aborted ? 'turn aborted' : 'turn complete'));
    setBusy(false);
    setLive('idle');
    dom.input.focus();
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
      case 'text': queueText(assistantNode(assistantBlock(), 'text'), str(ev.delta)); break;
      case 'reasoning': queueText(assistantNode(assistantBlock(), 'reasoning'), str(ev.delta)); break;
      case 'tool': breakBlock(); onTool(ev); break;
      case 'approval': breakBlock(); onApproval(ev); break;
      case 'approval-closed': onApprovalClosed(ev); break;
      case 'notice': breakBlock(); appendNotice(str(ev.text)); break;
      case 'usage': renderUsage(ev); break;
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

    try {
      dispatch(ev);
    } catch (err) {
      appendError('UI error handling ' + str(ev.type) + ': ' + (err && err.message ? err.message : err));
    }
    if (hasId) { state.lastEventId = idNum; }
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
   * again here is idempotent and keeps one function as the source of truth. */
  function applyTheme() {
    const theme = visibleTheme(themePref);
    const root = document.documentElement;
    root.setAttribute('data-theme', theme);
    root.setAttribute('data-theme-pref', themePref);
    root.style.colorScheme = theme;
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
    const list = tree.payload && Array.isArray(tree.payload.workspaces) ? tree.payload.workspaces : [];
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

  function toggleSidebar() {
    setSidebarCollapsed(!dom.sidebar.classList.contains('collapsed'));
  }

  function initSidebar() {
    setSidebarCollapsed(initialSidebarCollapsed());
    dom.btnSidebar.addEventListener('click', toggleSidebar);
    dom.sidebarCollapse.addEventListener('click', toggleSidebar);
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

  /* One session: a preview line and its age, and the same two-step delete the
   * dialogs used, so the gesture did not change when the list moved. */
  function sessionRow(name, item, index, activeId) {
    const id = str(item.id);
    const current = id !== '' && id === activeId;
    const li = el('li', 'session-row' + (current ? ' current' : ''));
    if (current) { li.setAttribute('aria-current', 'true'); }

    const btn = el('button', 'session-item');
    btn.type = 'button';
    btn.dataset.focusKey = 's:' + name + ':' + id;
    btn.title = id + (current ? ' — the session on screen' : '')
      + '\n' + str(name) + ' · ' + timeLabel(item.lastModified);
    btn.appendChild(el('span', 'session-id', clip(id, 30)));
    const preview = firstLine(str(item.preview));
    if (preview) { btn.appendChild(el('span', 'session-preview', clip(preview, 200))); }
    const meta = el('span', 'session-meta');
    const count = Number(item.messageCount);
    const messages = isFinite(count) ? count : 0;
    meta.appendChild(el('span', null, relativeTime(item.lastModified)));
    meta.appendChild(el('span', null, messages === 1 ? '1 message' : messages + ' messages'));
    btn.appendChild(meta);
    btn.addEventListener('click', function () { openWorkspaceSession(name, id, btn); });

    const actions = el('span', 'session-actions');
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
      cached.items.forEach(function (item, index) {
        list.appendChild(sessionRow(name, item, index, activeId));
      });
    }
    return list;
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
    // The control the keyboard was on may be gone (a deleted row, a forgotten
    // workspace); the selected node is where it lands instead.
    if (keep && !focusByKey(keep)) { focusByKey('ws:' + tree.selected); }
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
    if (!name || name === workspaceName()) { return; }
    clearSidebarMessages();
    if (button) { button.disabled = true; }
    try {
      await switchWorkspaceTo(name);
      // A switch invalidates every cached list, and the counts move with the
      // active mark, so the list is re-read rather than patched.
      await loadWorkspaces({ focusKey: 'ws:' + name });
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
    [dom.wsNameError, dom.wsPathError].forEach(function (node) {
      node.textContent = '';
      node.hidden = true;
    });
    dom.wsBrowseHint.textContent = '';
    dom.wsBrowseHint.hidden = true;
  }

  /* One 400 message per refusal, and the field it belongs to is decided here:
   * a bad or duplicate name lands under Name (the registry is keyed by name),
   * an unusable directory under Path, anything else above the tree. */
  function showWorkspaceError(message) {
    const text = str(message) || 'Workspace request failed.';
    if (/path|director|folder|\bdir\b|absolute|usable|permission|denied|creat/i.test(text)) {
      fieldError(dom.wsPathError, text);
      dom.wsNewPath.focus();
    } else if (/name|duplicate|already|exist|taken|invalid|reserved|blank/i.test(text)) {
      fieldError(dom.wsNameError, text);
      dom.wsNewName.focus();
    } else {
      sidebarError(text);
    }
  }

  function setAddFormOpen(open) {
    dom.workspaceAddForm.hidden = !open;
    dom.workspaceAddToggle.setAttribute('aria-expanded', open ? 'true' : 'false');
  }

  /* The chooser runs on the machine that serves the page, so this request *is*
   * the desktop dialog: it blocks until the user answers, or the server gives
   * up after two minutes and reports a cancel. Either way the field is the
   * fallback — a 400 (no desktop, no chooser) is shown next to it and stays
   * typed-in-able. */
  async function browseWorkspacePath() {
    clearWorkspaceErrors();
    dom.wsBrowse.disabled = true;
    dom.wsBrowse.textContent = 'Waiting…';
    dom.wsBrowseHint.textContent = 'A folder chooser was opened on the desktop — choose a folder in that window.';
    dom.wsBrowseHint.hidden = false;
    try {
      const res = await request('/api/workspaces/browse', { method: 'POST' });
      if (res && typeof res === 'object' && res.path && !res.cancelled) {
        dom.wsNewPath.value = str(res.path);
        dom.wsBrowseHint.textContent = '';
        dom.wsBrowseHint.hidden = true;
        dom.wsNewPath.focus();
      } else {
        dom.wsBrowseHint.textContent = 'No folder chosen — the chooser was dismissed. Type the path instead.';
      }
    } catch (err) {
      dom.wsBrowseHint.hidden = true;
      fieldError(dom.wsPathError, str(err && err.message) || 'Could not open a folder chooser.');
      dom.wsNewPath.focus();
    }
    dom.wsBrowse.disabled = false;
    dom.wsBrowse.textContent = 'Browse…';
  }

  async function addWorkspace() {
    clearWorkspaceErrors();
    const name = dom.wsNewName.value.trim();
    const path = dom.wsNewPath.value.trim();
    if (!name) {
      fieldError(dom.wsNameError, 'Enter a workspace name.');
      dom.wsNewName.focus();
      return;
    }
    if (!path) {
      fieldError(dom.wsPathError, 'Enter a directory.');
      dom.wsNewPath.focus();
      return;
    }
    dom.workspaceSave.disabled = true;
    try {
      const res = await postJSON('/api/workspaces', { name: name, path: path });
      dom.wsNewName.value = '';
      dom.wsNewPath.value = '';
      setAddFormOpen(false);
      // Adding is not switching: the new node is opened so its (empty) list is
      // visible, and the page stays in the workspace it was working in.
      tree.expanded.add(name);
      saveExpanded();
      sidebarNote('Workspace ' + name + ' added — press Use to work there.');
      acceptWorkspaces(res, { selected: name, focusKey: 'ws:' + name });
    } catch (err) {
      showWorkspaceError(err.message);
    }
    dom.workspaceSave.disabled = false;
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
      modelUse: root.querySelector('[data-role="modelUse"]'),
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

    function renderProviders() {
      nodes.providers.textContent = '';
      const list = pickerProviders();
      const selected = picker.selection.provider;
      const browsed = picker.browsed || selected;
      if (selected && !pickerProviderKnown(selected)) {
        list.unshift({ name: selected, kind: '', baseUrl: '', builtIn: false, known: false, models: [] });
      }
      if (!list.length) {
        nodes.providers.appendChild(el('li', 'picker-empty',
          state.catalogError ? 'Providers unavailable: ' + state.catalogError : 'No providers reported.'));
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
        nodes.providers.appendChild(li);
      });
    }

    function renderModels() {
      nodes.models.textContent = '';
      const provider = picker.browsed || picker.selection.provider;
      if (!provider) {
        nodes.models.appendChild(el('li', 'picker-empty', 'Choose a provider first.'));
        return;
      }
      const entries = catalogModelsFor(provider).slice();
      const current = picker.selection.provider.toLowerCase() === provider.toLowerCase()
        ? picker.selection.model : '';
      const names = entries.map(function (entry) { return str(entry.model).toLowerCase(); });
      if (current && names.indexOf(current.toLowerCase()) < 0) {
        entries.unshift({ provider: provider, model: current, source: '' });
      }
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
        const li = el('li');
        const btn = el('button', 'picker-option');
        btn.type = 'button';
        btn.dataset.model = model;
        const isCurrent = current !== '' && model.toLowerCase() === current.toLowerCase();
        btn.classList.toggle('active', isCurrent);
        btn.setAttribute('aria-pressed', isCurrent ? 'true' : 'false');
        btn.appendChild(el('span', 'picker-option-name', model));
        btn.appendChild(optionMeta([str(entry.source)]));
        btn.addEventListener('click', function () {
          choose('model', { provider: provider, model: model });
        });
        li.appendChild(btn);
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

    /* A provider pick is a browse: the right column swaps and nothing is sent.
     * In the settings form the pick is also a draft change of the form's
     * provider (there is no "apply" there until Save), so it moves the
     * selection; in the composer it deliberately does not. */
    function chooseProvider(name) {
      const clean = str(name).trim();
      if (!clean) { return; }
      clearError();
      picker.browsed = clean;
      if (providerSelects) { picker.selection.provider = clean; }
      picker.render();
      if (onSelect) { onSelect('provider', picker.selection); }
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
      });
    }

    /* The model column keeps a text field next to the list so a provider with
     * no listed models — or a model the catalogue does not know — is still
     * selectable by hand, the way the settings panel used to allow. */
    function useManualModel() {
      const model = nodes.modelInput.value.trim();
      const provider = picker.browsed || picker.selection.provider;
      if (!provider) { showError('Choose a provider first.'); return; }
      if (!model) { showError('Type a model name first.'); return; }
      choose('model', { provider: provider, model: model });
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
      const before = picker.selection.provider + '\u0000' + picker.selection.model
        + '\u0000' + picker.selection.reasoning;
      if ('provider' in status) { picker.selection.provider = str(status.provider); }
      if ('model' in status) { picker.selection.model = str(status.model); }
      if ('reasoning' in status) { picker.selection.reasoning = str(status.reasoning) || 'default'; }
      if (Array.isArray(status.reasoningLevels) && status.reasoningLevels.length) {
        state.reasoningLevels = status.reasoningLevels.map(str);
      }
      if (!picker.browsed) { picker.browsed = picker.selection.provider; }
      const after = picker.selection.provider + '\u0000' + picker.selection.model
        + '\u0000' + picker.selection.reasoning;
      if (before !== after) { picker.render(); }
    };

    picker.openPanel = function () {
      if (picker.open) { return; }
      picker.open = true;
      if (!picker.browsed) { picker.browsed = picker.selection.provider; }
      nodes.modelInput.value = '';
      nodes.panel.hidden = false;
      nodes.trigger.setAttribute('aria-expanded', 'true');
      clearError();
      picker.render();
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
    nodes.modelUse.addEventListener('click', useManualModel);
    /* Enter in the model field applies it — and must not reach the composer,
     * where the same key sends the message. */
    nodes.modelInput.addEventListener('keydown', function (event) {
      if (event.key !== 'Enter') { return; }
      event.preventDefault();
      event.stopPropagation();
      useManualModel();
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
    [dom.cfgProviderError, dom.cfgModelError, dom.cfgApiKeyError].forEach(function (node) {
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
      models: records(payload && payload.models)
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
  function settingsProviderChanged(name) {
    renderProviderHint();
    renderModelHint();
    const info = providerInfo(name);
    if (!info) { return; }
    if (str(info.baseUrl)) { dom.cfgBaseUrl.value = str(info.baseUrl); }
    dom.cfgApiKeyEnv.value = str(info.kind) === 'anthropic' ? 'ANTHROPIC_API_KEY' : 'OPENAI_API_KEY';
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

  /* Built-ins are code, so this list is only ever the user's own definitions —
   * and it says so out loud when there are none, rather than leaving an empty
   * box whose meaning the user has to guess. */
  function renderProviderSection() {
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

    dom.cfgBaseUrl.value = str(cfg.baseUrl);
    dom.cfgApiKeyEnv.value = str(cfg.apiKeyEnv) || 'OPENAI_API_KEY';
    dom.cfgMaxSteps.value = cfg.maxSteps === null || cfg.maxSteps === undefined || !isFinite(Number(cfg.maxSteps))
      ? '' : String(cfg.maxSteps);
    dom.cfgTemperature.value =
      cfg.temperature === null || cfg.temperature === undefined ? '' : str(cfg.temperature);

    const source = str(cfg.apiKeySource);
    dom.cfgApiKey.value = '';
    dom.cfgApiKey.disabled = false;
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

  /* Only the fields this form manages are sent; apiKey is omitted when the
   * field is empty so the stored key survives an unrelated change. */
  function settingsPayload() {
    const chosen = settingsPicker.selection;
    const payload = {
      provider: chosen.provider,
      model: chosen.model,
      baseUrl: dom.cfgBaseUrl.value.trim(),
      apiKeyEnv: dom.cfgApiKeyEnv.value.trim() || 'OPENAI_API_KEY',
      reasoning: chosen.reasoning || 'default'
    };
    const clearing = dom.cfgClearKey.checked;
    if (clearing) {
      payload.clearApiKey = true;
    } else if (dom.cfgApiKey.value) {
      payload.apiKey = dom.cfgApiKey.value;
    }
    const steps = numField(dom.cfgMaxSteps);
    if (steps !== undefined) { payload.maxSteps = Math.max(1, Math.round(steps)); }
    const temperature = numField(dom.cfgTemperature);
    if (temperature !== undefined) { payload.temperature = temperature; }
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
    if (status === 401 || status === 403 || /api[- ]?key|unauthor|forbidden|invalid key|401|403/i.test(message)) {
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
    if (state.busy) { return; }
    const text = dom.input.value.trim();
    if (!text) { return; }
    dom.input.value = '';
    state.stick = true;
    scrollToBottom();
    try {
      await postJSON('/api/message', { text: text });
      setBusy(true);
    } catch (err) {
      if (err.status === 409) {
        // 409 means "a turn is already running" or, on a fresh install, "no
        // model configured". Only the first one makes the page busy — the
        // second must leave the composer usable so the user can retry.
        appendError(err.message);
        setBusy(state.configured === false ? false : true);
      } else {
        appendError('send failed: ' + err.message);
      }
      dom.input.value = text;
    }
    dom.input.focus();
  }

  dom.composer.addEventListener('submit', function (event) {
    event.preventDefault();
    sendMessage();
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

  dom.workspaceAddToggle.addEventListener('click', function () {
    const open = dom.workspaceAddForm.hidden;
    setAddFormOpen(open);
    if (open) { dom.wsNewName.focus(); }
  });
  dom.workspaceAddForm.addEventListener('submit', function (event) {
    event.preventDefault();
    addWorkspace();
  });
  dom.wsBrowse.addEventListener('click', browseWorkspacePath);

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
   * toggle, on purpose. */
  function cancelArmedDeletes() {
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
    else if (!dom.workspaceAddForm.hidden) {
      setAddFormOpen(false);
      dom.workspaceAddToggle.focus();
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
    } else {
      const collapsed = dom.side.classList.toggle('collapsed');
      dom.btnSide.setAttribute('aria-expanded', collapsed ? 'false' : 'true');
    }
  });

  window.addEventListener('resize', function () {
    if (!window.matchMedia('(max-width: 900px)').matches) {
      dom.side.classList.remove('open');
    }
  });

  // --------------------------------------------------------------- start

  async function init() {
    initTheme();
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
