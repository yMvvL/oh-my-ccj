/* ccj --web 前端。
 *
 * 纯 ES2020 经典脚本：没有模块、没有打包器、没有依赖，从 jar 里拿出来离线就能跑。
 *
 * 安全：每一个来自服务器、模型或工具的字符串都用 textContent / createTextNode 写入。
 * 本文件不含 innerHTML、不含 insertAdjacentHTML，也不含 eval。
 */
'use strict';

(function () {
  // --------------------------------------------------------------- 辅助函数

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

  /* 重放的「工具结果」带的 elapsedMs 是 null —— 会话文件保存的是对话，不是计时 ——
   * 所以缺值就什么都不印，而不是编一个假的 "0 ms"。负值表示这次调用根本没跑过，
   * 同样不能读成一次测量。 */
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

  /* 会话 id 开头的时间戳（"20260913-001746-3377" → "20260913"）。
   * 行标题用的是问过什么，而不是这个；只有当同一列表里两行会读起来一样时才显示这个
   * 时间戳，因为那时光看标题说不清该点哪一个。 */
  function idStamp(id) {
    const t = str(id);
    const i = t.indexOf('-');
    return i > 0 ? t.slice(0, i) : t;
  }

  /* cwd 就是工作区路径（契约如此），所以它最后一段就是工作区名——这是给还没发送
   * status.workspace 的服务器留的退路，永远不是它的替代品。 */
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

  // -------------------------------------------------------------------- DOM

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
    photoMenu: $('photo-menu'),
    btnPhotoCamera: $('photo-camera-btn'),
    btnPhotoAlbum: $('photo-album-btn'),
    photoCamera: $('photo-camera'),
    photoAlbum: $('photo-album'),
    composerCard: $('composer-card'),
    photoPending: $('photo-pending'),
    photoPendingThumb: $('photo-pending-thumb'),
    photoPendingName: $('photo-pending-name'),
    btnPhotoDrop: $('photo-pending-drop'),
    btnUndo: $('btn-undo'),
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

  // ------------------------------------------------------------------- 状态

  const state = {
    lastEventId: -1,       // SSE 高水位标记；作用域 = 当前转录
    source: null,
    everOpen: false,
    retryTimer: 0,
    busy: false,
    /* 回合运行期间，服务器为这个对话暂存的消息。 */
    queued: [],
    /* 流失最后一次送来东西的时刻。光看 socket，页面分不清安静的服务器和已死的连接；
     * 而一个活得比它那个回合还久的 `busy` 标记，就是一个再也回不来的输入框。 */
    lastEventAt: Date.now(),
    runningTools: 0,
    /* 服务器上某个地方正跑着回合的会话，按 id 记下。页面并不渲染它们——这正是重点 ——
     * 但树会给这些行打标记，好让用户能再找到那个回合；别的对话里留下的运行中回合，
     * 是页面必须能显示出来的事实。 */
    runningSessions: new Set(),
    autoApprove: false,    // status.autoApprove，由服务器掌管
    block: null,           // 当前助手块
    toolCards: new Map(),  // 工具调用 id -> { root, ... }
    approvals: new Map(),  // 审批 id -> record
    /* 重放进行期间服务器报告的未决审批：一直留到转录重建完成，因为把提示画进一个
     * 就要被清空的转录，正是最初提示会丢的原因。 */
    pendingApprovalsSync: null,
    stick: true,           // 转录被钉在底部
    status: null,
    workspace: null,       // 当前工作区的 {name, path}
    cwdOverride: '',       // status.cwdOverride：-C 钉住了一个目录，通常情况下为 ''
    configured: null,      // status.configured：未知时为 null，之后是布尔值
    sessionId: '',         // 转录当前显示的会话 id
    historyPromise: null,  // 初始页面正在进行的历次加载
    historyInFlight: false,
    replaying: false,      // 正在渲染历史快照，而不是实时流
    pendingLive: [],       // 留到重放落地才处理的 SSE 消息
    usage: null,           // 最后看到的 usage 对象（来自事件或 status）
    catalog: null,         // GET /api/models 的 {providers, models}，加载完成前为 null
    catalogError: '',      // 上次目录刷新失败的原因；成功时为 ''
    reasoningLevels: null, // status.reasoningLevels：提供方接受的思考强度档位
    configProviders: [],    // 服务器称可用的提供方名字（GET /api/config）
    keyProvider: '',        // 设置表单的密钥字段当前针对哪个提供方
    rememberedProviders: [] // 服务器称已保存密钥的提供方名字（GET /api/config）
  };

  // ------------------------------------------------------------- 文本批处理

  // 助手的增量每次只来几个字符；每个元素维持一个待写缓冲，每帧最多冲一次，这样一
  // 个长回合的代价是每次冲刷一个文本节点，而不是成千上万个。用一个定时器给 rAF 兜底，
  // 因为 requestAnimationFrame 在隐藏/后台标签页里不会触发。
  //
  // markdown 走同一道闸门，只是工作单元不同：答案块的源码是累加后重新渲染，而不是追加。
  /* 一次重放往哪里追加。通常是转录；在绘制一段对话较早的部分时是一个游离的容器，
   * 这样一段往来可以离屏构建好，再一次性插到读者上方。 */
  let replayTarget = null;
  /** 每次重放都会自增，好让被取代的那一次停下，而不是写进新的转录。 */
  let replaySeq = 0;

  const queues = new Map();
  const mdQueues = new Map();   // markdown 渲染状态 -> 它渲染到的那个节点
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

  // ------------------------------------------------------------------- 滚动

  function nearBottom() {
    const gap = dom.transcript.scrollHeight - dom.transcript.scrollTop - dom.transcript.clientHeight;
    return gap < 48;
  }

  function scrollToBottom() {
    dom.transcript.scrollTop = dom.transcript.scrollHeight;
  }

  /* 服务器给没人应答的审批两分钟，然后就拒绝它。比这更老的卡片不可能还在等——不管
   * 这个页面怎么想——服务器死了、流不说话了——而把它当成还活着，会让输入框永远禁用。
   * 它按那个理由结掉，因为一张谎称还在等的卡片，比一张说出发生了什么的卡片更糟。 */
  const APPROVAL_MAX_AGE_MS = 150000;

  function expireStaleApprovals() {
    state.approvals.forEach(function (rec) {
      if (!rec.resolved && Date.now() - rec.at > APPROVAL_MAX_AGE_MS) {
        rec.settle(false, '服务器没有回音——按已拒绝处理', 'bad');
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

  /* 空转录需要一句在两种状态下都成立的话：流打开之前是正在连接，之后就是单纯在等输入。
   * 前面的工作区名说明这些输入会落在*哪里* —— 切换工作区必须在第一条消息之前就看得见。 */
  function placeholderText() {
    const ws = workspaceName();
    const connected = state.source && state.source.readyState === EventSource.OPEN;
    if (!ws) { return connected ? '已连接——发一条消息开始。' : '正在连接 ccj…'; }
    if (state.cwdOverride) {
      // -C 钉住的目录不是当前工作区的。在这里点名正是重点：树把那个工作区标成活动的，
      // 而输入落在别处，所以两者之一必须把这件事说出来。
      return connected
        ? ws + ' · 工具运行在 ' + state.cwdOverride + ' (-C)'
        : '正在连接 ' + ws + '…';
    }
    return connected ? ws + ' · 发一条消息开始' : '正在连接 ' + ws + '…';
  }

  /* 占位符是一个纯文本节点，实时流在打开时也会改写它；这里让工作区单独变化时它也跟着变。 */
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

  /* 滚动意图是在节点插入之前*立即*测量的，插入落地之后才应用。插入之后再测量，会拿新
   * 高度去比旧偏移，从而错误地得出「用户往上滚了」；只靠滚动事件缓存这个标记，又依赖
   * 隐藏标签页永远不会触发的事件。 */
  function appendToTranscript(node) {
    if (state.replaying) {
      // 从历史恢复：调暗它，并跳过逐节点的滚动记账——重放以一次滚到底收尾。
      // 在绘制一段对话较早的部分时，先放进游离的容器里，这样一段往来可以离屏构建好，
      // 再一次性插到读者上方。
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
    dom.jump.textContent = waiting ? '需要审批——跳到最新' : '跳到最新';
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

  // --------------------------------------------------------------- 头部小件

  function setLive(kind, name) {
    dom.live.dataset.state = kind;
    dom.liveText.textContent =
      kind === 'running' ? '运行中 ' + str(name) :
      kind === 'approval' ? '等待审批' :
      kind === 'offline' ? '已断开' :
      kind === 'thinking' ? '思考中' : '空闲';
  }

  function refreshLive() {
    if (pendingApprovals() > 0) { setLive('approval'); }
    else if (state.runningTools > 0) { /* keep the current tool label */ }
    else if (state.busy) { setLive('thinking'); }
    else { setLive('idle'); }
  }

  /* 什么都没配置时输入框依然可用——服务器会回一个 409 和一条可读的消息——但理由
   * 必须出现在屏幕上。 */
  function composerHint() {
    const queued = state.queued.length;
    if (queued > 0 && state.busy) {
      return queued + ' 条排队中——本回合结束后就开始下一条。';
    }
    if (queued > 0) { return queued + ' 条排队中。'; }
    if (state.busy) { return '回合正在运行——你现在发的消息要等它跑完。'; }
    if (state.configured === false) { return '尚未配置模型——打开「设置」选择一个提供方和模型。'; }
    return '';
  }

  function setBusy(busy) {
    state.busy = !!busy;
    dom.btnAbort.disabled = !state.busy;
    /* 「停止」属于它要停下的那个回合：代理干活时它出现在「发送」旁边，
     * 而不是整天灰在头部里。 */
    dom.btnAbort.hidden = !state.busy;
    /* 回合运行时「发送」保持可用：服务器会把消息排队而不是拒绝它，
     * 而禁用输入框曾是页面这边配合旧 409 的一半动作。 */
    dom.send.disabled = false;
    dom.hint.textContent = composerHint();
    refreshLive();
  }

  function paintAuto() {
    const on = state.autoApprove;
    dom.btnAuto.classList.toggle('on', on);
    dom.btnAuto.setAttribute('aria-pressed', on ? 'true' : 'false');
    dom.autoState.textContent = on ? '开' : '关';
    dom.btnAuto.title = on
      ? '「全部批准」已开启：本会话内工具调用不再询问。'
      : '开启后，本会话内每次工具调用都不再询问。';
  }

  function autoApproveOn() { return state.autoApprove; }

  function setChips(status) {
    const configured = typeof status.configured === 'boolean' ? status.configured : state.configured;
    state.configured = configured;

    if (configured === false) {
      // 什么都还没选，这时提供方/模型/接口地址三个小标签都会是占位符。
      // 一个诚实的标签胜过三个空标签。
      dom.chipProvider.textContent = '未配置';
      dom.chipProvider.title = '尚未配置模型——请打开「设置」。';
      dom.chipProvider.classList.add('chip-warn');
      dom.chipModel.hidden = true;
    } else {
      dom.chipProvider.classList.remove('chip-warn');
      dom.chipModel.hidden = false;
      if ('provider' in status) {
        dom.chipProvider.textContent = str(status.provider) || '提供方';
        dom.chipProvider.title = '提供方：' + str(status.provider);
      }
      if ('model' in status || 'baseUrl' in status) {
        dom.chipModel.textContent = str(status.model) || '模型';
        // 接口地址放在这个提示里，而不是做成第四个小标签：它很长、很少变，
        // 而且设置面板会完整显示它。
        dom.chipModel.title = '模型：' + (str(status.model) || '—')
          + (status.baseUrl ? '\n接口地址：' + str(status.baseUrl) : '');
      }
    }

    const id = str(status.sessionId);
    const count = Number(status.messageCount);
    if ('sessionId' in status || 'messageCount' in status) {
      const label = id ? clip(id, 26) : '无会话';
      dom.chipSession.textContent = label + (isFinite(count) ? ' · ' + count + ' 条消息' : '');
      dom.chipSession.title = '会话：' + (id || '无') + (isFinite(count) ? '（' + count + ' 条消息）' : '');
    }
  }

  /* 当前工作区这个事实由两处讲述：转录的占位符和树上的 `active` 标记。两者都跟随
   * status.workspace —— 头部原本还留着第三份，已经去掉了。 */
  function renderWorkspace(ws) {
    if (ws && typeof ws === 'object' && ('name' in ws || 'path' in ws)) {
      state.workspace = { name: str(ws.name), path: str(ws.path) };
    }
    refreshPlaceholder();
    noteActiveWorkspace();
  }

  /* status.workspace 才是权威；cwd 是同一个目录，只在该字段到达之前（或者更老的服务器
   * 不发送它时）顶一下。 */
  function applyWorkspace(status) {
    // cwd 和工作区自己的路径只在 -C 覆盖过它时才不同：页面必须同时带着这两个事实，
    // 因为用户是从占位符那里知道工具会跑在哪里的。
    state.cwdOverride = str(status.cwdOverride);
    if (status.workspace && typeof status.workspace === 'object') {
      renderWorkspace(status.workspace);
    } else if (!state.workspace && status.cwd) {
      renderWorkspace({ name: baseName(status.cwd), path: str(status.cwd) });
    } else {
      refreshPlaceholder();
    }
  }

  /* 每个工具一行：这个面板是图例，不是文档。描述的第一行完整内容放在该行的提示里。 */
  function renderTools(tools) {
    dom.toolList.textContent = '';
    if (!Array.isArray(tools) || !tools.length) {
      dom.toolList.appendChild(el('li', 'muted', '未报告任何工具。'));
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

  // --------------------------------------------------------------- 用量面板

  function fmtCount(v) {
    const n = Number(v);
    return v === null || v === undefined || !isFinite(n) ? '—' : n.toLocaleString();
  }

  /* null 表示「提供方什么都没报告」，这和零不是一回事：一个不报告缓存数字的模型，
   * 不能看起来像 0% 命中率。 */
  function fmtCacheTokens(v) {
    return v === null || v === undefined ? '未报告' : fmtCount(v);
  }

  function fmtHitRate(v) {
    if (v === null || v === undefined) { return '未报告'; }
    const n = Number(v);
    if (!isFinite(n)) { return '未报告'; }
    const pct = Math.max(0, n * 100);
    return (pct >= 10 ? pct.toFixed(0) : pct.toFixed(1)) + '%';
  }

  function fmtDuration(v) {
    const n = Number(v);
    if (!isFinite(n) || n < 0) { return '—'; }
    const s = n / 1000;
    if (s < 10) { return s.toFixed(1) + ' s'; }
    if (s < 60) { return Math.round(s) + ' s'; }
    /* 先整体取整，再拆分：秒和分各自独立取整时，任何差一点到整分钟的值都会印出
     * 「1m 60s」。 */
    const total = Math.round(s);
    const m = Math.floor(total / 60);
    const rest = total % 60;
    return m + 'm ' + (rest < 10 ? '0' : '') + rest + 's';
  }

  function setUsageCell(node, text, bad) {
    node.textContent = text;
    node.classList.toggle('bad', !!bad);
  }

  /* 紧凑的 token 计数：「12k / 200k」可以一眼读出，六位精确数字不行。两个数字都是估算，
   * 该行的标签也这么说。 */
  function fmtTokens(v) {
    const n = Number(v);
    if (!isFinite(n)) { return '—'; }
    if (n < 1000) { return String(Math.round(n)); }
    if (n < 1000000) { return (n / 1000).toFixed(n < 10000 ? 1 : 0) + 'k'; }
    return (n / 1000000).toFixed(1) + 'M';
  }

  /* 只有配置过预算时才显示它；没有预算时，该行依然回答了「这个对话已经多大了」，
   * 而这正是决定设预算之前要问的问题。 */
  function fmtContext(u) {
    const used = Number(u.contextTokens);
    if (!isFinite(used)) { return '—'; }
    const limit = Number(u.contextLimit);
    return isFinite(limit) && limit > 0 ? fmtTokens(used) + ' / ' + fmtTokens(limit) : fmtTokens(used);
  }

  /* 进度条是同一个数字的第二种读法，永远不是另一个数字：报告上来的 0% 什么都不填充
   * （但依然印出 "0.0%"），未报告的命中率则是一条空的*未填充*条加上下面的提示 ——
   * 而不是零。 */
  function setHitBar(rate) {
    const known = rate !== null && rate !== undefined && isFinite(Number(rate));
    const pct = known ? Math.min(100, Math.max(0, Number(rate) * 100)) : 0;
    dom.uHitFill.style.width = pct.toFixed(1) + '%';
    dom.uHitFill.classList.toggle('na', !known);
    dom.uHitBar.classList.toggle('na', !known);
    dom.uHitBar.setAttribute('aria-label', known
      ? '前缀缓存命中率 ' + fmtHitRate(rate)
      : '未报告前缀缓存命中率');
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

  /* 两个来源共用一个渲染器 —— `usage` 事件和 status.usage。 */
  function renderUsage(u) {
    if (!u || typeof u !== 'object') { resetUsage(); return; }
    state.usage = u;

    const noCache = u.cacheHitRate === null || u.cacheHitRate === undefined
      || u.cachedInputTokens === null || u.cachedInputTokens === undefined;
    const hit = fmtHitRate(u.cacheHitRate);
    dom.uHit.textContent = hit;
    dom.uHit.classList.toggle('na', hit === '未报告');
    setHitBar(u.cacheHitRate);
    dom.uHint.hidden = !noCache;

    setUsageCell(dom.uTurns, fmtCount(u.turns), false);
    setUsageCell(dom.uSteps, fmtCount(u.steps), false);
    setUsageCell(dom.uIn, fmtCount(u.inputTokens), false);
    setUsageCell(dom.uOut, fmtCount(u.outputTokens), false);
    setUsageCell(dom.uCached, fmtCacheTokens(u.cachedInputTokens), false);
    setUsageCell(dom.uTools, fmtCount(u.toolCalls), false);
    // 单独一行，而且只在发生过时才显示：压缩要花掉真实的 token，又不是一个回合，
    // 把它藏起来会让 token 计数看着不对，把它折进「步骤」又会让「步骤」有两个意思。
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

    // 失败的工具调用是信号，不是脚注：用文字和颜色说出来，而不是留一个没人看的零。
    const errors = Number(u.toolErrors);
    const bad = isFinite(errors) && errors > 0;
    setUsageCell(dom.uErrors, fmtCount(u.toolErrors), bad);
    dom.uErrorsRow.classList.toggle('bad', bad);
    dom.uAlert.hidden = !bad;
    dom.uAlert.textContent = bad
      ? errors + ' 个工具调用在本会话中失败'
      : '';
  }

  /* 一次压缩用摘要加上最新的若干往来替换了整个对话，所以转录是从服务器重建而不是就地
   * 编辑：`loadHistory` 是唯一知道怎么渲染一个会话的地方。 */
  async function onCompacted(ev) {
    const before = Number(ev.beforeTokens);
    const after = Number(ev.afterTokens);
    // 先清空转录：对话确实变了，而 `loadHistory` 是追加式的（它是为会话*切换*写的，
    // 那种情况下 id 变了、noteSession 已经为它清过屏）。不改的话，保留下来的往来会在
    // 屏幕上已有的那些下面再画一遍。
    clearTranscript();
    const saved = Number(ev.savedPercent);
    // 「? → ? token」比什么都不说更糟：缺的数字就省掉，而不是渲染成一个看起来像数据
    // 的占位符。
    const detail = (isFinite(before) && isFinite(after))
      ? ' — ' + fmtCount(before) + ' → ' + fmtCount(after) + ' token（估算）'
      : '';
    appendNotice(
      '已压缩：' + fmtCount(ev.summarised) + ' 条较早的消息变成了摘要，'
      + fmtCount(ev.kept) + ' 条原样保留'
      + (isFinite(saved) ? '（替换掉的那部分省下 ' + saved + '%）' : '')
      + detail + '。完整对话仍在 ' + str(ev.source));
    await loadHistory(str(state.status && state.status.sessionId));
  }

  /* The button is a request like any other: the server decides whether the
   * conversation can be compacted, and a refusal is shown where a refusal
   * belongs — in the transcript, not in a dialog. */
  async function compactNow() {
    if (state.busy) { return; }
    // 用禁用而不是改文案：这个按钮带一个字形节点和一个标签节点，写 textContent 会把
    // 两者都丢掉。忙碌状态从压缩即将改写的那个转录里就能看出来。
    dom.btnCompact.disabled = true;
    try {
      await request('/api/compact', { method: 'POST' });
      // 结果由事件带回来；这里只是不让按钮看起来卡住。
    } catch (err) {
      appendError(str(err && err.message) || '压缩失败。');
    }
    dom.btnCompact.disabled = false;
  }

  /* 服务器为这个对话暂存了什么，最早的在前。在输入框那里只画成计数：消息本身会在它们的
   * 回合开始时出现在转录里，所以排队中的消息不会被显示两次。 */
  function applyQueued(queued) {
    state.queued = Array.isArray(queued) ? queued.map(str).filter(Boolean) : [];
    dom.hint.textContent = composerHint();
  }

  function applyStatus(status) {
    if (!status || typeof status !== 'object') { return; }
    state.status = status;
    applyWorkspace(status);
    if ('sessionId' in status) { noteSession(str(status.sessionId)); }
    // 服务器说明哪些对话正在干活。屏幕上的会话由 `busy` 报告；每一个*别的*会话都在
    // `running` 里点名，两者合起来就是树要标记的东西。
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
    // 输入框上方的选择器与那些小标签是同一个事实，只是讲在用户即将打字的地方；
    // 两者都跟随服务器的状态。
    if (composerPicker) { composerPicker.setStatus(status); }
    expireStaleApprovals();
    // 仍然未决的请求是服务器对这个对话的答复的一部分。只在没有重放进行中时才应用：
    // 现在加进去的提示会被片刻之后从历史重建的转录抹掉，而这正是这段逻辑要防的损失。
    if (!state.historyInFlight && !state.replaying) {
      syncApprovals(status.approvals);
    } else {
      state.pendingApprovalsSync = Array.isArray(status.approvals) ? status.approvals : null;
    }
    // 屏幕上那个回合背后还等着什么，好让输入框说出来。
    applyQueued(status.queued);
    // 刷新之后那张缩略条必须回来：它描述的是服务器*持有*的东西，而不是这个页面记得的东西。
    if ('picture' in status) {
      setPendingPicture(status.picture ? {
        name: str(status.picture.name),
        description: str(status.picture.description)
      } : null);
    }
    setBusy(!!status.busy || pendingApprovals() > 0);
    paintRunningRows();
  }

  /* -------------------------------------------------------------------- 其他对话

   * 一个页面，一条流，服务器上的每一个对话。这个页面只渲染其中之一——屏幕上的那个 ——
   * 其余的在这里被跟踪，好让树能给正在工作的行打标记。外来回合的任何东西都不会被渲染进
   * 转录：它的正文属于另一个对话，而它的 `done` 不能解开这个输入框。 */

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

  /* 给树里已有的行重新打标记，而不是重新加载整个列表：正在跑的是用户自己的回合，
   * 而完整刷新会跟用户此刻对侧边栏做的事打架。 */
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
        mark.title = '正在跑一个回合——打开它旁观，或用 ■ 停止它';
        mark.setAttribute('aria-label', '运行中');
        button.querySelector('.session-name').appendChild(mark);
      } else if (!running && existing) {
        existing.remove();
      }
    }
  }

  /* 停止这个页面没有在显示的对话里正在跑的回合。id 是服务器自己的（树的行上带着它），
   * 所以没有任何猜测。 */
  async function stopSession(id) {
    const owner = str(id);
    if (owner === '') { return; }
    try {
      const res = await request('/api/abort?id=' + encodeURIComponent(owner), { method: 'POST' });
      if (res && res.aborted) {
        markRunning(owner, false);
      }
    } catch (err) {
      appendError('无法停止 ' + owner + '：' + str(err && err.message));
    }
    // 只刷新一次，不是两次。工作区展开时 `reorderSessionsAfterTurn` 自己会重读列表，
    // 先失效会让侧边栏为那次读取付两遍钱——在忙碌的机器上就是每个会话文件解析两次，
    // 而这恰恰是回合正在流式输出时这个页面最付不起的开销。
    invalidateSessions(owner);
    reorderSessionsAfterTurn();
  }

  async function refreshStatus() {
    try {
      const status = await request('/api/status');
      applyStatus(status);
    } catch (err) {
      appendError('无法读取状态：' + err.message);
    }
  }

  // -------------------------------------------------------------- markdown

  /* 助手的正文是 markdown，而且是几个字符几个字符地到的，所以渲染器必须既精确又增量。
   *
   * 精确：每次冲刷都从完整源码重新解析块——只做字符串活——而一个块只从它自己的源码
   * 渲染。因此一个到一半的消息渲染出来就是「到目前为止的消息」，早先画过的东西之后
   * 也不必撤回。
   *
   * 增量：源码没变的块继续用它已有的节点。一个只多了一个词的答案，只会重建它还在写的
   * 那一个块，而不是整条消息，所以滚动位置、选中内容和正在读的那一段都能安然渡过
   * 一个长回合。
   *
   * 这里是 CommonMark 的一个子集，按代理实际会写的东西挑的：标题、段落、围栏代码、
   * 列表（可嵌套，带 GFM 任务框）、引用块、表格、分隔线，以及行内代码、强调、删除线、
   * 链接和自动链接。三处「拒绝」是决定而不是遗漏：原始 HTML 永不解释——答案里的
   * `<div>` 就按它本来的文字显示，因为一个放行 HTML 的渲染器就是多绕几步的 HTML
   * 注入——链接的协议会被过滤，于是答案里的 `javascript:` 保持为文字，而不是变成
   * 这个页面会执行的东西；图片则按它同时是的那个链接来渲染，因为去抓取它等于告诉
   * 第三方这个对话正开在屏幕上。
   */

  // -------------------------------------------------------- markdown: parse

  const MD_ITEM_RE = /^([ \t]*)([-*+]|\d{1,9}[.)])([ \t]+)(.*)$/;
  const MD_HEADING_RE = /^ {0,3}(#{1,6})(?:[ \t]+(.*?))?[ \t]*$/;
  const MD_HR_RE = /^ {0,3}([-*_])[ \t]*(?:\1[ \t]*){2,}$/;
  const MD_QUOTE_RE = /^ {0,3}>/;
  const MD_FENCE_RE = /^ {0,3}(`{3,}|~{3,})[ \t]*([^ \t`]*)/;
  const MD_TABLE_DELIM_RE = /^ {0,3}\|?[ \t]*:?-+:?[ \t]*(\|[ \t]*:?-+:?[ \t]*)*\|?[ \t]*$/;
  const MD_PIPE_RE = /(?<!\\)\|/;

  /* 一个制表符算四列，缩进检查需要的就是这些。 */
  function mdWidth(text) { return str(text).replace(/\t/g, '    ').length; }

  /* 一行缩进了多少——算的是它开头的空白，不是整行宽度。这个区分就是「这一行属于上面
   * 那个条目」和「这一行恰好很长」的差别：按宽度来算时，列表后面的引用、代码块和表格
   * 都会变成它的条目。 */
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

  /* 一个会开启新块的行，因此也终止了它上面的段落或引用。未闭合的围栏块也算：
   * 它的那些行是代码，不是正文。 */
  function mdStartsBlock(line) {
    return MD_FENCE_RE.test(line) || MD_HEADING_RE.test(line) || MD_HR_RE.test(line)
      || MD_QUOTE_RE.test(line) || MD_ITEM_RE.test(line);
  }

  /* 源码 → 块列表。每个块把构建它的原始源码作为自己的 `key` 带着：渲染器比较 key
   * 来决定哪些还成立。 */
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
        // 以 `>` 开头的行，加上一个按 markdown 的懒惰段落规则延续上一行的普通行。
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
    // 转义的竖线是内容，不是列分隔。
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

  /* 表格需要表头行里有竖线，并且要有同样列数的分隔行——正是这个列数检查让「a | b」
   * 下面跟一行横线不会被读成一张根本没人写过的表格。 */
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

  /* 一个列表：同一缩进层级上的条目，每个条目带上属于它的那些行——折行的延续、嵌套
   * 列表、围栏块。嵌套不做特殊处理，因为条目自己的源码是递归解析的：对于 "- a" 下面
   * 跟着 "  - b"，内层解析自然会找到一个列表。
   *
   * 一行只有在*缩进*到某个条目的内容列时，才属于它上面的那个条目。第 0 列上的懒惰
   * 延续行 —— markdown 允许——故意不并进来：在一个列表之后，第 0 列的行远远更常是
   * 答案的下一块，而不是折行的项目符号；一次猜成「折行」，后面所有内容都会被嵌进
   * 最后一个条目里。 */
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
        // 空行要么分隔同一个松散列表里的条目，要么结束这个列表；下一条非空行说了算。
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
      // 不同*种类*的标记会开启一个新列表："- a" 下面跟着 "1. b" 是一个项目符号后面
      // 跟一个编号条目，而不是两个条目的项目符号列表。
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
        // 信息串是标签，不是内容：它告诉读者这是 shell 转录还是 JSON。
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
        // 宽表格在自己内部滚动，而不是把整列撑开。
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

  /* 紧凑条目是行内内容：用 <p> 包起来会让每个项目符号都带上段落的间距。松散的条目
   * （条目之间有空行）则写成段落，因为那才是作者要的。 */
  function mdRenderItem(item, loose) {
    const li = el('li', 'md-li');
    if (item.task) {
      const box = el('input', 'md-task');
      box.type = 'checkbox';
      box.checked = item.checked;
      box.disabled = true;   // 转录是一份记录，不是表单
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

  /* 文本 → 一个节点片段。缓冲区是让它保持廉价的关键：纯文本段收集起来作为*一个*文本
   * 节点发出，而不是每个标记一个节点。
   *
   * 渲染标签时 `inLink` 为真：链接自己的文字不再扫描一次链接，因为那里的自动链接会是
   * 锚点套锚点（浏览器会默默解开），而对于一个把自己当标签的裸 URL，还会无限递归。 */
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
        // 行尾两个空格或一个反斜杠是硬换行；单独的换行是 markdown 渲染成空格的软换行。
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

  /* 未闭合的反引号就是字面文字，而一个流在代码段中间的那几毫秒看起来正是这样。 */
  function mdCodeSpan(text, i) {
    const open = /^`+/.exec(text.slice(i))[0];
    const end = text.indexOf(open, i + open.length);
    if (end < 0) { return null; }
    let body = text.slice(i + open.length, end).replace(/\n/g, ' ');
    // 按规范，两侧各一个空格的填充不算内容。
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
    // 图片会让页面在答案到达的那一刻就去抓别人的 URL —— 一个读者从没要过的信标 ——
    // 所以按它同时是的那个链接来渲染。
    if (image && !label) { label = target; }
    return { node: mdAnchor(label, target), next: end + 1 };
  }

  /* 目标里可能含有成对的圆括号 —— `…/Foo_(bar)` 是模型会粘贴的 URL —— 所以闭括号靠
   * 计数找到，而不是取看到的第一个 `)`。 */
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
    // 句子标点不属于 URL：链接在「see https://example.dev.」的句点之前就结束了。
    while (url.length && /[.,;:!?]$/.test(url)) { url = url.slice(0, -1); }
    if (!url) { return null; }
    return { node: mdAnchor(url, url), next: i + url.length };
  }

  function mdFindClose(text, from, mark) {
    let at = text.indexOf(mark, from);
    while (at >= 0) {
      const before = text.charAt(at - 1);
      // 收尾标记不能紧跟空格、不能是同一个字符更长的连续串，也不能把刚跟过的开头
      // 重新收尾一次。
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
      // 分隔符不会以空格开头，而同一个字符的连续串不是两个分隔符。
      if (!after || after === mark.charAt(0) || /\s/.test(after)) { continue; }
      // 词中间的下划线是词的一部分：`snake_case` 不是斜体的 `snake`，这在一条满是
      // 标识符的答案里很重要。
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
    // 协议检查必须看到浏览器会看到的那个字符串，而 `a.href =` 会经过 URL 解析器：它会
    // 移除*任何位置*的制表符和换行，以及首尾的 C0 控制字符或空格。按原样测试
    // "java\nscript:" 会通过过滤，然后被解析成 javascript:，所以先做归一化，用的就是
    // 清洗后的目标。
    const raw = str(url)
      .replace(/[\t\n\r]/g, '')
      .replace(/^[\u0000-\u0020]+|[\u0000-\u0020]+$/g, '');
    // 只允许答案里的链接可能表示的那些协议。没有协议的目标是相对地址，它离不开页面
    // 自己的源。
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

  /* 上一次渲染已经产出多少个开头的块。源码相同就意味着节点相同，因为一个块只从它自己
   * 渲染——所以答案会保留它还在写的那个块之上的所有内容。 */
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

  /* 增量的合并方式与纯文本完全一样：解析每帧最多跑一次，而一条没人在等的消息
   * （后台标签页）依然会由定时器冲刷。 */
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

  /* 服务器通知（被拒的「新建会话」、提供方重试、被裁剪的上下文）都是转录里的行。
   * 用量面板归 `usage` 事件和 status.usage 管，所以通知从不写那里。 */
  function appendNotice(text) { return appendLine('ev ev-notice', text); }

  /* 压缩产生的摘要，画成一张自己的卡片。
   *
   * 它不是通知：通知讲的是边角上发生的事，而这个是对话较早的部分现在*就是*的样子。
   * 默认折叠，因为它很长，而且通常不是读者要找的东西——但它可以展开，因为「它有没有
   * 留下重要的东西」只有正文能回答，而它上面那行说明了完整对话还在哪里。 */
  function appendSummary(ev) {
    const text = str(ev.text);
    if (!text) { return null; }
    const covers = Number(ev.covers);
    const details = el('details', 'ev ev-summary');
    details.open = false;
    const summaryLine = el('summary', 'ev-summary-head');
    summaryLine.appendChild(el('span', 'ev-summary-glyph', '⤓'));
    summaryLine.appendChild(el('span', 'ev-summary-title', '已压缩的会话'));
    if (isFinite(covers) && covers > 0) {
      summaryLine.appendChild(el('span', 'ev-summary-count',
        fmtCount(covers) + ' 条消息已摘要'));
    }
    details.appendChild(summaryLine);
    const body = el('div', 'ev-summary-body');
    body.appendChild(el('div', 'ev-summary-note',
      '这是较早那些回合压缩后的样子。完整对话仍在磁盘上——如果缺了某个细节，'
      + '可以让模型读回它。'));
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
      // 推理是模型在自言自语，就按它本来的纯文本显示；只有答案——交给读者的那个东西 ——
      // 才是 markdown。
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
    // 待应用的同步属于被离开的那个对话；它的请求不是这个对话的。
    state.pendingApprovalsSync = null;
    state.runningTools = 0;
    state.lastEventId = -1;   // 去重作用域是一个转录
    state.stick = true;
    dom.jump.hidden = true;
    resetUsage();
  }

  // ---------------------------------------------------------------- 历史重放

  /* 会话 id 就是这个页面所说的「转录显示的是什么」。历史对每个 id 只取一次——在切换到
   * 它的那一刻——所以那些重复当前 id 的 status 事件（回合结束、重连、被拒的「新建
   * 会话」）永远不会把同一个对话取回或渲染两次。 */
  function noteSession(id) {
    if (id === state.sessionId) { return; }
    state.sessionId = id;
    // 切换工作区会分配一个全新的空会话；它的历史就是一屏空白，所以只记下 id，
    // 不去动那个面板。切换之后的那次 resume 才是真正取回对话的动作。
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
      // 一个卡住的历史请求不能把实时流当人质。
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
      // 全新页面的数字来自恢复对话的同一个响应，所以面板在第一个回合之前就是有内容的。
      if (data && data.usage && typeof data.usage === 'object') { renderUsage(data.usage); }
    } catch (err) {
      if (seq === historySeq) {
        appendError('无法加载历史：' + str(err && err.message));
      }
    } finally {
      clearTimeout(safety);
      if (seq === historySeq) {
        state.historyInFlight = false;
        flushPendingLive();
      }
    }
  }

  /* 一段恢复出来的对话在页面可用之前要画多少。
   *
   * 一个长会话是几千张工具卡片，一次性把它们全建出来会把线程堵住足够久，久到在切换背后
   * 跑着的那个回合看起来像卡死了——这笔开销花在进来的路上，而那正是用户在盯着看的时候。
   * 所以比这更老的页面分两遍画：最上面的事件现在画，其余的等浏览器一闲下来就补。
   * 什么都不丢，而且读者可以立刻开始滚动。
   *
   * 这个数字是事件数，不是往来数，因为开销与它成正比。它给得很宽——几百张工具卡片是
   * 很快的一画——但仍有上界，好让第一遍自己不变成它要避免的那种卡顿。 */
  const REPLAY_TAIL_EVENTS = 300;

  /* 把历史分成「现在画什么」和「之后在背后补什么」。
   *
   * 切口落在一条用户消息上，而这是渲染器本来就当作边界的地方（`appendUser` 会关闭
   * 当前打开的块）。在回合中间切开会把同一个助手回合交给两遍去画，第二遍就会把它的
   * 推理追加在第一遍画出的答案*后面*。
   *
   * 比额度更长的往来会被整体保留：半个答案比一屏慢更糟，而那条用户消息之后的事件全都
   * 属于那个回合。 */
  function splitReplay(events) {
    const clean = (Array.isArray(events) ? events : []).filter(function (ev) {
      return !!ev && typeof ev === 'object';
    });
    // 尾部从额度末尾（含）之前最后一条用户消息开始。从末尾往回走找到的是*最新*的这样一个
    // 边界，所以尾部在限额允许的范围内尽量小，同时又保持完整。没有额度可花时 ——
    // 历史很短——尾部就是全部。
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
      // 其余的等浏览器没事可做时再画。页面已经可用了，最新的部分也已经在屏幕上，
      // 所以这不会让切换感觉更慢。
      loadOlderReplay(split.head, split.hidden);
    }
  }

  /** 跑一遍重放：事件过渲染器，跳过实时生命周期的那几个。 */
  function replayPass(events) {
    events.forEach(function (ev) {
      const type = str(ev.type);
      if (type === 'status' || type === 'done') { return; }   // 实时生命周期，不是内容
      try {
        dispatch(ev);
      } catch (err) {
        appendError('重放 ' + type + ' 时出现 UI 错误：' + str(err && err.message ? err.message : err));
      }
    });
  }

  /* 把对话较早的部分画在屏幕上已有内容*之上*，在浏览器空闲时一次一段往来。
   *
   * 三件事让它不被察觉，而每一件都是这套机械存在的理由：
   *
   *  - 它在浏览器空闲时运行，所以背后正在流式的那个回合继续在画；
   *  - 它画进一个游离的片段，再作为一个节点插进去，所以永远不会只挂上一半；
   *  - 它用自己刚加上的高度来保住读者的位置。否则在视口上方插入会把正在读的文字往下推
   *    屏幕，推的距离正是加进去的那部分——这就是「在背后补」需要小心，而不能只是一次
   *    插入的全部原因。
   *
   * 分块边界是用户消息：一个被两块分开渲染的助手回合，最后会把推理排到答案后面。 */
  function loadOlderReplay(head, hidden) {
    const banner = el('div', 'ev ev-note replay-note',
      hidden + ' 条较早的事件——加载中…');
    dom.transcript.insertBefore(banner, dom.transcript.firstChild);

    const seq = ++replaySeq;
    let end = head.length;   // exclusive: everything from `end` on has been drawn

    const idle = window.requestIdleCallback
      ? function (fn) { return window.requestIdleCallback(fn, { timeout: 250 }); }
      : function (fn) { return setTimeout(function () { fn({ timeRemaining: function () { return 8; } }); }, 0); };

    const step = function (deadline) {
      // 另一次切换取代了这次重放：它的转录已经没了，这一次必须停下，而不是把一段旧对话
      // 追加进新的转录里。
      if (seq !== replaySeq) { return; }
      if (end <= 0) {
        if (banner.parentNode) { banner.parentNode.removeChild(banner); }
        return;
      }
      // 退到上一个边界，这样每一遍画一段往来。
      let from = end - 1;
      while (from > 0 && str(head[from].type) !== 'user') { from--; }

      const fragment = document.createDocumentFragment();
      const holder = el('div');
      const previousTranscript = dom.transcript;
      // 渲染器是往转录里追加的；这一遍它往一个游离的容器里追加，结果整体作为一个节点
      // 移上去。
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
        step(deadline);            // 这一帧还有时间
      } else {
        idle(step);                // 或者等浏览器空闲时再回来
      }
    };
    idle(step);
  }

  function flushPendingLive() {
    const queued = state.pendingLive;
    state.pendingLive = [];
    queued.forEach(processMessage);
    // 转录重建期间到达的 status 带着未决的审批；现在重放完成，把它们画出来不会再被
    // 重放抹掉。
    if (state.pendingApprovalsSync) {
      const waiting = state.pendingApprovalsSync;
      state.pendingApprovalsSync = null;
      syncApprovals(waiting);
    }
  }

  // ---------------------------------------------------------------- 工具卡片

  function toolCard(id, name, phase) {
    const existing = state.toolCards.get(id);
    // id 在每次调用内是稳定的，但服务器可能会把它复用到之后的调用上：
    // `end` 永远属于屏幕上那张卡片，而 `start` 只在卡片还在运行时才属于它。
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
      const toggle = el('button', 'link', '显示全部');
      toggle.type = 'button';
      toggle.addEventListener('click', function () {
        const wasAtBottom = nearBottom();
        card.expanded = !card.expanded;
        pre.textContent = card.expanded ? card.full : card.clipped;
        toggle.textContent = card.expanded ? '显示更少' : '显示全部';
        settleScroll(wasAtBottom);
      });
      row.appendChild(toggle);
      row.appendChild(el('span', 'muted', '+' + (lines.length - 6) + ' 行'));
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
      // 重放出来的 start 不能让实时指示器声称某个工具此刻正在运行。
      if (!state.replaying && !card.counted) { card.counted = true; state.runningTools += 1; }
      card.running = true;
      card.root.classList.remove('failed', 'ok');
      card.spinner.hidden = false;
      card.mark.hidden = true;
      card.meta.textContent = '运行中…';
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
      card.meta.textContent = (ok ? '已完成' : '失败') + (elapsed ? ' · ' + elapsed : '');
      if (!card.output) { renderToolOutput(card, ev.output); }
      if (!state.replaying) { refreshLive(); }
    }
  }

  // ---------------------------------------------------------------- 审批卡片

  function approvalRecord(id) { return state.approvals.get(id); }

  function onApproval(ev) {
    const id = str(ev.id);
    let rec = approvalRecord(id);
    // id 可能会在之后的调用里绕回来；只有*未决*的记录能吸收一次重复，否则循环会永远
    // 等在一张页面根本没显示过的卡片后面。
    if (rec && rec.resolved) { rec = null; }
    if (!rec) {
      rec = renderApproval(id, ev.title, ev.detail);
    }

    if (autoApproveOn() && !rec.resolved) {
      rec.answer('once');
      rec.stateEl.textContent = '已自动批准（全部批准）';
    }
    refreshLive();
    updateJump();
  }

  /* 画出一个未决请求并登记它。它从 onApproval 里拆出来，是因为 status 也带同样的三个
   * 字段，而一个请求正是靠这一点在页面去看别的对话时活下来：答案在服务器上，提示在这里，
   * 而这里是唯一知道怎么把它建出来的地方。 */
  function renderApproval(id, title, detail) {
    const root = el('div', 'ev ev-approval pending');
    const head = el('div', 'approval-head');
    head.appendChild(el('span', 'approval-flag', '需要审批'));
    head.appendChild(el('span', 'approval-title', str(title) || '工具调用'));
    root.appendChild(head);

    const detailNode = el('pre', 'approval-detail', str(detail) || '（无细节）');
    root.appendChild(detailNode);

    /* 四个答案，因为本来就有四个：不、同意一次、本会话内同意、以及从今以后都同意 ——
     * 后者会作为规则写进 approvals 文件。在这之前只有两个，而想表达「别再问这个了」的
     * 唯一办法是头部那个整会话开关，也就是那个没人敢碰的开关。按钮上的字就是服务器据以
     * 行动的字。 */
    const actions = el('div', 'approval-actions');
    const stateEl = el('span', 'approval-state', '等待回答');
    const deny = el('button', 'btn danger', '拒绝');
    const once = el('button', 'btn primary', '只允许这一次');
    const session = el('button', 'btn', '本会话都允许');
    const always = el('button', 'btn', '始终允许');
    [deny, once, session, always].forEach(function (button) { button.type = 'button'; });
    actions.appendChild(deny);
    actions.appendChild(once);
    actions.appendChild(session);
    actions.appendChild(always);
    actions.appendChild(stateEl);
    root.appendChild(actions);

    const rec = {
      id: id, root: root, approve: once, deny: deny,
      session: session, always: always, stateEl: stateEl, resolved: false,
      at: Date.now()
    };

    rec.settle = function (allow, label, cls) {
      rec.resolved = true;
      rec.root.classList.remove('pending');
      rec.root.classList.add(allow ? 'approved' : 'denied');
      rec.approve.disabled = true;
      rec.deny.disabled = true;
      rec.session.disabled = true;
      rec.always.disabled = true;
      rec.stateEl.textContent = label;
      rec.stateEl.className = 'approval-state ' + cls;
      refreshLive();
      updateJump();
      setBusy(state.busy);
    };

    rec.answer = function (choice) {
      if (rec.resolved) { return; }
      const allowed = choice !== 'deny';
      rec.settle(allowed, APPROVAL_LABELS[choice] || '已应答', allowed ? 'ok' : 'bad');
      answerApproval(id, choice);
    };

    deny.addEventListener('click', function () { rec.answer('deny'); });
    once.addEventListener('click', function () { rec.answer('once'); });
    session.addEventListener('click', function () { rec.answer('session'); });
    always.addEventListener('click', function () { rec.answer('always'); });

    state.approvals.set(id, rec);
    appendToTranscript(root);
    return rec;
  }

  /* 每个答案是什么意思，用的就是卡片在答案给出之后显示的那些字。`once` 是主按钮，
   * 也是自动批准开关代表用户去按的那一个。 */
  const APPROVAL_LABELS = {
    deny: '已拒绝',
    once: '已允许一次',
    session: '本会话内已允许',
    always: '从此允许（规则已保存）'
  };

  /* 服务器说屏幕上这个对话仍然未决的请求。
   *
   * 一个审批能扛过「去看别处」靠的就是这个。请求阻塞在服务器的内存里，而不是写进对话，
   * 所以重放历史带不回它——没有这段逻辑，用户一切走提示就消失了，只留下「停止」作为
   * 那个本来完全可以回答的回合的唯一出路。 */
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
    // 这个页面手上、而服务器已经不知道的记录，是在别处被应答了，或者超时了：在这里结掉它，
    // 而不是留成一张永远回答不了的卡片。
    state.approvals.forEach(function (rec, id) {
      if (!rec.resolved && !shown.has(id)) {
        rec.settle(false, '已不再等待（已在别处应答或已超时）', 'bad');
      }
    });
  }

  function onApprovalClosed(ev) {
    const rec = approvalRecord(str(ev.id));
    if (!rec || rec.resolved) { return; }
    const allow = ev.allow === true;
    // 服务器说明它是四个答案里的哪一个，所以一个在别处被应答的卡片，在这里读起来和
    // 那里一样。
    const label = APPROVAL_LABELS[str(ev.answer)] || (allow ? '已批准' : '已拒绝');
    rec.settle(allow, label, allow ? 'ok' : 'bad');
  }

  async function answerApproval(id, choice) {
    try {
      await postJSON('/api/approval', { id: id, answer: str(choice) || 'deny' });
    } catch (err) {
      const rec = approvalRecord(id);
      if (rec) {
        rec.resolved = false;
        rec.root.classList.remove('approved', 'denied');
        rec.root.classList.add('pending');
        rec.approve.disabled = false;
        rec.deny.disabled = false;
        rec.session.disabled = false;
        rec.always.disabled = false;
        rec.stateEl.textContent = '无法送出回答——请重试';
        rec.stateEl.className = 'approval-state bad';
      }
      appendError('审批：' + err.message);
      refreshLive();
      updateJump();
    }
  }

  /* 头部的自动批准开关，作用于已经显示在屏幕上的提示：每一个都按最窄的方式回答。
   * 它过去设的是「记住」，那意味着整个会话——用对每个命令都做决定来回答一个命令的问题，
   * 就是这个开关名声的来源。 */
  function approvePending() {
    state.approvals.forEach(function (rec) {
      if (!rec.resolved) {
        rec.answer('once');
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
    appendToTranscript(el('div', 'ev ev-done', aborted ? '回合已停止' : '回合完成'));
    setBusy(false);
    setLive('idle');
    dom.input.focus();
    reorderSessionsAfterTurn();
  }


  // ------------------------------------------------------------- dispatch

  /* 任何落在两段正文之间的东西都会关闭当前打开的助手块，这样之后的增量会在该项*下面*
   * 新起一个块，而不是悄悄追加到转录更上方那个已经存在的块里。 */
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
      case 'picture':
        // 服务器才是这张待发送图片的权威：上传、移除、以及别处（另一个标签页）碰它，都会到这里。
        setPendingPicture(ev.name ? { name: str(ev.name), description: str(ev.description) } : null);
        break;
      case 'summary': breakBlock(); appendSummary(ev); break;
      case 'usage': renderUsage(ev); break;
      case 'compacted':
        // 屏幕上的转录现在已经是另一个对话，所以是重读而不是打补丁：摘要和保留下来的
        // 往来由服务器来描述，而猜这个拼接正是页面最终显示出从未存在过的历史的原因。
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
    // 渲染历史快照期间到达的实时帧会被留住，之后重放，这样快照永远是基底，
    // 不会丢东西，也不会乱序穿插。
    if (state.historyInFlight) { state.pendingLive.push(msg); return; }
    processMessage(msg);
  }

  function processMessage(msg) {
    // SSE id 就是让转录在重连之间保持幂等的东西：服务器可能会重放页面已经渲染过的帧。
    // 重放的历史永远不经过这里，所以它不会消耗实时 id。
    const rawId = str(msg.lastEventId) || str(msg.id);
    const idNum = parseInt(rawId, 10);
    const hasId = isFinite(idNum);
    if (hasId && idNum <= state.lastEventId) { return; }

    let ev = null;
    try { ev = JSON.parse(msg.data); } catch (err) { return; }
    if (!ev || typeof ev !== 'object') { return; }

    // 高水位标记对每个事件都前进，包括这个页面不渲染的那些：它是一条共享流里的位置，
    // 因为另一个对话的回合而跳过它，会让下一次重连重放已经投递过的帧。
    if (hasId) { state.lastEventId = idNum; }

    // 一条流承载服务器上的每个对话，所以一个事件只在属于屏幕上这个转录时才渲染。
    // 另一个会话里正在跑的回合，正是用户被允许留在那里继续跑的：它的正文不能出现在
    // 这里，它的 `done` 也不能解开这个页面的输入框，或者声称这个对话刚刚回答过了。
    const owner = str(ev.sessionId);
    if (owner && owner !== state.sessionId) {
      noteForeignTurn(ev);
      return;
    }

    try {
      dispatch(ev);
    } catch (err) {
      appendError('处理 ' + str(ev.type) + ' 时出现 UI 错误：' + (err && err.message ? err.message : err));
    }
  }

  /* 从这里的视角看到另一个对话里的一个回合。它不会被渲染——转录只属于一个会话 ——
   * 但关于它有三件事值得处理：页面知道了哪些会话在干活，树给那些行打标记好让用户能再
   * 找到那个回合，而一个结束的回合会刷新列表（那个对话的标题和位置刚刚变了）。 */
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

  // -------------------------------------------------------------------- sse

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
        // 一次重启可能新增或忘记了工作区，而树里的计数跟随的是我们离开期间
        // 写下的那些会话。
        loadWorkspaces();
      }
      state.everOpen = true;
    };

    source.onerror = function () {
      showConnPill();
      if (source.readyState === EventSource.CLOSED && !state.retryTimer) {
        // 浏览器只在流处于 CONNECTING 时自动重试；一次致命的关闭（服务器重启
        // 或响应不合法）需要新建一个源。
        state.retryTimer = setTimeout(function () {
          state.retryTimer = 0;
          connect();
        }, 3000);
      }
    };

    source.onmessage = handleMessage;
    // 服务器的保活，它以具名事件到达，因为一个裸 `data` 帧会被派发给 onmessage，看起来
    // 就像一个类型未知的真实事件。在一个回合等着什么的时候——一次模型调用，或者一个
    // 没人应答的审批——这是唯一能到达页面的信号；没有它，下面那个 20 秒的陈旧计时器
    // 会在健康的连接上开火：页面在一个仍然打开的提示底下重连，那个提示就闪一下然后消失。
    // 这是在一个等待审批的会话里实测到的。
    source.addEventListener('ping', function () {
      state.lastEventAt = Date.now();
      // 一次 ping 证明连接还活着，这同时也是把「重新连接中」的药丸收起来（如果它挂着）的
      // 时机。
      hideConnPill();
    });
  }

  /* 一条流可以沉默多久，之后页面就不再相信自己那个「有回合正在运行」的判断。从这里看，
   * 安静的服务器和死掉的连接长得一模一样，而猜错的代价是一个再也回不来的输入框。 */
  const STALE_EVENT_MS = 20000;

  function streamLooksStale() {
    return Date.now() - state.lastEventAt > STALE_EVENT_MS;
  }

  /* 重新读取服务器的事实，如果流已经没了就重开它。在页面回到前台时调用——那是被节流的
   * 标签页的计时器重新开始跑的一刻——也在凭一个陈旧的 `busy` 吞掉消息之前调用。 */
  function resync() {
    if (!state.source || state.source.readyState !== EventSource.OPEN) { connect(); }
    refreshStatus();
  }

  // ------------------------------------------------------------------- 主题

  /* 一个控件上三个状态，循环 浅色 → 深色 → 跟随系统。调色板本身是 CSS，由 <html> 上的
   * data-theme 驱动；这里只负责挑值并让标签说实话。手动选择永远优先于操作系统：
   * 只有选择为「跟随系统」时才去问操作系统。 */
  const THEME_KEY = 'ccj.theme';
  const THEME_CYCLE = ['light', 'dark', 'system'];
  const THEME_ICON = { light: '☀', dark: '☾', system: '◐' };
  const THEME_LABEL = { light: '浅色', dark: '深色', system: '跟随系统' };
  const systemTheme = window.matchMedia('(prefers-color-scheme: light)');

  let themePref = 'system';

  function nextTheme(pref) {
    return THEME_CYCLE[(THEME_CYCLE.indexOf(pref) + 1) % THEME_CYCLE.length];
  }

  /* localStorage 可能不可用（隐私窗口、被禁用的存储）；页面仍然必须带着某个主题渲染，
   * 所以被拒绝时退回「跟随系统」。 */
  function storedTheme() {
    let value = '';
    try { value = str(localStorage.getItem(THEME_KEY)); } catch (err) { value = ''; }
    return THEME_CYCLE.indexOf(value) >= 0 ? value : 'system';
  }

  function visibleTheme(pref) {
    return pref === 'system' ? (systemTheme.matches ? 'light' : 'dark') : pref;
  }

  /* index.html 在首次绘制前内联解决了同一件事；在这里再做一遍是幂等的，并且让一个函数
   * 成为唯一的真相来源。
   *
   * 两个属性，一个决定：<html> 上的 `data-theme` 是这个页面自己的规则和绘制前脚本读的，
   * <body> 上的 `data-ds-dark-theme` 是随附的 DeepSeek Harness token 表读的。它们在这里
   * 一起设置，别处不设。 */
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
    dom.btnTheme.title = '主题：' + THEME_LABEL[themePref]
      + (themePref === 'system' ? '（跟随桌面）' : '')
      + ' — 点击切换到 ' + THEME_LABEL[nextTheme(themePref)];
    dom.btnTheme.setAttribute('aria-label',
      '主题：' + THEME_LABEL[themePref] + '。切换到 ' + THEME_LABEL[nextTheme(themePref)] + '。');
  }

  function setTheme(pref) {
    themePref = pref;
    try { localStorage.setItem(THEME_KEY, pref); } catch (err) { /* 这次访问内这个选择仍然有效 */ }
    applyTheme();
  }

  function initTheme() {
    themePref = storedTheme();
    applyTheme();
    dom.btnTheme.addEventListener('click', function () { setTheme(nextTheme(themePref)); });
    // 桌面会在「跟随系统」之下变化——日落、定时、换了显示器——所以跟随媒体查询，
    // 而不是给它拍快照。
    systemTheme.addEventListener('change', function () {
      if (themePref === 'system') { applyTheme(); }
    });
  }

  // ------------------------------------------------------------------- 壁纸

  /* 服务器提供的图片，在页面背后轮换。列表来自服务器的目录；选择和第几张来自这个浏览器，
   * 而一个没有图片的服务器会把这个控件藏起来，而不是给一个什么都不做的按钮。
   *
   * 轮换是硬切，不是淡入淡出：下一张在当前这张还在屏幕上时就已经取回，所以切换永远不会
   * 显示空帧。 */
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
      // 先把下一张热起来：轮换永远不该显示空帧。
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
      names = [];   // 没有目录就没有控件；这不是一个值得显示的错误
    }
    wallpapers.names = names;
    if (names.length === 0) { return; }

    const stored = Math.floor(Number(readStored(WALLPAPER_INDEX_KEY)));
    wallpapers.index = isFinite(stored) && stored >= 0 && stored < names.length ? stored : 0;
    // 默认开启，除非这个浏览器说了别的：一个有图片的目录是已经有人做过的决定，
    // 而开关就在头部，一点就到。
    wallpapers.on = readStored(WALLPAPER_KEY) !== 'off';

    dom.btnWallpaper.hidden = false;
    dom.btnWallpaper.setAttribute('aria-pressed', wallpapers.on ? 'true' : 'false');
    dom.btnWallpaper.classList.toggle('on', wallpapers.on);
    dom.btnWallpaper.addEventListener('click', function (event) {
      // Shift 点击是手动换一张：轮换才是重点，所以普通点击是开关，而这个手势用来
      // 立刻看下一张。
      if (event.shiftKey) { nextWallpaper(); return; }
      setWallpaper(!wallpapers.on);
    });
    paintWallpaper();
    setInterval(function () {
      if (wallpaperVisible()) { nextWallpaper(); }
    }, WALLPAPER_EVERY_MS);
  }

  // ----------------------------------------------------------------- 侧边栏

  /* 侧边栏就是工作区树：每个工作区一个节点，它的会话展开在下面。两条规则让它浏览起来是
   * 安全的：
   *
   *   - 展开一个节点只做*读取* —— `GET /api/sessions?workspace=<name>`，服务器回答它时
   *     不切换任何东西——所以看另一个工作区永远不会移动这个对话；
   *   - 切换是它自己一个显式手势：行上的「使用」，或者点击一个会话，后者的意思是
   *     「去那里并打开它」。
   *
   * 折叠状态和展开的节点都会被记住，所以刷新后会回到同一个视图。右侧面板靠 flex 项上的
   * `display: none` 折叠；这一个从另一侧用同一条规则，于是两个开关读起来是一对。
   *
   * 删除会点名它发生在哪个工作区 —— `DELETE /api/session` 和 `DELETE /api/sessions` 都
   * 接受一个 `workspace` 参数——所以折叠节点下面的一行是在它所在的地方被清掉的，而页面
   * 留在它原本工作的那个工作区里。 */
  const SIDEBAR_KEY = 'ccj.sidebar.collapsed';
  const EXPANDED_KEY = 'ccj.tree.expanded';

  /* 我们清空转录时，转录为什么是空的：删除会由服务器发一条通知，但它在新的会话状态清空
   * 面板*之前*就发了，于是用户只看到一屏空白，却不知道原因。 */
  const FRESH_SESSION = ' ccj 新建了一个会话，所以转录是空的。';

  function readStored(key) {
    try { return str(localStorage.getItem(key)); } catch (err) { return ''; }
  }

  function writeStored(key, value) {
    try { localStorage.setItem(key, value); } catch (err) { /* 这次访问内仍然成立 */ }
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
    payload: null,        // 上一次 GET /api/workspaces 的答复
    expanded: storedExpanded(),
    sessions: new Map(),  // 工作区名 -> {status, items, error}
    selected: '',         // 侧边栏自己的动作指向的那一行
    activeName: '',       // 页面真正在其中工作的那个工作区
    error: '',            // 工作区列表读不出来的原因
    busy: false,          // 一次删除正在进行
    throwaway: false,     // 切换后的空会话：记下它的 id，跳过它的历史
    deletes: []           // 本次渲染里已武装的两步确认控件
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

  /* 只有占位符算空：面板里任何别的东西都是用户已经能读到的内容。 */
  function transcriptEmpty() {
    return !dom.transcript.querySelector(':not(.placeholder)');
  }

  // ---------------------------------------------------------- 侧边栏：消息

  /* 拒绝及其结果都显示在侧边栏里，紧挨产生它们的那个控件——原本用来放这些行的对话框
   * 已经没有了。 */
  function sidebarMessages(note, error) {
    dom.sidebarNote.textContent = str(note);
    dom.sidebarNote.hidden = !note;
    dom.sidebarAlert.textContent = str(error);
    dom.sidebarAlert.hidden = !error;
  }

  function sidebarNote(text) { sidebarMessages(text, ''); }
  function sidebarError(text) { sidebarMessages('', text); }
  function clearSidebarMessages() { sidebarMessages('', ''); }

  // ---------------------------------------------------------- 侧边栏：折叠

  /* 低于 720px 时，两个面板都是盖在转录上的抽贴面板，而不是它旁边的列，而遮罩让
   * 「点外面」有了意义。抽贴面板永远不会在更宽的窗口背后留着打开：两种布局差别足够大，
   * 状态不会带过去。 */
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

  /* 在抽贴面板里选一个东西正是它被打开的原因，所以它会自己收起：留着它会挡住它被用来
   * 选择的那个对话。宽窗口上什么都不做：那里的面板是列。 */
  function closeSheetsWhenNarrow() {
    if (sheetsAreSheets()) { closeSheets(); }
  }

  /* 低于 900px 时转录比树更需要宽度，所以窄窗口一开始是折叠的；在宽窗口上，已保存的
   * 选择仍然优先。 */
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

  /* 一个控件，两种布局：宽窗口上是一条可折叠的列，手机上是一个来去的抽贴面板。
   * 这两个类保持互斥，这样状态从任一个都能读出来。 */
  function toggleSidebar() {
    if (sheetsAreSheets()) {
      const open = !dom.sidebar.classList.contains('open');
      dom.sidebar.classList.toggle('open', open);
      dom.sidebar.classList.toggle('collapsed', !open);
      // 一次只有一个抽贴面板：详情面板是另一个。
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

  // ------------------------------------------------------------ 侧边栏：树

  /* 用户操作树时焦点就在树里，而每次渲染都会从服务器的答复重建这些节点。所以每个控件都
   * 带着一个能重新找到它的键，这样展开节点或删除一行永远不会在做事中途把键盘掉到
   * <body> 上。 */
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

  /* 「全部删除」作用在这个选择上，所以改变它也会解除那道提问的武装：一个已武装的控件
   * 绝不能重新指向用户在提问之后才挑的工作区。 */
  function selectWorkspace(name) {
    if (tree.selected === name) { return; }
    tree.selected = name;
    deleteAllControl.reset();
  }

  /* 一个会话：问过什么、什么时候，以及对话框原本那套两步删除，所以列表挪了地方，手势
   * 没有变。
   *
   * 行是读出来的，不是扫出来的：标签是第一条用户消息，因为一串 `20260912-030245-7b27`
   * 完全说不清一个会话是干什么的。一个还没问过任何东西的会话没有标题可显示，所以退回它的
   * id —— 也就是头部那个小标签带着的同一个字符串。
   *
   * 行上*任何地方*都能点开它。删除控件在 li 里面而不在按钮里面，所以两者之间的空隙原本
   * 会是一块看起来和行里其余部分一模一样的死区；`rowOpensOn` 给那块区域同样的去处，
   * 同时给修饰键留了余地。 */
  function sessionRow(name, item, index, activeId, stamp) {
    const id = str(item.id);
    const current = id !== '' && id === activeId;
    // 这一行自己对「正在运行」的判断：服务器的列表说明哪些会话在干活，而
    // `paintRunningRows` 会把页面从流里学到的东西并进来（自那次列表取回以来开始的
    // 或停止的回合）。
    const running = !!item.running;
    const li = el('li', 'session-row' + (current ? ' current' : '') + (running ? ' running' : ''));
    if (current) { li.setAttribute('aria-current', 'true'); }

    const btn = el('button', 'session-item');
    btn.type = 'button';
    btn.dataset.focusKey = 's:' + name + ':' + id;
    btn.title = id + (current ? ' — 屏幕上这个会话' : '')
      + (running ? '\n这个会话正在跑一个回合' : '')
      + '\n' + str(name) + ' · ' + timeLabel(item.lastModified);
    const label = firstLine(str(item.title)) || firstLine(str(item.preview));
    const name_ = el('span', 'session-name');
    if (stamp) { name_.appendChild(el('span', 'session-id', stamp)); }
    const titleNode = el('span', 'session-title', clip(label || id, 200));
    // 这一行代表的 id，由运行标记读回：树是从服务器的列表重新渲染的，而这个标记必须
    // 能在不重新抓取任何东西的情况下再次找到这一行。
    titleNode.dataset.sessionId = id;
    name_.appendChild(titleNode);
    if (running) {
      // 一个点，而不是转圈：那个回合发生在另一个对话里，这个标记是用来把用户领回
      // 那里的，而不是装饰。
      const mark = el('span', 'session-running');
      mark.title = '正在跑一个回合——打开它旁观，或用 × 停止它';
      mark.setAttribute('aria-label', '运行中');
      name_.appendChild(mark);
    }
    btn.appendChild(name_);
    btn.addEventListener('click', function () { openWorkspaceSession(name, id, btn); });

    // 在恢复请求进行中被禁用是那个按钮，所以落在行的空白条上的点击报告的是同一个
    // 控件。
    rowOpensOn(li, function () { openWorkspaceSession(name, id, btn); });

    const actions = el('span', 'session-actions');
    if (running) {
      // 停止那个回合是用户没在看的那一行上唯一值得有的动作：没有它，一个后台回合
      // 只能先打开才能停下。
      const stop = el('button', 'session-stop');
      stop.type = 'button';
      stop.textContent = '■';
      stop.title = '停止 ' + id + ' 里正在跑的回合';
      stop.dataset.focusKey = 'stop:' + name + ':' + id;
      stop.addEventListener('click', function (event) {
        event.stopPropagation();
        stopSession(id, name);
      });
      actions.appendChild(stop);
    }
    const remove = deleteControl(actions, '删除', function (control) {
      deleteTreeSession(name, id, index, control, btn);
    });
    remove.idle.dataset.focusKey = 'del:' + name + ':' + id;
    remove.yes.dataset.focusKey = 'del2:' + name + ':' + id;
    remove.idle.title = '把 ' + id + ' 从磁盘上删除';
    tree.deletes.push(remove);

    li.appendChild(btn);
    li.appendChild(actions);
    return li;
  }

  /* 整行都是目标：落在 li 上、而行的控件没有先应答的任何点击都会打开这个会话。正是这一点
   * 把标题和「删除」之间那条——视觉上属于这一行、此前却是死的——空白条变成了可点的
   * 地方。删除控件是按钮，所以点它们的点击到达时目标不是 li 本身，会被放过；带修饰键的
   * 点击（新标签页、选中文字）也不算普通打开。
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

  /* 一个节点的会话，或者还没有可显示内容的原因。这些行是从节点自己的工作区读出来的，
   * 无论它是不是当前工作区。 */
  function sessionsList(name, listId) {
    const list = el('ul', 'ws-sessions');
    list.id = listId;
    list.setAttribute('aria-label', name + ' 里的会话');
    const cached = cachedSessions(name);
    if (!cached || cached.status === 'loading') {
      list.appendChild(el('li', 'ws-note muted', '加载中…'));
    } else if (cached.status === 'error') {
      list.appendChild(el('li', 'ws-note err', '无法加载会话：' + cached.error));
    } else if (!cached.items.length) {
      list.appendChild(el('li', 'ws-note muted',
        '没有已保存的会话——会话有了消息之后才会得到文件。'));
    } else {
      const activeId = activeSessionId();
      const items = cached.items;
      // 只有会读起来一样的行才配时间戳；一个互不冲突的列表从头到尾都是标题，
      // 而这正是这次改动要的。
      const stamps = repeatedStamps(items);
      items.forEach(function (item, index) {
        list.appendChild(sessionRow(name, item, index, activeId, stamps.get(str(item.id))));
      });
    }
    return list;
  }

  /* 会话 id → 时间戳，给那些标题已被同一列表里另一行显示的 id。按 id 索引，
   * 所以重复标题在一趟里就解答完。 */
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
      ? '\n这个页面正在其中工作的工作区——点击展开它的会话'
      : '\n点击展开它的会话——这不会切换工作区');
    nameBtn.appendChild(el('span', 'ws-chevron', open ? '▾' : '▸'));
    nameBtn.appendChild(el('span', 'ws-folder', '▣'));
    nameBtn.appendChild(el('span', 'ws-label', name));
    const count = Number(item.sessions);
    if (isFinite(count)) {
      const badge = el('span', 'ws-count', count.toLocaleString());
      badge.title = count === 1 ? '1 个会话' : count.toLocaleString() + ' 个会话';
      nameBtn.appendChild(badge);
    }
    nameBtn.addEventListener('click', function () { toggleNode(name); });
    row.appendChild(nameBtn);
    li.appendChild(row);

    // 左边是状态，右边是这一行自己的动作：卡片的一行，这样上面的名字永远不必
    // 被挤着让位。
    const foot = el('div', 'ws-foot');
    if (active) {
      const badge = el('span', 'ws-badge', '活动');
      badge.title = '当前工作区——无需切换';
      foot.appendChild(badge);
    } else {
      const use = el('button', 'btn ghost sm ws-use', '使用');
      use.type = 'button';
      use.dataset.focusKey = 'use:' + name;
      use.title = '切换到 ' + name + ' — 会在那里开始一个新会话';
      use.addEventListener('click', function () { activateWorkspace(name, use); });
      foot.appendChild(use);
    }
    li.appendChild(foot);

    const remove = deleteControl(foot, '移除', function (control) {
      removeWorkspace(name, control);
    }, { confirm: '移除？', busy: '移除中…' });
    remove.idle.dataset.focusKey = 'rm:' + name;
    remove.idle.classList.add('ws-remove');
    remove.idle.title = active
      ? '当前工作区不能被移除——请先切换到另一个'
      : '忘记 ' + name + ' — 它的会话文件仍留在磁盘上';
    remove.idle.disabled = active;
    tree.deletes.push(remove);

    if (open) { li.appendChild(sessionsList(name, listId)); }
    return li;
  }

  function renderTree(key) {
    const keep = key === undefined ? focusKey() : str(key);
    tree.deletes = [deleteAllControl];
    // 树是从服务器的答复重建的，而重建会把滚动位置一起带走——何况现在回合结束时
    // 也会自己重建一次。在另一个工作区很长的时候读这个工作区的历史，不能把侧边栏
    // 弹回顶部，所以位置的传递方式和焦点一样。
    const scrolled = treeScroll();
    dom.wsTree.textContent = '';
    if (!tree.payload) {
      dom.wsTree.appendChild(el('li', 'ws-note ' + (tree.error ? 'err' : 'muted'),
        tree.error || '加载中…'));
      return;
    }
    const items = workspaceItems();
    if (!items.length) {
      dom.wsTree.appendChild(el('li', 'ws-note muted', '未报告任何工作区。'));
      return;
    }
    items.forEach(function (item, index) { dom.wsTree.appendChild(nodeElement(item, index)); });
    treeScroll(scrolled);
    // 键盘原本所在的控件可能已经没了（被删的行、被忘记的工作区）；取而代之，
    // 焦点落在选中的节点上。
    if (keep && !focusByKey(keep)) { focusByKey('ws:' + tree.selected); }
  }

  /* 滚动的是侧边栏的主体，不是它里面的树：那些提示和动作行属于同一列。从拥有它的
   * 容器上读位置，正是它能熬过一次重建的原因。 */
  function treeScroll(to) {
    const body = dom.wsTree.parentNode;
    if (!body) { return 0; }
    if (to === undefined) { return body.scrollTop || 0; }
    // 只在有空间时才恢复位置：更短的列表不能被留在滚过自己末尾的地方，
    // 那会显示一屏空白。
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
      // 陈旧胜过什么都没有：树保留上一次的答复，而提示说明刷新为什么失败。
      // 一次答复都没有时就说这个，而不是永远显示「加载中…」。
      tree.error = '无法加载工作区：' + str(err && err.message);
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

  /* 展开一个节点就是让这个列表保持诚实的读取：展开节点的会话只取一次，之后一直缓存到
   * 有东西让它失效为止。 */
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
        status: 'error', items: [], error: str(err && err.message) || '请求失败'
      });
    }
  }

  /* 删除会话、新建会话、切换工作区和重读工作区列表，都会让缓存的列表失效。 */
  function invalidateSessions(name) {
    if (name === undefined || name === null || name === '') { tree.sessions.clear(); }
    else { tree.sessions.delete(str(name)); }
  }

  /* 一个回合刚刚结束，所以它跑在里面的那个会话动过了：服务器按每个文件的修改时间排序，
   * 而这个回合写过那个文件。页面手上还是回合之前取回的列表，这就是为什么一个又被用到的
   * 会话没有升上去，而一个第一条消息就是本回合的会话根本不在列表里。重新问一次，才能把
   * 你刚进行的对话放到最上面。
   *
   * 只重读当前工作区——别的工作区里没有追加过东西，顺序不可能变——而失败时把上一次的
   * 顺序留在屏幕上：后台刷新绝不能把行换成一条错误，因为旁边那个对话本身已经是对的。
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
        invalidateSessions(active);   // 屏幕上没有要重排的东西，但缓存已经陈旧
      }
      await loadWorkspaces({ keep: true });
    } catch (err) {
      // 保持上一次的顺序；下一个重读列表的动作会修正它。
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

  /* 页面自己的工作区和树上的 `active` 标记是同一个事实。当服务器说当前工作区变了，
   * 树就跟着变。 */
  function noteActiveWorkspace() {
    const name = workspaceName();
    if (name === tree.activeName) { return; }
    tree.activeName = name;
    if (name) { selectWorkspace(name); }
    renderTree();
    loadExpanded();
  }

  // -------------------------------------------------------- 侧边栏：动作

  /* 切换是两步动作：请求改变服务器，而回答它的 status 说明新工作区在哪个会话上。
   * 转录在*这里*就被清空，在答复到达之前，所以旧工作区的消息永远不会和新工作区的名字
   * 同屏——也永远不会挂在一个两边都不属于它的会话 id 下面。 */
  async function switchWorkspaceTo(name) {
    const res = await postJSON('/api/workspace', { name: name });
    selectWorkspace(name);
    state.sessionId = '';          // 下一个 status 必须清屏并重新取回
    clearTranscript();
    // 回答切换的那个 status 描述的是一个全新的空会话。去取*它的*历史等于为了一屏空白
    // 跑一趟往返，所以只记下 id、不动转录；随后那次 resume（用户挑了某个会话时）才是
    // 真正去取的动作。
    tree.throwaway = true;
    try {
      if (isStatusPayload(res)) { applyStatus(res); } else { await refreshStatus(); }
    } finally {
      tree.throwaway = false;
    }
  }

  /* 「使用」是唯一会移动页面的控件，而它的代价写在提示里：切换会在目标工作区里开始
   * 一个新会话。 */
  async function activateWorkspace(name, button) {
    if (!name || name === workspaceName()) {
      closeSheetsWhenNarrow();
      return;
    }
    clearSidebarMessages();
    if (button) { button.disabled = true; }
    try {
      await switchWorkspaceTo(name);
      // 一次切换会让所有缓存的列表失效，而计数是跟着活动标记走的，所以列表是重读
      // 而不是打补丁。
      await loadWorkspaces({ focusKey: 'ws:' + name });
      // 只在成功之后：失败是在这个抽贴面板里报告的，关掉它就会藏起这一按什么都没
      // 发生的原因。
      closeSheetsWhenNarrow();
    } catch (err) {
      sidebarError('无法切换到 ' + name + '：' + str(err && err.message));
      renderTree('use:' + name);
    }
  }

  /* 另一个工作区里的一个会话同时意味着两件事：先去那里，再打开它——顺序如此。两者
   * 之间转录里留着切换的占位符，所以错误工作区的历史永远不会闪出来。 */
  async function openWorkspaceSession(name, id, button) {
    clearSidebarMessages();
    if (button) { button.disabled = true; }
    try {
      if (name !== workspaceName()) {
        await switchWorkspaceTo(name);
        await loadWorkspaces({ keep: true, focusKey: 's:' + name + ':' + id });
      }
      await switchSession({ action: 'resume', id: id }, button);
      // 对话现在在屏幕上了，而这正是那个抽贴面板被打开的原因；下面的失败会把它留着，
      // 因为失败就是在那里报告的。
      closeSheetsWhenNarrow();
    } catch (err) {
      if (button) { button.disabled = false; }
      sidebarError('无法打开 ' + clip(id, 44) + '：' + str(err && err.message));
      renderTree('s:' + name + ':' + id);
    }
  }

  /* 恢复还是新建会话由服务器决定；答复说明页面现在在哪个会话上，而由它——永远不是
   * 被按下的那个按钮——决定是否清空转录并取回它的历史。 */
  async function switchSession(body, button) {
    if (button) { button.disabled = true; }
    try {
      const res = await postJSON('/api/session', body);
      if (isStatusPayload(res)) { applyStatus(res); } else { await refreshStatus(); }
      if (body.action === 'new') {
        // 让当前工作区的列表失效的是新建会话；恢复只改变哪一行带着标记。
        invalidateSessions(workspaceName());
        renderTree();
        loadExpanded();
      } else {
        renderTree();
      }
    } catch (err) {
      if (button) { button.disabled = false; }
      const message = 'session ' + body.action + ' 操作失败：' + str(err && err.message);
      appendError(message);
      sidebarError('session ' + body.action + ' 操作失败：' + str(err && err.message));
      renderTree();
    }
  }

  /* 删除一个会话。端点里带着工作区名，所以折叠节点下面的一行是在它所在的地方被删除的 ——
   * 页面不必切换，而在别处删除永远不会碰到屏幕上的那个会话。 */
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
      // 这个答复*就是*该工作区刷新后的列表，所以存下来并保留：再问一次等于为一手就有的
      // 信息跑一趟往返。
      tree.sessions.set(name, { status: 'ready', items: sessionsIn(res), error: '' });
      const items = cachedSessions(name).items;
      const next = items.length ? items[Math.min(index, items.length - 1)] : null;
      const focus = next ? 's:' + name + ':' + str(next.id) : 'ws:' + name;
      renderTree(focus);
      sidebarNote('已删除 ' + clip(id, 44) + '。' + (active ? FRESH_SESSION : ''));
      await loadWorkspaces({ keep: true, focusKey: focus });
      await afterSessionDelete(active);
    } catch (err) {
      sidebarError('无法删除 ' + clip(id, 44) + '：' + str(err && err.message));
      invalidateSessions(name);   // 屏幕上的列表不再可信
      renderTree('ws:' + name);
      loadExpanded();
    } finally {
      tree.busy = false;
    }
  }

  /* 「全部删除」清空侧边栏所选的那个工作区，工作区名写在请求里，所以它在任何节点上都
   * 能用——不限于当前工作区。 */
  async function deleteAllSelectedWorkspace(control) {
    if (tree.busy) { return; }
    const name = tree.selected || workspaceName();
    if (!name) {
      control.reset();
      sidebarError('请先选择一个工作区。');
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
      sidebarNote((gone === 1 ? '已删除 1 个会话。' : '已删除 ' + gone + ' 个会话。')
        + (inActive ? FRESH_SESSION : ''));
      await loadWorkspaces({ keep: true, focusKey: 'delall' });
      await afterSessionDelete(inActive);
    } catch (err) {
      control.reset();
      sidebarError('无法删除这些会话：' + str(err && err.message));
      renderTree('delall');
    } finally {
      tree.busy = false;
    }
  }

  /* 删除清掉了多少行：列表已经读过时用手上的计数，否则用工作区自己的计数。 */
  function cachedCount(name) {
    const cached = cachedSessions(name);
    if (cached && cached.status === 'ready') { return cached.items.length; }
    const item = workspaceItem(name);
    const count = Number(item && item.sessions);
    return isFinite(count) ? count : 0;
  }

  /* 删除当前会话会让服务器发一个新 id，页面跟着清空转录——这是对的，但除非面板说明
   * 原因，否则看起来像出了故障。先从服务器对齐（这样树不必等流），然后解释空面板，
   * 但只在没有别人解释过的时候。 */
  async function afterSessionDelete(active) {
    if (!active) { return; }
    await refreshStatus();
    if (state.historyPromise) { await state.historyPromise; }
    if (transcriptEmpty()) {
      appendNotice('你原本所在的会话已被删除 — ccj 新建了一个，所以这个转录是空的。');
    }
  }

  /* 删除是破坏性的，而且没有退回，所以一次点击只是*武装*它：按钮就地换成那句提问和
   * 一条出路，提问获得焦点，第二次点击才是真正花掉那个文件。
   *
   * `words` 让调用方说「移除」而不是「删除」，而不必为同一个手势写第二份实现。 */
  function deleteControl(host, label, onConfirm, words) {
    const askLabel = (words && words.confirm) || '删除？';
    const busyLabel = (words && words.busy) || '删除中…';
    const idle = el('button', 'btn danger sm', label);
    const ask = el('span', 'confirm-row');
    const yes = el('button', 'btn danger sm', askLabel);
    const no = el('button', 'btn ghost sm', '取消');
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

  /* 每个拒绝一条 400 消息，归属在这里决定：表单打开时，一个不可用的目录属于路径字段
   * 下面；表单关闭时——服务器拒绝了某次选择——屏幕上没有字段，所以消息走树上方，
   * 也就是原本放「已添加」通知的那个位置。 */
  function showWorkspaceError(message) {
    const text = str(message) || '工作区请求失败。';
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

  /* 选择器跑在提供这个页面的机器上，所以这个请求*就是*桌面对话框：它会一直阻塞到用户
   * 回答，或者服务器在两分钟后放弃并报告一次取消。只有真的选回了文件夹时才用路径调用
   * `picked`，所以取消既不是错误也不是添加。400（没有桌面、没有选择器）显示在字段旁边，
   * 而那个字段继续作为退路可用。 */
  async function pickWorkspaceFolder(picked) {
    clearWorkspaceErrors();
    dom.workspacePick.disabled = true;
    dom.wsBrowse.disabled = true;
    const label = dom.workspacePick.textContent;
    dom.workspacePick.textContent = '等待中…';
    dom.wsBrowseHint.textContent =
      '桌面上已打开一个文件夹选择器——请在那个窗口里选择一个文件夹。';
    dom.wsBrowseHint.hidden = false;
    try {
      const res = await request('/api/workspaces/browse', { method: 'POST' });
      if (res && typeof res === 'object' && res.path && !res.cancelled) {
        dom.wsBrowseHint.textContent = '';
        dom.wsBrowseHint.hidden = true;
        picked(str(res.path));
      } else {
        dom.wsBrowseHint.textContent = '没有选择文件夹——选择器被取消了。';
      }
    } catch (err) {
      dom.wsBrowseHint.hidden = true;
      fieldError(dom.wsPathError, str(err && err.message) || '无法打开文件夹选择器。');
    }
    dom.workspacePick.disabled = false;
    dom.wsBrowse.disabled = false;
    dom.workspacePick.textContent = label;
  }

  /* 名字是文件夹的，所以没有什么可问的：请求带着目录，服务器推导出名字（这个名字被
   * 占用时就加一个可用后缀）。 */
  async function addWorkspace(path) {
    clearWorkspaceErrors();
    const directory = str(path).trim();
    if (!directory) {
      fieldError(dom.wsPathError, '输入一个目录，或者选择一个文件夹。');
      dom.wsNewPath.focus();
      return;
    }
    dom.workspaceSave.disabled = true;
    try {
      const res = await postJSON('/api/workspaces', { path: directory });
      const added = addedWorkspace(res, directory);
      setAddFormOpen(false);
      // 添加不是切换：新节点被展开，好让它（空的）列表可见，而页面留在它原本工作的
      // 那个工作区里。
      if (added) {
        tree.expanded.add(added);
        saveExpanded();
        sidebarNote('工作区 ' + added + ' 已添加——按「使用」在那里工作。');
      } else {
        sidebarNote('工作区已添加——按「使用」在那里工作。');
      }
      acceptWorkspaces(res, added ? { selected: added, focusKey: 'ws:' + added } : undefined);
    } catch (err) {
      showWorkspaceError(err.message);
    }
    dom.workspaceSave.disabled = false;
  }

  /* 服务器刚创建的是哪一项：那个拥有所请求目录的项。名字由服务器选，所以是读回来的，
   * 而不是从路径猜的。 */
  function addedWorkspace(payload, directory) {
    const wanted = str(directory).replace(/\/+$/, '');
    let found = '';
    workspaceItemsOf(payload).forEach(function (item) {
      if (!found && str(item.path).replace(/\/+$/, '') === wanted) { found = str(item.name); }
    });
    return found;
  }

  /* 点击「添加工作区」这个手势就是：桌面自带的选择器，选定文件夹后工作区立刻出现。
   * 选择器跑不起来的机器会把这件事报告在路径字段下面，所以那时就打开表单 ——
   * 手输路径仍然只差一点，而不是藏在一次拒绝后面。 */
  async function addWorkspaceByPicking() {
    await pickWorkspaceFolder(function (path) { addWorkspace(path); });
    if (!dom.wsPathError.hidden) { openAddForm(); }
  }

  function openAddForm() {
    setAddFormOpen(true);
    dom.wsNewPath.focus();
  }

  /* 忘记不是删除：注册表里的条目没了，会话文件留在原处。当前工作区会被拒绝，
   * 而按钮在服务器必须开口之前就先说了。 */
  async function removeWorkspace(name, control) {
    clearSidebarMessages();
    control.busy();
    try {
      const res = await request('/api/workspace?name=' + encodeURIComponent(name), { method: 'DELETE' });
      sidebarNote('工作区 ' + name + ' 已被忘记——它的会话文件仍留在磁盘上。');
      await acceptWorkspaces(res, { focusKey: 'ws:' + name });
    } catch (err) {
      control.reset();
      sidebarError('无法移除 ' + name + '：' + str(err && err.message));
      renderTree();
    }
  }

  // --------------------------------------- provider / model / effort picker

  /* 一个控件，两个挂载点：输入框（就在输入区上方）和设置表单。两者读同一个目录 ——
   * GET /api/models 与 GET /api/config 报告的名字合并而来——所以它们永远不可能跑偏。
   * 区别在于一次选择*做了什么*，而那是调用方的回调，不是选择器：输入框通过
   * POST /api/config 立刻提交，设置表单只填自己的字段，直到保存。
   *
   * 面板是两列（左边提供方，右边该提供方的模型），下面一行是思考强度。选提供方只换右列；
   * 选模型或选档位才是提交。被拒绝的请求显示在面板里面，并且保留之前的选择，所以屏幕
   * 永远不会声称一次服务器拒绝了的改动。 */

  const EFFORT_HINT = {
    default: '默认——由提供方决定想多少。',
    low: '低——多想一点，多花一点 token。',
    high: '高——想得更多，token 也更多。',
    max: '最高——想得最多，token 也最多。'
  };
  const REASONING_FALLBACK = ['low', 'high', 'max'];

  /* 服务器接受的档位（status.reasoningLevels），最前面是隐含的 "default"。
   * 一个早于这个字段的服务器照样会拿到全部三档。 */
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

  function tierLabel(tier) { return str(tier) === 'default' || !str(tier) ? '默认' : str(tier); }

  /* 提供方列表先是目录里的，然后是 GET /api/config 知道、而目录里没有的名字：服务器接受
   * 一个这个版本从没听说过的提供方，所以选择器也必须提供它。 */
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
        ? '未配置'
        : [sel.provider, sel.model, tierLabel(sel.reasoning)].filter(Boolean).join(' · ');
      nodes.value.classList.toggle('picker-value-warn', !sel.provider || !sel.model);
      nodes.trigger.title = empty
        ? '尚未配置模型——选择一个提供方和模型'
        : '提供方 ' + (sel.provider || '—') + '、模型 ' + (sel.model || '—')
          + '、思考强度 ' + tierLabel(sel.reasoning);
    }

    function optionMeta(parts) {
      const meta = el('span', 'picker-option-meta');
      parts.forEach(function (part) {
        if (part) { meta.appendChild(el('span', 'picker-badge', part)); }
      });
      return meta;
    }

    /* 提供方这一列。目录抓取失败永远不会变成只有一行的列表：如果上一次的答复是好的，
     * 就留着它并在上面显示原因；一次答复都没有时就只显示原因。选中的提供方是真实列表
     * 之上的一行，永远不是它的替代品——而那正是让其他提供方看起来全都不见了的原因。 */
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
          '提供方列表不可用：' + state.catalogError
          + (loaded ? ' 正在显示上一次成功加载的列表。' : '')));
      }
      if (!list.length) {
        if (!state.catalogError) {
          nodes.providers.appendChild(el('li', 'picker-empty',
            loaded ? '未报告任何提供方。' : '正在加载提供方…'));
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
        // 没有目录时（一个不提供它的版本），种类以及内置/自有的区分根本就是未知的；
        // 只有名字是诚实的。
        if (p.known) {
          meta.push(p.builtIn ? '内置' : '你的提供方');
          meta.push(p.models.length === 1 ? '1 个模型' : p.models.length + ' 个模型');
        }
        btn.appendChild(optionMeta(meta));
        btn.addEventListener('click', function () { chooseProvider(p.name); });
        li.appendChild(btn);
        // 提供方可以在被选择的地方就被移除：自定义定义会被删除，内置别名只是被隐藏
        // （它删不掉——它是代码），并可以在「设置」里恢复。
        const actions = el('span', 'picker-option-actions');
        const drop = deleteControl(actions, '移除', function (control) {
          removeProvider(p.name, control);
        }, { confirm: '移除？', busy: '移除中…' });
        drop.idle.title = '把 ' + p.name + ' 从这个列表移除'
          + (p.builtIn ? '（内置只是被隐藏，不会被删除）' : ' — 它的定义会被删除');
        li.appendChild(actions);
        nodes.providers.appendChild(li);
      });
    }

    /* 模型这一列是目录里「正在浏览的那个提供方」的列表——通常是选中的提供方，除非用户
     * 有意在看左列的别的一行，而 openPanel 和 setStatus 会把它调回与选择一致。正在使用的
     * 模型和选择器自己的选择，在目录没列出它们时会被补上，所以两者都不会看起来消失了。 */
    function renderModels() {
      nodes.models.textContent = '';
      const provider = picker.browsed || picker.selection.provider;
      if (!provider) {
        nodes.models.appendChild(el('li', 'picker-empty', '请先选择一个提供方。'));
        return;
      }
      const wanted = provider.toLowerCase();
      const entries = catalogModelsFor(provider).slice();
      const chosen = str(picker.selection.provider).toLowerCase() === wanted
        ? str(picker.selection.model).trim() : '';
      const inUse = str(state.status && state.status.provider).toLowerCase() === wanted
        ? str(state.status && state.status.model).trim() : '';
      // 只列提供方真正提供的东西：一个被移除但仍在使用的模型，会继续显示在输入框上方的
      // 触发按钮里，但它不能在这里留一行，否则移除它会看起来什么都没发生。
      if (!entries.length) {
        // 「没有列出模型」只有在目录回答过时才是真的；没有目录时，诚实的说法是这个列表
        // 根本不可用。
        nodes.models.appendChild(el('li', 'picker-empty', state.catalog
          ? '没有为 ' + provider + ' 列出模型——在下面输入一个，或者在「设置」的'
            + '「你的提供方」里定义它。'
          : '模型列表不可用' + (state.catalogError ? ' — ' + state.catalogError : '')
            + '；在下面输入模型名称。'));
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
        btn.appendChild(optionMeta(isInUse ? ['使用中'] : (str(entry.source) ? [str(entry.source)] : [])));
        btn.addEventListener('click', function () {
          choose('model', { provider: provider, model: model });
        });
        li.appendChild(btn);
        // 忘记这个模型：与其他所有移除一样的那个两步确认控件。
        const actions = el('span', 'picker-option-actions');
        const forget = deleteControl(actions, '移除', function (control) {
          removeModel(provider, model, control);
        }, { confirm: '移除？', busy: '移除中…' });
        forget.idle.title = '为 ' + provider + ' 忘记 ' + model + ' — 它不再被提供';
        li.appendChild(actions);
        nodes.models.appendChild(li);
      });
    }

    function renderEffort() {
      nodes.effort.textContent = '';
      nodes.effort.appendChild(el('span', 'picker-effort-label', '思考强度'));
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

    /* 选一个提供方就必须足以切换到它。光靠浏览会让另一个提供方无法到达，除非它还有一行
     * 模型可以点，于是一个列表为空的提供方根本选不中。输入框会提交：在该模型仍被提供时
     * 把它与正在使用的模型配对，否则用第一个被提供的模型；设置表单则仍然只在保存之前
     * 起草这次改动。 */
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

    /* 唯一的提交路径：选模型会改提供方 + 模型，选档位只改档位。`apply` 可能拒绝
     * （提供方没有密钥、有回合正在运行）；那时显示错误，选择保持不变。 */
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
        showError(str(err && err.message) || '这次改动被拒绝了。');
        // 即使切换被拒绝，右列也跟着这次点击走，否则面板会一直显示上一个提供方的模型，
        // 而新加的模型看起来像被替换掉了。
        if (kind === 'model' && str(patch.provider)) {
          picker.browsed = str(patch.provider);
          picker.render();
        }
      });
    }

    /* /api/models 返回的 400 就是服务器在说明原因：正在使用的模型永远不能被忘记，
     * 不属于这个提供方的名字同样不能。面板把原因说出来；什么都不改。 */
    function modelError(err) {
      if (err && err.status === 404) {
        return '这个服务器版本还不会记住模型，所以什么都没改'
          + '（POST/DELETE /api/models 返回了 404）。';
      }
      return str(err && err.message) || '模型没能保存。';
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

    /* 模型这一列在列表旁边留了一个文本框，这样一个列表为空的提供方——或者目录不认识的
     * 模型——仍然能被描述出来。「添加」只会往列表里追加：切换是点模型行做的事，把两者
     * 混为一谈正是第二个模型看起来替换掉第一个的原因。重渲染用服务器的答复，并且让面板
     * 保持打开。 */
    async function useManualModel() {
      const model = nodes.modelInput.value.trim();
      const provider = picker.browsed || picker.selection.provider;
      if (!provider) { showError('请先选择一个提供方。'); return; }
      if (!model) { showError('请先输入模型名称。'); return; }
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

    /* 跟随服务器的状态。只在真正变化时才重渲染，所以空闲页面那些重复的 status 帧永远
     * 不会偷走打开面板里的焦点。 */
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
      // 模型这一列属于选中的提供方；来自任何地方（另一个标签页、一次保存）的提供方
      // 变化都会带着这一列一起走。
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
      // 这一列跟随选择：上一次面板打开时留下的浏览位置，不能把另一个提供方的模型
      // 放在那里。
      picker.browsed = picker.selection.provider;
      nodes.modelInput.value = '';
      nodes.panel.hidden = false;
      nodes.trigger.setAttribute('aria-expanded', 'true');
      clearError();
      picker.render();
      // 上次看之后才定义的提供方（另一个窗口、终端、一个脚本）必须出现在这里，
      // 而不是等到别的事情碰巧刷新才可见。上面的渲染已经显示了手头有的东西。
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
    /* 在模型输入框里按 Enter 会应用它——而且必须不会到达输入框，那里同一个键
     * 是发送消息的。 */
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

  /* 输入框会提交每一次选择；设置表单把选择当作草稿留到保存，所以它的回调只处理凭据
   * 字段。 */
  async function applyComposerChoice(kind, next) {
    const payload = kind === 'reasoning'
      ? { reasoning: next.reasoning }
      : { provider: next.provider, model: next.model };
    const res = await postJSON('/api/config', payload);
    if (res && typeof res === 'object') { applyStatus(res); }   // 先更新小标签，status 事件稍后到
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

  // ---------------------------------------------------------------- 设置

  /* 保存下来的密钥永远不会到达 DOM：renderSettings() 会清空这个字段，只有它的占位符
   * 报告有东西被保存着。 */
  const KEY_PLACEHOLDER = {
    config: '•••••••• 已保存',
    env: '来自环境变量',
    none: '粘贴你的 API 密钥'
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

  // ------------------------------------------------------------ 提供方目录

  /* 目录（GET /api/models）就是设置表单所提供的东西：有哪些提供方、哪些是用户自己的、
   * 每个提供方提供哪些模型。它是可选的——手输名字和手输模型照样能用——所以刷新失败会被
   * 记下并显示，永远不会抛出去。 */
  async function refreshCatalog() {
    try {
      setCatalog(await request('/api/models'));
    } catch (err) {
      // 保留上一次的目录：陈旧的建议胜过没有建议。
      state.catalogError = err && err.status === 404
        ? '这个服务器版本不提供提供方目录（GET /api/models 返回了 404）'
        : (str(err && err.message) || '目录无法加载');
    }
    refreshPickers();
    renderProviderSection();
  }

  /* 只保留对象条目：目录是一串提供方记录，每条有名字、种类、端点和模型。 */
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

  /* 名字按大小写不敏感匹配，与服务器匹配它们的方式一致。 */
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

  /* 设置里选择器下方的摘要：所选提供方列出了几个模型，以及它们来自哪里。面板本身按行
   * 按需讲同样的事。 */
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
        text = (entries.length === 1 ? '1 个模型' : entries.length + ' 个模型') + '，属于 ' + name
          + (sources.length ? ' · 来源：' + sources.join(', ') : '');
      } else if (providerInfo(name)) {
        text = name + ' 没有列出模型——在选择器里输入模型名称。';
      }
    }
    dom.cfgModelHint.textContent = text;
    dom.cfgModelHint.hidden = !text;
  }

  /* 说明所选提供方是什么：编译进来的、用户自己的，或者还没定义——最后一种是一个真实的
   * 状态，不是要藏起来的笔误。设置表单仍然接受任何名字，因为服务器也接受。 */
  function renderProviderHint() {
    if (!settingsPicker) { return; }
    const name = settingsPicker.selection.provider.trim();
    const info = providerInfo(name);
    let text = '';
    if (info) {
      const parts = [info.builtIn === true ? '内置提供方' : '你自己的提供方'];
      if (str(info.kind)) { parts.push('协议 ' + str(info.kind)); }
      const count = Array.isArray(info.models) ? info.models.length : 0;
      parts.push(count === 1 ? '1 个模型' : count + ' 个模型');
      text = parts.join(' · ');
    } else if (name && state.catalog) {
      text = '「' + name + '」在这个服务器上还没有定义——在下面的「你的提供方」里添加它，'
        + '或者就按原样保存。';
    }
    dom.cfgProviderHint.textContent = text;
    dom.cfgProviderHint.hidden = !text;
  }

  /* 提供方的端点和密钥变量属于该提供方，所以选中一个已知的提供方会把这些填上。这对正确性
   * 也有影响：服务器把与提供方默认值不同的接口地址当作一次显式覆盖，所以字段里留着上一个
   * 提供方的地址，会悄无声息地压过刚选中的那个定义。 */
  /* 服务器是否说这个提供方保存了密钥。名字按服务器匹配它们的方式匹配，而列表里只有名字 ——
   * 密钥永远不会到达页面。 */
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
    // 定义里指定了变量时就用它：一个从 MYRELAY_KEY 读密钥的中转服务，不该被要求去读
    // OPENAI_API_KEY，那是另一个提供方的密钥。
    dom.cfgApiKeyEnv.value = str(info.apiKeyEnv)
      || (str(info.kind) === 'anthropic' ? 'ANTHROPIC_API_KEY' : 'OPENAI_API_KEY');
    // 密钥是按提供方保存的，所以这一个有没有密钥是服务器回答的问题（只按名字，永远不涉及
    // 密钥本身）：这里说「已保存」和服务器说「会发送」是同一个事实，而一个对不会被发送的
    // 密钥说「已保存」的表单，正是把错误的密钥记下来的方式。
    if (str(name).toLowerCase() !== str(state.keyProvider).toLowerCase()) {
      dom.cfgApiKey.value = '';
      const remembered = providerHasSavedKey(name);
      dom.cfgApiKey.placeholder = remembered ? KEY_PLACEHOLDER.config : KEY_PLACEHOLDER.none;
      dom.cfgApiKeyHint.textContent = remembered
        ? '已为 ' + str(name) + ' 保存了一个密钥。留空即可保留它。'
        : '这个提供方没有保存密钥——粘贴一个才能切换过去。';
      dom.cfgApiKeyHint.hidden = false;
      // 只在有东西可清时才提供清除，而且它清的是这个提供方的。
      dom.cfgClearKey.checked = false;
      dom.cfgClearKeyWrap.hidden = !remembered;
    }
    const models = Array.isArray(info.models) ? info.models.map(str) : [];
    if (models.length && models.indexOf(settingsPicker.selection.model) < 0) {
      // 提供方的第一个模型是合理的默认值；在保存之前什么都不发送。
      settingsPicker.setSelection({
        provider: settingsPicker.selection.provider,
        model: models[0],
        reasoning: settingsPicker.selection.reasoning
      });
    }
  }

  // -------------------------------------------------- 你的提供方（自定义的）

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

  /* 这里的 404 不是请求失败，而是这个版本的问题：这两个提供方端点是 API 里比较新的。
   * 把它说出来，就是「什么都没发生」和「它成功了，相信我」之间的差别。 */
  function providersUnsupported(action, method) {
    return '这个服务器版本还不支持' + action + '，所以什么都没改'
      + '（' + method + ' /api/providers 返回了 404）。';
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
      top.appendChild(el('span', 'provider-badge in-use', '使用中'));
    }
    const actions = el('span', 'provider-actions');
    const remove = deleteControl(actions, '移除', function (control) {
      removeProvider(name, control);
    }, { confirm: '移除？', busy: '移除中…' });
    remove.idle.title = '移除 ' + name + ' 的定义——它的模型不再被提供';
    top.appendChild(actions);

    const count = Array.isArray(info.models) ? info.models.length : 0;
    li.appendChild(top);
    li.appendChild(el('span', 'provider-url', str(info.baseUrl) || '—'));
    li.appendChild(el('span', 'provider-meta',
      count === 0 ? '没有列出模型' : count === 1 ? '1 个模型' : count + ' 个模型'));
    return li;
  }

  /* 当前不在列表里的内置提供方，作为「添加」表单里的一个选项提供。它们不是被「隐藏」了，
   * 也没有什么要恢复的：列表里就是没有它们，而添加一个就是一次普通的添加。 */
  function renderBuiltInChoices() {
    const names = Array.isArray(state.catalog && state.catalog.builtIns)
      ? state.catalog.builtIns : [];
    dom.cfgBuiltIns.textContent = '';
    dom.cfgBuiltInRow.hidden = names.length === 0;
    names.forEach(function (name) {
      const add = el('button', 'btn ghost sm', name);
      add.type = 'button';
      add.title = '把内置提供方 ' + name + ' 加到你的列表里';
      add.addEventListener('click', function () { addBuiltInProvider(name, add); });
      dom.cfgBuiltIns.appendChild(add);
    });
  }

  /* 把一个内置提供方加回列表：与选择器删除它用的是同一个请求，方向相反。用 PUT，
   * 因为被修改的是列表，而不是在创建一份定义。 */
  async function addBuiltInProvider(name, button) {
    providerNote('');
    clearSettingsErrors();
    button.disabled = true;
    try {
      const res = await request('/api/providers', {
        method: 'PUT',
        body: JSON.stringify({ name: name })
      });
      acceptCatalogue(res, '提供方 ' + name + ' 已添加。');
    } catch (err) {
      providerNote(err && err.status === 404
        ? providersUnsupported('添加内置提供方', 'PUT')
        : '无法添加 ' + name + '：' + str(err && err.message));
    } finally {
      button.disabled = false;
    }
  }

  /* 内置提供方是代码，所以这个列表里永远只有用户自己的定义——一个都没有时它会明说，
   * 而不是留一个要用户自己猜含义的空框。 */
  function renderProviderSection() {
    renderBuiltInChoices();
    dom.cfgProviderList.textContent = '';
    if (state.catalogError) {
      dom.cfgProviderList.appendChild(el('li', 'muted',
        '无法列出提供方：' + state.catalogError));
      return;
    }
    const custom = customProviders();
    if (!custom.length) {
      dom.cfgProviderList.appendChild(el('li', 'muted provider-empty', '还没有自定义提供方。'));
      return;
    }
    custom.forEach(function (info) { dom.cfgProviderList.appendChild(providerRow(info)); });
  }

  function setProviderFormOpen(open) {
    dom.cfgProviderForm.hidden = !open;
    dom.cfgProviderAddToggle.setAttribute('aria-expanded', open ? 'true' : 'false');
  }

  /* 选择器里的「添加提供方…」不是第二个表单：它打开「设置」中「你的提供方」下的那一个，
   * 并把光标放进名称字段，这个手势需要的就这些。面板先关上——两个界面同时问同一个
   * 提供方会争抢这个答案。 */
  async function openAddProviderForm() {
    pickers.forEach(function (p) { p.closePanel(false); });
    if (dom.settingsOverlay.hidden) { await openSettings(); }
    setProviderFormOpen(true);
    dom.cfgNewName.focus();
    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    dom.cfgProviderForm.scrollIntoView({ block: 'nearest', behavior: reduce ? 'auto' : 'smooth' });
  }

  /* 两个提供方端点都用整个目录作答，所以树、选择器和模型计数全都来自服务器的答复 ——
   * 永远不用表单自己对刚刚发送了什么的看法。 */
  function acceptCatalogue(payload, note) {
    if (payload && typeof payload === 'object' && Array.isArray(payload.providers)) {
      setCatalog(payload);
    }
    refreshPickers();
    renderProviderSection();
    providerNote(note);
  }

  /* POST 和 DELETE /api/models 同样用整个目录作答，所以记住或忘记一个模型之后面板显示的
   * 是服务器的列表，而不是本地猜测。通知区属于提供方表单，所以这里不去动它：这两者通过
   * 自己的通知说明发生了什么。 */
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

  /* 每个拒绝一条 400 消息，它属于哪个字段在这里决定：名字的问题落在「名称」下面，
   * 协议的问题落在「协议」下面，端点的问题落在「接口地址」下面——与工作区表单同一条
   * 规则。 */
  function showProviderFormError(err) {
    const message = str(err && err.message) || '无法添加该提供方。';
    if (err && err.status === 404) {
      providerNote(providersUnsupported('添加提供方', 'POST'));
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
      fieldError(dom.cfgNewNameError, '为该提供方输入一个名称。');
      dom.cfgNewName.focus();
      return;
    }
    if (!baseUrl) {
      fieldError(dom.cfgNewBaseUrlError, '输入该提供方所用的接口地址。');
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
      acceptCatalogue(res, '提供方 ' + name + ' 已保存——在上面选中它并按「保存」使用。');
      // 定义选择器已经选中的那个提供方，会顺带定下它的凭据字段。
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
      // 答复就是新的目录；/api/config 的名字列表更旧，所以被移除的名字在这里就被丢掉，
      // 而不是继续提供到下次打开为止。
      const gone = str(name).trim().toLowerCase();
      state.configProviders = state.configProviders.filter(function (entry) {
        return str(entry).trim().toLowerCase() !== gone;
      });
      acceptCatalogue(res, '提供方 ' + name + ' 已移除。');
    } catch (err) {
      control.reset();
      providerNote(err && err.status === 404
        ? providersUnsupported('移除提供方', 'DELETE')
        : '无法移除 ' + name + '：' + str(err && err.message));
    }
  }

  function renderSettings(cfg) {
    // 服务器称可用的那些名字。当前这个即使这个版本不再列出也留在下拉里，而字段保留它
    // 手上的内容：提供方名字是配置，不是一个封闭集合。
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

    // 为另一个提供方输入过的端点不是这一个的：自定义提供方用自己定义里的那个，所以显示
    // 存下来的值等于提议用这个提供方的名字保存错误的地址。目录已经知道这个提供方自己的
    // 端点是什么。
    const info = providerInfo(current);
    const storedIsOurs = cfg.usesStoredSettings !== false;
    dom.cfgBaseUrl.value = storedIsOurs ? str(cfg.baseUrl) : str(info && info.baseUrl);
    dom.cfgApiKeyEnv.value =
      (storedIsOurs ? str(cfg.apiKeyEnv) : str(info && info.apiKeyEnv)) || 'OPENAI_API_KEY';
    dom.cfgTemperature.value =
      cfg.temperature === null || cfg.temperature === undefined ? '' : str(cfg.temperature);

    const source = str(cfg.apiKeySource);
    dom.cfgApiKey.value = '';
    dom.cfgApiKey.disabled = false;
    // 这个密钥字段是针对哪个提供方的，以及哪些提供方已保存密钥——按提供方区分，所以切到
    // 一个以前用过的提供方时显示的是「已保存」，而不是空。
    state.keyProvider = current;
    state.rememberedProviders = Array.isArray(cfg.rememberedProviders)
      ? cfg.rememberedProviders.map(str).filter(Boolean)
      : [];
    dom.cfgApiKey.placeholder = KEY_PLACEHOLDER[source] || KEY_PLACEHOLDER.none;
    if (source === 'env') {
      dom.cfgApiKeyHint.textContent =
        '正在使用环境里的 ' + (str(cfg.apiKeyEnv) || '环境变量');
      dom.cfgApiKeyHint.hidden = false;
    } else if (source === 'config') {
      dom.cfgApiKeyHint.textContent = '配置文件里保存着一个密钥。留空即可保留它。';
      dom.cfgApiKeyHint.hidden = false;
    } else {
      dom.cfgApiKeyHint.textContent = '';
      dom.cfgApiKeyHint.hidden = true;
    }

    dom.cfgClearKey.checked = false;
    dom.cfgClearKeyWrap.hidden = source !== 'config';

    renderVisionSettings(cfg);

    dom.cfgApiKeyEnvHint.textContent = source === 'env' ? '密钥从这个变量读取' : '';
    dom.cfgApiKeyEnvHint.hidden = source !== 'env';

    dom.settingsFoot.textContent = str(cfg.configFile)
      ? '设置保存在 ' + str(cfg.configFile)
      : '服务器没有报告配置文件路径。';

    clearSettingsErrors();
    clearProviderFormErrors();
    providerNote('');
    setProviderFormOpen(false);
  }

  /* 描述图片的模型，它与上面的提供方分开配置：有自己的端点、密钥和预算。密钥遵循这个页面
   * 上所有密钥的同一条规则——服务器只说有没有、来自哪里，永远不说它是什么，所以字段永远
   * 是空的，而空表示「保留现有的」。 */
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
      dom.cfgVisionApiKeyHint.textContent = '配置文件里保存着视觉模型密钥。留空即可保留它。';
      dom.cfgVisionApiKeyHint.hidden = false;
    } else if (source === 'env') {
      dom.cfgVisionApiKeyHint.textContent = '正在使用环境里的 ' + (str(vision.apiKeyEnv) || '环境变量');
      dom.cfgVisionApiKeyHint.hidden = false;
    } else {
      dom.cfgVisionApiKeyHint.textContent = '';
      dom.cfgVisionApiKeyHint.hidden = true;
    }
    // 图片按钮实际会做什么，这跟「配置块填了东西」不是一回事：这个功能需要端点、模型和
    // 密钥，而一个把填了一半的配置块称为「已开启」的表单，会承诺一次随后被拒绝的描述。
    if (vision.on) {
      dom.cfgVisionState.textContent = '已开启 — ' + str(vision.baseUrl) + ' · ' + str(vision.model);
    } else if (vision.configured) {
      dom.cfgVisionState.textContent = '不完整——缺少密钥，所以图片会被拒绝';
    } else {
      dom.cfgVisionState.textContent = '已关闭——图片会被拒绝并说明该设置什么';
    }
  }

  /* 只发送这个表单管理的字段；字段为空时省略 apiKey，这样保存下来的密钥能挺过一次
   * 无关的改动。 */
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
    const temperature = numField(dom.cfgTemperature);
    if (temperature !== undefined) { payload.temperature = temperature; }

    // 图片。空表示不变，和这里其他所有文本字段一样，所以那两件不是值的事——忘记密钥、
    // 关闭功能——就以它们本来的标志位发送。
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
    if (status.provider) { bits.push('提供方 ' + str(status.provider)); }
    if (status.model) { bits.push('模型 ' + str(status.model)); }
    return bits.length ? prefix + ' — ' + bits.join('，') : prefix;
  }

  function showSaveError(err) {
    const message = str(err && err.message) || '保存失败。';
    const status = err && err.status;
    // 服务器的消息现在是中文，所以每条规则同时匹配中文措辞和仍然保留的 ASCII 关键字。
    if (/vision|picture|视觉|图片/i.test(message)) {
      // 故意排在密钥分支之前：「视觉密钥缺失」说的是图片字段，而下面那条通用 apiKey
      // 规则会匹配它，然后怪到提供方的密钥头上。
      fieldError(dom.cfgVisionError, message);
    } else if (status === 401 || status === 403
      || /api[- ]?key|unauthor|forbidden|invalid key|401|403|密钥/i.test(message)) {
      fieldError(dom.cfgApiKeyError, message);
    } else if (/model|模型/i.test(message)) {
      // 「no model configured」也会点名提供方，所以模型分支优先。
      fieldError(dom.cfgModelError, message);
    } else if (/provider|提供方/i.test(message)) {
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
      if (res && typeof res === 'object') { applyStatus(res); }   // 先更新小标签，status 事件稍后到
      appendNotice(configSummary(res, '设置已保存'));
      // 一次保存可能改变哪个提供方是活动的，而列表会标出这一点。
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
    dom.settingsTest.textContent = '测试中…';
    try {
      const res = await postJSON('/api/config/test', settingsPayload());
      const reply = clip(firstLine(str(res && res.reply)), 200) || '（空回复）';
      const elapsed = msLabel(res && res.elapsedMs);
      showTestResult('正常——回复：“' + reply + '”' + (elapsed ? '（' + elapsed + '）' : ''), 'ok');
    } catch (err) {
      showTestResult(str(err && err.message) || '测试失败。', 'bad');
    }
    dom.settingsTest.textContent = '测试连接';
    dom.settingsTest.disabled = false;
  }

  function focusSettingsForm() { settingsPicker.focus(); }

  function closeSettings() {
    if (dom.settingsOverlay.hidden) { return; }
    if (settingsPicker) { settingsPicker.closePanel(false); }
    dom.settingsOverlay.hidden = true;
    // 这个面板是从按钮打开的（或者在首次运行时自动打开）；无论哪种，关掉它时焦点都
    // 应当回到那个按钮上。
    dom.btnSettings.focus();
  }

  /* 调用方手上已经有 GET /api/config 时，`preloaded` 跳过那次往返。 */
  async function openSettings(preloaded) {
    dom.settingsOverlay.hidden = false;

    // 每次打开都会抓取目录，与配置并行：面板提供的是这个服务器*现在*有的东西，
    // 而不是上次有的。
    const catalog = refreshCatalog();

    let cfg = preloaded && typeof preloaded === 'object' ? preloaded : null;
    if (!cfg) {
      dom.settingsError.textContent = '正在加载配置…';
      dom.settingsError.hidden = false;
      try {
        cfg = await request('/api/config');
      } catch (err) {
        dom.settingsError.textContent = '无法加载设置：' + str(err && err.message);
        dom.settingsError.hidden = false;
        return;
      }
    }
    await catalog;   // renderSettings 依据目录给提供方列表加标签
    renderSettings(cfg && typeof cfg === 'object' ? cfg : {});
    focusSettingsForm();
  }

  /* 首次运行时还没有模型，而在选定一个之前页面做不了任何有用的事，所以面板会自己打开。
   * init() 已经读过 GET /api/config 时，`preloaded` 省掉那次往返。 */
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

  /* 服务器称可用的提供方名字，启动时读一次，这样输入框的选择器在「设置」被打开之前
   * 就能提供这些提供方。 */
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

  // ---------------------------------------------------------------- 输入框

  async function sendMessage() {
    // 不再检查忙不忙：回合运行期间发的消息，服务器会排队，所以页面照发并说明结果。
    // 在这里拒绝曾是服务器过去用 409 回答时页面这边的对应动作，而在一个长回合里
    // 打出来的想法值得留着。
    const text = dom.input.value.trim();
    // 一张待发送的图片本身就可以是一条消息，所以空正文在它存在时是可以的。
    if (!text && !state.picture) { return; }
    const picture = state.picture ? state.picture.name : '';
    dom.input.value = '';
    state.stick = true;
    scrollToBottom();
    // 在请求之前设置，而不是之后。一个很快的回合可能在这个响应到达之前就结束——而且
    // 它的 `done` 已经从事件流渲染过了——之后再设这个标记会把输入框卡在一个早已结束的
    // 回合上。流才是权威，无论哪种情况它都会纠正这里。
    try {
      const res = await postJSON('/api/message', { text: text, picture: picture });
      // 「已排队」表示那个回合还在跑，而这条消息是下一条。计数*不*在这里加：服务器排队时
      // 会发布一个 status，那个事件常常比这个响应先到，而本地也加一次，正是提示对一条消息
      // 说出「2 条排队中」的原因——这是在浏览器里实测到的，同一个事实的两个来源之间的
      // 竞态正是在那里显形。status 才是权威；这里只说输入框该看起来忙。
      if (res && res.queued) {
        setBusy(true);
      }
      /* 它跟着这句话走了，所以缩略条该消失了——而且要由本地清掉，不能等 status：图片
       * 已经不在了，而下一个 status 正是被这条消息触发的。 */
      if (picture) { setPendingPicture(null); }
    } catch (err) {
      // 409 留给它现在仍然表示的意思：队列满了，或者没有配置模型。两件都是用户必须处理的
      // 事，而两件都不是「有回合正在运行」。
      appendError(err.message);
      refreshStatus();
      dom.input.value = text;
      // 图片和正文一起退回来：一次被拒绝的发送不该花掉一次上传。
    }
    dom.input.focus();
  }

  dom.btnCompact.addEventListener('click', compactNow);

  /* 图片不作为消息体传输。服务器把它存在该会话自己的目录下，请视觉模型描述它，然后基于
   * 描述启动一个回合——所以跑起来的是一个普通文本上的普通回合，而存下来的是一段可读的
   * 文本，旁边是磁盘上的一个文件。
   *
   * 描述就是在这个请求里要到的，这就是这里不把输入框置为忙碌的原因：服务器自己的 status
   * 事件会说明回合什么时候真的在跑。一个连不上的视觉模型会带着理由拒绝这张图片，而不是让
   * 页面一直等一个从未开始的回合，而在此期间也没有任何东西发给主模型。
   */
  async function sendPhoto(file) {
    if (!file) { return; }
    state.stick = true;
    setPhotoHint('正在描述 ' + file.name + '…');
    try {
      const res = await request('/api/attachment?name=' + encodeURIComponent(file.name), {
        method: 'POST',
        headers: { 'Content-Type': file.type || 'application/octet-stream' },
        body: file
      });
      /* 描述回来了，而它要停在这里等你写下一句话。本地缩略图来自你刚交给我们的那个
       * File——服务器上的那份文件是它的副本，不是这个对象。 */
      setPendingPicture({
        name: res && res.attachment ? res.attachment.split(/[\\/]/).pop() : file.name,
        description: res ? str(res.description) : '',
        localUrl: URL.createObjectURL(file)
      });
      if (res && res.replaced) { appendNotice('这张图片取代了上一张还没发出去的图片'); }
      setPhotoHint('已描述，发送时会跟着你的话一起发出去');
      dom.input.focus();
    } catch (err) {
      appendError('图片被拒绝：' + err.message);
    } finally {
      refreshStatus();
    }
  }

  /* 待发送的图片只有一份，因为一次只有一张会跟着消息走。它是可见的、可移除的，而且
   * 服务器也这么认为——刷新页面后服务器会用 status 把同一条缩略条重新送回来。 */
  function setPendingPicture(picture) {
    const previous = state.picture;
    /* 同一个上传的两条路径会先后到达：这个请求的响应（它带着页面手上那个 File，所以有一张真的
     * 缩略图）和服务器随后发布的事件（它没有）。名字一样就是同一张图片，所以本地那张缩略图跟着
     * 一起留下，而不是被一条更晚、更无知的消息抹掉。 */
    if (picture && !picture.localUrl && previous && previous.localUrl &&
        previous.name === picture.name) {
      picture.localUrl = previous.localUrl;
      previous.localUrl = null;
    }
    if (previous && previous.localUrl) { URL.revokeObjectURL(previous.localUrl); }
    state.picture = picture || null;
    const current = state.picture;
    dom.photoPending.hidden = !current;
    dom.photoPendingThumb.hidden = !current;
    if (!current) {
      dom.photoPendingName.textContent = '';
      dom.photoPendingThumb.removeAttribute('src');
      setPhotoHint('');
      return;
    }
    // 缩略条是提醒，不是描述本身：描述有时是一整段（推理模型尤其如此），而它真正被读到的
    // 地方是它将要成为的那条消息。所以这里只留一个开头。
    const glimpse = firstLine(current.description);
    dom.photoPendingName.textContent =
      current.name + (glimpse ? ' · ' + clip(glimpse, 60) : '');
    if (current.localUrl) {
      dom.photoPendingThumb.src = current.localUrl;
    } else {
      dom.photoPendingThumb.removeAttribute('src');
      dom.photoPendingThumb.hidden = true;
    }
  }

  async function dropPendingPicture() {
    try {
      await request('/api/attachment', { method: 'DELETE' });
    } catch (err) {
      appendError('移除图片：' + err.message);
    }
    setPendingPicture(null);
    refreshStatus();
  }

  function openPhotoMenu(open) {
    dom.photoMenu.hidden = !open;
    dom.btnPhoto.setAttribute('aria-expanded', open ? 'true' : 'false');
  }

  function setPhotoHint(text) {
    dom.photoHint.textContent = text;
    dom.photoHint.hidden = !text;
  }

  dom.btnPhoto.addEventListener('click', function (event) {
    event.stopPropagation();
    openPhotoMenu(dom.photoMenu.hidden);
  });
  dom.btnPhotoCamera.addEventListener('click', function () { openPhotoMenu(false); dom.photoCamera.click(); });
  dom.btnPhotoAlbum.addEventListener('click', function () { openPhotoMenu(false); dom.photoAlbum.click(); });
  dom.btnPhotoDrop.addEventListener('click', dropPendingPicture);
  /* 点别处、按 Esc 就关掉。菜单留在一个没有焦点的按钮下面，是那种「点了没反应」的
   * 来源。 */
  document.addEventListener('click', function (event) {
    if (!dom.photoMenu.hidden && !dom.photoMenu.contains(event.target)) { openPhotoMenu(false); }
  });
  document.addEventListener('keydown', function (event) {
    if (event.key === 'Escape' && !dom.photoMenu.hidden) { openPhotoMenu(false); }
  });

  /* 把上一回合改动过的东西放回去。服务器用恢复的内容作答并发布一条通知，转录里的说明就在
   * 那里——这一侧只报告拒绝，因为拒绝正是没有别的东西会报告的那种情况。 */
  async function undoLastTurn() {
    try {
      const res = await postJSON('/api/undo', {});
      if (res && res.restored === 0) { appendNotice('没有可退回的改动'); }
    } catch (err) {
      appendError('退回：' + err.message);
    }
  }

  dom.btnUndo.addEventListener('click', undoLastTurn);

  dom.photoCamera.addEventListener('change', function () {
    const file = dom.photoCamera.files && dom.photoCamera.files[0];
    /* 在上传之前清掉，所以连续两次选同一张图片是两次上传，而不是一次上传加一次
     * 没反应。 */
    dom.photoCamera.value = '';
    sendPhoto(file);
  });

  dom.photoAlbum.addEventListener('change', function () {
    const file = dom.photoAlbum.files && dom.photoAlbum.files[0];
    dom.photoAlbum.value = '';
    sendPhoto(file);
  });

  // ------------------------------------------------- 图片的入口：按钮、粘贴、拖放
  /* 桌面上绝大多数图片来自剪贴板：截图之后 Ctrl+V，而不是「存成文件、再去选它」。粘贴和拖放因此
   * 走的是和选文件完全相同的那条路（sendPhoto），不是第二条——一条图片路径意味着一个行为，而两
   * 条路径迟早会分叉。 */
  document.addEventListener('paste', function (event) {
    const file = imageFrom(event.clipboardData);
    if (!file) {
      return; // 文本粘贴照旧落进输入框，这里什么都不做。
    }
    event.preventDefault();
    sendPhoto(file);
  });

  /* 拖动期间的高亮：拖过窗口时不说，用户没法知道这里能不能放。 */
  let dragDepth = 0;
  document.addEventListener('dragenter', function (event) {
    if (!hasFiles(event.dataTransfer)) {
      return;
    }
    event.preventDefault();
    dragDepth += 1;
    dom.composerCard.classList.add('drop-target');
  });
  document.addEventListener('dragover', function (event) {
    if (hasFiles(event.dataTransfer)) {
      event.preventDefault(); // 不阻止默认行为，浏览器会直接打开那个文件。
    }
  });
  document.addEventListener('dragleave', function () {
    dragDepth = Math.max(0, dragDepth - 1);
    if (dragDepth === 0) {
      dom.composerCard.classList.remove('drop-target');
    }
  });
  document.addEventListener('drop', function (event) {
    if (!hasFiles(event.dataTransfer)) {
      return;
    }
    event.preventDefault();
    dragDepth = 0;
    dom.composerCard.classList.remove('drop-target');
    const file = imageFrom(event.dataTransfer);
    if (file) {
      sendPhoto(file);
    } else {
      appendNotice('只能拖入图片：把图片存下来再拖，或者按「图片」按钮选一张');
    }
  });

  /** 这次剪贴板／拖放里要上传的那张图片，或者 null。 */
  function imageFrom(dataTransfer) {
    if (!dataTransfer) {
      return null;
    }
    const files = dataTransfer.files;
    if (files && files.length) {
      for (let i = 0; i < files.length; i++) {
        if (String(files[i].type || '').startsWith('image/')) {
          return files[i];
        }
      }
      return null;
    }
    // 粘贴时文件常常只以 item 的形式出现，而不在 files 里。
    const items = dataTransfer.items;
    if (!items) {
      return null;
    }
    for (let i = 0; i < items.length; i++) {
      const item = items[i];
      if (item.kind === 'file' && String(item.type || '').startsWith('image/')) {
        return item.getAsFile();
      }
    }
    return null;
  }

  /** 这次拖放里有没有文件，用来和拖一段选中的文本区分开。 */
  function hasFiles(dataTransfer) {
    return !!dataTransfer && Array.prototype.indexOf.call(dataTransfer.types || [], 'Files') >= 0;
  }
  // --------------------------------------------- 图片的入口结束

  dom.composer.addEventListener('submit', function (event) {
    event.preventDefault();
    sendMessage();
  });

  /* 后台标签页的计时器会被节流，所以一条在页面被隐藏时死掉的流可能一直没被重试过。
   * 回到前台是唯一能廉价察觉到的时刻，而那也正是用户即将再次打字的时刻。 */
  document.addEventListener('visibilitychange', function () {
    if (document.visibilityState === 'visible') { resync(); }
  });

  window.addEventListener('focus', function () {
    if (streamLooksStale()) { resync(); }
  });

  dom.input.addEventListener('keydown', function (event) {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
      event.preventDefault();
      sendMessage();
    }
  });

  dom.btnAbort.addEventListener('click', async function () {
    dom.btnAbort.disabled = true;
    try {
      await postJSON('/api/abort', {});
    } catch (err) {
      appendError('停止失败：' + err.message);
      dom.btnAbort.disabled = !state.busy;
    }
  });

  /* 新建会话是留在头部里的唯一一个会话动作；它发生在页面已经在的那个工作区里，所以树
   * 没有什么要切换的。焦点交给输入框，因为新建一个会话就是为了用它。 */
  dom.btnNew.addEventListener('click', function () {
    switchSession({ action: 'new' }, null).then(function () { dom.input.focus(); });
  });

  /* 侧边栏带着和那些行一样的两步确认控件，所以「全部删除」同样永远不会是一次点击。 */
  const deleteAllControl = deleteControl(dom.wsDeleteAllHost, '全部删除',
    deleteAllSelectedWorkspace);
  deleteAllControl.idle.dataset.focusKey = 'delall';
  deleteAllControl.idle.title = '删除所选工作区里的每一个会话';

  /* 「添加工作区」是选择器，不是表单：点击打开桌面自带的对话框，而工作区的名字就是这个
   * 文件夹的名字。在选择器跑不起来的机器上，这个触发器改为打开表单，这也是它同时是一个
   * 切换钮的原因——两个手势都离侧边栏只有一次点击。 */
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
  /* 「浏览」是单独使用的同一个选择器：它填充字段而不是直接添加，因为打出来的路径
   * 在发送之前还要编辑。 */
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

  /* Esc 关闭打开的东西，从最内层开始：打开的选择器，然后是设置对话框，然后是「添加
   * 工作区」表单，然后是一个已武装的删除。侧边栏本身是一个区域，不是弹出层——它故意
   * 只用自己的切换钮关。 */  function cancelArmedDeletes() {
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

  /* 点击落在选择器外面时它就会关上。触发器自己负责切换，所以点它在根节点内，永远不会
   * 触发两次。这项检查跑在捕获阶段，因为选中一个选项会重渲染列表，而等到事件冒泡时被点的
   * 那个节点已经游离了——那看起来会和点在外部一模一样。 */
  document.addEventListener('click', function (event) {
    pickers.forEach(function (picker) {
      if (picker.isOpen() && !picker.contains(event.target)) { picker.closePanel(false); }
    });
  }, true);

  dom.btnAuto.addEventListener('click', async function () {
    const next = !autoApproveOn();
    dom.btnAuto.disabled = true;
    // 乐观地先画；响应和随后的 status 事件会把它对齐。
    state.autoApprove = next;
    paintAuto();
    try {
      const res = await postJSON('/api/auto-approve', { enabled: next });
      if (res && typeof res.autoApprove === 'boolean') { state.autoApprove = res.autoApprove; }
    } catch (err) {
      state.autoApprove = !next;
      appendError('自动批准失败：' + err.message);
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
      // 一次只有一个抽贴面板：侧边栏是另一个，把它留着会让这个面板藏在另一个后面。
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
      // 回到列布局：抽贴面板是手机的布局，而在这个布局里两个面板有自己的状态
      // （折叠或未折叠）。
      dom.sidebar.classList.remove('open');
      dom.side.classList.remove('open');
    }
    paintScrim();
  });

  // ---------------------------------------------------------------- 启动

  async function init() {
    initTheme();
    initWallpaper();
    initSidebar();
    initPickers();
    paintAuto();
    setBusy(false);
    // 第一个 status 告诉我们页面在显示哪个会话；它的历史在流打开之前就渲染好，
    // 所以最初那次重放没有实时事件跟它抢。树同时加载，展开哪些节点按存下来的
    // 展开状态来。
    await refreshStatus();
    loadWorkspaces();
    if (state.historyPromise) { await state.historyPromise; }
    connect();
    // 输入框上方的选择器必须在「设置」被打开之前就能提供目录，所以两者在启动时
    // 各读一次。
    const cfg = await readSettingsConfig();
    await refreshCatalog();
    const settingsOpen = await maybeOpenSettingsOnFirstLoad(cfg);
    if (!settingsOpen) { dom.input.focus(); }
  }

  init();
})();
