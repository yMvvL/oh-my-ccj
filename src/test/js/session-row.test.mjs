/* 侧边栏的会话行，不靠浏览器运行。
 *
 * 这一行是由 web/app.js 里的 `sessionRow` 构建的，那是一个没有导出的经典脚本，所以这个
 * 函数自己的源码是从发布的文件里抽出来、跑在一个约 20 行的 node 桩上的。因此这些断言针对
 * 的是随包发布的代码，而不是它的副本 —— markdown 那些用例用的是同一个手法。
 *
 * `node src/test/js/session-row.test.mjs` —— 装了 node 时也会由
 * com.ccj.agent.web.WebSessionRowTest 运行，所以 `mvn test` 也覆盖它。 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const APP = join(here, '..', '..', 'main', 'resources', 'web', 'app.js');
const src = readFileSync(APP, 'utf8');

// 没有构建步骤的经典脚本：整体解析一遍是它唯一能得到的 lint，而在一个改动几乎总是很小的
// 文件里，这也是抓多余花括号最便宜的办法。
new Function(src);

function slice(startMark, endMark) {
  const from = src.indexOf(startMark);
  const to = src.indexOf(endMark);
  if (from < 0 || to < 0 || to < from) {
    throw new Error('could not locate ' + startMark + ' .. ' + endMark);
  }
  return src.slice(from, to);
}

// 这一行读的那些辅助函数（str、firstLine、clip、timeLabel、idStamp）住在 transport 之上；
// 行本身，以及它取时间戳用的那趟重复标题处理，住在侧边栏的树那一节里。
const code = slice('function str(', '// ------------------------------------------------------------- transport')
  + slice('function sessionRow(', '/* A workspace is a folder')
  + slice('function treeScroll(', '/* The server owns the list');

// ------------------------------------------------------------------ node 桩

function node(tag, cls, text) {
  return {
    tag: tag,
    cls: cls || '',
    dataset: {},
    kids: [],
    listeners: [],
    setAttribute() {},
    addEventListener(type, fn) { this.listeners.push({ type: type, fn: fn }); },
    appendChild(child) { this.kids.push(child); return child; },
    /* 一次正好落在这个节点上的点击，按浏览器报告它的方式：只有当里面没有任何东西已经
     * 处理过它时，target 才是那个 li。 */
    fire(type, event) { this.listeners.filter((l) => l.type === type).forEach((l) => l.fn(event || {})); }
  };
}

const el = (tag, cls, text) => {
  const n = node(tag, cls);
  if (text !== undefined && text !== null) { n.kids.push({ text: String(text) }); }
  return n;
};

const tree = { deletes: [] };
const deleteControl = () => ({ idle: { dataset: {} }, yes: { dataset: {} } });
let opened = [];
const openWorkspaceSession = (name, id) => { opened.push(name + '/' + id); };
// `window` 是浏览器的全局对象；这一行读它只是为了检查有没有选中内容。
let selectionText = '';
globalThis.window = { getSelection: () => ({ toString: () => selectionText }) };

/* 停止一个后台回合：这一行的停止按钮会调用它，而测试只需要看到它被问的是正确的
 * 会话。 */
let stopped = [];
const stopSession = (id) => { stopped.push(id); };

const api = new Function('el', 'str', 'tree', 'deleteControl', 'openWorkspaceSession', 'stopSession', 'dom',
  code + '\nreturn {sessionRow, repeatedStamps, idStamp, treeScroll};')(
  el, (v) => (v === undefined || v === null ? '' : String(v)), tree, deleteControl,
  openWorkspaceSession, stopSession, { wsTree: { parentNode: null } });

// ------------------------------------------------------------------ 节点查询

function flat(n) {
  if (n.text !== undefined) { return n.text; }
  return n.kids.map(flat).join('');
}

function child(node_, cls) {
  return node_.kids.filter((k) => k.cls === cls)[0] || null;
}

function has(node_, cls) { return child(node_, cls) !== null; }

/* sessionRow 返回 <li>；可点的内容是它的按钮。 */
function row(item, activeId, stamp) {
  const li = api.sessionRow('ws', item, 0, activeId || '', stamp);
  return { li: li, btn: child(li, 'session-item'), name: child(child(li, 'session-item'), 'session-name') };
}

function items(list) { return list.map((it, i) => Object.assign({ messageCount: 1, lastModified: '2026-09-13T00:00:00+08:00' }, it, { index: i })); }

/* 这一行自己的点击处理器，按浏览器的方式从 li 上取下来：事件的目标决定是这一行打开，
 * 还是它里面的某个控件已经应答了。 */
function clickRow(li, options) {
  const opts = options || {};
  opened = [];
  const event = Object.assign({
    target: opts.target === undefined ? li : opts.target,
    button: 0,
    defaultPrevented: false
  }, opts.event || {});
  li.fire('click', event);
  return opened.length;
}

// -------------------------------------------------------------------- 用例

let pass = 0;
const failures = [];
function eq(name, actual, expected) {
  if (actual === expected) { pass += 1; return; }
  failures.push(name + '\n    expected: ' + JSON.stringify(expected) + '\n    actual:   ' + JSON.stringify(actual));
}
function ok(name, cond, detail) {
  if (cond) { pass += 1; return; }
  failures.push(name + (detail ? '\n    ' + detail : ''));
}

// 1. 标签是第一条用户消息；时间戳 id 不在屏幕上。
{
  const r = row({ id: '20260913-001746-3377', title: 'rename the sidebar', preview: 'rename the sidebar' });
  eq('这一行由第一个请求来标记', flat(child(r.name, 'session-title')), 'rename the sidebar');
  ok('标题够用时就不画 id', !has(r.name, 'session-id'), flat(r.name));
  ok('id 仍然在提示里', r.btn.title.indexOf('20260913-001746-3377') === 0, r.btn.title);
}

// 2. 比这一行还长的标题会被裁切，而不是无限折行。
{
  const long = 'x'.repeat(400);
  const r = row({ id: 'a-1', title: long });
  const shown = flat(child(r.name, 'session-title'));
  ok('粘贴的长问题不会变成这一行', shown.length <= 200 && shown.endsWith('…'), String(shown.length));
}

// 3. 还什么都没问过：这一行退回某个东西，而不是一行空白。
{
  const r = row({ id: '20260913-010000-aaaa', title: '', preview: '(no messages)' });
  eq('空标题显示占位文本', flat(child(r.name, 'session-title')), '(no messages)');
}

// 4. 冲突的标题会被打上时间戳；不冲突的不会。
{
  const list = items([
    { id: '20260912-095619-ffe0', title: 'add markdown rendering' },
    { id: '20260912-114300-d242', title: '你好' },
    { id: '20260913-001746-3377', title: '你好' },
    { id: '20260913-020000-ffff', title: 'third task' }
  ]);
  const stamps = api.repeatedStamps(list);
  eq('唯一的标题不加时间戳', stamps.get('20260912-095619-ffe0'), undefined);
  eq('冲突的一对里的第一个被打上时间戳', stamps.get('20260912-114300-d242'), '20260912');
  eq('第二个也是', stamps.get('20260913-001746-3377'), '20260913');
  eq('后面那个唯一标题不加时间戳', stamps.get('20260913-020000-ffff'), undefined);

  const stamped = row({ id: '20260913-001746-3377', title: '你好' }, '', stamps.get('20260913-001746-3377'));
  eq('时间戳渲染在标题旁边', flat(stamped.name), '20260913你好');
  const plain = row({ id: '20260913-020000-ffff', title: 'third task' });
  eq('没有时间戳的行就只有标题', flat(plain.name), 'third task');
}

// 5. 当前会话仍然被标记，删除控件也仍然出现 —— 两者都不是这次改动的重点，而重写这一行
// 时两者都很容易丢掉。
{
  const r = row({ id: '20260913-001746-3377', title: 'x' }, '20260913-001746-3377');
  ok('当前行在 <li> 上说明这一点', r.li.cls.indexOf('current') >= 0, r.li.cls);
  ok('这一行保留删除控件', tree.deletes.length > 0);
}

// 6. 整行都能打开。用户指的那条 —— 标题和「删除」之间的空白条 —— 是一次目标为 li
// 本身、没有别的目标的点击。
{
  const r = row({ id: '20260913-001746-3377', title: 'x' });
  eq('点在行本身上会打开这个会话', clickRow(r.li), 1);
  eq('而且它打开的是它所属的那一行', opened[0], 'ws/20260913-001746-3377');
}

// 7. ……但它里面的控件仍然是它们说的意思。
{
  const r = row({ id: '20260913-001746-3377', title: 'x' });
  const del = child(r.li, 'session-actions');
  eq('点「删除」不会顺便打开这个会话', clickRow(r.li, { target: del }), 0);
  eq('点打开按钮是那个按钮自己的事，不是这一行的',
    clickRow(r.li, { target: r.btn }), 0);
}

// 8. 带修饰键的点击不是普通打开：浏览器被要求做别的事（开新标签页），而这个页面没有
// URL 给它。
{
  const r = row({ id: '20260913-001746-3377', title: 'x' });
  eq('ctrl 点击留给浏览器', clickRow(r.li, { event: { ctrlKey: true } }), 0);
  eq('中键点击也不算打开', clickRow(r.li, { event: { button: 1 } }), 0);
}

// 9. 在标题上拖过不能触发这一行：选中就是选中。
{
  const r = row({ id: '20260913-001746-3377', title: 'x' });
  selectionText = 'part of the title';
  eq('结束一次选中的点击不会打开这个会话', clickRow(r.li), 0);
  selectionText = '';
  eq('而一次干净的点击仍然会打开', clickRow(r.li), 1);
}

// 10. 一次单独的重建（一个结束的回合）不能把长列表弹回顶部：滚动位置会被带过去，
// 并在新列表比旧列表短时被夹住。
{
  // 一个有余地滚动的列表：300px 窗口里放 600px 的内容。
  const body = { scrollTop: 180, scrollHeight: 600, clientHeight: 300 };
  const holder = { wsTree: { parentNode: body } };
  const inner = new Function('el', 'str', 'tree', 'deleteControl', 'openWorkspaceSession', 'dom',
    code + '\nreturn {treeScroll};')(el, (v) => (v === undefined || v === null ? '' : String(v)),
    tree, deleteControl, openWorkspaceSession, holder);
  eq('位置被读回来', inner.treeScroll(), 180);
  body.scrollTop = 0;
  eq('重建之后位置被恢复', inner.treeScroll(180), 180);
  body.scrollHeight = 240;
  eq('更短的列表不能被滚过自己的末尾', inner.treeScroll(180), 0);
}


// 11. 在别处运行的一个对话会被标记，而且能从这里停止：这正是这次改动的全部意义 ——
// 留在另一个会话里跑的回合必须能被找到、能不开它就先停下。
{
  const r = row({ id: '20260913-001746-3377', title: 'long job', running: true });
  eq('这一行说明它正在运行', r.li.cls.indexOf('running') >= 0, true);
  eq('标题旁边有一个标记', has(r.name, 'session-running'), true);

  const idle = row({ id: '20260913-001746-3377', title: 'quiet' });
  eq('空闲的行不带标记', has(idle.name, 'session-running'), false);
  eq('而且没有被标为运行中', idle.li.cls.indexOf('running') >= 0, false);

  // 停止控件在这一行上，而且它点名了它会停下的那个会话。
  const actions = child(r.li, 'session-actions');
  const stop = child(actions, 'session-stop');
  eq('运行中的行提供停止控件', stop !== null, true);
  stopped = [];
  stop.fire('click', { stopPropagation() {} });
  eq('停止它会向服务器要这个会话', stopped.join(','), '20260913-001746-3377');
  eq('空闲的行不提供停止控件', child(child(idle.li, 'session-actions'), 'session-stop'), null);
}

if (failures.length) {
  console.error(failures.length + ' failed, ' + pass + ' passed\n');
  for (const f of failures) { console.error('  ✗ ' + f); }
  process.exit(1);
}
console.log('session row: ' + pass + ' cases passed');
