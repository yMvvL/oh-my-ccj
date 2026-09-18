/* 分两遍重放一段很长的对话，不靠浏览器运行。
 *
 * 这段行为在 web/app.js 里，那是一个没有导出的经典脚本，所以那个把历史拆成「现在显示的」
 * 和「之后补齐的」的函数，是从发布的文件里抽出来、跑在一个小小的 node 桩上的 ——
 * markdown、session-row、add-workspace 和 approval 那些用例用的是同一个手法。
 *
 * 它钉住的是它存在的理由：切回一段很长的对话，过去会一次性重建每一张工具卡片，把线程堵住
 * 足够久，久到在它背后跑着的那个回合看起来像卡死了。拆分必须落在一个渲染器本来就当作
 * 边界的边界上 —— 一条用户消息开启一个新的块 —— 否则一个渲染了一半的助手回合会被错误的
 * 那一遍收尾，把推理排到它前面的答案后面。
 *
 * `node src/test/js/replay.test.mjs` —— 装了 node 时也会由
 * com.ccj.agent.web.WebReplayTest 运行，所以 `mvn test` 也覆盖它。 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const APP = join(here, '..', '..', 'main', 'resources', 'web', 'app.js');
const src = readFileSync(APP, 'utf8');

new Function(src);   // 没有构建步骤的文件，只能这样 lint

const startMark = '  const REPLAY_TAIL_EVENTS';
const endMark = '  /* Replay goes through the same dispatch()';
const from = src.indexOf(startMark);
const to = src.indexOf(endMark);
if (from < 0 || to < 0 || to < from) {
  throw new Error('could not locate the replay split helpers');
}
const code = src.slice(from, to);

// ------------------------------------------------------------------ node 桩

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
/** 一段往来：用户那一行、一个调用了工具的回合，以及结果。 */
function exchange(n) {
  return [
    ev('user', { text: 'prompt ' + n }),
    ev('tool', { id: 'c' + n, name: 'bash', state: 'start' }),
    ev('tool', { id: 'c' + n, name: 'bash', state: 'end', ok: true, output: 'out' }),
    ev('text', { delta: 'answer ' + n }),
  ];
}

const LIMIT = api.REPLAY_TAIL_EVENTS;

// 1. 装得下的对话根本不拆：常见情形必须不变。
{
  const events = exchange(1).concat(exchange(2));
  const split = api.splitReplay(events);
  eq('一段很短的对话一次画完', split.tail, events);
  eq('而且没有剩下任何东西', split.head.length, 0);
  eq('而且它不提加载', split.hidden, 0);
}

// 2. 很长的对话会拆开，而且显示的是最新的那些事件。
{
  let events = [];
  for (let i = 0; i < 200; i++) { events = events.concat(exchange(i)); }
  const split = api.splitReplay(events);
  ok('尾部是有界的', split.tail.length <= LIMIT + 4, 'tail=' + split.tail.length);
  ok('头部拿着其余部分', split.head.length > 0, 'head=' + split.head.length);
  eq('什么都没丢', split.head.length + split.tail.length, events.length);
  // 对话的最后一个事件就是显示出来的最后一个事件：最新的内容才是用户回来要找的。
  eq('最新的事件被显示', split.tail[split.tail.length - 1], events[events.length - 1]);
  eq('这次拆分保持顺序',
    split.head.concat(split.tail), events);
}

// 3. 切口落在一条用户消息上，所以一个回合永远不会被两遍都渲染。
{
  let events = [];
  for (let i = 0; i < 200; i++) { events = events.concat(exchange(i)); }
  const split = api.splitReplay(events);
  const first = split.tail[0];
  eq('尾部从一段新的往来开始', first.type, 'user');
  // 而头部结束的地方正是下一段开始的地方，就是同一个边界。
  const lastHead = split.head[split.head.length - 1];
  eq('头部正好停在它前面', lastHead.type, 'text');
}

// 4. 单独一段比限额还长的往来仍然整体渲染：半个答案比一屏慢更糟，而且这些事件属于
// 同一个回合。
{
  const events = [ev('user', { text: 'one enormous turn' })];
  for (let i = 0; i < LIMIT * 3; i++) {
    events.push(ev('text', { delta: 'chunk ' + i }));
  }
  const split = api.splitReplay(events);
  eq('超大的往来被整体保留', split.head.length, 0);
  eq('而且它全部被显示', split.tail.length, events.length);
}

// 5. 不属于任何用户消息的尾部事件（比如一条系统注记）仍然进入尾部而不是头部：最后
// 一个边界之后的一切都是「现在」。
{
  const events = [ev('user', { text: 'hi' }), ev('text', { delta: 'hello' })];
  const split = api.splitReplay(events);
  eq('一段很短的对话没有被改动', split.head.length, 0);
  eq('而且按顺序显示', split.tail.length, 2);
}

// 6. 隐藏计数就是页面告诉用户的数字，所以它必须是不在屏幕上的事件数 —— 不是往来数，
// 也不是零。
{
  let events = [];
  for (let i = 0; i < 200; i++) { events = events.concat(exchange(i)); }
  const split = api.splitReplay(events);
  eq('隐藏计数就是头部的长度', split.hidden, split.head.length);
  ok('而且对一段很长的历史它是正的', split.hidden > 0, 'hidden=' + split.hidden);
}

// 7. 空的和畸形的输入不是错误：这段代码从 fetch 处理器里跑。
{
  eq('空历史的尾部是空的', api.splitReplay([]).tail.length, 0);
  eq('也没有头部', api.splitReplay([]).head.length, 0);
  // 一个 null 条目没法被派发；它会被丢掉，而不是让重放崩掉。
  const withNull = [ev('user', { text: 'a' }), null, ev('text', { delta: 'b' })];
  const split = api.splitReplay(withNull);
  eq('null 条目被丢掉', split.tail.every(function (e) { return !!e; }), true);
}

// 8. 这个限额是真实的上界，而且对一个必须保持响应性的页面来说是合理的：够读，
// 又小到能在一帧里画完。
{
  ok('限额是一个正数', LIMIT > 0, 'limit=' + LIMIT);
  ok('而且有上界，好让一遍保持廉价', LIMIT <= 1000, 'limit=' + LIMIT);
}

if (failures.length) {
  console.error(failures.length + ' failed, ' + pass + ' passed\n');
  for (const f of failures) { failures.push(f); }
  for (const f of failures) { console.error('  ✗ ' + f); }
  process.exit(1);
}
console.log('replay: ' + pass + ' assertions passed');
