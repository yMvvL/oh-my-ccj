# 更新日志

改了什么，最新的在前，用读者会用的说法——不是提交主题的倾倒。每一条的推理在做出它的那次提交里，也在该条目链接到的文档里；这个文件是索引。

版本：**`v0.1.0`**（2026-09-21，打了标签）。这个 jar 是为 **JDK 21** 构建的（Temurin 21，`maven.compiler.release=21`），CI 在 Linux 与 macOS 上跑的就是它。它不是产品、不承诺兼容、也没有发布节奏——见 README 顶部那段英文摘要。

## 2026-09-21 — 第一次 CI：两个平台全红，修完再推，全绿

这个仓库此前**没有 remote**，所以那个工作流一次都没跑过——路线图里凡是写着「CI 是唯一的证据」的地方，等的
就是这一刻。第一次推上去，Linux 与 macOS 两个平台都红了，各自两条，而且**四条都是真问题**，本机一条都撞
不上：

- **服务端在拒掉一个超限上传时把连接关了，而客户端还在传。** 客户端看到的不是那条 413，而是「响应被截断」
  （ubuntu 上 `fixed content-length: 72, bytes received: 0`）。修法：拒绝之前把请求体读掉（有界）。这条是
  **先复现再修**的——新写的用例用手写 socket 造出那种交错，修复前连接在 2.2 MB 处被重置，修复后客户端发完
  全部 8.4 MB 并读到完整的 413。
- **没人回答的对话框没有在截止时间被关掉。** 输出抽在读的那个线程上，于是「等多久」由管道决定：被杀进程的
  后代仍握着写端，EOF 永不到来——1 秒的截止时间在 ubuntu 上等了 30 秒。修法：抽到自己的线程上，我们只在
  自己的预算内等，到点杀掉**整棵进程树**。
- **一条测试在 macOS 上偶发，因为它在批复之后抢跑。** 批复返回 200 只说明答案记下了，工具可能还在跑。
- **一条测试把「大小写不敏感的文件系统」当成了不存在。** macOS 的 APFS 上 `ccj.md` 就是 `CCJ.md`，所以那
  条测试在该平台上问的根本不是同一件事；现在它先问文件系统一句，再按那台机器上的**事实**断言。

顺带：冒烟脚本的收尾不再因为临时目录删不掉而判失败（ubuntu 上它已经通过了，退出码却是 1），并加了网络证据；
工作流的四个 action 升到当前主版本，并新增一步「失败的测试」——失败时把红了的测试类连同断言消息直接打进
日志（私有仓库的日志唯一的读者是人，不该逼人去下载 artifact）。

**结果：** 同一提交在两个平台全绿。这是这个项目第一次有 CI 说过话。

## 2026-09-21 — `v0.1.0`：Phase 1 与 2 剩下的账，以及分发

这一批把「还欠着的正确性缺口」清完，把两种新线路接上，并把发布要做的事做完。它同时是第一个打了标签的
版本。

**子代理的截止时间终于有证据了。** 机制一直在（一条 daemon 线程到点 `loop.abort()`），缺的是触发那半边的
证明：此前只在它产生的那条消息上有断言——一句手写的字符串喂给 `SubAgentReport.failed`，从未让截止时间真的
到期。现在一个「正常时永不返回、被中断时立刻退出」的提供方把它跑通了。顺路修掉一处自欺：报告用
`toMinutes()` 说上限，于是 45 秒会被说成「0 分钟」，读起来像护栏坏了。

**用量账本改由循环自己记，于是抓到两个真问题。** 此前账本只在网页那条路上被写：终端（REPL 与 `-p`）
**一个 token 都不记**，所以 `--max-total-tokens` 在那两个前端永远不会触发，一条终端会话的文件里除了压缩
次数什么都没有。第二处更贵：**压缩那次摘要请求的用量被整个丢掉**（监听器是 `event -> {}`），而用户为它付
钱——每一次压缩都让花费上限少算一笔。现在两者都进账本（摘要不进 `steps`/`turns`，那两格不是它的）。
另修两处口径：OpenAI 那个提供方此前**每个 chunk** 都转发一次 usage，而某些网关每帧重复发累计用量，同一笔
钱会按 chunk 数被记上几十遍；子代理没上报缓存时，命中率不再从「未上报」变成 0.0%。

**一个驱动真实浏览器的冒烟测试。** 这是 Phase 1 最后一项，也是唯一能覆盖「布局、焦点、真的审批握手」的
一种测试。零依赖、零 npm：脚本自己找一个能用的 Chrome、以 headless 起来、用 node 自带的全局 `WebSocket`
说 CDP，然后驱动**发布出去的那个页面**——等输入框与工具列表出现、真的敲字、真的按按钮、等回答进 DOM。
Java 那边起真服务器并断言回答里有磁盘上的哨兵，那串东西页面编不出来。三种结局都实测过：跑通、没有浏览器
时记成 **skipped**、失败时把页面证据打出来。CI 的 Node 因此从 20 升到 22——按 20 装的话它在两个平台上都会
变成 skipped，而「可选」退化成「从没跑过」就不是可选了。

**四种线路，而不是两种。** 加上 OpenAI 的 Responses API 与 Google 的 Gemini。两条路都能选中它们：直接用
协议名当 `--provider`，或作为一个自定义定义的 `kind`（名字归用户，协议归代码）。Responses 那条按核过的
形状写，其中一个决定是产品性的：**显式 `store:false`**，因为它的默认值是 true，而这里的叙事是会话在我的
文件里。Gemini 那条记下了两条关键差异（`FunctionCall.args` 是 JSON 对象而不是字符串；`?alt=sse` 这个分帧
方式未核实），并且**不给默认模型**——型号名换得太快，猜一个就是把一次配置错误变成一次莫名其妙的 404。

**一个角色可以被钉到自己的模型与档位上。** 配置里一块 `subAgents`、命令行两个可重复的 flag、两组环境
变量；没点名的角色跟随主对话，而钉住只换模型与档位两栏。顺带把「一次委派花了多少」放进了报告本身
（`USAGE:` 一行），于是终端与网页的卡片同时能读到它，而任务卡不再是一串参数 JSON。

**跑命令的那个程序可以配置了。** `"shell"` / `--shell` / `CCJ_SHELL`：不配置时 POSIX 上是 `/bin/bash`，
Windows 上是 `%COMSPEC%` 或 `cmd.exe`；参数形状按程序名决定（`cmd` 收 `/c`，`powershell`/`pwsh` 收
`-NoProfile -Command`，其余 `-lc`）。仓库根多了一个 `ccj.cmd` 启动器。顺路修掉一个真 bug：**起不来的
shell 此前被报成「超时」**，于是一个配置错误看起来像一次卡住。Windows 本身**没有被验证过**——这台机器与
CI 都没有 Windows，所以路线图里 2.5 停在 `doing`，收尾放在 4.6。

**文档不再能悄悄漂移。** 新的 `docs` 测试拿发布出去的字节做两件事：README 的「用法」一节必须逐字等于
`CliOptions.usage()`；工具表里的工具、顺序、参数名、以及哪个参数带 `?`，必须与每个工具的 schema 一致。
它第一次运行就抓到一处不精确（README 把 `old_string`/`new_string` 说成必填，而 `edit` 其实要么给这一对、
要么给 `edits`）。

**分发那一侧。** README 顶部一段英文摘要与一句「这是个人工具，不是产品」；一张真机截图（真的跑了一个
回合之后截的）；tailnet 地址与机器名换成占位符；README 加了「想要一个自带运行时的包？」一节，里面是实测
过的 `jpackage` 一行——98 MB 的产物、`bin/ccj --version` 与一个真实 `--demo` 回合都跑得通，而不做
`deb`/`rpm`/`msi` 的理由（平台工具链、不能跨平台构建）写在同一段里。版本打上 `v0.1.0`。

**验证。** `./mvnw -o test`：**742 个测试，0 失败，0 跳过**（+54）。真机做过的事：真浏览器里跑通那个冒烟
（并分别看到 skipped 与 failed）；起真服务器打过新端点；账本那条等号用一个八组往来 + 一次委派 + 一次压缩
的会话算出 11828/1208/2988 与逐回合之和逐位相等；`jpackage` 的产物真的跑了一个回合。**没有**做过的：
真 Windows 上跑一次、对真实 Responses/Gemini 端点各发一次请求（本机到那两个官方站点一个 403、一个超时）。

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
