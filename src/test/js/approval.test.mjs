/* Outstanding approvals, run without a browser.
 *
 * The behaviour lives in web/app.js, a classic script with no exports, so the two
 * functions that own it are lifted out of the shipped file and run against a
 * small node stub. The assertions are therefore about the code that ships, not
 * about a copy of it — the same trick the markdown, session-row and
 * add-workspace cases use.
 *
 * What it pins down is the bug this exists for: a turn waiting for approval used
 * to lose its prompt as soon as the user looked at another conversation, leaving
 * abort as the only way out. An approval is a request blocked in memory on the
 * server, not a message, so replaying a conversation cannot bring it back — the
 * server reports what is outstanding and the page draws it again. These cases are
 * about that redrawing, and about the two ways it can go wrong: a prompt for the
 * wrong conversation, and a stale prompt that can never be answered.
 *
 * `node src/test/js/approval.test.mjs` — also run, when a node is installed, by
 * com.ccj.agent.web.WebApprovalTest, so `mvn test` covers it too. */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const APP = join(here, '..', '..', 'main', 'resources', 'web', 'app.js');
const src = readFileSync(APP, 'utf8');

new Function(src);   // the only lint a build-step-less file gets

const startMark = '  function renderApproval(';
const endMark = '  function onApprovalClosed(';
const from = src.indexOf(startMark);
const to = src.indexOf(endMark);
if (from < 0 || to < 0 || to < from) {
  throw new Error('could not locate renderApproval( .. onApprovalClosed(');
}
// `approvalRecord` is the one-liner both functions read through, and it lives just
// above them; taken from the shipped file rather than restated here.
const recordStart = src.indexOf('  function approvalRecord(');
const recordEnd = src.indexOf('\n', recordStart);
const code = src.slice(recordStart, recordEnd + 1) + '\n' + src.slice(from, to);

// --------------------------------------------------------------- node stub

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

// Everything renderApproval and syncApprovals touch from the outside.
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

// 1. A request from the server is drawn, once.
{
  reset();
  api.syncApprovals([{ id: 'ap-1', title: 'bash', detail: 'echo hi' }]);
  eq('one prompt is drawn', appended.length, 1);
  eq('with its title', titles(), ['bash']);
  eq('and it is pending', appended[0].classList.contains('pending'), true);
  eq('and registered so it can be answered', state.approvals.has('ap-1'), true);

  // The same request arriving again must not produce a second prompt: the page
  // asks for status repeatedly, and a duplicate card would ask the same question
  // twice.
  api.syncApprovals([{ id: 'ap-1', title: 'bash', detail: 'echo hi' }]);
  eq('the same request is not drawn twice', appended.length, 1);
}

// 2. Answering one sends the answer and closes the prompt.
{
  reset();
  api.syncApprovals([{ id: 'ap-1', title: 'bash', detail: 'echo hi' }]);
  // The primary button is "Allow"; the danger one beside it is "Deny".
  const actions = appended[0].querySelector('.approval-actions');
  const approve = actions.children.filter((c) => c.classList.contains('primary'))[0];
  approve.fire('click');
  eq('the answer goes to the server', answered, [{ id: 'ap-1', choice: 'once' }]);
  eq('and the prompt is closed', appended[0].classList.contains('pending'), false);
  eq('and it is marked approved', appended[0].classList.contains('approved'), true);
}

// 2b. The four answers are four different answers, and each one says what it means.
{
  reset();
  api.syncApprovals([{ id: 'ap-9', title: 'bash', detail: 'mvn test' }]);
  const byText = (label) => appended[0].querySelector('.approval-actions').children
    .filter((c) => c.textContent === label)[0];
  byText('Allow for session').fire('click');
  eq('session allow is sent as itself', answered, [{ id: 'ap-9', choice: 'session' }]);
  const state = appended[0].querySelector('.approval-state');
  eq('and the card says so', state.textContent, 'Allowed for this session');

  reset();
  api.syncApprovals([{ id: 'ap-10', title: 'write', detail: 'src/Foo.java' }]);
  appended[0].querySelector('.approval-actions').children
    .filter((c) => c.textContent === 'Always allow')[0].fire('click');
  eq('always allow is sent as itself', answered, [{ id: 'ap-10', choice: 'always' }]);

  reset();
  api.syncApprovals([{ id: 'ap-11', title: 'bash', detail: 'rm -rf /' }]);
  appended[0].querySelector('.approval-actions').children
    .filter((c) => c.classList.contains('danger'))[0].fire('click');
  eq('deny is sent as deny', answered, [{ id: 'ap-11', choice: 'deny' }]);
  eq('and the card is marked denied', appended[0].classList.contains('denied'), true);
}

// 3. A request the server no longer knows about is closed, not left hanging.
// This is the "answered in another tab, or timed out" case: a card that can never
// be answered is worse than one that says it is over.
{
  reset();
  api.syncApprovals([{ id: 'ap-1', title: 'bash', detail: '' }]);
  api.syncApprovals([]);
  eq('the stale prompt is closed', appended[0].classList.contains('pending'), false);
  eq('and says it is no longer waiting',
    appended[0].querySelector('.approval-state').textContent.indexOf('No longer waiting') >= 0, true);
}

// 4. A request for another conversation is never drawn here. The status of the
// conversation on screen carries only its own, so an empty list means this page
// must show nothing — which is what stops one conversation's question appearing
// in another's transcript.
{
  reset();
  api.syncApprovals([]);
  eq('nothing is drawn for a conversation with no requests', appended.length, 0);

  api.syncApprovals([{ id: 'ap-9', title: 'write', detail: 'x' }]);
  eq('and a later request for it is drawn', titles(), ['write']);
}

// 5. Several requests at once are all drawn: a turn can ask for two tools.
{
  reset();
  api.syncApprovals([
    { id: 'ap-1', title: 'bash', detail: 'a' },
    { id: 'ap-2', title: 'write', detail: 'b' },
  ]);
  eq('both prompts are drawn', titles(), ['bash', 'write']);
  eq('and both can be answered', state.approvals.size, 2);
}

// 6. A missing or malformed payload changes nothing rather than throwing: this
// runs from a status handler, and a crash there would take the page down.
{
  reset();
  api.syncApprovals(undefined);
  api.syncApprovals(null);
  api.syncApprovals('nonsense');
  api.syncApprovals([{ title: 'no id' }]);
  eq('nothing is drawn without an id', appended.length, 0);
}

if (failures.length) {
  console.error(failures.length + ' failed, ' + pass + ' passed\n');
  for (const f of failures) { console.error('  ✗ ' + f); }
  process.exit(1);
}
console.log('approval: ' + pass + ' assertions passed');
