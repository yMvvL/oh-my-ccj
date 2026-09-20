# 更新日志

改了什么，最新的在前，用读者会用的说法——不是提交主题的倾倒。每一条的推理在做出它的那次提交里，也在该条目链接到的文档里；这个文件是索引。

目前还没有版本号：[ROADMAP](ROADMAP.md) 3.1 是标签、发布说明和一句 JDK 声明落地的地方。在那之前，一个条目就是一个日期和它做了什么，而启动器打印的是 `0.1.0`。

## 2026-09-21 — Phase 2.6 剩下的九项

**粘贴与拖放图片。** 桌面上绝大多数图片来自剪贴板。`paste` 与 `drop` 接进**同一个 `sendPhoto`**（和相机、
相册同一条路），而「入口只有一条」正是这个功能上一次坏掉的地方——多一条图片路径就是多一个会在用户说出想做
什么之前就动手的地方。粘进来的文本照常落进输入框；拖进来的不是图片时说一句话，而不是静默地什么都不做。

**转录里的图片被摆成图，文本一字未改。** 一条 `[picture <名>] <描述> … <用户的话>` 的消息在页面上是：缩略图
+ 文件名、一段**可展开**的描述、然后是用户自己那句话。那段文本一个字都不能动——会话文件、重放和压缩都靠它
——所以渲染层只读它、从不拼它，这条区别有测试钉着。解析不出来时原样当文本，不猜。

**审批的第五个答案「以后都拒绝」。** 终端 `d`、网页第五个按钮、线上值 `never`。它写一条 **deny** 规则进同一个
`approvals.json`，主语与「始终允许」逐字相同：本次调用被拒绝，下一次同样的调用由规则挡下且不再问人。在那之前，
四个答案里只有一个方向是持久的——被一个反复出现的提示烦到的人，唯一的出路是关掉整条守卫。

**退回分两步（网页）。** `GET /api/undo` 是**预览**（`{"files":[{"path":…,"action":"restore"|"delete"}],"turns":N}`，
只读、不建目录、不写文件），`POST /api/undo` 才真的动磁盘；第一次按「退回」是武装并列出清单，第二次才执行。
退回到底是唯一一个会覆盖磁盘的按钮，所以它现在和删除用同一个手势。

**服务端缩略图。** `GET /api/attachment/thumb?name=`：最长边 96 像素的 PNG，只服务**这条会话附件目录**里的
文件（`../`、绝对路径、指向别处的符号链接都当没有这张图片）。解不出来的格式（WebP 是一例）**原样交回字节**
——画不出小图不是错误，那张图片本身是好的。

**花费上限。** `--max-total-tokens` / `CCJ_MAX_TOTAL_TOKENS` / `"maxTotalTokens"`，按**会话累计**：超过上限之后
不再开始新回合，判定在回合开始**之前**（而不是把正在跑的回合砍掉），终端与网页同一条规矩。拒绝信息说出已用
多少、上限多少、以及三种改法；用量面板在设了上限时多一行「已用 / 上限」。

**`fetch` 的取文本模式。** 可选参数 `as` 只接受 `"text"`：剥标签与 `script`/`style` 内容、块级标签变换行、压缩
空白、解开常见实体，并在结果开头声明它剥过标记、且这是**调用方要求的猜测**。不传时行为一字不变——那条
「剥掉标记是一种猜测」的理由仍然成立，只是现在由调用方来要求这次猜测。

**联网搜索：核心不写。** `docs/MCP.md` 给了一个能照抄的搜索服务器例子（Brave 官方的
`@brave/brave-search-mcp-server`，取代了 MCP 参考仓库里归档的那份），README 一句指针。搜索要第三方密钥与
供应商选择，属于外挂；而它顺带证明了这个抽象是真的。

**编辑后检查的豁免：定案，保持放宽。** 那条命令是用户自己写进配置文件里的，和写进 `CCJ.md` 或直接在终端敲
出来是同一件事；让它走审批等于每次编辑弹一次提示，走规则则等于让它永远不问——比一个写明的放宽只多了一层
假装。它没有变宽：只运行配置里那条命名的命令，在会话的工作目录里。

**设置面板里的规则与检查编辑器。** 今天这两样只能手编文件。现在设置对话框里有两节，能列出、添加、删除（删除
用仓库既有的两段式手势），写出来的文件与手编的完全一样（`approvals.json` 的 `allow`/`deny`、配置文件的
`checks`，都是 `0600`）。**两边都在下一次使用时就生效**——规则每次工具调用重读文件，检查每次查找适用项时也
重读；这里曾有一句「保存后要重启」的错误说法，代码里 `Checks` 的 javadoc 早就写明它每次都读。保存规则时**不**
重开规则实例，因为那会把「本会话内放行」的清单丢掉——保存一条规则不该变成把本次会话答过的一切再问一遍。

## 2026-09-21 — 图片现在也能粘贴和拖进来

桌面上绝大多数图片来自剪贴板：截图之后按 Ctrl+V，而不是「存成文件、再去点按钮选它」。`paste` 与
`drop` 因此接进了**同一个 `sendPhoto`**——和相机、相册是同一条路——而「入口只有一条」正是这个功能上一次
坏掉的地方：上传曾经自己开启一个回合，而描述本该等着和用户的下一句话一起发；多一条图片路径，就是多一个
会那么干的地方。所以这条不变式现在有测试钉着。

**三个手势各自的行为。** 粘进来的图片被上传，并且**接管这次粘贴**（不接管的话浏览器还会把它交给别的
东西）；粘进来的**文本**一点都不受影响，照常落进输入框。拖进窗口时输入框亮起来（能放的地方不说，用户没
法知道这里能不能放），放下第一张图片就上传；放下的不是图片时说一句话，而不是静默地什么都不做——静默正
是用户会再试三次的那种反馈。一次只上传一张（一次只有一张会跟着消息走），拖进来好几张时取其中第一张。

**验证方式值得说一下。** 浏览器的这些行为在一个 node 用例里钉着（`src/test/js/composer-picture.test.mjs`，
19 条断言）：它把那一节从**发布出去的** `app.js` 里抠出来，用一个只有四样东西的桩（`document`、
`dom`、`sendPhoto`、`appendNotice`）把三种手势真的触发一遍，而不是去断言源码里写着什么。真机那一侧用
一个真浏览器核过：在 `--demo` 下粘一张 PNG，服务器真的收到了 `POST /api/attachment?name=pasted.png`，
并被拒绝并说明要配置什么——那条拒绝就是它抵达的证据；拖一个 `.txt` 进去，出现的是「只能拖入图片」。

## 2026-09-21 — 思考语言这个设置没有了

删掉的是「用这种语言思考和回答」那一整套：`Config.language`、`--language`、`CCJ_LANGUAGE`、设置面板里的
那个下拉框，以及 `Prompts` 里十门语言的指令表和拼接它们的机制。理由不是它没用，而是它**替用户回答了一个
正在被模型自己回答的问题**：提示词里那句「必须用 X 思考」，对答案是可靠的、对推理是不可靠的（同一段中文
指令下，一个 OpenAI 形状中继上的 `deepseek-v4.1` 仍然用英文思考——那是当初实测出来、写在文档里的），而
想把它钉死的人现在可以写进 `CCJ.md` 或 `--system`：那是关于这项工作的具体陈述，不是一个全局开关。

**行为变了吗：没有，对任何人都是。** 去掉那一句之后的系统提示词，与过去「`--language` 没设置」时**逐字
相同**——项目规则在前、内置规则在后，没有语言那一行。也就是说删掉的是一个默认什么都不做的设置。传
`--language` 现在是一条未知参数，走现有的用法错误路径。

`Prompts` 因此从 166 行掉到 56 行，`Config` 少了一个字段。删掉一个 record 组件在这个项目里是一次**位置
参数手术**：`language` 夹在 `systemPrompt` 与 `reasoning` 之间，三个都是 `String`，直接删参数会让编译器
沉默地接受错位的值。做法是先把它临时换成另一个类型，让编译器逐一点名那十个真正传语言的地方，删干净之后
再删字段——最后编译确认没有残留。

同一次改动里修掉两处跟它一样陈旧的东西：设置面板里那段图片说明还写着「描述内容成为你的消息」（图片流程
改成「等着和你的下一句话一起发」之后就不对了），以及 README 的测试表——它是手写的，已经漂到实际数字的
一半左右，现在按包从 surefire 报告里数出来：**649 个测试，54 个类**，另附一句「让它从代码生成是 3.3」。

## 2026-09-21 — 从别的设备访问：几条路，以及一个更硬的 token

**Tailscale 不是必需的。** 文档此前把「经由 tailnet 访问」写成了唯一的路，而它其实只是最省事的那条。
现在 [WEBUI.md](WEBUI.md) 的「从别的设备访问」一节列了全部六条——tailnet、别的 mesh VPN、同一个局域网、
SSH 端口转发、隧道、VPS 加反向代理——每条给出命令、要在手机上打开什么、以及各自的代价。同一节也说明了
三件在代码里成立的事：**它自己不提供 HTTPS**（TLS 属于隧道或反代那一层），**一条路走不走得通就看能不能
发出一条消息**（跨源护栏要求 `Origin` 的主机是回环、或等于请求所寻址的 `Host`，所以改写了 `Host` 的反代
会得到一条带着 `Origin` 的 403），**以及哪几条在信任模型里**：回环、mesh VPN、SSH 转发是你自己的设备集合；
局域网、隧道、公网反代不是。

**token 的两件事被测试钉住了。** 一，**一次安装只有一个 token**：两个进程同时启动（一次重启——旧进程以
75 退出、启动器起新进程——或者同一个 home 下的第二个工作区）会撞上「文件还不存在」的那一瞬，过去两边
各自生成、各自写入，磁盘上留后写的那个，而先写的那个进程还在用内存里的值服务；现在创建是独占的，输的
一方读赢家写下的值（并给它几微秒把内容写完——测试第一次跑就抓住了这个窗口，它比读一次还短）。二，生成
出来的是 32 个字节的 `SecureRandom`，也就是 256 位、64 个十六进制字符，而**自己设的 token 短于 16 个字符
会在启动时警告一次**：它是这台机器上唯一的门禁，而短到可以猜的秘密比没有秘密更糟，因为它看起来像是锁着
的。撞上同一个 256 位值需要大约 2^128 次生成，所以这里不需要查重——需要保证的是「不重」的另一种意思。

**显式要求通配地址时会有警告。** 默认仍然从不绑 `0.0.0.0`（那会把端口放到这台机器所在的每一个网络上，
包括你正在坐着的这个 wifi），但平板和手机在同一局域网时它是个合理的请求——所以现在允许，并打印一行
说明代价、给出更窄的那条路。

## 2026-09-21 — 图片等你的下一句话，以及相机

**上传不等于发送。** 报告自实机体验，而且报的是设计里没有问过的那个问题：*谁来决定拿这张图片做什么？*
上传会立刻开启一个回合，于是模型先收到描述、自己决定去看它，然后在用户说出想让它做什么之前就已经
开始干活——「我传一张待会要用的图」，换回来一个自作主张的回合。现在描述挂在这条会话上等着，用户的
下一句话和它拼成*一条*消息：描述在前，要求在后。顺序是刻意的——模型读到末尾时，最后读到的那件事就是
要它做的事。

两个随之而来的变化。一张图可以在一个回合正在跑的时候上传：描述不是那个回合的工作，结果就停在那里等着，
而旧的「还有回合在跑；请先中止它」把一次上传变成了一条死路——你在等一个长回合时拍的那张照片，正是你
接下来要用的那一张。第二次上传**取代**还在等着的那一张，并说它取代了谁，而不是排成第二个回合：一次
只有一张会跟着消息走。

**相机。** 按钮过去只有 `accept="image/*"`，在手机上调图库——拍一张的路根本不存在，而那正是这个功能最
主要的用法。`capture` 是按钮级的属性，所以一个按钮没法同时表示「拍一张」和「从图库里选一张」；按钮现在
先问来源，再打开对应的那个文件输入，两边都可达。

**看得见，才不会被误发。** 待发送的图片在输入框上方有一条缩略条，可以移除（`DELETE /api/attachment`），
而服务器也在 `status` 里报同一件事——刷新页面之后它还在，而不是变成一张看不见、却会跟着下一句话发出去
的图片。设计记录里的旧决定在 [VISION.md](VISION.md) 里就地进行修正，而不是删掉。

## 2026-09-19 — a configuration file somebody typed by hand could not be saved from the form

Reported from use. The settings form always posts its key-variable field, pre-filled with the
provider's default (`OPENAI_API_KEY`), and posting the default counted as *this change names its own
endpoint and key* — so `changedBy` dropped the unmarked pair in the file and the provider build then
failed with `no API key for provider 'openai'`. A `config.json` written by hand has no `settingsFor`
mark, so **every** save from the form failed with a 400 and no setting could be changed from the panel
at all. A value that is only the provider's default is not a value now, which is the rule the merge
already followed when it decided what to write down.

**`write` writes the file the diff was made against, or nothing.** It refused a path that *appeared*
while the approval was up and wrote over one that *changed* — the same shape of bug `edit` had, in the
one tool that shows you a diff. It now fingerprints the target before prompting — size, modification
time, and a streamed hash of every byte, never held in the heap — and refuses if the answer comes back
to a different file. A size and a timestamp alone would have missed an editor saving within the same
granularity or a formatter writing back the same number of bytes; that case is pinned by a test.
Reported as ROADMAP 1.1.

## 2026-09-18 — pictures

**A picture arrives as a description.** Photograph a screenshot, send it from the phone, and the
*description* — not the image — joins the conversation. The main model never receives an image, which
is why no wire format, renderer or compaction step grew an image branch, and why a session file stays
readable text with a picture beside it. `POST /api/attachment`, the composer's picture button, and a
vision model configured on its own: its own endpoint, key, model and completion budget, in Settings,
the config file's `vision` block, or `--vision-*`/`CCJ_VISION_*`. Pictures live in
`<sessions>/<id>.attachments/` and go when their session goes. Designed in [VISION.md](VISION.md).

**The description budget was wrong the first time, and the failure was silent.** A reasoning model
spends the completion budget before it writes a word, so too small a budget does not shorten a
description, it deletes it: a phone screenshot at `max_tokens: 1500` returned HTTP 200 with
`finish_reason: length`, all 1500 tokens spent thinking, and empty `content`. Measured again at 4096
(2882 used) and 8192 (1084 used); the default is now 8192, the number is configurable, and the refusal
says which setting fixes it instead of printing a wall of JSON.

**A page on the tailnet may change state, and it could not.** The cross-origin guard accepted only
loopback origins, so *every* state-changing request from a phone — a message, an abort, a settings
save, an approval, a picture — was refused as another site. The rule is now "a loopback address, or
the host the request was aimed at", which is still a guard: another site's origin is its own host, and
a page reaching the server under a rebinding name has no token. See [SECURITY.md](../SECURITY.md).

**The vision model is configurable from Settings**, with the two things a form cannot say with an
empty field — forget the saved key, turn pictures off — sent as the flags they are.

Also: `edit` and `write` re-read before they write and refuse a file that changed while the approval
waited, both write through a temp file and an atomic rename, the SSE keep-alive is a real event the
page can hear, and an error body is read bounded rather than truncated after the fact.

**Undo.** Every turn that changes a file can be taken back — `POST /api/undo`, the composer's Undo
button, or `/undo` in the REPL — and each press goes one turn further back. A file the turn created is
deleted rather than emptied; a file it changed is written back as the turn found it. Snapshots live
beside the session, twenty turns are kept, a file over 4 MB is skipped rather than copied, and a file
outside the session's directory is not recorded at all. This is the other half of approval: approval
answers "may this run", undo answers "may it be taken back", and it is the second question that decides
whether the first one can be left switched on.

**Prompt caching, and a conversation that compacts itself.** Anthropic requests now carry three
`cache_control` breakpoints — the system prompt, the tool set, and the end of the conversation — which
is what makes turn *n+1* a cache read of the prefix turn *n* already paid to write. (Live, on the
OpenAI-shaped relay this runs against, the same effect is visible without doing anything: turns report
90-94% of their prompt as cached.) And the budget that
trims a request also compacts the conversation that outgrew it: between turns, only when the summary is
genuinely smaller, and with a notice that says what happened. The projection's own elision keeps
working; this is the version that tells the model what it lost. Measured live: a reasoning model can
write a summary several times the size of the exchanges it replaces (866 tokens against 255), so
refusals are normal at first and each one now waits for double the growth before trying again —
otherwise a refusal would be a paid model call every few turns.

**MCP servers.** A server named in `<home>/mcp.json` contributes its tools to the agent, each named
`mcp__<server>__<tool>` so the prompt, the transcript and a rule can all say where a capability came
from. Started the first time one of its tools is called; discovery asks each server once and closes it,
so a server nobody uses costs nothing. Every call goes through the same approver as `bash`, a name that
would shadow a built-in is refused, every request has a deadline, and every server this run started is
killed when ccj exits. The transport is the server's own stdin and stdout — HTTP/SSE, resources,
prompts and sampling are not implemented, and [docs/MCP.md](MCP.md) says so rather than half-doing them.

**`edit` changes several places in one file together, and `fetch` reaches the network.** The `edits`
form is one approval, one write and a diff of the whole file: nothing is written unless every hunk
matches, overlapping hunks are refused, and a hunk that misses comes back with the file's numbered
lines around where it was expected instead of a bare "no exact match". `fetch` is the first tool that
leaves the machine — `http`/`https` only and checked before anything is dialled, text content types
only, the body bounded while reading, redirects followed by hand so every hop is scheme-checked, and
the URL is what an approval rule matches (`https://docs.example.com/*`, where the star must follow a
separator so a rule cannot swallow a neighbouring hostname). What it is not: a browser — no
JavaScript, cookies, credentials or POST.

**A message sent while a turn is running waits instead of being refused.** The old answer was `409`,
which disabled the composer until the turn ended — so a thinking pause was a dead stop and whatever
you thought of while waiting was gone by the time it finished. Now the message queues, the composer
says how many are waiting, and each one runs as a turn of its own. The queue is per conversation and
bounded at sixteen; abort stops the turn **and drops what was behind it**, because stopping is what
that button is for, and the count is published so a dropped message is visible rather than silent.

**Approval has four answers, and one of them can be written down.** Deny, allow once, allow for this
session, allow from now on. The last writes a rule into `<home>/approvals.json` keyed by project, and
that rule answers the next identical request without a prompt. This removes the friction that made
people run `--yolo`: "stop asking me about this" used to mean "stop asking me about anything", because
the only other answer the gate had was a session-wide switch. Rules match a command exactly, or widen
it by one trailing ` *`, and a widened rule never covers a command containing `;`, `&&`, `|`, a
substitution or a redirect. Deny wins over everything, a path rule cannot leave the project, an
unreadable rules file asks instead of allowing, and every automatic answer is in the transcript as
`allowed by rule — …`. See [SECURITY.md](../SECURITY.md).

**A check runs itself after an edit, and the conventions became a document.** `checks` in the config
file names commands that run inside the `edit`/`write` call that matches their glob, so the compiler's
verdict arrives with the change rather than three turns later — see the README row and
[SECURITY.md](../SECURITY.md) for the one place this deliberately runs something without asking. The
process plumbing `bash` already had (closed stdin, bounded capture, deadline, process-tree kill) moved
to `tool/ProcessRunner` so both callers share it, with `BashTool`'s eleven tests as the net. Two things
came out of trying it on this repository rather than from writing it: a check's glob decides *when* it
runs and not what the command looks at, so `mvn compile` reported `exit 0` for a broken file at the
root it never compiled — the passing line now says `exit 0` instead of "clean", because "clean" is a
claim the command did not make — and a pattern written `**/*.java` has to match a file at the project
root, which `PathMatcher` alone does not do. Alongside:
[docs/CONVENTIONS.md](CONVENTIONS.md) gained the naming table, the comment rules, error shape, the
front end's own rules and spelling; [ROADMAP](ROADMAP.md) gained Phase 2.5, the experience work, with
2.5.1 done; and the spelling pass that came out of writing the rules down is enforced by
`core/ConventionsTest`.

## 2026-09-14 — the second front end gets serious

**`/compact`.** The older turns are replaced by a summary the model writes, as the next *generation*
of the same session (`<id>.g1.jsonl` beside the untouched file), so the conversation it replaced is
still on disk and the summary names it. Refused when the summary would not come out smaller than what
it replaces — measured on a real session at 4239 → 4236 tokens. See [COMPACT.md](COMPACT.md).

**Tailnet reach.** The server binds named addresses — loopback plus this machine's tailnet address —
never a wildcard, with a generated token for everything that is not loopback. See
[WEBUI.md](WEBUI.md).

**Project rules.** A `CCJ.md` in the working directory leads the prompt, read from that directory only,
so "which prompt is this run using" is answerable by looking at the folder you started in.

**Several conversations at once**, each with its own busy flag, its own working directory from the
moment the turn started, and events that name their session so a background turn's prose cannot land
in the transcript you are reading.

## 2026-09-12 — the page becomes the product

The web UI becomes the default front end and can configure the model at runtime: a layered
provider → model → effort picker, custom providers defined in the form, per-provider endpoint and key
that travel together (switching provider loads that provider's pair rather than passing the old one
on), themes, desktop folder picker, workspaces with a VS Code-style sidebar, session deletion, resume
fixes, and per-session usage with a cache hit rate. Workspaces keep their own directories and their own
history; switching moves both.

## 2026-09-11 — the first working agent

A coding agent runtime in plain Java 21: the loop, one model turn at a time with every requested tool
run and fed back, the tool set (`read`, `write`, `edit`, `bash`, `glob`, `grep`, `restart`), approval
before anything writes or executes, sessions as append-only JSONL under `~/.oh-my-ccj/sessions/`,
both wire protocols (OpenAI-shaped and Anthropic) with streaming and retry, an offline playground, a
launcher that survives being symlinked onto `PATH`, and `--demo`: no model and no key, so the loop,
the tools and the approval prompts can be tried before anything is configured.

## 还没有的东西

MCP、插件、沙箱、多用户账号——以及体验类的工作，接下来的改动就去那里：[ROADMAP](ROADMAP.md) Phase 2.5。
