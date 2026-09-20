/* 一个真的浏览器，驱动一个真的回合。
 *
 * 为什么不是 node 里的 DOM 桩：`*.test.mjs` 那些用例把 `app.js` 里的函数抠出来跑在桩上，
 * 覆盖的是解析和渲染。它们碰不到布局、碰不到焦点、碰不到真的 `EventSource`，也碰不到
 * 「点了发送之后服务器真的开了一个回合」这件事 —— 一个只在真浏览器里才存在的失败，在那里
 * 是看不见的。
 *
 * 所以这个脚本自己起一个 headless Chrome，用 CDP（Chrome DevTools Protocol）驱动页面：
 * 等页面和服务器握完手，往输入框里敲一句话，真的按一下发送按钮，然后等助手的回答出现在
 * 转录里。零依赖：CDP 是用 node 自带的 WebSocket 说的，没有 npm、没有 puppeteer。
 *
 *   node src/test/js/browser-smoke.mjs --url <服务器地址> --prompt <要说的话> [--expect <回答里必须有的字>]
 *   node src/test/js/browser-smoke.mjs --self-check
 *
 * 退出码就是给 JUnit 看的接口（见 WebBrowserSmokeTest）：
 *   0  通过（断言了什么会打印出来）
 *   3  这台机器跑不了 —— 没有能用的 Chrome，或者这个 node 没有全局 WebSocket（需要 22.4+）。
 *      调用方把它记成*跳过*，所以一台没有浏览器的构建机器照样是绿的，但报告里写明了为什么。
 *   1  真的失败 —— 打印失败时页面上的证据（等的是哪个选择器、DOM 里当时有什么）。
 *
 * 「跑不了」只包括三件事：找不到浏览器、浏览器起不来、这个 node 不会说 WebSocket。浏览器一旦
 * 起来，剩下的任何事都是失败：一个起得来却不听话的页面，正是这个用例要来抓的东西。 */

import { spawn, spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const PASS = 0;
const FAIL = 1;
const SKIP = 3;

/** 整个用例的预算，从「找浏览器」开始算：起浏览器、跑一个回合、等回答。 */
const DEFAULT_BUDGET_SECONDS = 60;
/** 两次求值之间的间隔：够短，不至于把一次快的回合拖长；够长，不至于把页面问爆。 */
const POLL_MS = 100;
/** 连上 CDP 端点的时间上限：端点已经打印出来了，所以它只该是本地的一次握手。 */
const CONNECT_TIMEOUT_MS = 10_000;
/** 探测一个候选浏览器是不是真的在（`--version`）的时间上限。 */
const PROBE_TIMEOUT_MS = 20_000;
/** 失败时收集页面证据的时间上限：报告要快，而一个答不上来的页面本来就是一条证据。 */
const EVIDENCE_TIMEOUT_MS = 5_000;

/* 按名字找浏览器：先绝对路径，再交给 PATH。
 *
 * Linux 和 macOS 的名字都在这里，因为开发机和 CI 都跑这两个平台：Linux 上是发行版的
 * `google-chrome-stable`/`google-chrome`/`chromium`，macOS 上 Chrome 不在 PATH 里，只在
 * 应用包里（brew cask 也是装到那里），所以绝对路径那一项是 macOS 唯一能找到它的方式。 */
const CHROME_CANDIDATES = [
  '/usr/bin/google-chrome-stable',
  '/usr/bin/google-chrome',
  '/opt/google/chrome/chrome',
  '/usr/bin/chromium',
  '/usr/bin/chromium-browser',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/Applications/Chromium.app/Contents/MacOS/Chromium',
  'google-chrome-stable',
  'google-chrome',
  'chromium',
  'chromium-browser',
];

const USAGE = `用法：
  node src/test/js/browser-smoke.mjs --url <地址> --prompt <要说的话> [--expect <片段>] [--timeout <秒>]
  node src/test/js/browser-smoke.mjs --self-check
  node src/test/js/browser-smoke.mjs --help

用 headless Chrome 驱动页面跑一个回合：等 /api/status 渲染完，往 #input 里敲进
--prompt，点 #send，然后等助手的回答出现在转录里。给了 --expect 就要求回答里含有那个片段
（调用方用它来证明工具真的读到了磁盘上的东西，而不是页面自己编了一段话）。

--url      要驱动的服务器，例如 http://127.0.0.1:8080。必填。
--prompt   敲进输入框的那句话。必填。
--expect   助手的回答里必须出现的片段。省略时只要求有一段非空的回答。
--timeout  整个用例的预算秒数，默认 ${DEFAULT_BUDGET_SECONDS}。
--self-check  只报告这台机器能不能跑（找到的浏览器、node 有没有 WebSocket），不起服务器。

环境变量：CCJ_CHROME 指定浏览器可执行文件，优先于上面那张候选表。

退出码：0 通过，1 失败，3 跑不了（没有浏览器或 node 太老，调用方记为跳过）。`;

/** 「这台机器跑不了」，而不是「页面坏了」：它的消息以 SKIP 打印，退出码 3。 */
class CannotRun extends Error {}

// ------------------------------------------------------------------ 入口

function parseArgs(argv) {
  const options = {
    url: '',
    prompt: '',
    expect: '',
    budgetSeconds: DEFAULT_BUDGET_SECONDS,
    help: false,
    selfCheck: false,
  };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--help' || arg === '-h') {
      options.help = true;
    } else if (arg === '--self-check') {
      options.selfCheck = true;
    } else if (arg === '--url') {
      options.url = (argv[++i] || '').trim();
    } else if (arg === '--prompt') {
      options.prompt = argv[++i] || '';
    } else if (arg === '--expect') {
      options.expect = argv[++i] || '';
    } else if (arg === '--timeout') {
      options.budgetSeconds = Number(argv[++i]);
    } else {
      throw new Error('不认识的参数 ' + arg + '（--help 看用法）');
    }
  }
  if (!isFinite(options.budgetSeconds) || options.budgetSeconds <= 0) {
    throw new Error('--timeout 需要一个正的秒数，拿到的是 ' + JSON.stringify(options.budgetSeconds));
  }
  return options;
}

/** 这台机器能不能跑这个用例；能跑的话，用哪个浏览器。 */
function probeRuntime() {
  if (typeof WebSocket !== 'function') {
    return {
      ok: false,
      reason:
        '这个 node（' + process.version + '）没有全局 WebSocket，说不了 CDP；需要 node 22.4 或更新',
    };
  }
  const chrome = findChrome();
  if (!chrome.ok) {
    return { ok: false, reason: chrome.reason };
  }
  return { ok: true, chrome };
}

function findChrome() {
  const explicit = (process.env.CCJ_CHROME || '').trim();
  if (explicit) {
    // 点名了就用它，而且它用不了就是「跑不了」：安静地退回候选表，会让一个打错的环境变量
    // 变成「这台机器没有浏览器」，而那是另一件事。
    const probe = probeChrome(explicit);
    return probe.ok
      ? probe
      : { ok: false, reason: 'CCJ_CHROME 指的浏览器用不了：' + explicit + '（' + probe.reason + '）' };
  }
  const tried = [];
  for (const candidate of CHROME_CANDIDATES) {
    const probe = probeChrome(candidate);
    if (probe.ok) {
      return probe;
    }
    tried.push(candidate + '：' + probe.reason);
  }
  return {
    ok: false,
    reason: '这些名字里没有一个能用的浏览器（CCJ_CHROME 可以指定一个）：\n  ' + tried.join('\n  '),
  };
}

/** 「找到」的意思是它能打印自己的版本：一个不可执行的文件不是浏览器。 */
function probeChrome(executable) {
  const run = spawnSync(executable, ['--version'], { encoding: 'utf8', timeout: PROBE_TIMEOUT_MS });
  if (run.error) {
    return { ok: false, reason: run.error.code === 'ENOENT' ? '不存在' : run.error.message };
  }
  if (run.status !== 0) {
    return { ok: false, reason: '`--version` 退出码 ' + run.status };
  }
  const version = ((run.stdout || '') + (run.stderr || '')).trim();
  return { ok: true, path: executable, version: version || '（它没报告版本）' };
}

async function main() {
  let options;
  try {
    options = parseArgs(process.argv.slice(2));
  } catch (err) {
    // 参数错了是调用方写错了，不是这台机器跑不了：这必须是一次失败。
    console.error(err.message);
    console.error('');
    console.error(USAGE);
    return FAIL;
  }
  if (options.help) {
    console.log(USAGE);
    return PASS;
  }

  const runtime = probeRuntime();
  if (options.selfCheck) {
    if (runtime.ok) {
      console.log('自检通过 —— 这个用例在这台机器上跑得起来：');
      console.log('  浏览器：' + runtime.chrome.path + '（' + runtime.chrome.version + '）');
      console.log('  node：' + process.version + '（全局 WebSocket 在）');
      return PASS;
    }
    console.log('SKIP ' + runtime.reason);
    return SKIP;
  }
  if (!runtime.ok) {
    console.log('SKIP ' + runtime.reason);
    return SKIP;
  }
  if (!options.url || !options.prompt) {
    console.error('--url 和 --prompt 都是必填的');
    console.error('');
    console.error(USAGE);
    return FAIL;
  }

  // 预算从现在开始算，所以「浏览器起不来」也算在里面，而不是另外送它一段时间。
  const deadline = Date.now() + options.budgetSeconds * 1000;
  const profile = mkdtempSync(join(tmpdir(), 'ccj-browser-smoke-'));
  const chrome = launchChrome(runtime.chrome.path, profile);
  let cdp = null;
  try {
    cdp = await connect(await chrome.endpoint(deadline), deadline);
    await openPage(cdp, options.url, deadline);
    const answer = await driveTurn(cdp, options, deadline);
    console.log('浏览器冒烟通过（' + runtime.chrome.path + '，' + runtime.chrome.version + '）：');
    console.log('  页面 ' + options.url + ' 上：输入框和发送按钮出现，/api/status 渲染出工具列表');
    console.log('  敲进 ' + JSON.stringify(options.prompt) + '，真的按了一下 #send');
    console.log('  助手的回答：' + summarise(answer, 200));
    if (options.expect) {
      console.log('  断言：回答里有 ' + JSON.stringify(options.expect) + '（那是磁盘上的东西，页面编不出来）');
    }
    return PASS;
  } catch (err) {
    if (err instanceof CannotRun) {
      console.log('SKIP ' + err.message);
      return SKIP;
    }
    console.error('浏览器冒烟失败了：' + err.message);
    if (cdp) {
      console.error(await pageEvidence(cdp));
    } else {
      console.error('（还没连上页面，所以没有 DOM 证据）');
    }
    return FAIL;
  } finally {
    if (cdp) {
      cdp.close();
    }
    chrome.stop();
    rmSync(profile, { recursive: true, force: true });
  }
}

// ------------------------------------------------------------------ 浏览器

/* 起一个 headless Chrome，并把它的 CDP 端点交出来。
 *
 * 端口写 0 让内核挑一个空闲的，`--remote-debugging-port` 因此只在这台机器上开着；真正要用的
 * 是 Chrome 自己打印在 stderr 上的那一行 `DevTools listening on ws://…`，所以这里不去猜端口。
 * 用户数据目录是临时的：浏览器的状态（cookie、缓存、上一次运行的什么）不该从一个用例漏进
 * 下一个，也不该漏进用户自己的浏览器。 */
function launchChrome(executable, profileDir) {
  const args = [
    '--headless=new',
    '--remote-debugging-port=0',
    '--user-data-dir=' + profileDir,
    '--no-first-run',
    '--disable-gpu',
    '--no-default-browser-check',
    // 密码库用本地存法，别去问操作系统的钥匙串。Linux 上默认那条路是 D-Bus 上的
    // gnome-keyring/KWallet，而一台没有会话总线（或者没人解锁过钥匙串）的机器上，那次调用会
    // 把导航堵死：请求发出去了，页面永远停在装载中。这是实测出来的——同一台机器上，一行
    // `--password-store=basic` 就决定了一个页面是 6 毫秒装载完还是永远装载不完。
    // `--use-mock-keychain` 是 macOS 上同一件事的另一副面孔：headless 里没有人可以点那个
    // 钥匙串授权框。这个用例不保存任何密码，所以两样都不需要。
    '--password-store=basic',
    '--use-mock-keychain',
    // 这个用例只连 loopback，所以后台的更新、组件和同步请求一律关掉：一次离线的构建不该因为
    // 等它们而变慢。
    '--disable-background-networking',
    '--disable-component-update',
    // 固定的窗口尺寸：点击是按坐标发的，所以视口得是一个已知的大小。
    '--window-size=1280,900',
    'about:blank',
  ];
  if (typeof process.getuid === 'function' && process.getuid() === 0) {
    // root 下 Chrome 的沙箱起不来，而容器里的构建常常就是 root。
    args.unshift('--no-sandbox');
  }
  const child = spawn(executable, args, { stdio: ['ignore', 'ignore', 'pipe'] });
  let stderr = '';
  let endpoint = null;
  let failure = null;
  let wake = () => {};
  const woken = new Promise((resolve) => {
    wake = resolve;
  });

  child.stderr.setEncoding('utf8');
  child.stderr.on('data', (chunk) => {
    // 只留尾巴：出错时要打印的就是它，而无界的缓冲区会让一个吵个不停的浏览器变成内存问题。
    stderr = (stderr + chunk).slice(-4_000);
    const match = /DevTools listening on (ws:\/\/\S+)/.exec(stderr);
    if (match) {
      endpoint = match[1];
      wake();
    }
  });
  child.on('error', (err) => {
    failure = '起不了浏览器 ' + executable + '：' + err.message;
    wake();
  });
  child.on('exit', (code, signal) => {
    failure =
      '浏览器在打印 CDP 端点之前就退出了（' +
      (code === null ? '被信号 ' + signal + ' 杀掉' : '退出码 ' + code) +
      '）：\n' +
      stderr;
    wake();
  });

  return {
    async endpoint(deadline) {
      await giveUpAfter(woken, Math.max(0, deadline - Date.now()));
      // 端点先到就赢：进程随后退出（比如我们在收尾时杀了它）不是「跑不了」。
      if (endpoint) {
        return endpoint;
      }
      throw new CannotRun(failure || '浏览器在预算用完之后才打印 CDP 端点：\n' + stderr);
    },
    stop() {
      child.kill();
    },
  };
}

// ------------------------------------------------------------------ CDP

function connect(endpoint, deadline) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(endpoint);
    const expired = setTimeout(
      () => reject(new Error('连不上 CDP 端点（' + CONNECT_TIMEOUT_MS / 1000 + ' 秒）：' + endpoint)),
      CONNECT_TIMEOUT_MS,
    );
    socket.addEventListener('open', () => {
      clearTimeout(expired);
      resolve(new Cdp(socket, deadline));
    });
    socket.addEventListener('error', () => {
      clearTimeout(expired);
      reject(new Error('连不上 CDP 端点：' + endpoint));
    });
  });
}

/** 够用的 CDP 客户端：发命令、收结果、把页面自己的抱怨记下来。 */
class Cdp {
  constructor(socket, deadline) {
    this.socket = socket;
    this.deadline = deadline;
    this.sessionId = null;
    this.nextId = 1;
    this.pending = new Map();
    /** 还在等的域事件（`once`），按方法名分组。 */
    this.waiters = new Map();
    /** 页面自己说的话：未捕获的异常、控制台错误、崩溃。失败时它们常常是唯一的线索。 */
    this.notes = [];
    socket.addEventListener('message', (event) => this.receive(String(event.data)));
    socket.addEventListener('close', () => {
      for (const entry of this.pending.values()) {
        entry.reject(new Error('CDP 连接断了；还没回答的是 ' + entry.method));
      }
      this.pending.clear();
    });
  }

  /* 发一条命令，等它的结果。
   *
   * 每条命令都受预算约束。浏览器可以起得来、说得出端点，然后卡在别的地方（实测过：一个等钥匙
   * 串的 Linux 就是这样，连导航的回复都不给），而没有这条约束时，等的人永远等下去——超时会是
   * 外面那个进程截止时间给的，那时留下的只有一句「没跑完」，而不是这里能给出的证据。 */
  send(method, params = {}, sessionId = this.sessionId) {
    const id = this.nextId++;
    const frame = { id, method, params };
    if (sessionId) {
      frame.sessionId = sessionId;
    }
    return new Promise((resolve, reject) => {
      const expired = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(method + ' 没有在预算用完之前回答'));
      }, Math.max(0, this.deadline - Date.now()));
      // 不因为一个还没响的闹钟而拖着进程不放：真正在等的时候，是 socket 让事件循环活着的，所以
      // 这条闹钟照样会响；而走到一半就走开的那种情况（比如导航自己报了错），它不该把一次失败
      // 拖到预算用完。
      expired.unref();
      this.pending.set(id, {
        method,
        resolve: (value) => {
          clearTimeout(expired);
          resolve(value);
        },
        reject: (err) => {
          clearTimeout(expired);
          reject(err);
        },
      });
      this.socket.send(JSON.stringify(frame));
    });
  }

  /** 求一个表达式的值，按值取回来（awaitPromise 让页面里的 promise 也能等）。 */
  async evaluate(expression) {
    const result = await this.send('Runtime.evaluate', {
      expression,
      awaitPromise: true,
      returnByValue: true,
    });
    if (result.exceptionDetails) {
      const thrown = result.exceptionDetails.exception;
      throw new Error(
        '页面里求值出错：' + (thrown && thrown.description ? thrown.description : result.exceptionDetails.text) +
          '\n表达式：' + summarise(expression, 200),
      );
    }
    return result.result.value;
  }

  /* 等一个域事件，只等一次（比如 `Page.loadEventFired`）。
   *
   * 装载是唯一一段不该一边求值一边等的时间：导航会把执行上下文换掉，而在这中间求值，报回来的
   * 「上下文没了」是浏览器在说实话，却和页面坏了长得一样。所以装载用事件等，不看 DOM。
   *
   * 超时给 null，不抛：等的人可能已经因为别的原因先走开了（比如导航自己报错），而一个没人接的
   * 拒绝会变成进程崩溃，而不是一次打印得清清楚楚的失败。 */
  once(method, deadline) {
    return new Promise((resolve) => {
      const entry = {
        fire: (params) => {
          clearTimeout(timer);
          resolve(params);
        },
      };
      const timer = setTimeout(() => {
        const waiting = this.waiters.get(method) || [];
        const at = waiting.indexOf(entry);
        if (at >= 0) {
          waiting.splice(at, 1);
        }
        resolve(null);
      }, Math.max(0, deadline - Date.now()));
      // 同 `send`：一个没人再等的闹钟不该把进程留到预算用完。
      timer.unref();
      const waiting = this.waiters.get(method) || [];
      waiting.push(entry);
      this.waiters.set(method, waiting);
    });
  }

  close() {
    this.socket.close();
  }

  receive(raw) {
    let message;
    try {
      message = JSON.parse(raw);
    } catch {
      return; // CDP 只说 JSON；一帧读不懂的东西没有别的处理办法，而且它不是页面的错。
    }
    if (message.id !== undefined) {
      const entry = this.pending.get(message.id);
      if (!entry) {
        return;
      }
      this.pending.delete(message.id);
      if (message.error) {
        entry.reject(new Error(entry.method + ' 被拒绝：' + message.error.message));
      } else {
        entry.resolve(message.result);
      }
      return;
    }
    this.note(message);
    const waiting = this.waiters.get(message.method);
    if (waiting && waiting.length) {
      this.waiters.delete(message.method);
      for (const entry of waiting) {
        entry.fire(message.params);
      }
    }
  }

  note(message) {
    if (message.method === 'Runtime.exceptionThrown') {
      const details = message.params && message.params.exceptionDetails;
      this.notes.push('未捕获的异常：' + ((details && details.exception && details.exception.description) || (details && details.text) || ''));
    } else if (message.method === 'Runtime.consoleAPICalled') {
      const type = message.params && message.params.type;
      if (type === 'error' || type === 'warning') {
        const text = ((message.params && message.params.args) || [])
          .map((arg) => arg.value || arg.description || '')
          .join(' ');
        this.notes.push('控制台 ' + type + '：' + text);
      }
    } else if (message.method === 'Inspector.targetCrashed') {
      this.notes.push('页面崩溃了');
    }
  }
}

/* 开一个页面并进入它。
 *
 * 目标先建在 about:blank 上再导航，而不是直接建成那个 URL：这样 `Page.enable` 和
 * `Runtime.enable` 一定早于页面自己的脚本，页面一开始说的话（异常、控制台错误）就都在
 * `cdp.notes` 里，而不是在订阅之前就过去了。 */
async function openPage(cdp, url, deadline) {
  const created = await cdp.send('Target.createTarget', { url: 'about:blank' });
  const attached = await cdp.send('Target.attachToTarget', { targetId: created.targetId, flatten: true });
  cdp.sessionId = attached.sessionId;
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');

  const loaded = cdp.once('Page.loadEventFired', deadline);
  const navigation = await cdp.send('Page.navigate', { url });
  if (navigation.errorText) {
    throw new Error('打不开 ' + url + '：' + navigation.errorText);
  }
  if ((await loaded) === null) {
    throw new Error('页面没有报装载完成：' + url);
  }
}

// ------------------------------------------------------------------ 用例

/** 驱动一个回合：等页面就绪、敲一句话、按发送、等回答。 */
async function driveTurn(cdp, options, deadline) {
  // 1. 页面自己的脚本跑起来了（否则连要点的东西都没有）。
  await waitFor(
    cdp,
    `!!(document.getElementById('input') && document.getElementById('send'))`,
    '输入框 #input 和发送按钮 #send 出现',
    deadline,
  );

  // 2. `/api/status` 那一轮渲染完了。等的是结构而不是文案：工具列表在标记里是空的，只有
  // status 载荷落地才会长出名字来 —— 一个改过界面文案的改动不该让这个用例变红。
  await waitFor(
    cdp,
    `document.querySelectorAll('#tool-list li .name').length > 0`,
    '/api/status 的载荷渲染出工具列表',
    deadline,
  );

  // 3. 真的点、真的敲：焦点和文字都走浏览器的输入管线，而不是直接改 DOM 的值。
  await click(cdp, '#input');
  await cdp.send('Input.insertText', { text: options.prompt });
  const typed = await cdp.evaluate(`document.getElementById('input').value`);
  if (typed !== options.prompt) {
    throw new Error(
      '输入框里的字是 ' + JSON.stringify(typed) + '，不是 ' + JSON.stringify(options.prompt) + '：敲进去的东西没落地',
    );
  }

  // 4. 按发送。#send 是表单的提交按钮，所以这一下走的是页面自己那条 submit 路径。
  await click(cdp, '#send');

  // 5. 助手的回答真的出现在转录里 —— 这是唯一能说明「HTTP、SSE 和一个回合的编排都通了」的证据。
  const answer = await waitFor(
    cdp,
    expectedAnswer(options.expect),
    options.expect
      ? '转录里出现含 ' + JSON.stringify(options.expect) + ' 的助手回答'
      : '转录里出现助手的回答',
    deadline,
  );
  return answer;
}

/* 在页面里找一段满足条件的助手回答，找到就把它的正文交出来。
 *
 * 比较前把空白压掉：markdown 渲染器有权把制表符和连续空格归成一个，而工具输出的形状正是
 * 空格加制表符。比的是被压过空白的两段文字，所以这种归一化不会让一个真的回答落空。 */
function expectedAnswer(expect) {
  return `(() => {
    const want = ${JSON.stringify(expect)}.replace(/\\s+/g, '');
    for (const node of document.querySelectorAll('#transcript .ev-assistant .msg-answer')) {
      const text = node.textContent.replace(/\\s+/g, '');
      if (want ? text.includes(want) : text.length > 0) { return node.textContent.trim(); }
    }
    return '';
  })()`;
}

async function waitFor(cdp, expression, what, deadline) {
  for (;;) {
    const value = await cdp.evaluate(expression);
    if (value) {
      return value;
    }
    if (Date.now() >= deadline) {
      throw new Error('等不到「' + what + '」：预算用完了');
    }
    await sleep(Math.min(POLL_MS, Math.max(0, deadline - Date.now())));
  }
}

/* 在元素的中心真的按一下鼠标。
 *
 * 走 CDP 的 Input 域而不是页面里的 `element.click()`：命中的事情交给浏览器，所以「按钮被别的
 * 东西盖住了」这种失败会真的发生，而不是被一次直接调用绕过去。 */
async function click(cdp, selector) {
  const box = await cdp.evaluate(`(() => {
    const node = document.querySelector(${JSON.stringify(selector)});
    if (!node) { return null; }
    const rect = node.getBoundingClientRect();
    return {
      x: rect.x + rect.width / 2,
      y: rect.y + rect.height / 2,
      width: rect.width,
      height: rect.height,
      viewport: { width: window.innerWidth, height: window.innerHeight },
    };
  })()`);
  if (!box) {
    throw new Error('点不到 ' + selector + '：页面上没有这个元素');
  }
  if (box.width <= 0 || box.height <= 0) {
    throw new Error('点不到 ' + selector + '：它量出来是 ' + box.width + '×' + box.height);
  }
  if (box.x < 0 || box.y < 0 || box.x > box.viewport.width || box.y > box.viewport.height) {
    throw new Error(
      '点不到 ' + selector + '：中心 (' + Math.round(box.x) + ', ' + Math.round(box.y) + ') 落在视口 ' +
        box.viewport.width + '×' + box.viewport.height + ' 之外',
    );
  }
  for (const type of ['mousePressed', 'mouseReleased']) {
    await cdp.send('Input.dispatchMouseEvent', { type, x: box.x, y: box.y, button: 'left', clickCount: 1 });
  }
}

/** 失败时把页面当时的样子打出来：等的是哪个选择器已经在上面的消息里，这里补的是 DOM。 */
async function pageEvidence(cdp) {
  let snapshot;
  try {
    // 证据是尽力而为的，所以它有自己的上限：真正想跑的那件事已经失败了，而在一个连求值都不
    // 回答的页面上（导航卡住时就是），等下去只会把一次失败拖成一次超时。
    snapshot = await giveUpAfter(
      cdp.evaluate(`(() => ({
        url: location.href,
        readyState: document.readyState,
        composer: !!document.getElementById('input') && !!document.getElementById('send'),
        tools: document.querySelectorAll('#tool-list li .name').length,
        live: (document.getElementById('live-text') || {}).textContent || '',
        typed: (document.getElementById('input') || {}).value || '',
        answers: document.querySelectorAll('#transcript .ev-assistant .msg-answer').length,
        transcript: (document.getElementById('transcript') || {}).textContent || '',
      }))()`),
      EVIDENCE_TIMEOUT_MS,
    );
  } catch (err) {
    return '页面证据拿不到：' + err.message + '\n' + notesOf(cdp);
  }
  if (!snapshot) {
    return (
      '页面证据拿不到：DOM 在 ' + EVIDENCE_TIMEOUT_MS / 1000 + ' 秒内没有回答\n' + notesOf(cdp)
    );
  }
  const lines = [
    '页面证据：',
    '  ' + snapshot.url + '（readyState ' + snapshot.readyState + '）',
    '  输入框和发送按钮都在：' + (snapshot.composer ? '是' : '否'),
    '  工具列表里的名字：' + snapshot.tools + ' 个',
    '  状态行：' + snapshot.live,
    '  输入框里的字：' + JSON.stringify(snapshot.typed),
    '  转录里的助手回答：' + snapshot.answers + ' 段，正文（截断）：' + summarise(snapshot.transcript, 600),
  ];
  return lines.join('\n') + '\n' + notesOf(cdp);
}

function notesOf(cdp) {
  if (!cdp || !cdp.notes.length) {
    return '  页面没报错。';
  }
  return '  页面自己的抱怨：\n' + cdp.notes.map((note) => '    ' + note).join('\n');
}

// ------------------------------------------------------------------ 小工具

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/* 给一件事一条自己的上限：到点就放弃它，交出一个 null 来。
 *
 * 它跟 `sleep` 的区别是有归属：闹钟在事情有结果时就被清掉，而且从不因为自己还挂着而把进程
 * 留到预算用完——一个已经给出答案的用例不该再花掉剩下的每一秒。 */
function giveUpAfter(promise, ms) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => resolve(null), ms);
    timer.unref();
    promise.then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      (err) => {
        clearTimeout(timer);
        reject(err);
      },
    );
  });
}

/** 一行以内的一段正文：换行和连续空白都压成一个空格，长了就截断。 */
function summarise(text, limit) {
  const flat = String(text == null ? '' : text).replace(/\s+/g, ' ').trim();
  return flat.length > limit ? flat.slice(0, limit) + '…' : flat;
}

// 用 exitCode 而不是 process.exit()：输出走的是管道，硬退出会把还没冲出去的日志砍掉，而
// 那些日志正是失败时唯一的证据。子进程和 socket 都关了，事件循环自己会空下来。
process.exitCode = await main();
