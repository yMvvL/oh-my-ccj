/* "Add workspace" without the name field, run without a browser.
 *
 * The behaviour lives in web/app.js, a classic script with no exports, so the
 * section that owns it is lifted out of the shipped file (between the two banner
 * comments below) and run against a small node stub. The assertions are
 * therefore about the code that ships, not about a copy of it — the same trick
 * the markdown and session-row cases use.
 *
 * What it pins down is the gesture: the click opens the desktop's chooser, a
 * chosen folder is added in one request, and that request carries a path and no
 * name — the name is the folder's and the server derives it. A dismissed
 * chooser adds nothing, and a chooser that cannot run at all leaves the form
 * open so a typed path still works.
 *
 * `node src/test/js/workspace-add.test.mjs` — also run, when a node is installed,
 * by com.ccj.agent.web.WebWorkspaceAddTest, so `mvn test` covers it too. */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const APP = join(here, '..', '..', 'main', 'resources', 'web', 'app.js');
const src = readFileSync(APP, 'utf8');

// A classic script with no build step: parsing the whole thing is the only lint
// it gets, and it is the cheapest way to catch a stray brace.
new Function(src);

const START = '  // ------------------------------------------- sidebar: adding a workspace';
const END = '  // --------------------------------------- provider / model / effort picker';
const from = src.indexOf(START);
const to = src.indexOf(END);
if (from < 0 || to < 0 || to < from) { throw new Error('could not locate the add-workspace section'); }
const code = src.slice(from, to);

// --------------------------------------------------------------- node stub

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

// The harness: one request queue, one set of messages, one tree payload.
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

function reset() {
  // Mutated in place: the lifted section was handed this exact object.
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

// 1. The click is the chooser: a chosen folder is added with a path and no name.
{
  reset();
  replies = [
    { path: '/home/you/projects/api' },
    payloadFor('api', '/home/you/projects/api')
  ];
  await api.addWorkspaceByPicking();

  ok('the click asks the server for a chooser', requests.length >= 1 && requests[0].url === '/api/workspaces/browse',
    JSON.stringify(requests));
  eq('the add request carries the path', requests[1].body.path, '/home/you/projects/api');
  eq('and no name at all — the folder names it', 'name' in requests[1].body, false);
  eq('the form is put away', dom.workspaceAddForm.hidden, true);
  eq('the trigger says it is closed', dom.workspaceAdd.attrs['aria-expanded'], 'false');
  eq('the outcome is reported', notes.length, 1);
  ok('the note names the workspace the server chose', notes[0].indexOf('api') >= 0, notes[0]);
  ok('the new node is opened', expanded.indexOf('api') >= 0, JSON.stringify(expanded));
  eq('the sidebar is pointed at it', accepted[0] && accepted[0].selected, 'api');
}

// 2. Two folders with the same last segment: the name the server chose is what
//    the page believes, not the last segment it could have guessed.
{
  reset();
  replies = [
    { path: '/home/you/work/api' },
    payloadFor('api-2', '/home/you/work/api')
  ];
  await api.addWorkspaceByPicking();

  ok('the suffixed name is used', notes[0].indexOf('api-2') >= 0, notes[0]);
  eq('and it is the node that opens', expanded.indexOf('api-2') >= 0, true);
}

// 3. Dismissing the chooser is not an add and not an error.
{
  reset();
  replies = [{ cancelled: true }];
  await api.addWorkspaceByPicking();

  eq('nothing was added', requests.length, 1);
  eq('an empty answer is not a failure', errors.length, 0);
  ok('the hint says the chooser was dismissed', dom.wsBrowseHint.textContent.indexOf('dismissed') >= 0,
    dom.wsBrowseHint.textContent);
  eq('and the form stayed out of the way', dom.workspaceAddForm.hidden, true);
}

// 4. A machine with no chooser: the refusal is shown and the form opens, so a
//    typed path is one click away rather than hidden behind the failure.
{
  reset();
  replies = [new Error('no desktop session available, so ccj cannot open a folder chooser')];
  await api.addWorkspaceByPicking();

  ok('the refusal is shown under the path field', dom.wsPathError.hidden === false, JSON.stringify(dom.wsPathError));
  ok('it is the chooser that could not run', dom.wsPathError.textContent.indexOf('desktop') >= 0,
    dom.wsPathError.textContent);
  eq('the form opens so the path can be typed', dom.workspaceAddForm.hidden, false);
  eq('the trigger says so', dom.workspaceAdd.attrs['aria-expanded'], 'true');
  eq('nothing was added', requests.length, 1);
}

// 5. Typing a path and submitting is the same request; the name is still the
//    server's to derive, and the directory is trimmed.
{
  reset();
  replies = [{ active: 'ws', workspaces: [{ name: 'scratch', path: '/home/you/scratch' }] }];
  await api.addWorkspace('  /home/you/scratch  ');

  eq('the trimmed path is what is sent', requests[0].body.path, '/home/you/scratch');
  eq('no name is invented by the page', 'name' in requests[0].body, false);
  eq('a path with no entry to point at is still reported', notes.length, 1);
  ok('without naming a node it does not claim one', notes[0].indexOf('press Use') >= 0, notes[0]);
}

// 6. An empty field and a refused add both land under the path field — but only
//    while the form is open, because that is when a field is on screen at all.
{
  reset();
  dom.workspaceAddForm.hidden = false;
  await api.addWorkspace('   ');
  eq('an empty path sends nothing', requests.length, 0);
  ok('and says what is missing', dom.wsPathError.textContent.indexOf('directory') >= 0, dom.wsPathError.textContent);

  reset();
  dom.workspaceAddForm.hidden = false;
  replies = [new Error('not a directory: /home/you/ws.txt')];
  await api.addWorkspace('/home/you/ws.txt');
  ok('a refusal is shown on the field', dom.wsPathError.hidden === false, JSON.stringify(dom.wsPathError));
  eq('and not as a sidebar alert', errors.length, 0);
}

// 7. A refused pick has no form on screen, so its message goes above the tree
//    instead of onto a field nobody can see.
{
  reset();
  replies = [new Error('that directory is already the workspace \'ws\'')];
  await api.addWorkspace('/home/you/ws');

  eq('nothing is written to a hidden field', dom.wsPathError.hidden, true);
  ok('the refusal is a sidebar alert instead', errors.length === 1, JSON.stringify(errors));
  ok('and it says which entry owns the directory', errors[0].indexOf('ws') >= 0, errors[0]);
}

// 8. Reading the added entry back: the last path segment is not the answer.
{
  const payload = payloadFor('api-2', '/home/you/work/api');
  eq('the entry is found by its directory', api.addedWorkspace(payload, '/home/you/work/api/'), 'api-2');
  eq('an unknown directory names nothing', api.addedWorkspace(payload, '/home/you/elsewhere'), '');
}

// ------------------------------------------------------------------- result

if (failures.length) {
  console.error('workspace-add: ' + failures.length + ' failed, ' + pass + ' passed');
  failures.forEach((f) => console.error('  ✗ ' + f));
  process.exit(1);
}
console.log('workspace-add: ' + pass + ' passed');
