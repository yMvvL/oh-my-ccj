/* Replaying a long conversation in two passes, run without a browser.
 *
 * The behaviour lives in web/app.js, a classic script with no exports, so the
 * function that splits a history into what is shown now and what is filled in
 * later is lifted out of the shipped file and run against a small node stub —
 * the same trick the markdown, session-row, add-workspace and approval cases use.
 *
 * What it pins down is the reason it exists: switching back to a long
 * conversation used to rebuild every tool card at once, which blocked the thread
 * for long enough that the turn running behind it looked frozen. The split has to
 * happen at a boundary the renderer already treats as one — a user message starts
 * a new block — or a half-rendered assistant turn would be finished by the wrong
 * pass, putting reasoning after the answer it preceded.
 *
 * `node src/test/js/replay.test.mjs` — also run, when a node is installed, by
 * com.ccj.agent.web.WebReplayTest, so `mvn test` covers it too. */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const APP = join(here, '..', '..', 'main', 'resources', 'web', 'app.js');
const src = readFileSync(APP, 'utf8');

new Function(src);   // the only lint a build-step-less file gets

const startMark = '  const REPLAY_TAIL_EVENTS';
const endMark = '  /* Replay goes through the same dispatch()';
const from = src.indexOf(startMark);
const to = src.indexOf(endMark);
if (from < 0 || to < 0 || to < from) {
  throw new Error('could not locate the replay split helpers');
}
const code = src.slice(from, to);

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

const api = new Function('str', code + '\nreturn {REPLAY_TAIL_EVENTS, splitReplay};')(
  (v) => (v === undefined || v === null ? '' : String(v)));

function ev(type, extra) {
  return Object.assign({ type: type }, extra || {});
}
/** One exchange: the user's line, a turn that called a tool, and the result. */
function exchange(n) {
  return [
    ev('user', { text: 'prompt ' + n }),
    ev('tool', { id: 'c' + n, name: 'bash', state: 'start' }),
    ev('tool', { id: 'c' + n, name: 'bash', state: 'end', ok: true, output: 'out' }),
    ev('text', { delta: 'answer ' + n }),
  ];
}

const LIMIT = api.REPLAY_TAIL_EVENTS;

// 1. A conversation that fits is not split at all: the common case must not change.
{
  const events = exchange(1).concat(exchange(2));
  const split = api.splitReplay(events);
  eq('a short history is rendered in one pass', split.tail, events);
  eq('and nothing is left over', split.head.length, 0);
  eq('and it says nothing about loading', split.hidden, 0);
}

// 2. A long one is split, and the newest events are the ones shown.
{
  let events = [];
  for (let i = 0; i < 200; i++) { events = events.concat(exchange(i)); }
  const split = api.splitReplay(events);
  ok('the tail is bounded', split.tail.length <= LIMIT + 4, 'tail=' + split.tail.length);
  ok('the head holds the rest', split.head.length > 0, 'head=' + split.head.length);
  eq('nothing is lost', split.head.length + split.tail.length, events.length);
  // The last event of the conversation is the last event shown: the newest content
  // is what the user came back for.
  eq('the newest event is shown', split.tail[split.tail.length - 1], events[events.length - 1]);
  eq('the split is order preserving',
    split.head.concat(split.tail), events);
}

// 3. The split lands on a user message, so a turn is never rendered by both passes.
{
  let events = [];
  for (let i = 0; i < 200; i++) { events = events.concat(exchange(i)); }
  const split = api.splitReplay(events);
  const first = split.tail[0];
  eq('the tail starts a new exchange', first.type, 'user');
  // And the head ends where the next one begins, which is the same boundary.
  const lastHead = split.head[split.head.length - 1];
  eq('the head stops just before it', lastHead.type, 'text');
}

// 4. A single exchange longer than the limit is still rendered whole: an answer cut
// in half is worse than a slow screen, and the events belong to one turn.
{
  const events = [ev('user', { text: 'one enormous turn' })];
  for (let i = 0; i < LIMIT * 3; i++) {
    events.push(ev('text', { delta: 'chunk ' + i }));
  }
  const split = api.splitReplay(events);
  eq('the oversized exchange is kept whole', split.head.length, 0);
  eq('and all of it is shown', split.tail.length, events.length);
}

// 5. Trailing events that belong to no user message (a system note, say) still
// reach the tail rather than the head: everything after the last boundary is
// "now".
{
  const events = [ev('user', { text: 'hi' }), ev('text', { delta: 'hello' })];
  const split = api.splitReplay(events);
  eq('a short conversation is untouched', split.head.length, 0);
  eq('and shown in order', split.tail.length, 2);
}

// 6. The hidden count is what the page tells the user, so it has to be the number
// of events that are not on screen — not the number of exchanges, and not zero.
{
  let events = [];
  for (let i = 0; i < 200; i++) { events = events.concat(exchange(i)); }
  const split = api.splitReplay(events);
  eq('the hidden count is the head length', split.hidden, split.head.length);
  ok('and it is positive for a long history', split.hidden > 0, 'hidden=' + split.hidden);
}

// 7. Empty and malformed input is not an error: this runs from a fetch handler.
{
  eq('an empty history has an empty tail', api.splitReplay([]).tail.length, 0);
  eq('and no head', api.splitReplay([]).head.length, 0);
  // A null entry cannot be dispatched; it is dropped rather than crashing the replay.
  const withNull = [ev('user', { text: 'a' }), null, ev('text', { delta: 'b' })];
  const split = api.splitReplay(withNull);
  eq('a null entry is dropped', split.tail.every(function (e) { return !!e; }), true);
}

// 8. The limit is a real bound, and a sensible one for a page that has to stay
// responsive: enough to read, small enough to draw in one frame.
{
  ok('the limit is a positive number', LIMIT > 0, 'limit=' + LIMIT);
  ok('and bounded so one pass stays cheap', LIMIT <= 1000, 'limit=' + LIMIT);
}

if (failures.length) {
  console.error(failures.length + ' failed, ' + pass + ' passed\n');
  for (const f of failures) { failures.push(f); }
  for (const f of failures) { console.error('  ✗ ' + f); }
  process.exit(1);
}
console.log('replay: ' + pass + ' assertions passed');
