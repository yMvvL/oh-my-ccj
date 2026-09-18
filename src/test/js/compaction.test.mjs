/* 一次压缩落地时页面做什么，不靠浏览器运行。
 *
 * 它钉住两个 bug，两个都熬过了一轮服务器端测试，因为 curl 看不见转录：
 *
 *  1. `loadHistory` 是*追加*式的 —— 它是为会话切换写的，那种情况下 id 变了、`noteSession`
 *     已经为它清过屏。压缩保持同一个 id，所以压缩之后取历史会把保留下来的往来在屏幕上
 *     已有的那些下面再画一遍。
 *  2. 摘要本身从来没被画出来过。它现在是一个 `summary` 事件，但一次压缩之后重新加载的
 *     页面只显示保留下来的尾部，没有任何迹象说明发生过什么 —— 而那正是用户用来确认压缩
 *     生效的东西。
 *
 * 所以这里断言的是 `onCompacted` 做事的顺序，以及摘要事件到底有没有被渲染。这些函数是从
 * 发布的文件里抽出来、跑在一个小小的 node 桩上的，其他用例用的是同一个手法。
 *
 * `node src/test/js/compaction.test.mjs` —— 装了 node 时也会由
 * com.ccj.agent.web.WebCompactTest 运行，所以 `mvn test` 也覆盖它。 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import assert from 'node:assert/strict';

const here = dirname(fileURLToPath(import.meta.url));
const APP = join(here, '..', '..', 'main', 'resources', 'web', 'app.js');
const src = readFileSync(APP, 'utf8');

new Function(src);   // 没有构建步骤的文件，只能这样 lint

let passed = 0;
/* 等待函数体这件事很重要：`onCompacted` 会等待 `loadHistory`，所以一个没被等到的回调
 * 会在下一个用例已经清空 `calls` 之后继续跑 —— 而断言随后读到的是上一个用例的通知。
 * 那正是这个文件存在的意义所在的那种混乱，所以这里不会发生。 */
async function check(name, fn) {
  await fn();
  passed++;
  console.log(`  ok  ${name}`);
}

/* ------------------------------------------------------------------ node 桩 */

/* 一个刚好够摘要卡片用的 DOM：元素创建、追加，以及 `appendSummary` 会碰的那几个属性。 */
function makeElement(tag) {
  return {
    tagName: tag,
    children: [],
    className: '',
    textContent: '',
    hidden: false,
    open: false,
    style: {},
    appendChild(child) { this.children.push(child); return child; },
    insertBefore(child) { this.children.unshift(child); return child; },
    setAttribute() {},
    classList: { add() {}, remove() {}, toggle() {} },
    get firstChild() { return this.children[0] || null; },
    get parentNode() { return null; },
  };
}

/* `el(tag, className, text)` 是页面自己的辅助函数；下面那段切片就是在它有作用域的
 * 情况下跑的。 */
function el(tag, className, text) {
  const node = makeElement(tag);
  if (className) { node.className = className; }
  if (text !== undefined && text !== null) { node.textContent = String(text); }
  return node;
}

function fmtCount(value) {
  const n = Number(value);
  return isFinite(n) ? String(n) : '—';
}

function str(value) {
  return value === undefined || value === null ? '' : String(value);
}

/* 转录，以及 `appendSummary` 和 `onCompacted` 会做的调用。 */
const transcriptChildren = [];
const calls = [];
const dom = {
  transcript: {
    children: transcriptChildren,
    appendChild(node) { transcriptChildren.push(node); return node; },
    insertBefore(node) { transcriptChildren.unshift(node); return node; },
    get firstChild() { return transcriptChildren[0] || null; },
    set textContent(value) { if (value === '') { transcriptChildren.length = 0; } },
    get textContent() { return ''; },
  },
};
const state = { status: { sessionId: 'abc' }, sessionId: 'abc' };

function appendNotice(text) { calls.push(['notice', text]); }
function scrollToBottom() {}

/* 按页面定义来的 `clearTranscript`，去掉了桩没有概念的队列记账：重要的是它清空了
 * 面板。 */
function clearTranscript() {
  transcriptChildren.length = 0;
  calls.push(['clear']);
}

/* 真正的 loadHistory 会追加服务器返回的任何东西；桩只记录这次调用，好让顺序能被
 * 检查。 */
async function loadHistory(sessionId) {
  calls.push(['loadHistory', sessionId]);
}

/* ---------------------------------------------------------------- 被测代码切片 */

/* `appendSummary` 和 `onCompacted`，与发布的一模一样。两者都按标记而不是按行号抽取，
 * 这样一次重排版不会悄悄测到别的东西；标记不见了会在下面大声失败。 */
function sliceFrom(startMark, endMark, name) {
  const from = src.indexOf(startMark);
  const to = src.indexOf(endMark, from);
  assert.ok(from >= 0, `start marker not found in app.js: ${name}`);
  assert.ok(to > from, `end marker not found in app.js: ${name}`);
  return src.slice(from, to);
}

const appendSummarySrc = sliceFrom(
  '  function appendSummary(ev) {',
  '  function assistantNode(block, kind) {',
  'appendSummary',
);
const onCompactedSrc = sliceFrom(
  '  async function onCompacted(ev) {',
  '  /* The button is a request like any other',
  'onCompacted',
);

/* 切片会调用的两个辅助函数，放在它们期待的作用域里。 */
const runner = new Function(
  'el', 'fmtCount', 'str', 'dom', 'state', 'appendNotice', 'clearTranscript', 'loadHistory',
  'scrollToBottom',
  `${appendSummarySrc}\n${onCompactedSrc}\nreturn { appendSummary, onCompacted };`,
);
const page = runner(
  el, fmtCount, str, dom, state, appendNotice, clearTranscript, loadHistory, scrollToBottom,
);

/* -------------------------------------------------------------------- 用例 */

await check('摘要事件画出一张折叠的卡片，带计数', () => {
  transcriptChildren.length = 0;
  const card = page.appendSummary({
    type: 'summary',
    text: '1. Goal — fix the parser.',
    covers: 13,
    source: '/home/me/.oh-my-ccj/sessions/abc.jsonl',
  });
  assert.ok(card, 'appendSummary returned nothing');
  assert.equal(card.tagName, 'details', 'a summary is a fold, not a paragraph');
  assert.equal(card.open, false, 'collapsed by default: it is long and rarely the target');
  const head = card.children[0];
  assert.equal(head.tagName, 'summary');
  const texts = head.children.map((c) => c.textContent);
  assert.ok(texts.some((t) => t.includes('已压缩的会话')), texts.join('|'));
  assert.ok(texts.some((t) => t.includes('13 条消息已摘要')), texts.join('|'));
  // 摘要正文本身在 body 里，由 textContent 原样放进去。
  const body = card.children[1];
  const rendered = body.children.find((c) => c.className === 'ev-summary-text');
  assert.ok(rendered, 'the summary text is not in the card');
  assert.equal(rendered.textContent, '1. Goal — fix the parser.');
  // 而且它说明了怎么把某个细节找回来，这正是点名那个文件的意义。
  const note = body.children.find((c) => c.className === 'ev-summary-note');
  assert.ok(note.textContent.includes('读回'), note.textContent);
});

await check('空摘要什么都不画，而不是画一张空卡片', () => {
  transcriptChildren.length = 0;
  assert.equal(page.appendSummary({ type: 'summary', text: '' }), null);
  assert.equal(transcriptChildren.length, 0);
});

await check('压缩在取新历史之前先清空转录', async () => {
  // 那个 bug：loadHistory 是追加式的，所以不清屏的话保留下来的往来会出现两次。
  transcriptChildren.length = 0;
  transcriptChildren.push(makeElement('div'), makeElement('div'));
  calls.length = 0;

  await page.onCompacted({
    summarised: 13,
    kept: 63,
    beforeTokens: 30088,
    afterTokens: 27885,
    savedPercent: 61,
    generation: 1,
    source: '/home/me/.oh-my-ccj/sessions/abc.jsonl',
  });

  const order = calls.map((c) => c[0]);
  assert.deepEqual(order, ['clear', 'notice', 'loadHistory'],
    `the pane must be emptied before the new history is appended: ${JSON.stringify(calls)}`);
  assert.equal(transcriptChildren.length, 0,
    'and the clear must actually have emptied it, not just been called');
  assert.equal(calls[2][1], 'abc', 'history is re-fetched for the same session id');
});

await check('通知说明改了什么、省了多少，以及原件在哪里', async () => {
  calls.length = 0;
  await page.onCompacted({
    summarised: 13, kept: 63, beforeTokens: 30088, afterTokens: 27885, savedPercent: 61,
    source: '/home/me/.oh-my-ccj/sessions/abc.jsonl',
  });

  const notice = calls.find((c) => c[0] === 'notice')[1];
  assert.ok(notice.includes('13 条较早的消息'), notice);
  assert.ok(notice.includes('63 条原样保留'), notice);
  assert.ok(notice.includes('61%'), `the saving is the number the user cares about: ${notice}`);
  assert.ok(notice.includes('30088') && notice.includes('27885'), notice);
  assert.ok(notice.includes('估算'),
    `an estimate must not read as a measurement: ${notice}`);
  assert.ok(notice.includes('/home/me/.oh-my-ccj/sessions/abc.jsonl'), notice);
});

await check('没有报告节省量的压缩也照样渲染', async () => {
  // 防御性：一个更老的服务器，或者一个丢了 savedPercent 的形状，不能在转录里
  // 产生 "undefined%"。
  calls.length = 0;
  await page.onCompacted({ summarised: 2, kept: 8, source: 'x.jsonl' });
  const notice = calls.find((c) => c[0] === 'notice')[1];
  assert.ok(!notice.includes('undefined'), notice);
  assert.ok(!notice.includes('NaN'), notice);
  assert.ok(notice.includes('2 条较早的消息'), notice);
});

console.log(`compaction: ${passed} passed`);
