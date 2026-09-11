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
    btnWorkspace: $('btn-workspace'),
    wsName: $('ws-name'),
    live: $('live'),
    liveText: $('live-text'),
    btnNew: $('btn-new'),
    btnSessions: $('btn-sessions'),
    btnAuto: $('btn-auto'),
    autoState: $('auto-state'),
    btnSettings: $('btn-settings'),
    btnAbort: $('btn-abort'),
    btnSide: $('btn-side'),
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
    sessionInfo: $('session-info'),
    workspaceInfo: $('workspace-info'),
    overlay: $('sessions-overlay'),
    sessionsBody: $('sessions-body'),
    sessionsClose: $('sessions-close'),
    workspaceOverlay: $('workspace-overlay'),
    workspaceList: $('workspace-list'),
    workspaceClose: $('workspace-close'),
    workspaceError: $('workspace-error'),
    workspaceAddToggle: $('workspace-add-toggle'),
    workspaceAddForm: $('workspace-add-form'),
    workspaceSave: $('workspace-save'),
    wsNewName: $('ws-new-name'),
    wsNewPath: $('ws-new-path'),
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
    cfgProvider: $('cfg-provider'),
    cfgModel: $('cfg-model'),
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
    cfgTemperature: $('cfg-temperature')
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
    usage: null            // last usage object seen (event or status)
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

  /* A long path must be allowed to wrap at its separators, not inside a
   * directory name: each `/` becomes a real break opportunity, so the panel
   * reads "…/Desktop/" + "Workspace/oh-my-ccj" instead of cutting a name in
   * half. The text itself is unchanged — <wbr> carries no character. */
  function appendPathValue(node, text) {
    const value = str(text);
    value.split('/').forEach(function (part, i) {
      if (i) {
        node.appendChild(document.createTextNode('/'));
        node.appendChild(document.createElement('wbr'));
      }
      if (part) { node.appendChild(document.createTextNode(part)); }
    });
    if (!value) { node.textContent = '—'; }
  }

  function fillKV(node, rows) {
    node.textContent = '';
    rows.forEach(function (row) {
      node.appendChild(el('span', 'k', row[0]));
      const value = el('span', 'v');
      appendPathValue(value, row[1]);
      node.appendChild(value);
    });
  }

  /* The header control and the WORKSPACE panel block read from one place, so
   * the same name is never spelled two ways on screen. */
  function renderWorkspace(ws) {
    if (ws && typeof ws === 'object' && ('name' in ws || 'path' in ws)) {
      state.workspace = { name: str(ws.name), path: str(ws.path) };
    }
    const w = state.workspace || { name: '', path: '' };
    dom.wsName.textContent = w.name || 'workspace';
    dom.btnWorkspace.title = w.path
      ? w.name + ' — ' + w.path + '\nClick to switch workspace'
      : 'Switch workspace';
    fillKV(dom.workspaceInfo, [
      ['name', w.name || '—'],
      ['path', w.path || '—']
    ]);
    refreshPlaceholder();
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

  function renderSessionInfo(status) {
    const ws = state.workspace && state.workspace.path
      ? state.workspace.path
      : (str(status.cwd) || '—');
    fillKV(dom.sessionInfo, [
      ['version', str(status.version) || '—'],
      ['workspace', ws],
      ['session', str(status.sessionId) || '—'],
      ['messages', isFinite(Number(status.messageCount)) ? String(status.messageCount) : '—']
    ]);
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
    renderSessionInfo(status);
    if (Array.isArray(status.tools)) { renderTools(status.tools); }
    if (status.usage && typeof status.usage === 'object') { renderUsage(status.usage); }
    if (typeof status.autoApprove === 'boolean') {
      state.autoApprove = status.autoApprove;
      paintAuto();
    }
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
      if (state.everOpen) { refreshStatus(); }
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

  // ------------------------------------------------------------ sessions

  function closeSessions() { dom.overlay.hidden = true; }

  async function openSessions() {
    dom.overlay.hidden = false;
    dom.sessionsBody.textContent = '';
    dom.sessionsBody.appendChild(el('p', 'muted', 'Loading…'));
    try {
      const data = await request('/api/sessions');
      const list = data && Array.isArray(data.sessions) ? data.sessions : [];
      dom.sessionsBody.textContent = '';
      if (!list.length) {
        dom.sessionsBody.appendChild(el('p', 'muted', 'No saved sessions.'));
        return;
      }
      const ul = el('ul', 'session-list');
      list.forEach(function (item) {
        const li = el('li');
        const btn = el('button', 'session-item');
        btn.type = 'button';
        const current = str(item.id) === str(state.status && state.status.sessionId);
        if (current) { btn.classList.add('current'); }
        btn.appendChild(el('span', 'session-id', str(item.id)));
        if (item.preview) { btn.appendChild(el('span', 'session-preview', clip(firstLine(item.preview), 200))); }
        const meta = el('span', 'session-meta');
        meta.appendChild(el('span', null, str(Number(item.messageCount) || 0) + ' messages'));
        meta.appendChild(el('span', null, timeLabel(item.lastModified)));
        btn.appendChild(meta);
        btn.addEventListener('click', function () { switchSession({ action: 'resume', id: str(item.id) }, btn); });
        li.appendChild(btn);
        ul.appendChild(li);
      });
      dom.sessionsBody.appendChild(ul);
    } catch (err) {
      dom.sessionsBody.textContent = '';
      dom.sessionsBody.appendChild(el('p', 'err', 'Could not load sessions: ' + err.message));
    }
  }

  async function switchSession(body, button) {
    if (button) { button.disabled = true; }
    try {
      const res = await postJSON('/api/session', body);
      closeSessions();
      // Whether the transcript is cleared (and history fetched) is decided by
      // the session id in the answer, never by which button was pressed: a
      // refused `new` on an empty session answers with the same id, and the
      // transcript must stay exactly as it is.
      if (res && typeof res === 'object' && ('sessionId' in res || 'busy' in res)) {
        applyStatus(res);   // /api/session answers with the full status payload
      } else {
        await refreshStatus();
      }
      dom.input.focus();
    } catch (err) {
      if (button) { button.disabled = false; }
      appendError('session ' + body.action + ' failed: ' + err.message);
      if (!dom.overlay.hidden) {
        dom.sessionsBody.appendChild(el('p', 'err', 'Session ' + body.action + ' failed: ' + err.message));
      }
    }
  }

  // ----------------------------------------------------------- workspaces

  function clearWorkspaceErrors() {
    [dom.workspaceError, dom.wsNameError, dom.wsPathError].forEach(function (node) {
      node.textContent = '';
      node.hidden = true;
    });
  }

  /* One 400 message per refusal, and the field it belongs to is decided here:
   * a bad or duplicate name lands under Name (the registry is keyed by name),
   * an unusable directory under Path, anything else above the list. */
  function showWorkspaceError(message) {
    const text = str(message) || 'Workspace request failed.';
    if (/path|director|folder|\bdir\b|absolute|usable|permission|denied|creat/i.test(text)) {
      fieldError(dom.wsPathError, text);
      dom.wsNewPath.focus();
    } else if (/name|duplicate|already|exist|taken|invalid|reserved|blank/i.test(text)) {
      fieldError(dom.wsNameError, text);
      dom.wsNewName.focus();
    } else {
      dom.workspaceError.textContent = text;
      dom.workspaceError.hidden = false;
    }
  }

  function setAddFormOpen(open) {
    dom.workspaceAddForm.hidden = !open;
    dom.workspaceAddToggle.setAttribute('aria-expanded', open ? 'true' : 'false');
  }

  function closeWorkspaces() {
    if (dom.workspaceOverlay.hidden) { return; }
    dom.workspaceOverlay.hidden = true;
    dom.btnWorkspace.focus();
  }

  function renderWorkspaces(payload, focusName) {
    const list = payload && Array.isArray(payload.workspaces) ? payload.workspaces : [];
    const active = str(payload && payload.active);
    dom.workspaceList.textContent = '';
    if (!list.length) {
      dom.workspaceList.appendChild(el('li', 'muted', 'No workspaces reported.'));
      return;
    }
    let focusTarget = null;
    list.forEach(function (item) {
      const name = str(item.name);
      const isActive = item.active === true || (name !== '' && name === active);
      const li = el('li', 'workspace-item' + (isActive ? ' current' : ''));
      li.setAttribute('data-workspace', name);

      const pick = el('button', 'workspace-pick');
      pick.type = 'button';
      if (isActive) { pick.setAttribute('aria-current', 'true'); }
      const head = el('span', 'workspace-head');
      head.appendChild(el('span', 'workspace-name', name));
      if (isActive) { head.appendChild(el('span', 'workspace-badge', 'active')); }
      pick.appendChild(head);
      pick.appendChild(el('span', 'workspace-path', str(item.path) || '—'));
      const sessions = Number(item.sessions);
      if (isFinite(sessions)) {
        pick.appendChild(el('span', 'workspace-meta',
          sessions === 1 ? '1 session' : sessions.toLocaleString() + ' sessions'));
      }
      pick.addEventListener('click', function () { pickWorkspace(item, pick, isActive); });

      const remove = el('button', 'btn ghost workspace-remove', 'Remove');
      remove.type = 'button';
      remove.title = 'Forget ' + name + ' — its session files stay on disk';
      remove.addEventListener('click', function () { removeWorkspace(name, remove); });

      li.appendChild(pick);
      li.appendChild(remove);
      dom.workspaceList.appendChild(li);

      if (name === focusName || (!focusName && isActive)) { focusTarget = pick; }
    });
    // Focus is inside the dialog at all times while it is open: the active row
    // when there is one, otherwise the first row a keyboard can reach.
    if (!focusTarget) { focusTarget = dom.workspaceList.querySelector('.workspace-pick'); }
    if (focusTarget) { focusTarget.focus(); }
  }

  async function loadWorkspaces(focusName) {
    dom.workspaceList.textContent = '';
    dom.workspaceList.appendChild(el('li', 'muted', 'Loading…'));
    try {
      const data = await request('/api/workspaces');
      renderWorkspaces(data, focusName);
    } catch (err) {
      dom.workspaceList.textContent = '';
      dom.workspaceList.appendChild(el('li', 'err', 'Could not load workspaces: ' + str(err && err.message)));
    }
  }

  async function openWorkspaces() {
    if (!dom.settingsOverlay.hidden) { closeSettings(); }
    if (!dom.overlay.hidden) { closeSessions(); }
    clearWorkspaceErrors();
    dom.wsNewName.value = '';
    dom.wsNewPath.value = '';
    setAddFormOpen(false);
    dom.workspaceOverlay.hidden = false;
    await loadWorkspaces();
  }

  async function addWorkspace() {
    clearWorkspaceErrors();
    const name = dom.wsNewName.value.trim();
    const path = dom.wsNewPath.value.trim();
    if (!name) { fieldError(dom.wsNameError, 'Enter a workspace name.'); dom.wsNewName.focus(); return; }
    if (!path) { fieldError(dom.wsPathError, 'Enter a directory.'); dom.wsNewPath.focus(); return; }
    dom.workspaceSave.disabled = true;
    try {
      const res = await postJSON('/api/workspaces', { name: name, path: path });
      dom.wsNewName.value = '';
      dom.wsNewPath.value = '';
      setAddFormOpen(false);
      renderWorkspaces(res, name);   // the list is rebuilt from the answer
    } catch (err) {
      showWorkspaceError(err.message);
    }
    dom.workspaceSave.disabled = false;
  }

  async function removeWorkspace(name, button) {
    clearWorkspaceErrors();
    button.disabled = true;
    try {
      const res = await request('/api/workspace?name=' + encodeURIComponent(name), { method: 'DELETE' });
      renderWorkspaces(res);
    } catch (err) {
      button.disabled = false;
      showWorkspaceError(err.message);
    }
  }

  /* Switching is a two-step move: the request changes the server, and the
   * status event that follows is what tells the page which session the new
   * workspace is on. The transcript is emptied *here* so the old workspace's
   * messages are never on screen next to the new workspace's name. */
  async function pickWorkspace(item, button, isActive) {
    const name = str(item.name);
    if (isActive || (state.workspace && state.workspace.name === name)) {
      closeWorkspaces();   // already here — do not clear a transcript for nothing
      return;
    }
    clearWorkspaceErrors();
    button.disabled = true;
    try {
      const res = await postJSON('/api/workspace', { name: name });
      closeWorkspaces();
      state.sessionId = '';   // the next status must clear and re-fetch
      clearTranscript();
      if (res && typeof res === 'object' && ('sessionId' in res || 'busy' in res || 'workspace' in res)) {
        applyStatus(res);
      } else {
        await refreshStatus();
      }
      dom.input.focus();
    } catch (err) {
      button.disabled = false;
      showWorkspaceError(err.message);
    }
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
    [dom.cfgModelError, dom.cfgApiKeyError].forEach(function (node) {
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

  function renderSettings(cfg) {
    // The provider list comes from the server; the current value is kept even
    // if this build of the server does not list it.
    const providers = Array.isArray(cfg.providers) ? cfg.providers.slice() : [];
    const current = str(cfg.provider);
    if (current && providers.indexOf(current) < 0) { providers.unshift(current); }
    if (!providers.length) { providers.push(current || 'custom'); }
    dom.cfgProvider.textContent = '';
    providers.forEach(function (name) {
      dom.cfgProvider.appendChild(el('option', null, str(name)));
    });
    dom.cfgProvider.value = current || str(providers[0]);

    dom.cfgModel.value = str(cfg.model);
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
  }

  /* Only the fields this form manages are sent; apiKey is omitted when the
   * field is empty so the stored key survives an unrelated change. */
  function settingsPayload() {
    const payload = {
      provider: dom.cfgProvider.value,
      model: dom.cfgModel.value.trim(),
      baseUrl: dom.cfgBaseUrl.value.trim(),
      apiKeyEnv: dom.cfgApiKeyEnv.value.trim() || 'OPENAI_API_KEY'
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
      fieldError(dom.cfgModelError, message);
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

  function focusSettingsForm() { dom.cfgProvider.focus(); }

  function closeSettings() {
    if (dom.settingsOverlay.hidden) { return; }
    dom.settingsOverlay.hidden = true;
    // The panel is opened from the button (or automatically on a first run);
    // either way the button is where focus belongs when it closes.
    dom.btnSettings.focus();
  }

  /* `preloaded` skips the round trip when the caller already has GET /api/config. */
  async function openSettings(preloaded) {
    if (!dom.overlay.hidden) { closeSessions(); }
    if (!dom.workspaceOverlay.hidden) { closeWorkspaces(); }
    dom.settingsOverlay.hidden = false;

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
    renderSettings(cfg && typeof cfg === 'object' ? cfg : {});
    focusSettingsForm();
  }

  /* A first run has no model, and the page cannot do anything useful until one
   * is picked, so the panel opens itself. */
  async function maybeOpenSettingsOnFirstLoad() {
    let cfg = null;
    try {
      cfg = await request('/api/config');
    } catch (err) {
      return false;
    }
    if (!cfg || typeof cfg !== 'object' || cfg.configured !== false) { return false; }
    await openSettings(cfg);
    return true;
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

  dom.btnNew.addEventListener('click', function () {
    switchSession({ action: 'new' });
  });

  dom.btnSessions.addEventListener('click', openSessions);
  dom.sessionsClose.addEventListener('click', closeSessions);
  dom.overlay.addEventListener('click', function (event) {
    if (event.target === dom.overlay) { closeSessions(); }
  });

  dom.btnWorkspace.addEventListener('click', function () { openWorkspaces(); });
  dom.workspaceClose.addEventListener('click', closeWorkspaces);
  dom.workspaceOverlay.addEventListener('click', function (event) {
    if (event.target === dom.workspaceOverlay) { closeWorkspaces(); }
  });
  dom.workspaceAddToggle.addEventListener('click', function () {
    const open = dom.workspaceAddForm.hidden;
    setAddFormOpen(open);
    if (open) { dom.wsNewName.focus(); }
  });
  dom.workspaceAddForm.addEventListener('submit', function (event) {
    event.preventDefault();
    addWorkspace();
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
  dom.cfgClearKey.addEventListener('change', function () {
    const clearing = dom.cfgClearKey.checked;
    dom.cfgApiKey.disabled = clearing;
    if (clearing) { dom.cfgApiKey.value = ''; }
  });

  document.addEventListener('keydown', function (event) {
    if (event.key !== 'Escape') { return; }
    if (!dom.settingsOverlay.hidden) { closeSettings(); }
    else if (!dom.workspaceOverlay.hidden) { closeWorkspaces(); }
    else if (!dom.overlay.hidden) { closeSessions(); }
  });

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
    paintAuto();
    setBusy(false);
    // The first status tells us which session the page is showing; its history
    // is rendered before the stream opens, so the initial replay has no live
    // events to race with.
    await refreshStatus();
    if (state.historyPromise) { await state.historyPromise; }
    connect();
    const settingsOpen = await maybeOpenSettingsOnFirstLoad();
    if (!settingsOpen) { dom.input.focus(); }
  }

  init();
})();
