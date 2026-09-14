/* What the page does when a compaction lands, run without a browser.
 *
 * Two bugs this pins down, both of which survived a server-side test pass because
 * curl cannot see a transcript:
 *
 *  1. `loadHistory` *appends* — it is written for a session switch, where the id
 *     changed and `noteSession` cleared the pane for it. A compaction keeps the
 *     same id, so fetching history after one drew the kept exchanges a second time
 *     underneath the ones already on screen.
 *  2. The summary itself was never drawn. It is a `summary` event now, but a page
 *     reloaded after a compaction showed the kept tails with no sign of what had
 *     happened — the exact thing a user checks to know the compaction worked.
 *
 * So what is asserted here is the order `onCompacted` does things in, and that the
 * summary event is rendered at all. The functions are lifted out of the shipped
 * file and run against a small node stub, the same trick the other cases use.
 *
 * `node src/test/js/compaction.test.mjs` — also run, when a node is installed, by
 * com.ccj.agent.web.WebCompactTest, so `mvn test` covers it too. */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import assert from 'node:assert/strict';

const here = dirname(fileURLToPath(import.meta.url));
const APP = join(here, '..', '..', 'main', 'resources', 'web', 'app.js');
const src = readFileSync(APP, 'utf8');

new Function(src);   // the only lint a build-step-less file gets

let passed = 0;
/* Awaiting the body matters: `onCompacted` awaits `loadHistory`, so a callback
 * left un-awaited keeps running after the next case has already cleared `calls`
 * — and the assertion then reads the previous case's notice. That is exactly the
 * kind of confusion this file exists to prevent, so it is not happening here. */
async function check(name, fn) {
  await fn();
  passed++;
  console.log(`  ok  ${name}`);
}

/* ---------------------------------------------------------------- the stub */

/* A DOM just real enough for the summary card: element creation, appending, and
 * the few properties `appendSummary` touches. */
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

/* `el(tag, className, text)` is the page's own helper; the slice below is run
 * with this in scope. */
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

/* The transcript, plus the calls `appendSummary` and `onCompacted` make. */
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

/* `clearTranscript` as the page defines it, minus the queue bookkeeping the stub
 * has no notion of: what matters is that it empties the pane. */
function clearTranscript() {
  transcriptChildren.length = 0;
  calls.push(['clear']);
}

/* The real loadHistory appends whatever the server returns; the stub records the
 * call so the ordering can be checked. */
async function loadHistory(sessionId) {
  calls.push(['loadHistory', sessionId]);
}

/* ------------------------------------------------------- the slice under test */

/* `appendSummary` and `onCompacted`, exactly as shipped. Both are lifted by
 * marker rather than by line number so a reformat does not silently test
 * something else; a marker that has gone missing fails loudly below. */
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

/* The two helpers the slices call, in the scope they expect. */
const runner = new Function(
  'el', 'fmtCount', 'str', 'dom', 'state', 'appendNotice', 'clearTranscript', 'loadHistory',
  'scrollToBottom',
  `${appendSummarySrc}\n${onCompactedSrc}\nreturn { appendSummary, onCompacted };`,
);
const page = runner(
  el, fmtCount, str, dom, state, appendNotice, clearTranscript, loadHistory, scrollToBottom,
);

/* ------------------------------------------------------------------- cases */

await check('a summary event draws a card, collapsed, with its count', () => {
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
  assert.ok(texts.some((t) => t.includes('Compacted conversation')), texts.join('|'));
  assert.ok(texts.some((t) => t.includes('13 messages summarised')), texts.join('|'));
  // The summary text itself is in the body, unescaped-in by textContent.
  const body = card.children[1];
  const rendered = body.children.find((c) => c.className === 'ev-summary-text');
  assert.ok(rendered, 'the summary text is not in the card');
  assert.equal(rendered.textContent, '1. Goal — fix the parser.');
  // And it says how to get a detail back, which is the point of naming the file.
  const note = body.children.find((c) => c.className === 'ev-summary-note');
  assert.ok(note.textContent.includes('read it back'), note.textContent);
});

await check('an empty summary draws nothing rather than an empty card', () => {
  transcriptChildren.length = 0;
  assert.equal(page.appendSummary({ type: 'summary', text: '' }), null);
  assert.equal(transcriptChildren.length, 0);
});

await check('a compaction clears the transcript before fetching the new history', async () => {
  // The bug: loadHistory appends, so without the clear the kept exchanges appear twice.
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

await check('the notice says what changed, what it saved, and where the original is', async () => {
  calls.length = 0;
  await page.onCompacted({
    summarised: 13, kept: 63, beforeTokens: 30088, afterTokens: 27885, savedPercent: 61,
    source: '/home/me/.oh-my-ccj/sessions/abc.jsonl',
  });

  const notice = calls.find((c) => c[0] === 'notice')[1];
  assert.ok(notice.includes('13 earlier message(s)'), notice);
  assert.ok(notice.includes('63 kept verbatim'), notice);
  assert.ok(notice.includes('61%'), `the saving is the number the user cares about: ${notice}`);
  assert.ok(notice.includes('30088') && notice.includes('27885'), notice);
  assert.ok(notice.includes('estimated'),
    `an estimate must not read as a measurement: ${notice}`);
  assert.ok(notice.includes('/home/me/.oh-my-ccj/sessions/abc.jsonl'), notice);
});

await check('a compaction with no reported saving still renders', async () => {
  // Defensive: an older server, or a shape that lost savedPercent, must not
  // produce "undefined%" in the transcript.
  calls.length = 0;
  await page.onCompacted({ summarised: 2, kept: 8, source: 'x.jsonl' });
  const notice = calls.find((c) => c[0] === 'notice')[1];
  assert.ok(!notice.includes('undefined'), notice);
  assert.ok(!notice.includes('NaN'), notice);
  assert.ok(notice.includes('2 earlier message(s)'), notice);
});

console.log(`compaction: ${passed} passed`);
