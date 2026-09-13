/* The markdown renderer's cases, run without a browser.
 *
 * The renderer lives in web/app.js, which is a classic script with no exports, so
 * its own source is lifted out of the real file (between the two banner comments
 * below) and run against a ~40-line DOM. The assertions are therefore about the
 * shipped code, not a copy of it.
 *
 * `node src/test/js/markdown.test.mjs` — also run, when a node is installed, by
 * com.ccj.agent.web.WebMarkdownTest, so `mvn test` covers it too. */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

// Resolved from this file, so it runs from any working directory.
const here = dirname(fileURLToPath(import.meta.url));
const APP = join(here, '..', '..', 'main', 'resources', 'web', 'app.js');
const src = readFileSync(APP, 'utf8');

const START = '  // -------------------------------------------------------------- markdown';
const END = '  // ----------------------------------------------------------- transcript';
const from = src.indexOf(START);
const to = src.indexOf(END);
if (from < 0 || to < 0 || to < from) { throw new Error('could not locate the markdown section'); }
const code = src.slice(from, to);

// ---------------------------------------------------------------- mini DOM

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

// --------------------------------------------------------------- serializer

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

/* The shape of the block structure, for cases where the point is which block is a
 * sibling of which rather than the markup of each one. */
function tags(source) {
  return render(source).state.nodes.map((n) => '<' + n.tag + '>').join('');
}

// -------------------------------------------------------------------- tests

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

// 1. headings, paragraphs, hard breaks, emphasis, inline code, links
eq('heading + paragraph', html('# Title\n\nSome **bold**, *em*, `code` and a [link](https://example.dev).'),
  '<h1 class="md-h">Title</h1><p class="md-p">Some <strong>bold</strong>, <em>em</em>, <code class="md-code-inline">code</code> and a <a class="md-a" href="https://example.dev">link</a>.</p>');

eq('heading levels', html('### three\n\n#### four'),
  '<h3 class="md-h">three</h3><h4 class="md-h">four</h4>');

eq('strikethrough and underscore-in-word', html('~~gone~~ and snake_case stays'),
  '<p class="md-p"><del>gone</del> and snake_case stays</p>');

eq('hard break keeps a two-space break', html('a  \nb'), '<p class="md-p">a<br>b</p>');
eq('soft break becomes a space', html('a\nb'), '<p class="md-p">a b</p>');

// 2. code blocks
eq('fenced code with language', html('```bash\nccj --demo\n```'),
  '<div class="md-code-wrap"><div class="md-code-lang">bash</div><pre class="md-pre"><code class="md-code">ccj --demo</code></pre></div>');

eq('fenced code keeps markdown inside', html('```\n# not a heading\n**not bold**\n```'),
  '<div class="md-code-wrap"><pre class="md-pre"><code class="md-code"># not a heading\n**not bold**</code></pre></div>');

eq('tilde fence and a longer closing fence', html('~~~\ncode\n~~~~'),
  '<div class="md-code-wrap"><pre class="md-pre"><code class="md-code">code</code></pre></div>');

// 3. lists
eq('tight unordered list', html('- one\n- two'),
  '<ul class="md-list"><li class="md-li">one</li><li class="md-li">two</li></ul>');

eq('ordered list honours the start number', html('3. three\n4. four'),
  '<ol class="md-list"><li class="md-li">three</li><li class="md-li">four</li></ol>');

eq('nested list', html('- a\n  - b\n- c'),
  '<ul class="md-list"><li class="md-li">a<ul class="md-list"><li class="md-li">b</li></ul></li><li class="md-li">c</li></ul>');

eq('task list', html('- [x] done\n- [ ] todo'),
  '<ul class="md-list"><li class="md-li"><input class="md-task" type="checkbox" checked=true disabled>done</li>'
  + '<li class="md-li"><input class="md-task" type="checkbox" checked=false disabled>todo</li></ul>');

eq('wrapped continuation line joins the item', html('- first\n  continued\n- second'),
  '<ul class="md-list"><li class="md-li">first continued</li><li class="md-li">second</li></ul>');

eq('list inside a list item, with a code block', html('1. step\n\n   ```sh\n   run\n   ```'),
  '<ol class="md-list"><li class="md-li"><p class="md-p">step</p><div class="md-code-wrap"><div class="md-code-lang">sh</div><pre class="md-pre"><code class="md-code">run</code></pre></div></li></ol>');

// A list must end where it ends. Measuring a continuation line's *width* rather
// than its indentation made everything after a list — a quote, a fence, a table,
// the next list — become items of it, which is how the whole tail of a real
// answer ended up nested inside its last bullet.
eq('a list does not swallow the block after it',
  tags('# t\n\n1. one\n2. two\n\n> note\n\n```sh\nx\n```\n\n| a | b |\n| - | - |\n| 1 | 2 |\n\n- [x] done\n\nThe end.'),
  '<h1><ol><blockquote><div><div><ul><p>');
eq('the swallowed tail keeps its own kind',
  html('1. one\n2. two\n\n- [x] done\n- [ ] todo'),
  '<ol class="md-list"><li class="md-li">one</li><li class="md-li">two</li></ol>'
  + '<ul class="md-list"><li class="md-li"><input class="md-task" type="checkbox" checked=true disabled>done</li>'
  + '<li class="md-li"><input class="md-task" type="checkbox" checked=false disabled>todo</li></ul>');
eq('mixed markers are separate lists', tags('- a\n1. b\n- c'), '<ul><ol><ul>');
eq('a marker of the other kind ends the list', tags('1. a\n- b\n2. c'), '<ol><ul><ol>');

// 4. quotes, rules, tables
eq('blockquote with a nested list', html('> tip:\n> - a\n> - b'),
  '<blockquote class="md-quote"><p class="md-p">tip:</p><ul class="md-list"><li class="md-li">a</li><li class="md-li">b</li></ul></blockquote>');

eq('lazy continuation inside a quote', html('> one\ntwo'),
  '<blockquote class="md-quote"><p class="md-p">one two</p></blockquote>');

eq('thematic break', html('a\n\n---\n\nb'), '<p class="md-p">a</p><hr class="md-hr"><p class="md-p">b</p>');

eq('table with alignment', html('| a | b |\n|:--|--:|\n| 1 | 2 |'),
  '<div class="md-table-wrap"><table class="md-table"><thead><tr><th class="md-align-left">a</th><th class="md-align-right">b</th></tr></thead>'
  + '<tbody><tr><td class="md-align-left">1</td><td class="md-align-right">2</td></tr></tbody></table></div>');

eq('a lone pipe line is not a table', html('a | b\nno dashes here'),
  '<p class="md-p">a | b no dashes here</p>');

// 5. inline details
eq('autolink in angle brackets', html('see <https://example.dev/a>'),
  '<p class="md-p">see <a class="md-a" href="https://example.dev/a">https://example.dev/a</a></p>');

eq('bare url, trailing period excluded', html('see https://example.dev/x.'),
  '<p class="md-p">see <a class="md-a" href="https://example.dev/x">https://example.dev/x</a>.</p>');

eq('unclosed backtick is literal', html('a `b'), '<p class="md-p">a `b</p>');
eq('escaped star is literal', html('a \\*b\\* c'), '<p class="md-p">a *b* c</p>');
eq('underscore inside a word does not open', html('call some_fn_name here'),
  '<p class="md-p">call some_fn_name here</p>');
eq('unclosed strong stays literal', html('a **b'), '<p class="md-p">a **b</p>');

// 6. the security refusals
eq('raw html is text', html('<img src=x onerror=alert(1)>'),
  '<p class="md-p">&lt;img src=x onerror=alert(1)&gt;</p>');
eq('script tag is text', html('<script>alert(1)</script>'),
  '<p class="md-p">&lt;script&gt;alert(1)&lt;/script&gt;</p>');
eq('javascript: link stays text', html('[click](javascript:alert(1))'),
  '<p class="md-p">click</p>');
eq('data: link stays text', html('[x](data:text/html,<b>)'), '<p class="md-p">x</p>');
eq('a destination with parentheses survives', html('[wiki](https://en.wikipedia.org/wiki/Foo_(bar))'),
  '<p class="md-p"><a class="md-a" href="https://en.wikipedia.org/wiki/Foo_(bar)">wiki</a></p>');
eq('a link title is not part of the href', html('[a](https://example.dev "title")'),
  '<p class="md-p"><a class="md-a" href="https://example.dev">a</a></p>');
eq('a url label is not linked inside a link', html('[https://example.dev](https://example.dev/x)'),
  '<p class="md-p"><a class="md-a" href="https://example.dev/x">https://example.dev</a></p>');
eq('relative link is kept', html('[docs](docs/WEBUI.md)'),
  '<p class="md-p"><a class="md-a" href="docs/WEBUI.md">docs</a></p>');
eq('an image becomes its link', html('![alt](https://example.dev/i.png)'),
  '<p class="md-p"><a class="md-a" href="https://example.dev/i.png">alt</a></p>');

// 7. incremental rendering: same DOM as a full render, and earlier nodes survive
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
  eq('streaming reaches the same DOM as one shot', domAt[domAt.length - 1], full);

  // A prefix that already closed its blocks must keep the exact nodes on screen.
  const prefix = '# Plan\n\nRead the file:';
  const first = render(prefix);
  const keptNodes = first.state.nodes.slice();
  first.state.source = prefix + '\n\nAnd more text arrives.';
  api.renderMarkdown(first.state, first.node);
  ok('the unchanged heading node is reused', first.state.nodes[0] === keptNodes[0]);
  ok('no node is re-created for a growing answer', first.state.nodes.length === 3,
    'nodes=' + first.state.nodes.length);
  ok('the earlier nodes are the very same objects',
    first.state.nodes[0] === keptNodes[0] && first.state.nodes[1] === keptNodes[1]);
  eq('the grown answer renders both blocks',
    ser(first.node),
    '<h1 class="md-h">Plan</h1><p class="md-p">Read the file:</p><p class="md-p">And more text arrives.</p>');
}

// 8. a mid-block prefix never throws and never leaves a stray node
{
  const { node, state } = render('');
  const partial = 'Some **bo';
  for (let i = 1; i <= partial.length; i += 1) {
    state.source = partial.slice(0, i);
    api.renderMarkdown(state, node);
  }
  ok('partial delimiters render literally', ser(node) === '<p class="md-p">Some **bo</p>', ser(node));
}

// 9. a link target is judged the way the browser will parse it
{
  // `a.href =` goes through the URL parser, which drops ASCII tab and newline anywhere and
  // leading/trailing C0 controls before it reads a scheme — so "java\nscript:" is a javascript:
  // URL to the browser even though it does not look like one here. The filter has to see the same
  // string the parser will, or a target like that lands in the transcript as a live link.
  eq('an ordinary link is kept', api.mdSafeUrl('https://example.dev/a?b=1'), 'https://example.dev/a?b=1');
  eq('a relative target is kept', api.mdSafeUrl('/docs/page'), '/docs/page');
  eq('a javascript: target is dropped', api.mdSafeUrl('javascript:alert(1)'), '');
  eq('a tab inside the scheme does not hide it', api.mdSafeUrl('java\tscript:alert(1)'), '');
  eq('a newline inside the scheme does not hide it', api.mdSafeUrl('java\nscript:alert(1)'), '');
  eq('a leading control character does not hide it', api.mdSafeUrl('\u0001javascript:alert(1)'), '');

  const smuggled = html('see [the docs](java\nscript:alert(document.domain))');
  ok('an inline link that hides a scheme stays text', !smuggled.includes('<a '), smuggled);
  eq('and the label is still shown', smuggled, '<p class="md-p">see the docs</p>');
}

// --------------------------------------------------------------- report
console.log(pass + ' assertions passed');
if (failures.length) {
  console.log('\n' + failures.length + ' FAILED:');
  failures.forEach((f) => console.log('  - ' + f));
  process.exit(1);
}
