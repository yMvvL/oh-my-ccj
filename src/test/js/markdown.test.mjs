/* markdown 渲染器的用例，不靠浏览器运行。
 *
 * 渲染器住在 web/app.js 里，那是一个没有导出的经典脚本，所以它的源码是从真实文件里
 * （下面那两条横幅注释之间）抽出来、跑在一个约 40 行的 DOM 上的。因此这些断言针对的是
 * 随包发布的代码，而不是它的副本。
 *
 * `node src/test/js/markdown.test.mjs` —— 装了 node 时也会由
 * com.ccj.agent.web.WebMarkdownTest 运行，所以 `mvn test` 也覆盖它。 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

// 从本文件解析路径，所以在任何工作目录下都能跑。
const here = dirname(fileURLToPath(import.meta.url));
const APP = join(here, '..', '..', 'main', 'resources', 'web', 'app.js');
const src = readFileSync(APP, 'utf8');

const START = '  // -------------------------------------------------------------- markdown';
const END = '  // ----------------------------------------------------------- transcript';
const from = src.indexOf(START);
const to = src.indexOf(END);
if (from < 0 || to < 0 || to < from) { throw new Error('could not locate the markdown section'); }
const code = src.slice(from, to);

// ---------------------------------------------------------------- 迷你 DOM

class T {
  constructor(data) { this.data = String(data); this.parentNode = null; }
  get textContent() { return this.data; }
  set textContent(v) { this.data = String(v); }
  get childNodes() { return []; }
}
class N {
  constructor(tag) {
    this.tag = tag; this.className = ''; this.parentNode = null;
    this.childNodes = []; this.attrs = {};
  }
  appendChild(c) { c.parentNode = this; this.childNodes.push(c); return c; }
  removeChild(c) {
    const i = this.childNodes.indexOf(c);
    if (i >= 0) { this.childNodes.splice(i, 1); c.parentNode = null; }
    return c;
  }
  get firstChild() { return this.childNodes[0] || null; }
  get textContent() { return this.childNodes.map((c) => c.textContent).join(''); }
  set textContent(v) {
    this.childNodes = [];
    if (v !== undefined && v !== null && String(v) !== '') { this.appendChild(new T(v)); }
  }
}
class Frag extends N { constructor() { super('#frag'); } }

const document = {
  createElement: (t) => new N(t),
  createTextNode: (d) => new T(d),
  createDocumentFragment: () => new Frag()
};

const el = (tag, cls, text) => {
  const node = new N(tag);
  if (cls) { node.className = cls; }
  if (text !== undefined && text !== null) { node.textContent = String(text); }
  return node;
};
const str = (v) => (v === undefined || v === null ? '' : String(v));

const api = new Function('el', 'str', 'document', 'mdQueues',
  code + '\nreturn {mdBlocks, mdInline, renderMarkdown, mdRenderBlock, mdCodeSpan, mdSafeUrl};'
)(el, str, document, new Map());

// ------------------------------------------------------------------ 序列化器

const VOID = new Set(['br', 'hr', 'input', 'img']);
function ser(node) {
  if (node instanceof T) { return esc(node.data); }
  if (node.tag === '#frag') { return node.childNodes.map(ser).join(''); }
  if (node.tag === 'div' && !node.className) { return node.childNodes.map(ser).join(''); }
  const cls = node.className ? ' class="' + node.className + '"' : '';
  const attrs = Object.keys(node.attrs).map((k) => ' ' + k + '="' + node.attrs[k] + '"').join('');
  const extra = node.tag === 'a' && node.href ? ' href="' + esc(node.href) + '"' : '';
  const type = node.type ? ' type="' + node.type + '"' : '';
  const box = node.type === 'checkbox' ? ' checked=' + node.checked + ' disabled' : '';
  if (VOID.has(node.tag)) { return '<' + node.tag + cls + type + extra + attrs + box + '>'; }
  return '<' + node.tag + cls + type + attrs + extra + box + '>'
    + node.childNodes.map(ser).join('') + '</' + node.tag + '>';
}
function esc(s) { return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'); }

function render(mdSource, into) {
  const node = into || new N('div');
  const state = { source: mdSource, blocks: null, nodes: [] };
  api.renderMarkdown(state, node);
  return { node, state };
}
function html(source) { return render(source).node.childNodes.map(ser).join(''); }

/* 块结构的形状，用于那些重点在「哪个块和哪个块是兄弟」而不是各自标记的用例。 */
function tags(source) {
  return render(source).state.nodes.map((n) => '<' + n.tag + '>').join('');
}

// -------------------------------------------------------------------- 用例

let pass = 0;
const failures = [];
function eq(name, actual, expected) {
  if (actual === expected) { pass += 1; return; }
  failures.push(name + '\n    expected: ' + expected + '\n    actual:   ' + actual);
}
function ok(name, cond, detail) {
  if (cond) { pass += 1; return; }
  failures.push(name + (detail ? '\n    ' + detail : ''));
}

// 1. 标题、段落、硬换行、强调、行内代码、链接
eq('标题 + 段落', html('# Title\n\nSome **bold**, *em*, `code` and a [link](https://example.dev).'),
  '<h1 class="md-h">Title</h1><p class="md-p">Some <strong>bold</strong>, <em>em</em>, <code class="md-code-inline">code</code> and a <a class="md-a" href="https://example.dev">link</a>.</p>');

eq('标题级别', html('### three\n\n#### four'),
  '<h3 class="md-h">three</h3><h4 class="md-h">four</h4>');

eq('删除线和词内下划线', html('~~gone~~ and snake_case stays'),
  '<p class="md-p"><del>gone</del> and snake_case stays</p>');

eq('硬换行保留两个空格', html('a  \nb'), '<p class="md-p">a<br>b</p>');
eq('软换行变成一个空格', html('a\nb'), '<p class="md-p">a b</p>');

// 2. 代码块
eq('带语言的围栏代码', html('```bash\nccj --demo\n```'),
  '<div class="md-code-wrap"><div class="md-code-lang">bash</div><pre class="md-pre"><code class="md-code">ccj --demo</code></pre></div>');

eq('围栏代码内部的 markdown 保持原样', html('```\n# not a heading\n**not bold**\n```'),
  '<div class="md-code-wrap"><pre class="md-pre"><code class="md-code"># not a heading\n**not bold**</code></pre></div>');

eq('波浪线围栏和更长的闭合围栏', html('~~~\ncode\n~~~~'),
  '<div class="md-code-wrap"><pre class="md-pre"><code class="md-code">code</code></pre></div>');

// 3. 列表
eq('紧凑的无序列表', html('- one\n- two'),
  '<ul class="md-list"><li class="md-li">one</li><li class="md-li">two</li></ul>');

eq('有序列表尊重起始编号', html('3. three\n4. four'),
  '<ol class="md-list"><li class="md-li">three</li><li class="md-li">four</li></ol>');

eq('嵌套列表', html('- a\n  - b\n- c'),
  '<ul class="md-list"><li class="md-li">a<ul class="md-list"><li class="md-li">b</li></ul></li><li class="md-li">c</li></ul>');

eq('任务列表', html('- [x] done\n- [ ] todo'),
  '<ul class="md-list"><li class="md-li"><input class="md-task" type="checkbox" checked=true disabled>done</li>'
  + '<li class="md-li"><input class="md-task" type="checkbox" checked=false disabled>todo</li></ul>');

eq('折行的延续行并入该条目', html('- first\n  continued\n- second'),
  '<ul class="md-list"><li class="md-li">first continued</li><li class="md-li">second</li></ul>');

eq('列表条目里的列表，带一个代码块', html('1. step\n\n   ```sh\n   run\n   ```'),
  '<ol class="md-list"><li class="md-li"><p class="md-p">step</p><div class="md-code-wrap"><div class="md-code-lang">sh</div><pre class="md-pre"><code class="md-code">run</code></pre></div></li></ol>');

// 列表必须在它该结束的地方结束。测量延续行的*宽度*而不是它的缩进，会让列表之后的一切
// —— 引用、围栏、表格、下一个列表 —— 都变成它的条目，一个真实答案的整个尾部就是这样
// 被嵌进它最后一个项目符号里的。
eq('列表不会吞掉它后面的块',
  tags('# t\n\n1. one\n2. two\n\n> note\n\n```sh\nx\n```\n\n| a | b |\n| - | - |\n| 1 | 2 |\n\n- [x] done\n\nThe end.'),
  '<h1><ol><blockquote><div><div><ul><p>');
eq('被吞的尾部保持自己的种类',
  html('1. one\n2. two\n\n- [x] done\n- [ ] todo'),
  '<ol class="md-list"><li class="md-li">one</li><li class="md-li">two</li></ol>'
  + '<ul class="md-list"><li class="md-li"><input class="md-task" type="checkbox" checked=true disabled>done</li>'
  + '<li class="md-li"><input class="md-task" type="checkbox" checked=false disabled>todo</li></ul>');
eq('混合标记是彼此分开的列表', tags('- a\n1. b\n- c'), '<ul><ol><ul>');
eq('另一种标记会结束这个列表', tags('1. a\n- b\n2. c'), '<ol><ul><ol>');

// 4. 引用、分隔线、表格
eq('带嵌套列表的引用块', html('> tip:\n> - a\n> - b'),
  '<blockquote class="md-quote"><p class="md-p">tip:</p><ul class="md-list"><li class="md-li">a</li><li class="md-li">b</li></ul></blockquote>');

eq('引用里的懒惰延续', html('> one\ntwo'),
  '<blockquote class="md-quote"><p class="md-p">one two</p></blockquote>');

eq('分隔线', html('a\n\n---\n\nb'), '<p class="md-p">a</p><hr class="md-hr"><p class="md-p">b</p>');

eq('带对齐的表格', html('| a | b |\n|:--|--:|\n| 1 | 2 |'),
  '<div class="md-table-wrap"><table class="md-table"><thead><tr><th class="md-align-left">a</th><th class="md-align-right">b</th></tr></thead>'
  + '<tbody><tr><td class="md-align-left">1</td><td class="md-align-right">2</td></tr></tbody></table></div>');

eq('孤立的竖线行不是表格', html('a | b\nno dashes here'),
  '<p class="md-p">a | b no dashes here</p>');

// 5. 行内细节
eq('尖括号里的自动链接', html('see <https://example.dev/a>'),
  '<p class="md-p">see <a class="md-a" href="https://example.dev/a">https://example.dev/a</a></p>');

eq('裸 URL，末尾句点除外', html('see https://example.dev/x.'),
  '<p class="md-p">see <a class="md-a" href="https://example.dev/x">https://example.dev/x</a>.</p>');

eq('未闭合的反引号是字面文字', html('a `b'), '<p class="md-p">a `b</p>');
eq('转义的星号是字面文字', html('a \\*b\\* c'), '<p class="md-p">a *b* c</p>');
eq('词内的下划线不开启强调', html('call some_fn_name here'),
  '<p class="md-p">call some_fn_name here</p>');
eq('未闭合的加粗保持字面', html('a **b'), '<p class="md-p">a **b</p>');

// 6. 安全上的那几处拒绝
eq('原始 HTML 是文字', html('<img src=x onerror=alert(1)>'),
  '<p class="md-p">&lt;img src=x onerror=alert(1)&gt;</p>');
eq('script 标签是文字', html('<script>alert(1)</script>'),
  '<p class="md-p">&lt;script&gt;alert(1)&lt;/script&gt;</p>');
eq('javascript: 链接保持为文字', html('[click](javascript:alert(1))'),
  '<p class="md-p">click</p>');
eq('data: 链接保持为文字', html('[x](data:text/html,<b>)'), '<p class="md-p">x</p>');
eq('带圆括号的目标得以保留', html('[wiki](https://en.wikipedia.org/wiki/Foo_(bar))'),
  '<p class="md-p"><a class="md-a" href="https://en.wikipedia.org/wiki/Foo_(bar)">wiki</a></p>');
eq('链接标题不属于 href', html('[a](https://example.dev "title")'),
  '<p class="md-p"><a class="md-a" href="https://example.dev">a</a></p>');
eq('链接内部的 URL 标签不再被链接', html('[https://example.dev](https://example.dev/x)'),
  '<p class="md-p"><a class="md-a" href="https://example.dev/x">https://example.dev</a></p>');
eq('相对链接被保留', html('[docs](docs/WEBUI.md)'),
  '<p class="md-p"><a class="md-a" href="docs/WEBUI.md">docs</a></p>');
eq('图片变成它同时是的那个链接', html('![alt](https://example.dev/i.png)'),
  '<p class="md-p"><a class="md-a" href="https://example.dev/i.png">alt</a></p>');

// 7. 增量渲染：DOM 与一次完整渲染相同，而且较早的节点活下来
{
  const message = [
    '# Plan', '', 'Read the file:', '', '```java', 'var x = 1;', '```', '',
    '- [x] read', '- [ ] write', '', '| k | v |', '| - | - |', '| a | 1 |', '',
    'Done. See https://example.dev.'
  ].join('\n');
  const { node, state } = render('');
  let identity = [];
  const domAt = [];
  for (let i = 1; i <= message.length; i += 1) {
    state.source = message.slice(0, i);
    api.renderMarkdown(state, node);
    const at = [];
    for (let b = 1; b <= Math.ceil(message.length / 8); b += 1) { at.push(0); }
    identity.push(state.nodes.length);
    domAt.push(ser(node));
  }
  const full = html(message);
  eq('流式渲染与一次性渲染得到相同的 DOM', domAt[domAt.length - 1], full);

  // 一个已经闭合了自己那些块的前缀，必须原地保留屏幕上那些确切的节点。
  const prefix = '# Plan\n\nRead the file:';
  const first = render(prefix);
  const keptNodes = first.state.nodes.slice();
  first.state.source = prefix + '\n\nAnd more text arrives.';
  api.renderMarkdown(first.state, first.node);
  ok('未变的标题节点被复用', first.state.nodes[0] === keptNodes[0]);
  ok('增长的答案不会重建任何节点', first.state.nodes.length === 3,
    'nodes=' + first.state.nodes.length);
  ok('较早的节点就是同一批对象',
    first.state.nodes[0] === keptNodes[0] && first.state.nodes[1] === keptNodes[1]);
  eq('增长的答案渲染两个块',
    ser(first.node),
    '<h1 class="md-h">Plan</h1><p class="md-p">Read the file:</p><p class="md-p">And more text arrives.</p>');
}

// 8. 块中间的半截内容永不抛出，也永不留下游离的节点
{
  const { node, state } = render('');
  const partial = 'Some **bo';
  for (let i = 1; i <= partial.length; i += 1) {
    state.source = partial.slice(0, i);
    api.renderMarkdown(state, node);
  }
  ok('不完整的分隔符按字面渲染', ser(node) === '<p class="md-p">Some **bo</p>', ser(node));
}

// 9. 链接目标按浏览器将要解析它的方式来判断
{
  // `a.href =` 会经过 URL 解析器，它在读取协议之前会丢掉任意位置的 ASCII 制表符和换行，
  // 以及首尾的 C0 控制字符 —— 所以 "java\nscript:" 对浏览器来说就是一个 javascript: URL，
  // 尽管在这里看起来不像。过滤器必须看到解析器将要看到的同一个字符串，否则这样一个目标
  // 会作为一条活链接落进转录里。
  eq('普通链接被保留', api.mdSafeUrl('https://example.dev/a?b=1'), 'https://example.dev/a?b=1');
  eq('相对目标被保留', api.mdSafeUrl('/docs/page'), '/docs/page');
  eq('javascript: 目标被丢弃', api.mdSafeUrl('javascript:alert(1)'), '');
  eq('协议里的制表符藏不住它', api.mdSafeUrl('java\tscript:alert(1)'), '');
  eq('协议里的换行藏不住它', api.mdSafeUrl('java\nscript:alert(1)'), '');
  eq('开头的控制字符藏不住它', api.mdSafeUrl('\u0001javascript:alert(1)'), '');

  const smuggled = html('see [the docs](java\nscript:alert(document.domain))');
  ok('隐藏协议的行内链接保持为文字', !smuggled.includes('<a '), smuggled);
  eq('而且标签仍然显示', smuggled, '<p class="md-p">see the docs</p>');
}

// -------------------------------------------------------------------- 报告
console.log(pass + ' assertions passed');
if (failures.length) {
  console.log('\n' + failures.length + ' FAILED:');
  failures.forEach((f) => console.log('  - ' + f));
  process.exit(1);
}
