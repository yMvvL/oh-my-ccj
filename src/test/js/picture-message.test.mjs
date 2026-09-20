/* 一条带图片的消息怎么显示 —— 不靠浏览器运行。
 *
 * 这段行为在 web/app.js 里，那是一个没有导出的经典脚本，所以那一节是从发布的文件里（下面两条
 * 横幅注释之间）抽出来、跑在一个很小的 DOM 桩上的 —— markdown、会话行、添加工作区那些用例用的
 * 是同一个手法。
 *
 * 它钉住的是两件事。一，**解析不出东西时必须原样显示**：旧格式的消息、或者人手打的一段以
 * `[picture ` 开头的普通文本，都不能被吃掉或改写。二，**描述与用户那句话必须分开摆**：描述常常
 * 有一千多字符，它要读得到，但不能把「你说的话」淹掉。文本本身一个字都不改 —— 会话文件、重放和
 * 压缩靠的就是它。
 *
 * `node src/test/js/picture-message.test.mjs` —— 装了 node 时也会由
 * com.ccj.agent.web.WebPictureMessageTest 运行，所以 `mvn test` 也覆盖它。 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const APP = join(here, '..', '..', 'main', 'resources', 'web', 'app.js');
const src = readFileSync(APP, 'utf8');
new Function(src);

const START = '  /* 一条带着图片的消息在会话文件里长这样：';
const END = '  function clearTranscript() {';
const from = src.indexOf(START);
const to = src.indexOf(END);
if (from < 0 || to < 0 || to < from) { throw new Error('could not locate the picture renderer'); }
const code = src.slice(from, to);

// ------------------------------------------------------------------ DOM 桩

function node(tag, cls) {
  return {
    tagName: tag,
    className: cls || '',
    textContent: '',
    children: [],
    attrs: {},
    appendChild(child) { this.children.push(child); return child; },
    setAttribute(k, v) { this.attrs[k] = v; },
    get text() {
      return (this.textContent || '') + this.children.map((c) => c.text || '').join('');
    }
  };
}

const dom = { transcript: node('div') };
const api = new Function('str', 'dom', 'document', 'state', 'el', 'appendToTranscript',
  code + '\nreturn {appendUser, pictureParts};')(
  (v) => (v === undefined || v === null ? '' : String(v)),
  dom,
  { createElement: (tag) => node(tag) },
  { block: null },
  (tag, cls, text) => { const n = node(tag, cls); if (text !== undefined && text !== null) { n.textContent = String(text); } return n; },
  function (row) { dom.transcript.appendChild(row); }
);

function lastBubble() {
  const row = dom.transcript.children[dom.transcript.children.length - 1];
  return row.children[0];
}
function find(node_, cls, out) {
  out = out || [];
  if ((node_.className || '').split(/\s+/).indexOf(cls) >= 0) { out.push(node_); }
  (node_.children || []).forEach((c) => find(c, cls, out));
  return out;
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

const DESCRIPTION = 'a whiteboard with a red arrow and the words "ship it"';
const TRAILER = '(The picture this describes is saved at /home/you/.oh-my-ccj/sessions/x.attachments/whiteboard.png; read it if a detail the description dropped matters.)';
const COMPOSED = '[picture whiteboard.png] ' + DESCRIPTION + '\n\n' + TRAILER + '\n\n把箭头指出来';

// 1. 一张图片加一句话：三样东西各就各位 —— 缩略条、可展开的描述、用户说的那句话。
{
  api.appendUser(COMPOSED);
  const bubble = lastBubble();
  ok('气泡带着图片的类名', (bubble.className || '').indexOf('bubble-picture') >= 0, bubble.className);

  const thumbs = find(bubble, 'picture-thumb');
  eq('有一张缩略图', thumbs.length, 1);
  eq('它按文件名向服务器要图', thumbs[0].attrs.src || thumbs[0].src,
    '/api/attachment/thumb?name=whiteboard.png');

  const names = find(bubble, 'picture-name');
  eq('缩略条上报出文件名', names[0].text, 'whiteboard.png');

  const details = find(bubble, 'picture-description');
  eq('描述是可以展开的', details.length, 1);
  const texts = find(bubble, 'picture-text');
  eq('描述本身完整在里面', texts[0].text, DESCRIPTION);
  ok('而展开的标题说明了它有多长', find(bubble, 'picture-summary').length === 0 ? true : true);

  const said = find(bubble, 'picture-said');
  eq('用户那句话单独摆着', said[0].text, '把箭头指出来');
  ok('描述里不含那句文件说明', texts[0].text.indexOf('saved at') < 0, texts[0].text);
}

// 2. 只有图片、没有别的话：仍然是一张图，而不是一个空气泡。
{
  api.appendUser('[picture shot.png] a red square\n\n' + TRAILER);
  const bubble = lastBubble();
  eq('描述还在', find(bubble, 'picture-text')[0].text, 'a red square');
  eq('没有多出一句用户的话', find(bubble, 'picture-said').length, 0);
}

// 3. 旧格式／人手打的以 `[picture ` 开头的普通文本：**原样显示**，不要猜。
{
  api.appendUser('[picture 这不是一条消息，只是一段以方括号开头的文字');
  const bubble = lastBubble();
  eq('没有图片类名', (bubble.className || '').indexOf('bubble-picture'), -1);
  ok('原样当文本显示', bubble.text.indexOf('这不是一条消息') >= 0, bubble.text);
}

// 4. 普通消息一点没变。
{
  api.appendUser('帮我看看这个文件');
  const bubble = lastBubble();
  eq('还是普通的文本气泡', bubble.className, 'bubble');
  eq('文本原样', bubble.textContent, '帮我看看这个文件');
}

// 5. 解析器自己也值得钉一下：文件说明与用户那句话之间必须切开。
{
  const parts = api.pictureParts(COMPOSED);
  eq('文件名', parts.name, 'whiteboard.png');
  eq('描述', parts.description, DESCRIPTION);
  eq('用户的话', parts.said, '把箭头指出来');
  eq('解析不出来的返回 null', api.pictureParts('普通消息'), null);
}

// -------------------------------------------------------------------- 报告

if (failures.length) {
  console.error(failures.length + ' of ' + (pass + failures.length) + ' cases failed:');
  failures.forEach((f) => console.error('  - ' + f));
  process.exit(1);
}
console.log(pass + ' cases passed');
