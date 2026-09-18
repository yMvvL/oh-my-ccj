/* 未决审批，不靠浏览器运行。
 *
 * 这段行为在 web/app.js 里，那是一个没有导出的经典脚本，所以拥有它的两个函数是从
 * 随包发布的文件里抽出来、跑在一个小小的 node 桩上的。因此这些断言针对的是真正发布的
 * 代码，而不是它的副本 —— markdown、session-row 和 add-workspace 这些用例用的是同一个
 * 手法。
 *
 * 它钉住的是这段代码存在的那个 bug：一个等待审批的回合，过去只要用户去看另一个对话就会
 * 丢掉提示，只留下中止作为唯一出路。审批是阻塞在服务器内存里的一个请求，不是消息，所以
 * 重放一段对话带不回它 —— 服务器报告什么仍未决，页面再把它画一遍。这些用例讲的就是那次
 * 重画，以及它可能出错的两种方式：给错对话的提示，和一张永远回答不了的陈旧提示。
 *
 * `node src/test/js/approval.test.mjs` —— 装了 node 时也会由
 * com.ccj.agent.web.WebApprovalTest 运行，所以 `mvn test` 也覆盖它。 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const APP = join(here, '..', '..', 'main', 'resources', 'web', 'app.js');
const src = readFileSync(APP, 'utf8');

new Function(src);   // 没有构建步骤的文件，只能这样 lint

const startMark = '  function renderApproval(';
const endMark = '  function onApprovalClosed(';
const from = src.indexOf(startMark);
const to = src.indexOf(endMark);
if (from < 0 || to < 0 || to < from) {
  throw new Error('could not locate renderApproval( .. onApprovalClosed(');
}
// `approvalRecord` 是两个函数都要穿过的那个单行函数，它就住在两者上方；
// 这里从发布的文件里取，而不是在这里重述。
const recordStart = src.indexOf('  function approvalRecord(');
const recordEnd = src.indexOf('\n', recordStart);
const code = src.slice(recordStart, recordEnd + 1) + '\n' + src.slice(from, to);

// ------------------------------------------------------------------ node 桩

let failures = [];
let pass = 0;
function eq(name, actual, expected) {
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  if (a === e) { pass++; } else { failures.push(name + ': expected ' + e + ', got ' + a); }
}
function ok(name, cond, detail) {
  if (cond) { pass++; } else { failures.push(name + (detail ? ': ' + detail : '')); }
}

function node(tag, cls, text) {
  const n = {
    tag: tag, className: cls || '', textContent: text === undefined ? '' : String(text),
    children: [], listeners: [], attrs: {}, disabled: false, checked: false, type: '',
    classList: {
      _own: new Set((cls || '').split(' ').filter(Boolean)),
      add(c) { this._own.add(c); },
      remove(c) { this._own.delete(c); },
      contains(c) { return this._own.has(c); },
      toggle(c, on) { if (on) { this._own.add(c); } else { this._own.delete(c); } },
    },
    setAttribute(k, v) { this.attrs[k] = v; },
    appendChild(c) { this.children.push(c); return c; },
    addEventListener(t, fn) { this.listeners.push({ type: t, fn: fn }); },
    fire(t) { this.listeners.filter((l) => l.type === t).forEach((l) => l.fn({})); },
    querySelector(cls) {
      const want = cls.replace(/^\./, '');
      const walk = (n2) => {
        for (const c of (n2.children || [])) {
          if (c.classList && c.classList.contains(want)) { return c; }
          const deeper = walk(c);
          if (deeper) { return deeper; }
        }
        return null;
      };
      return walk(this);
    },
    querySelectorAll(cls) {
      const want = cls.replace(/^\./, '');
      const out = [];
      const walk = (n2) => {
        for (const c of (n2.children || [])) {
          if (c.classList && c.classList.contains(want)) { out.push(c); }
          walk(c);
        }
      };
      walk(this);
      return out;
    },
  };
  if (cls) { n.className = cls; }
  return n;
}
const el = (tag, cls, text) => node(tag, cls, text);

// renderApproval 和 syncApprovals 从外部会碰到的一切。
const state = { approvals: new Map(), busy: false };
const appended = [];
const transcript = node('div', 'transcript');
const answered = [];
const api = new Function(
  'el', 'str', 'state', 'appendToTranscript', 'refreshLive', 'updateJump', 'setBusy',
  'autoApproveOn', 'answerApproval',
  code + '\nreturn {renderApproval, syncApprovals};')(
  el,
  (v) => (v === undefined || v === null ? '' : String(v)),
  state,
  (n) => { appended.push(n); transcript.appendChild(n); },
  () => {},
  () => {},
  () => {},
  () => false,
  (id, choice) => { answered.push({ id: id, choice: choice }); });

function reset() {
  state.approvals.clear();
  appended.length = 0;
  transcript.children.length = 0;
  answered.length = 0;
}

function titles() {
  return appended.map((n) => {
    const head = n.querySelector('.approval-head');
    const t = head && head.querySelector('.approval-title');
    return t ? t.textContent : '';
  });
}

// 1. 来自服务器的一个请求被画出来，只画一次。
{
  reset();
  api.syncApprovals([{ id: 'ap-1', title: 'bash', detail: 'echo hi' }]);
  eq('一个提示被画出来', appended.length, 1);
  eq('带着它的标题', titles(), ['bash']);
  eq('而且它是未决的', appended[0].classList.contains('pending'), true);
  eq('而且被登记，所以能回答', state.approvals.has('ap-1'), true);

  // 同一个请求再来一次不能产生第二个提示：页面会反复要 status，而重复的卡片会把同一个
  // 问题问两遍。
  api.syncApprovals([{ id: 'ap-1', title: 'bash', detail: 'echo hi' }]);
  eq('同一个请求不会被画两次', appended.length, 1);
}

// 2. 回答一个请求会送出答案并关闭提示。
{
  reset();
  api.syncApprovals([{ id: 'ap-1', title: 'bash', detail: 'echo hi' }]);
  // 主按钮是「只允许这一次」；它旁边的危险色按钮是「拒绝」。
  const actions = appended[0].querySelector('.approval-actions');
  const approve = actions.children.filter((c) => c.classList.contains('primary'))[0];
  approve.fire('click');
  eq('答案送到服务器', answered, [{ id: 'ap-1', choice: 'once' }]);
  eq('而且提示被关闭', appended[0].classList.contains('pending'), false);
  eq('而且它被标为已批准', appended[0].classList.contains('approved'), true);
}

// 2b. 四个答案是四个不同的答案，而且每一个都说出自己的意思。
{
  reset();
  api.syncApprovals([{ id: 'ap-9', title: 'bash', detail: 'mvn test' }]);
  const byText = (label) => appended[0].querySelector('.approval-actions').children
    .filter((c) => c.textContent === label)[0];
  byText('本会话都允许').fire('click');
  eq('本会话允许原样送出', answered, [{ id: 'ap-9', choice: 'session' }]);
  const state = appended[0].querySelector('.approval-state');
  eq('而且卡片这么说了', state.textContent, '本会话内已允许');

  reset();
  api.syncApprovals([{ id: 'ap-10', title: 'write', detail: 'src/Foo.java' }]);
  appended[0].querySelector('.approval-actions').children
    .filter((c) => c.textContent === '始终允许')[0].fire('click');
  eq('始终允许原样送出', answered, [{ id: 'ap-10', choice: 'always' }]);

  reset();
  api.syncApprovals([{ id: 'ap-11', title: 'bash', detail: 'rm -rf /' }]);
  appended[0].querySelector('.approval-actions').children
    .filter((c) => c.classList.contains('danger'))[0].fire('click');
  eq('拒绝原样送出为拒绝', answered, [{ id: 'ap-11', choice: 'deny' }]);
  eq('而且卡片被标为已拒绝', appended[0].classList.contains('denied'), true);
}

// 3. 服务器已经不知道的请求会被结掉，而不是挂着。
// 这就是「在另一个标签页里被应答了，或者超时了」的情形：一张永远回答不了的卡片，
// 比一张说明自己已经结束的卡片更糟。
{
  reset();
  api.syncApprovals([{ id: 'ap-1', title: 'bash', detail: '' }]);
  api.syncApprovals([]);
  eq('陈旧的提示被关闭', appended[0].classList.contains('pending'), false);
  eq('而且说明它已不再等待',
    appended[0].querySelector('.approval-state').textContent.indexOf('已不再等待') >= 0, true);
}

// 4. 别的对话的请求永远不会画在这里。屏幕上这个对话的 status 只带自己的请求，所以
// 空列表意味着这个页面必须什么都不显示 —— 这正是阻止一个对话的问题出现在另一个对话的
// 转录里的东西。
{
  reset();
  api.syncApprovals([]);
  eq('没有请求的对话什么都不画', appended.length, 0);

  api.syncApprovals([{ id: 'ap-9', title: 'write', detail: 'x' }]);
  eq('而之后给它的请求会被画出来', titles(), ['write']);
}

// 5. 同时来的多个请求都会被画出来：一个回合可以请求两个工具。
{
  reset();
  api.syncApprovals([
    { id: 'ap-1', title: 'bash', detail: 'a' },
    { id: 'ap-2', title: 'write', detail: 'b' },
  ]);
  eq('两个提示都被画出来', titles(), ['bash', 'write']);
  eq('而且两个都能回答', state.approvals.size, 2);
}

// 6. 缺失或畸形的载荷什么都不改，而不是抛出：这段代码从 status 处理器里跑，那里崩一下
// 会把整个页面带下去。
{
  reset();
  api.syncApprovals(undefined);
  api.syncApprovals(null);
  api.syncApprovals('nonsense');
  api.syncApprovals([{ title: 'no id' }]);
  eq('没有 id 时什么都不画', appended.length, 0);
}

if (failures.length) {
  console.error(failures.length + ' failed, ' + pass + ' passed\n');
  for (const f of failures) { console.error('  ✗ ' + f); }
  process.exit(1);
}
console.log('approval: ' + pass + ' assertions passed');
