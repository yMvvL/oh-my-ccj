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

  function msLabel(v) {
    const n = Number(v);
    return isFinite(n) ? Math.max(0, Math.round(n)) + ' ms' : '';
  }

  function timeLabel(v) {
    const d = new Date(str(v));
    return isNaN(d.getTime()) ? str(v) : d.toLocaleString();
  }

  function hasContent(node) { return !!(node && node.firstChild); }

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
    chipBaseUrl: $('chip-baseurl'),
    chipSession: $('chip-session'),
    live: $('live'),
    liveText: $('live-text'),
    btnNew: $('btn-new'),
    btnSessions: $('btn-sessions'),
    btnAuto: $('btn-auto'),
    autoState: $('auto-state'),
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
    sessionInfo: $('session-info'),
    overlay: $('sessions-overlay'),
    sessionsBody: $('sessions-body'),
    sessionsClose: $('sessions-close')
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
    status: null
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

  /* The empty transcript needs a line that is true in both states: before the
   * stream opens it is connecting, afterwards it is simply waiting for input. */
  function placeholderText() {
    return state.source && state.source.readyState === EventSource.OPEN
      ? 'Connected — send a message to start.'
      : 'Connecting to ccj…';
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
    const atBottom = nearBottom();
    dom.transcript.appendChild(node);
    settleScroll(atBottom);
    return node;
  }

  function settleScroll(atBottom) {
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

  function setBusy(busy) {
    state.busy = !!busy;
    dom.btnAbort.disabled = !state.busy;
    dom.send.disabled = state.busy;
    dom.hint.textContent = state.busy ? 'A turn is running — Send is disabled while the agent works.' : '';
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
    if ('provider' in status) {
      dom.chipProvider.textContent = str(status.provider) || 'provider';
      dom.chipProvider.title = 'provider: ' + str(status.provider);
    }
    if ('model' in status) {
      dom.chipModel.textContent = str(status.model) || 'model';
      dom.chipModel.title = 'model: ' + str(status.model);
    }
    if ('baseUrl' in status) {
      dom.chipBaseUrl.textContent = clip(status.baseUrl, 40) || 'base URL';
      dom.chipBaseUrl.title = 'base URL: ' + str(status.baseUrl);
    }
    const id = str(status.sessionId);
    const count = Number(status.messageCount);
    if ('sessionId' in status || 'messageCount' in status) {
      const label = id ? clip(id, 26) : 'no session';
      dom.chipSession.textContent = label + (isFinite(count) ? ' · ' + count + ' msgs' : '');
      dom.chipSession.title = 'session: ' + (id || 'none') + (isFinite(count) ? ' (' + count + ' messages)' : '');
    }
  }

  function renderSessionInfo(status) {
    const rows = [
      ['version', str(status.version) || '—'],
      ['cwd', str(status.cwd) || '—'],
      ['session', str(status.sessionId) || '—'],
      ['messages', isFinite(Number(status.messageCount)) ? String(status.messageCount) : '—']
    ];
    dom.sessionInfo.textContent = '';
    rows.forEach(function (row) {
      dom.sessionInfo.appendChild(el('span', 'k', row[0]));
      dom.sessionInfo.appendChild(el('span', 'v', row[1]));
    });
  }

  function renderTools(tools) {
    dom.toolList.textContent = '';
    if (!Array.isArray(tools) || !tools.length) {
      dom.toolList.appendChild(el('li', 'muted', 'No tools reported.'));
      return;
    }
    tools.forEach(function (tool) {
      const li = el('li');
      li.appendChild(el('span', 'name', str(tool && tool.name)));
      const desc = firstLine(str(tool && tool.description));
      if (desc) { li.appendChild(el('span', 'desc', desc)); }
      dom.toolList.appendChild(li);
    });
  }

  function applyStatus(status) {
    if (!status || typeof status !== 'object') { return; }
    state.status = status;
    setChips(status);
    renderSessionInfo(status);
    if (Array.isArray(status.tools)) { renderTools(status.tools); }
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

  function appendNotice(text) {
    dom.usage.textContent = str(text) || '—';
    return appendLine('ev ev-notice', text);
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
    state.lastEventId = -1;   // dedupe scope is one transcript
    state.stick = true;
    dom.jump.hidden = true;
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
      if (!card.counted) { card.counted = true; state.runningTools += 1; }
      card.running = true;
      card.root.classList.remove('failed');
      card.spinner.hidden = false;
      card.mark.hidden = true;
      card.meta.textContent = 'running…';
      setLive('running', ev.name);
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
      const elapsed = msLabel(ev.elapsedMs);
      card.meta.textContent = (ok ? 'done' : 'failed') + (elapsed ? ' · ' + elapsed : '');
      if (!card.output) { renderToolOutput(card, ev.output); }
      refreshLive();
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
    // The SSE id is what makes the transcript idempotent across reconnects:
    // the server may replay frames the page has already rendered.
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
      clearTranscript();
      state.runningTools = 0;
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
        // The server refused a second turn; never retry, just say so.
        appendError(err.message);
        setBusy(true);
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
  document.addEventListener('keydown', function (event) {
    if (event.key === 'Escape' && !dom.overlay.hidden) { closeSessions(); }
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
    await refreshStatus();
    connect();
    dom.input.focus();
  }

  init();
})();
