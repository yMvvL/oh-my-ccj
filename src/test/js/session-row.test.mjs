/* The sidebar's session row, run without a browser.
 *
 * The row is built by `sessionRow` in web/app.js, a classic script with no
 * exports, so the function's own source is lifted out of the shipped file and
 * run against a ~20-line node stub. The assertions are therefore about the code
 * that ships, not about a copy of it — the same trick the markdown cases use.
 *
 * `node src/test/js/session-row.test.mjs` — also run, when a node is installed,
 * by com.ccj.agent.web.WebSessionRowTest, so `mvn test` covers it too. */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const APP = join(here, '..', '..', 'main', 'resources', 'web', 'app.js');
const src = readFileSync(APP, 'utf8');

// A classic script with no build step: parsing the whole thing is the only lint
// it gets, and it is the cheapest way to catch a stray brace in a file whose
// edit is almost always a small one.
new Function(src);

function slice(startMark, endMark) {
  const from = src.indexOf(startMark);
  const to = src.indexOf(endMark);
  if (from < 0 || to < 0 || to < from) {
    throw new Error('could not locate ' + startMark + ' .. ' + endMark);
  }
  return src.slice(from, to);
}

// The helpers the row reads (str, firstLine, clip, timeLabel, idStamp) live
// above transport; the row itself, and the duplicate-title pass
// it takes its stamps from, live in the sidebar tree section.
const code = slice('function str(', '// ------------------------------------------------------------- transport')
  + slice('function sessionRow(', '/* A workspace is a folder')
  + slice('function treeScroll(', '/* The server owns the list');

// --------------------------------------------------------------- node stub

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
    /* A click landing on this exact node, the way the browser reports it: the
     * target is the li only when nothing inside already handled it. */
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
// `window` is a browser global; the row reads it only to check for a selection.
let selectionText = '';
globalThis.window = { getSelection: () => ({ toString: () => selectionText }) };

/* Stopping a background turn: the row's stop button calls this, and the test
 * only needs to see that it was asked for the right session. */
let stopped = [];
const stopSession = (id) => { stopped.push(id); };

const api = new Function('el', 'str', 'tree', 'deleteControl', 'openWorkspaceSession', 'stopSession', 'dom',
  code + '\nreturn {sessionRow, repeatedStamps, idStamp, treeScroll};')(
  el, (v) => (v === undefined || v === null ? '' : String(v)), tree, deleteControl,
  openWorkspaceSession, stopSession, { wsTree: { parentNode: null } });

// ------------------------------------------------------------- node queries

function flat(n) {
  if (n.text !== undefined) { return n.text; }
  return n.kids.map(flat).join('');
}

function child(node_, cls) {
  return node_.kids.filter((k) => k.cls === cls)[0] || null;
}

function has(node_, cls) { return child(node_, cls) !== null; }

/* sessionRow returns the <li>; the clickable content is its button. */
function row(item, activeId, stamp) {
  const li = api.sessionRow('ws', item, 0, activeId || '', stamp);
  return { li: li, btn: child(li, 'session-item'), name: child(child(li, 'session-item'), 'session-name') };
}

function items(list) { return list.map((it, i) => Object.assign({ messageCount: 1, lastModified: '2026-09-13T00:00:00+08:00' }, it, { index: i })); }

/* The row's own click handler, taken off the li the way the browser would:
 * the event's target decides whether the row opens or a control inside it
 * already answered. */
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

// -------------------------------------------------------------------- cases

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

// 1. The first user message is the label; the timestamp id is not on screen.
{
  const r = row({ id: '20260913-001746-3377', title: 'rename the sidebar', preview: 'rename the sidebar' });
  eq('the row is labelled by the first request', flat(child(r.name, 'session-title')), 'rename the sidebar');
  ok('no id is drawn when the title is enough', !has(r.name, 'session-id'), flat(r.name));
  ok('the id is still in the tooltip', r.btn.title.indexOf('20260913-001746-3377') === 0, r.btn.title);
}

// 2. A title longer than the row is clipped, not wrapped forever.
{
  const long = 'x'.repeat(400);
  const r = row({ id: 'a-1', title: long });
  const shown = flat(child(r.name, 'session-title'));
  ok('a pasted problem does not become the row', shown.length <= 200 && shown.endsWith('…'), String(shown.length));
}

// 3. Nothing asked yet: the row falls back to something rather than an empty line.
{
  const r = row({ id: '20260913-010000-aaaa', title: '', preview: '(no messages)' });
  eq('an empty title shows the placeholder', flat(child(r.name, 'session-title')), '(no messages)');
}

// 4. Titles that collide are stamped; the ones that do not, are not.
{
  const list = items([
    { id: '20260912-095619-ffe0', title: 'add markdown rendering' },
    { id: '20260912-114300-d242', title: '你好' },
    { id: '20260913-001746-3377', title: '你好' },
    { id: '20260913-020000-ffff', title: 'third task' }
  ]);
  const stamps = api.repeatedStamps(list);
  eq('a unique title gets no stamp', stamps.get('20260912-095619-ffe0'), undefined);
  eq('the first of a colliding pair is stamped', stamps.get('20260912-114300-d242'), '20260912');
  eq('so is the second', stamps.get('20260913-001746-3377'), '20260913');
  eq('a later unique title gets no stamp', stamps.get('20260913-020000-ffff'), undefined);

  const stamped = row({ id: '20260913-001746-3377', title: '你好' }, '', stamps.get('20260913-001746-3377'));
  eq('the stamp renders beside the title', flat(stamped.name), '20260913你好');
  const plain = row({ id: '20260913-020000-ffff', title: 'third task' });
  eq('an unstamped row is the title alone', flat(plain.name), 'third task');
}

// 5. The active session is still marked, and the delete control still appears —
// neither of which is the point of the change, and both of which are easy to
// drop while rewriting the row.
{
  const r = row({ id: '20260913-001746-3377', title: 'x' }, '20260913-001746-3377');
  ok('the current row says so on <li>', r.li.cls.indexOf('current') >= 0, r.li.cls);
  ok('the row keeps the delete control', tree.deletes.length > 0);
}

// 6. The whole row opens. The strip the user pointed at — between the title and
// Delete — is a click on the li with nothing else as its target.
{
  const r = row({ id: '20260913-001746-3377', title: 'x' });
  eq('a click on the row itself opens the session', clickRow(r.li), 1);
  eq('and it opens the row it belongs to', opened[0], 'ws/20260913-001746-3377');
}

// 7. …but the controls inside it still mean what they say.
{
  const r = row({ id: '20260913-001746-3377', title: 'x' });
  const del = child(r.li, 'session-actions');
  eq('a click on Delete does not also open the session', clickRow(r.li, { target: del }), 0);
  eq('a click on the open button is the button\'s own business, not the row\'s',
    clickRow(r.li, { target: r.btn }), 0);
}

// 8. A modified click is not a plain open: the browser is being asked for
// something else (a new tab), and this page has no URL to give it.
{
  const r = row({ id: '20260913-001746-3377', title: 'x' });
  eq('ctrl-click is left to the browser', clickRow(r.li, { event: { ctrlKey: true } }), 0);
  eq('middle-click is not an open either', clickRow(r.li, { event: { button: 1 } }), 0);
}

// 9. Dragging across a title must not fire the row: a selection is a selection.
{
  const r = row({ id: '20260913-001746-3377', title: 'x' });
  selectionText = 'part of the title';
  eq('a click that ends a selection does not open the session', clickRow(r.li), 0);
  selectionText = '';
  eq('and a clean click still does', clickRow(r.li), 1);
}

// 10. A rebuild on its own (a finished turn) must not snap a long list back to
// the top: the scroll position is carried across, and clamped when the new list
// is shorter than the old one.
{
  // A list with room to scroll: 600px of content in a 300px window.
  const body = { scrollTop: 180, scrollHeight: 600, clientHeight: 300 };
  const holder = { wsTree: { parentNode: body } };
  const inner = new Function('el', 'str', 'tree', 'deleteControl', 'openWorkspaceSession', 'dom',
    code + '\nreturn {treeScroll};')(el, (v) => (v === undefined || v === null ? '' : String(v)),
    tree, deleteControl, openWorkspaceSession, holder);
  eq('the position is read back', inner.treeScroll(), 180);
  body.scrollTop = 0;
  eq('the position is restored after a rebuild', inner.treeScroll(180), 180);
  body.scrollHeight = 240;
  eq('a shorter list cannot be scrolled past its end', inner.treeScroll(180), 0);
}


// 11. A conversation running somewhere else is marked, and can be stopped from
// here: this is the whole point of the change — a turn left running in another
// session has to be findable and stoppable without opening it first.
{
  const r = row({ id: '20260913-001746-3377', title: 'long job', running: true });
  eq('the row says it is running', r.li.cls.indexOf('running') >= 0, true);
  eq('with a mark beside the title', has(r.name, 'session-running'), true);

  const idle = row({ id: '20260913-001746-3377', title: 'quiet' });
  eq('an idle row carries no mark', has(idle.name, 'session-running'), false);
  eq('and is not marked running', idle.li.cls.indexOf('running') >= 0, false);

  // The stop control is on the row, and it names the session it would stop.
  const actions = child(r.li, 'session-actions');
  const stop = child(actions, 'session-stop');
  eq('a running row offers a stop control', stop !== null, true);
  stopped = [];
  stop.fire('click', { stopPropagation() {} });
  eq('stopping it asks the server for this session', stopped.join(','), '20260913-001746-3377');
  eq('an idle row offers no stop control', child(child(idle.li, 'session-actions'), 'session-stop'), null);
}

if (failures.length) {
  console.error(failures.length + ' failed, ' + pass + ' passed\n');
  for (const f of failures) { console.error('  ✗ ' + f); }
  process.exit(1);
}
console.log('session row: ' + pass + ' cases passed');
