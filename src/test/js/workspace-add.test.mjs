/* 没有名称字段的「添加工作区」，不靠浏览器运行。
 *
 * 这段行为在 web/app.js 里，那是一个没有导出的经典脚本，所以拥有它的那一节是从发布的
 * 文件里（下面那两条横幅注释之间）抽出来、跑在一个小小的 node 桩上的。因此这些断言针对的
 * 是随包发布的代码，而不是它的副本 —— markdown 和 session-row 那些用例用的是同一个手法。
 *
 * 它钉住的是那个手势：一次点击打开桌面的选择器，选中的文件夹在一次请求里被添加，而那个
 * 请求只带路径、不带名字 —— 名字是文件夹的，由服务器推导。被取消的选择器什么都不添加，
 * 而一个根本跑不起来的选择器会让表单打开着，好让手输路径照样能用。
 *
 * `node src/test/js/workspace-add.test.mjs` —— 装了 node 时也会由
 * com.ccj.agent.web.WebWorkspaceAddTest 运行，所以 `mvn test` 也覆盖它。 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const APP = join(here, '..', '..', 'main', 'resources', 'web', 'app.js');
const src = readFileSync(APP, 'utf8');

// 没有构建步骤的经典脚本：整体解析一遍是它唯一能得到的 lint，也是抓多余花括号最便宜的
// 办法。
new Function(src);

const START = '  // ------------------------------------------- sidebar: adding a workspace';
const END = '  // --------------------------------------- provider / model / effort picker';
const from = src.indexOf(START);
const to = src.indexOf(END);
if (from < 0 || to < 0 || to < from) { throw new Error('could not locate the add-workspace section'); }
const code = src.slice(from, to);

// ------------------------------------------------------------------ node 桩

function field(hidden) {
  return { value: '', textContent: '', hidden: hidden === true, disabled: false, focused: false, focus() { this.focused = true; } };
}

function newDom() {
  return {
    workspaceAdd: { attrs: {}, setAttribute(k, v) { this.attrs[k] = v; } },
    workspaceAddForm: { hidden: true },
    workspaceSave: field(),
    workspacePick: Object.assign(field(), { textContent: 'Choose folder…' }),
    wsNewPath: field(),
    wsBrowse: Object.assign(field(), { textContent: 'Browse…' }),
    wsBrowseHint: field(true),
    wsPathError: field(true)
  };
}

// 测试脚手架：一个请求队列、一套消息、一份树载荷。
let replies = [];
let requests = [];
let notes = [];
let errors = [];
let expanded = [];
let accepted = [];

function request(url, options) {
  requests.push({ url: url, body: options && options.body ? JSON.parse(options.body) : null });
  const next = replies.shift();
  if (next instanceof Error) { return Promise.reject(next); }
  return Promise.resolve(next);
}
function postJSON(url, payload) { return request(url, { method: 'POST', body: JSON.stringify(payload) }); }
const workspaceItemsOf = (payload) =>
  (payload && Array.isArray(payload.workspaces) ? payload.workspaces : []).filter((i) => !!i && typeof i === 'object');

const dom = newDom();
const tree = { expanded: { add: (n) => expanded.push(n) } };

const api = new Function(
  'str', 'dom', 'tree', 'fieldError', 'sidebarError', 'sidebarNote', 'saveExpanded',
  'acceptWorkspaces', 'postJSON', 'request', 'workspaceItemsOf',
  code + '\nreturn {addWorkspaceByPicking, addWorkspace, pickWorkspaceFolder, addedWorkspace, showWorkspaceError};'
)(
  (v) => (v === undefined || v === null ? '' : String(v)),
  dom,
  tree,
  (node, message) => { node.textContent = message; node.hidden = false; },
  (text) => { errors.push(text); },
  (text) => { notes.push(text); },
  () => { expanded.push('saved'); },
  (payload, opts) => { accepted.push(opts); return Promise.resolve(); },
  postJSON,
  request,
  workspaceItemsOf
);

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

function reset() {
  // 就地修改：被抽出来的那一节拿到的就是这个确切的对象。
  const fresh = newDom();
  Object.keys(fresh).forEach((key) => { dom[key] = fresh[key]; });
  replies = [];
  requests = [];
  notes = [];
  errors = [];
  expanded = [];
  accepted = [];
}

const payloadFor = (name, path) => ({
  active: 'ws',
  workspaces: [
    { name: 'ws', path: '/home/you/ws', sessions: 0, active: true },
    { name: name, path: path, sessions: 0, active: false }
  ]
});

// 1. 那次点击就是选择器：选中的文件夹带着路径添加，不带名字。
{
  reset();
  replies = [
    { path: '/home/you/projects/api' },
    payloadFor('api', '/home/you/projects/api')
  ];
  await api.addWorkspaceByPicking();

  ok('这次点击向服务器要一个选择器', requests.length >= 1 && requests[0].url === '/api/workspaces/browse',
    JSON.stringify(requests));
  eq('添加请求带着路径', requests[1].body.path, '/home/you/projects/api');
  eq('而且完全没有名字 —— 名字由文件夹给', 'name' in requests[1].body, false);
  eq('表单被收起', dom.workspaceAddForm.hidden, true);
  eq('触发器说明它是关的', dom.workspaceAdd.attrs['aria-expanded'], 'false');
  eq('结果被报告出来', notes.length, 1);
  ok('通知点名了服务器选的工作区', notes[0].indexOf('api') >= 0, notes[0]);
  ok('新节点被展开', expanded.indexOf('api') >= 0, JSON.stringify(expanded));
  eq('侧边栏指向它', accepted[0] && accepted[0].selected, 'api');
}

// 2. 两个最后一段相同的文件夹：页面相信的是服务器选的名字，而不是它本可以猜的那个
//    最后一段。
{
  reset();
  replies = [
    { path: '/home/you/work/api' },
    payloadFor('api-2', '/home/you/work/api')
  ];
  await api.addWorkspaceByPicking();

  ok('用了带后缀的名字', notes[0].indexOf('api-2') >= 0, notes[0]);
  eq('而且展开的就是那个节点', expanded.indexOf('api-2') >= 0, true);
}

// 3. 取消选择器既不是添加，也不是错误。
{
  reset();
  replies = [{ cancelled: true }];
  await api.addWorkspaceByPicking();

  eq('什么都没添加', requests.length, 1);
  eq('空答复不是失败', errors.length, 0);
  ok('提示说明了选择器被取消', dom.wsBrowseHint.textContent.indexOf('取消') >= 0,
    dom.wsBrowseHint.textContent);
  eq('而且表单没有挡路', dom.workspaceAddForm.hidden, true);
}

// 4. 一台没有选择器的机器：拒绝被显示出来，表单也打开，所以手输路径只差一次点击，
//    而不是藏在一次失败后面。
{
  reset();
  replies = [new Error('no desktop session available, so ccj cannot open a folder chooser')];
  await api.addWorkspaceByPicking();

  ok('拒绝显示在路径字段下面', dom.wsPathError.hidden === false, JSON.stringify(dom.wsPathError));
  ok('跑不起来的是那个选择器', dom.wsPathError.textContent.indexOf('desktop') >= 0,
    dom.wsPathError.textContent);
  eq('表单打开，好让路径能手输', dom.workspaceAddForm.hidden, false);
  eq('触发器说明了这一点', dom.workspaceAdd.attrs['aria-expanded'], 'true');
  eq('什么都没添加', requests.length, 1);
}

// 5. 手输路径并提交是同一个请求；名字仍然由服务器推导，而目录会去掉首尾空白。
{
  reset();
  replies = [{ active: 'ws', workspaces: [{ name: 'scratch', path: '/home/you/scratch' }] }];
  await api.addWorkspace('  /home/you/scratch  ');

  eq('送出的是去掉首尾空白的路径', requests[0].body.path, '/home/you/scratch');
  eq('页面不编造名字', 'name' in requests[0].body, false);
  eq('一个没有对应条目可指的路径仍然被报告', notes.length, 1);
  ok('没有点出节点，它就不声称有', notes[0].indexOf('使用') >= 0, notes[0]);
}

// 6. 空字段和被拒绝的添加都落在路径字段下面 —— 但只在表单打开时，因为那时屏幕上才
//    真的有字段。
{
  reset();
  dom.workspaceAddForm.hidden = false;
  await api.addWorkspace('   ');
  eq('空路径什么都不发', requests.length, 0);
  ok('而且说明缺了什么', dom.wsPathError.textContent.indexOf('目录') >= 0, dom.wsPathError.textContent);

  reset();
  dom.workspaceAddForm.hidden = false;
  replies = [new Error('not a directory: /home/you/ws.txt')];
  await api.addWorkspace('/home/you/ws.txt');
  ok('拒绝显示在字段上', dom.wsPathError.hidden === false, JSON.stringify(dom.wsPathError));
  eq('而且不是作为侧边栏提示', errors.length, 0);
}

// 7. 被拒绝的一次选择屏幕上没有表单，所以它的消息走到树上方，而不是落在一个没人看得见
//    的字段上。
{
  reset();
  replies = [new Error('that directory is already the workspace \'ws\'')];
  await api.addWorkspace('/home/you/ws');

  eq('什么都没写进隐藏的字段', dom.wsPathError.hidden, true);
  ok('拒绝改为作为侧边栏提示出现', errors.length === 1, JSON.stringify(errors));
  ok('而且它说明那个目录归哪一项所有', errors[0].indexOf('ws') >= 0, errors[0]);
}

// 8. 把添加的那一项读回来：答案不是路径的最后一段。
{
  const payload = payloadFor('api-2', '/home/you/work/api');
  eq('按目录找到那一项', api.addedWorkspace(payload, '/home/you/work/api/'), 'api-2');
  eq('未知的目录不点名任何东西', api.addedWorkspace(payload, '/home/you/elsewhere'), '');
}

// -------------------------------------------------------------------- 结果

if (failures.length) {
  console.error('workspace-add: ' + failures.length + ' failed, ' + pass + ' passed');
  failures.forEach((f) => console.error('  ✗ ' + f));
  process.exit(1);
}
console.log('workspace-add: ' + pass + ' passed');
