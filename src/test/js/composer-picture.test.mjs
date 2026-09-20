/* 图片的三个入口：按钮、粘贴、拖放 —— 不靠浏览器运行。
 *
 * 这段行为在 web/app.js 里，那是一个没有导出的经典脚本，所以拥有它的那一节是从发布的
 * 文件里（下面那两条横幅注释之间）抽出来、跑在一个小小的 node 桩上的。因此这些断言针对的
 * 是随包发布的代码，而不是它的副本 —— markdown、会话行和添加工作区那些用例用的是同一个
 * 手法。
 *
 * 它钉住的是**入口只有一条**：三个手势最后都调用 sendPhoto，而 sendPhoto 是唯一把图片
 * 交给服务器的地方。这一点值得钉，是因为它上一次坏掉的方式：上传曾经自己开启一个回合、而
 * 描述本该等着和用户的下一句话一起发；多一条图片路径就是多一个会这么干的地方。顺带钉住那
 * 些手势本身——文本粘贴必须原样落进输入框，非图片的拖放必须说话，而不是静默地什么都不做。
 *
 * `node src/test/js/composer-picture.test.mjs` —— 装了 node 时也会由
 * com.ccj.agent.web.WebComposerPictureTest 运行，所以 `mvn test` 也覆盖它。 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const APP = join(here, '..', '..', 'main', 'resources', 'web', 'app.js');
const src = readFileSync(APP, 'utf8');

// 没有构建步骤的经典脚本：整体解析一遍是它唯一能得到的 lint。
new Function(src);

const START = '  // ------------------------------------------------- 图片的入口：按钮、粘贴、拖放';
const END = '  // --------------------------------------------- 图片的入口结束';
const from = src.indexOf(START);
const to = src.indexOf(END);
if (from < 0 || to < 0 || to < from) { throw new Error('could not locate the image entry points'); }
const code = src.slice(from, to);

// ------------------------------------------------------------------ node 桩

function file(name, type) {
  return { name: name, type: type, size: 1024 };
}

const listeners = {};
const classes = new Set();
let uploaded = [];
let notes = [];

const api = new Function(
  'str', 'dom', 'document', 'sendPhoto', 'appendNotice',
  code + '\nreturn {imageFrom, hasFiles};'
)(
  (v) => (v === undefined || v === null ? '' : String(v)),
  {
    composerCard: {
      classList: {
        add: (name) => classes.add(name),
        remove: (name) => classes.delete(name)
      }
    }
  },
  {
    addEventListener: (type, handler) => { (listeners[type] = listeners[type] || []).push(handler); }
  },
  (f) => { uploaded.push(f); },
  (text) => { notes.push(text); }
);

function fire(type, event) {
  const handlers = listeners[type] || [];
  event = event || {};
  event.defaultPrevented = false;
  event.preventDefault = function () { event.defaultPrevented = true; };
  handlers.forEach((handler) => handler(event));
  return event;
}

// 剪贴板和拖放都同时带 files 和 items，形状略有不同，所以两种都要有。
const transfer = (files, items) => ({
  files: files,
  items: items || (files || []).map((f) => ({ kind: 'file', type: f.type, getAsFile: () => f })),
  types: files ? ['Files'] : ['text/plain']
});

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
  uploaded = [];
  notes = [];
  classes.clear();
}

// 1. 粘贴一张截图就是上传它，而且这条请求的来源被拦住：不拦的话浏览器还会把它交给别的
//    东西处理。
{
  reset();
  const shot = file('Screenshot.png', 'image/png');
  const event = fire('paste', { clipboardData: transfer([], [{ kind: 'file', type: 'image/png', getAsFile: () => shot }]) });

  eq('粘贴的图片被上传', uploaded.length, 1);
  eq('上传的就是那一张', uploaded[0], shot);
  ok('而这次粘贴被接管了', event.defaultPrevented);
}

// 2. 粘贴文本必须一点都不受影响：它要落进输入框，而输入框是用户正在打字的地方。
{
  reset();
  const event = fire('paste', {
    clipboardData: { files: [], items: [{ kind: 'string', type: 'text/plain' }], types: ['text/plain'] }
  });

  eq('文本粘贴不会被当成一张图片', uploaded.length, 0);
  eq('而且什么都没被接管', event.defaultPrevented, false);
}

// 3. 拖进来的时候输入框亮起来，拖走之后灭掉 —— 不然用户没法知道这里能不能放。
{
  reset();
  const enter = fire('dragenter', { dataTransfer: transfer([file('a.png', 'image/png')]) });
  ok('拖动时有高亮', classes.has('drop-target'));
  ok('而且拖进窗口不会让浏览器打开这个文件', enter.defaultPrevented);

  fire('dragleave', {});
  eq('拖走之后高亮没了', classes.has('drop-target'), false);
}

// 4. 放下一张图片：它成为待发送的那一张，和按钮选出来的走同一条路。
{
  reset();
  const shot = file('diagram.png', 'image/png');
  const event = fire('drop', { dataTransfer: transfer([shot]) });

  eq('放下的图片被上传', uploaded.length, 1);
  eq('上传的就是那一张', uploaded[0], shot);
  ok('放下之后高亮也没了', !classes.has('drop-target'));
  ok('这次放下被接管了', event.defaultPrevented);
}

// 5. 放下一个不是图片的文件：说出来，而不是静默地什么都不做 —— 静默的不作为正是用户
//    会再试三次的那种反馈。
{
  reset();
  fire('drop', { dataTransfer: transfer([file('notes.txt', 'text/plain')]) });

  eq('没有上传', uploaded.length, 0);
  eq('但说了一句话', notes.length, 1);
  ok('说的那句话解释了怎么办', notes[0].indexOf('图片') >= 0, notes[0]);
}

// 6. 一次拖进来好几张：拿第一张图片。一次只有一张会跟着消息走，而默默地上传最后一张，
//    会让用户以为前面那几张也发出去了。
{
  reset();
  const first = file('first.png', 'image/png');
  fire('drop', { dataTransfer: transfer([file('cover.pdf', 'application/pdf'), first, file('second.png', 'image/png')]) });

  eq('只上传了一张', uploaded.length, 1);
  eq('而且是其中第一张图片', uploaded[0], first);
}

// 7. 结构上的那条不变式：三个入口只有一个出口。
{
  const entries = src.split('sendPhoto(').length - 1;
  ok('整份文件里 sendPhoto 只在一个地方被定义为函数，且入口都调用它',
    src.indexOf('async function sendPhoto(file)') > 0 && entries >= 4,
    'sendPhoto 出现次数: ' + entries);
  ok('粘贴、拖放、相机、相册四条线都接上了',
    listeners.paste && listeners.paste.length === 1 &&
    listeners.drop && listeners.drop.length === 1 &&
    src.indexOf('dom.photoCamera.addEventListener') > 0 &&
    src.indexOf('dom.photoAlbum.addEventListener') > 0);
}

// -------------------------------------------------------------------- 报告

if (failures.length) {
  console.error(failures.length + ' of ' + (pass + failures.length) + ' cases failed:');
  failures.forEach((f) => console.error('  - ' + f));
  process.exit(1);
}
console.log(pass + ' cases passed');
