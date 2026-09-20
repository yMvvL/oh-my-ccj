# oh-my-ccj

[![build](https://github.com/ArchCCJ/oh-my-ccj/actions/workflows/build.yml/badge.svg)](https://github.com/ArchCCJ/oh-my-ccj/actions/workflows/build.yml)

从零开始用纯 Java 21 写成的编码代理运行时。没有代理框架，没有 HTTP 客户端库，没有 CLI 库——传输用
`java.net.http`，Web UI 和测试替身用 `com.sun.net.httpserver`——16.6k 行 Java 加一个 8.0k 行的原生
页面，旁边还有 14.8k 行测试。

`ccj` 与模型流式地对话，让模型调用能触碰你文件系统的工具，把结果回喂给它，如此重复直到模型给出回答。
它是每个编码代理都围绕的那个循环的一个小巧、可读的实现。

```
$ ccj -p "add a null check to Parser.java and run the tests"
⚙ read Parser.java
✔ read (3 ms)
⚙ edit Parser.java
✔ edit (1 ms)
⚙ bash mvn -q test
✔ bash (8420 ms)
    exit code 0
Added the check on line 42 and the suite passes.
```

## 为什么选它

别的编码代理有的是。以下是这个做了、而它们大多没做的事，每一件都是长会话或被中断的会话会出问题的地方：

- **被中断的回合会被修复，而不是致命。** 在助手回合与它请求的工具调用之间杀掉进程，你就得到一份再没有
  任何 API 会接受的历史——之后每个请求都被拒，对话就此死掉。ccj 在它发送的投影里补上缺失的结果
  （「未运行」才是实际发生的事），并把错位的回答带回提出请求的那个回合，于是会话得以继续。
- **`/compact` 是可逆的。** 压缩通常是单向的损失：旧回合没了，只剩下摘要。这里它会写出同一个会话的
  *新一代*，原始文件原封不动，而且摘要会点出那个文件名，模型就能把被丢掉的细节 `read` 回来。当摘要不会
  比它替换的内容更小时，它还会拒绝运行——在一个真实会话上实测是 4239 → 4236 token，纯属花了钱没好处。
- **代理能读自己的历史。** 会话就是普通 JSONL，放在 `read` 工具够得到的目录里，上面那条才可能成立。
- **上下文裁剪保持协议有效。** 先省略旧的工具输出，再丢掉整个来回——绝不把助手回合和它发起的调用结果
  拆开。
- **子代理的改动要经过你的审批。** 它在一个你看不见的会话里读东西，但写入和你自己的写入一样要问你，
  中止该回合就是给了答复。它也不能再往下委派：那个工具根本不在它的注册表里。
- **Web UI 能从手机访问，却不必暴露在公网上。** 它只绑定具名地址——loopback 加上你的 tailnet——从不绑
  通配地址，而且每个非 loopback 地址都需要 token。会改变状态的跨源请求一律拒绝。

它刻意不做的东西：插件、沙箱、多用户账号。图片是以描述而不是以图像的形式到达的——见下文「图片」一行——
其余都在[限制](#限制)一节。

## 运行之前请先读这一节

`ccj` 会在你的机器上执行 shell 命令。这正是它的全部意义，也意味着下面这些边界不是小字条款——它们就是
设计本身。

- **代理以你的身份、带着你的环境运行。** `bash` 没有沙箱，不限于某个目录，也不受命令清单限制。你能敲的
  东西，它都能敲。
- **审批是唯一的防线。** 会写入或执行的工具先发问；没有终端可问时，它们是*被拒绝*，而不是被当成默认
  同意。`--yolo`（或 UI 里的自动批准）会把这层防线整个拿掉；在一个同时还能被网络访问到的会话上，这个
  组合按设计就是远程代码执行。
- **Web UI 是一个能通过 HTTP 够到的 shell 提示符。** 它只在你指名的地址上服务，从不绑通配地址；除
  loopback 外每个地址都需要 token（`~/.oh-my-ccj/web-token`，32 个随机字节，`0600`，首次需要时生成）。
  在能连上这个端口的设备和执行命令的能力之间，只有这个 token。把带 token 的 URL 当密码对待：它只在启动
  时打印一次，只有你自己把它粘进 shell，它才会出现在你的 shell 历史中。
- **不要把它暴露给你不掌控的网络。** tailnet 地址是合理的，因为能连上它的设备集合由你管理。公网接口
  ——VPS、端口转发、咖啡店网络——就不合理，token 也改变不了这一点：它只是过滤来者，并不能让这个服务
  可以安全地对外发布。

这些都不是以后要修的缺陷；跑不了命令的编码代理干不了这活。这也是 `ccj` 被做成一台你自己拥有的机器上的
个人工具的原因。

## 快速开始

```bash
./mvnw package                   # builds target/ccj.jar (shaded, no classpath juggling)

./ccj                            # opens the web UI; configure your model there
./ccj --demo                     # the same, with a local stand-in model: no key, no network
./ccj --repl                     # terminal REPL instead of the browser
./ccj -p "what does src/Main.java do?"   # one-shot
```

首次运行时还没有配置模型，所以 Web UI 会打开它的设置面板并索要一个——提供方、模型、base URL、API key。
保存会写入 `~/.oh-my-ccj/config.json`（0600）并立即切换正在运行的会话：不用重启，不用手改 JSON。脚本化
使用时，命令行 flag 和环境变量依然可用。

### 运行环境

**Linux 和 macOS，需要 JDK 21+。** 启动器（`./mvnw`）自带 Maven，所以不必安装 Maven——但需要一个 POSIX
shell，这是一项真正的依赖，而不是顺带的前提：

| 需要 | 原因 |
|---|---|
| `/bin/bash` | `bash` 工具通过它执行命令，整个设计就是围绕这个工具搭起来的。 |
| POSIX shell 工具 | 启动器（`ccj`）用到 `readlink`；各工具期望 `grep`、`find` 之类按惯常方式工作。 |
| JDK 21+ | `./mvnw` 和 `java -jar` 都需要它。 |

不支持 Windows。Java 本身能在上面跑，但 shell 工具将无物可跑：`cmd.exe` 或 PowerShell 路径是另一个工具，
引号规则不同，不是改个配置的事。**WSL 可用**，在 Windows 上这是使用本项目的正路——在 WSL 里，上面每一条
都成立。

其它什么也不需要：不需要 Maven，不需要 Node（Node 缺席时浏览器用例会被跳过，并且*报告*为跳过——CI 会装上
它，所以不可能悄悄蒙混过去），不需要 API 密钥（`--demo` 跑的是本地替身模型）。

## 安装

把启动器放进 `PATH` 一次，就能像任何别的 CLI 一样在任意目录里用 `ccj`：

```bash
./mvnw -DskipTests package                    # or let the launcher build it on first use
ln -sfn "$PWD/ccj" ~/.local/bin/ccj           # ~/.local/bin is on PATH by default
cd ~/some/other/project && ccj -p "explain this repo"
```

启动器运行 `java -jar target/ccj.jar`，当 `src/main` 下的任何文件——包括那个页面，不只是源码——或
`pom.xml` 比 jar 新时就会重新构建，所以这个符号链接不可能跑到过期的代码。工具按当前工作区解析相对路径，
工作区默认是你启动 `ccj` 时所在的目录，直到你另选一个（`-C` 对单次运行覆盖它，会话会说明这一点）；会话
存放在 `~/.oh-my-ccj` 下，跨项目共享（`--home` 可以把它们隔离开）。密钥放在环境里或配置文件里——`ccj`
是个小程序，不是服务。

### 不用 API 密钥也能试

```bash
ccj --demo                        # web UI with the stand-in model
ccj --demo --repl                 # the same in the terminal
ccj --demo -p "read pom.xml"      # one-shot
```

`--demo` 把模型换成一个本地工具路由器：`read <path>`、`run <command>`、`list [glob]` 和
`search <regex>` 会变成**真正的**工具调用，其它输入则用这套词汇回答。其余一切照旧——同一个循环、同一批
工具、同样的审批提示、同样的会话——而且不需要密钥、不需要网络、不需要第二个进程。这是观察代理干活最快的
方式。

## Web UI

```bash
ccj                       # the default: serve on 127.0.0.1 and on this machine's tailnet address,
                          # then open it
ccj --no-open             # for scripts: serve it without launching a browser
ccj --repl                # the terminal front end instead
```

这个页面流式显示助手的散文，把每次工具调用显示成带参数、耗时和输出的卡片，并把审批请求渲染成阻塞卡片，
上面是批准 / 拒绝——因为它本来就是：循环线程一直停着，直到有人回答，超时即拒绝。回答以 markdown 到达，
并在流式传输的同时按 markdown 渲染（标题、列表、表格、围栏代码、链接）。会话就是 CLI 用的那批 JSONL
文件，所以在终端里开始的对话能在浏览器里继续，反过来也一样。

**一条命令，两个入口。** 服务绑定的是具名地址，而不是通配地址：

```
$ ccj
oh-my-ccj 0.1.0 — web UI: http://127.0.0.1:6767/
                          also on http://100.72.92.41:6767/?token=…
  the first address is this machine (no token needed there); the others are the tailnet, and ask for
  the token in the URL
```

- **本机**用 `127.0.0.1` 直接够到它，什么都不用带。loopback 不是网络，为了用你自己的命令行还要输一个
  秘密，那是没人要的密码。
- **你的其它设备**通过 tailnet 地址够到它，必须带上 token。token 首次使用时生成，保存在
  `~/.oh-my-ccj/web-token`（0600），这样重启不会把手机踢下线，并打印在 URL 里——打开那个 URL 一次，
  token 就落进 HttpOnly cookie。
- **其它任何东西**都够不到它：不绑通配地址，所以咖啡店 wifi、办公室 LAN 和 docker bridge 都没有监听者
  可以对话。`--host <addr>` 改为只绑一个地址，`--host tailscale` 只绑 tailnet 地址；此时除 loopback
  外的任何地址都需要 token——来自 `--web-token`、`CCJ_WEB_TOKEN` 或那个文件。
- 无 token 的服务器（没有 tailnet 的机器）还会坚持要求 `Host` 头是 loopback：你打开的任意页面都能不经
  预检直接 POST 到 `127.0.0.1`，而 DNS rebinding 能让它读到回答，所以指名其它 host 的请求会被拒绝，
  而不是被服务。

`--port` 改端口。代理能跑 shell 命令，所以它能被够到的地址是个安全决策，不是便利决策：从网络访问它的
代价就是一个 token。

模型设置——提供方、模型、base URL、API key 和 temperature——就在 UI 里（**设置**面板），**测试连接**会在
保存前发一个很小的请求来检查它们。effort 档位则在消息框上方选择。上下文预算刻意不放进表单：它是
`--max-context-tokens`、`CCJ_MAX_CONTEXT_TOKENS` 或配置文件里的 `"maxContextTokens"`。密钥从不回传给
浏览器，只传是否存在以及它来自哪里。见 [docs/WEBUI.md](docs/WEBUI.md)。

### 用手机通过 Tailscale 访问

不用多输任何东西：如果这台机器在某个 tailnet 里，`ccj` 已经在为你的手机服务了。

```bash
ccj                    # this machine at http://127.0.0.1:6767/
                       # and the phone at http://100.72.92.41:6767/?token=…
```

在手机上打开第二个 URL 一次；token 进入 HttpOnly cookie，之后的书签就什么都不用带了。token 首次使用时
生成并保存在 `~/.oh-my-ccj/web-token`（0600，只有你能读），这样重启不会把手机踢下线；`--web-token` 和
`CCJ_WEB_TOKEN` 可以覆盖它，两处任意一处留空都算「没有 token」，而不是空密码。

有两件事让它成为*你的*，而不只是「在 tailnet 上」：

在手机上打开打印出来的 URL 一次；token 进入 HttpOnly cookie，之后的书签就什么都不用带了。有两件事让它
成为*你的*，而不只是「在 tailnet 上」：

- **绑定的是地址，不是每个接口。** `ss -ltn | grep 6767` 会显示两个监听：`127.0.0.1:6767` 和
  `100.72.92.41:6767`。手机用的是 tailnet 那个，它也是唯一能从本机之外够到的。`--host tailscale`
  只服务 tailnet 地址（那样本机也得用它，或者用 MagicDNS 名字 `archymwl.taile88351.ts.net`）。
- **一个 tailnet 不等于一台设备。** 其中的每台设备都能连上那个端口；挡住其它设备的是 token。如果还想
  把它钉死到一台设备，就在 tailnet 的访问规则里加上：

  ```json
  { "acls": [ { "action": "accept", "src": ["ffdancer"], "dst": ["archymwl:6767"] },
              { "action": "accept", "src": ["archymwl"], "dst": ["archymwl:6767"] } ] }
  ```

  （Tailscale 的默认规则本来就允许 tailnet 内的一切——上面这一对才是把它*收窄*的东西。
  `tailscale status` 会打印设备名。）

这串 tailnet 地址是通过 `tailscale0` 接口解析出来的；在那些用别的接口承载 tailnet 的系统上，则通过
`tailscale ip -4` 询问得到。`100.64.0.0/10` 之外的地址永远不被接受，没有 tailnet 的机器就只服务
loopback，不绑任何别的东西。`--host <addr>` 仍然接受任意地址，而任何非 loopback 地址都**需要** token。
想要带真证书、完全不开端口的 HTTPS，在一个 loopback 绑定前面加 `tailscale serve --bg 6767` 也行——它
仍然需要 token，因为那时服务器看到的 `Host` 是主机名，而不是 loopback。

## 它能做什么

| 能力 | 细节 |
|---|---|
| 提供方 | 任何 OpenAI 兼容端点（`/chat/completions`，SSE）和 Anthropic（`/v1/messages`，SSE）。流式传输并增量拼装工具调用，遇 408/429/5xx 退避重试。 |
| 自定义提供方 | 在设置面板里或通过 HTTP 定义你自己的提供方：一个名字、一个协议、一个端点、一个可选的密钥变量名以及它提供的模型。中继、网关、本地 vLLM 或你自己的 API 路由器都只是一个定义，而不是一次发版。**任何**提供方（包括内置的）的模型列表都可编辑并持久化，所以手输的模型会成为被记住的选择，而不是一次性的。你不用的提供方可以直接从选择器里移除：你自己的定义会被删除，编译进去的别名则离开列表（列表变成显式的，加回来就是一次普通的添加）。 |
| 端点和密钥 | 它们属于当初输入它们时所选的提供方，配置文件为每个提供方保留一对：生效中的那一对，加上你配置过的每个其它提供方的 `remembered` 条目。切换提供方会载入新提供方的那一对，而不是把旧的那对传下去，所以会话不可能给刚离开的提供方记上账——切回去时也不必再贴一遍密钥。 |
| 工具 | `read`、`write`、`edit`、`bash`、`glob`、`grep`——每个都有手写的 JSON Schema 和自我说明的错误。`bash` 执行命令时它的 stdin 已经关闭，所以 `cat`、`sort` 或脚本里的 `read` 看到的是输入结束，而不是去等一个并不存在的终端。三个只读工具声明自己是只读的，在一个回合里连着跑它们会并发执行。 |
| 循环 | 每个对话同一时刻只有一个模型回合；每一个被请求的工具都会执行，结果回传，然后再次问模型。没有步数上限——回合在模型给出回答或你中止它时结束，因为上限区分不出卡住的模型和正在啃长任务的模型。中止是真的：正在跑的 shell 命令会被杀掉，而不是等它跑完。 |
| 子代理 | 代理可以把一件自成一体的任务委派给子代理，子代理在自己的会话里干活，只返回一份报告：带着文件路径的结论，而不是得出这个结论的阅读过程，所以跨三十个文件的搜索只花主对话一段话。三种角色——`explore` 和 `verify` 只读，`build` 会写，它的改动和你的改动一样要审批。它不能再往下委派：`task` 不在它的注册表里，所以递归是不可能，而不只是被限制。除非传 `--subagents`，否则关闭。 |
| 项目规则 | 把 `CCJ.md` 放进某个工作目录，它的内容就会领起在那里发出的每个请求的提示词：项目自己说明这里的工作怎么做，内置规则跟在它后面，而不是被它替换。只从**那个目录**读——树上更高层的文件不会被考虑，所以「这次运行用的是哪份提示词」看一眼你启动时的目录就能回答。`--system` 仍然覆盖内置的那部分。缺失、空和读不了都表示「没有项目规则」。 |
| 上下文预算 | `--max-context-tokens`（或 `CCJ_MAX_CONTEXT_TOKENS`，或配置文件）以估算 token 数封顶一次请求所携带的内容。超出时先省略旧的工具输出，再丢掉整个更早的来回——绝不把助手回合和它发起的调用结果拆开——如果正在回答的这个来回仍然放不下，就把其中最大的结果截断并留下显式标记。会话文件保留一切；只有请求是投影，转录会说明裁掉了什么。 |
| 上下文 | 侧边面板报告估算的提示词大小相对于预算（`--max-context-tokens`）的位置，这样你能在转录说「被裁剪了」之前就看出某个会话快要被裁。它是估算，并且被如实标注为估算：token 计数只是一种启发式。不显示钱——价格是对一份说改就改的价目表的假设，改时不会通知 ccj。 |
| 压缩 | `/compact`（或压缩按钮）用模型写的一段摘要替换更早的回合，这样长会话能继续下去，无需把读过的一切再发一遍。它写成同一个会话的下一代——`<id>.g1.jsonl` 紧挨着未被改动的 `<id>.jsonl`——所以被替换掉的对话仍在磁盘上、仍可读，摘要会告诉模型去哪里 `read` 回来。最新的五个来回保持原样；如果摘要不会比它替换的内容更小，就拒绝写入而不是写出去。见 [docs/COMPACT.md](docs/COMPACT.md)。 |
| 用量 | 每个会话的 prompt/output token、步数、工具调用数和**缓存命中率**，从两种协议里解析出来（`prompt_tokens_details.cached_tokens`、`prompt_cache_hit_tokens`、`cache_read_input_tokens`），在侧边面板和每个回合的 token 行里实时显示，并随会话持久化，所以继续会话就继续计数。 |
| 会话 | 追加写的 JSONL，位于 `~/.oh-my-ccj/sessions/` 下，在第一条消息时创建（空会话不留下文件），可以用 `--resume` / `--continue` 继续，UI 打开某个会话时会把它重放进转录，还能从侧栏一次删一个或一次全删——包括你当前不在的工作区里的。侧栏用你问的第一件事给每个会话打标签，因为一串时间戳 id 说明不了它当初是什么任务。被中断弄得缺少工具结果的回合（在调用和执行之间按了 `Ctrl-C`，或进程被杀），会在下一次请求时被修复，而不是永远被拒；回答被中途写入的消息挤走的回合也一样——回答会被带回提出请求的那个回合，因为 API 无法安置的 `tool` 消息就是一个会被拒的请求。 |
| 审批 | 任何写入或执行都先问；没有终端时它被拒绝，而不是被悄悄允许。 |
| 渲染 | 流式散文、带参数摘要的工具卡片、每次调用的耗时、变暗的推理、在提示出现时把终端让出来的 spinner。 |
| Markdown | 回答是 markdown，在浏览器里按 markdown 渲染：标题、列表（可嵌套，带任务框）、带语言的围栏代码、带对齐的表格、引用、行内代码、强调和链接——从流式的 delta 里解析出来，所以半途到达的回答可读，而正在写的那个块之上的块绝不会被重画。回答里的原始 HTML 按文本显示，图片不会被抓取，链接的 scheme 会被过滤。 |
| 主题 | 亮色/暗色/跟随系统，按浏览器记住，在首帧绘制前应用。 |
| 思考语言 | 设置里的一项设置，保存在 `config.json`（或单次运行用 `--language` / `CCJ_LANGUAGE`）。回答总是用所选语言给出，不管你用什么语言写的，提示词还会要求*思考*也用该语言——有些模型照做，有些不；设置里的提示如实这么说，而不是作出承诺。`auto` 则什么都不说。 |
| MCP 服务器 | 配置在 `<home>/mcp.json` 里的服务器会把它的工具贡献给这个代理，命名为 `mcp__<server>__<tool>`，这样审批提示、规则和转录都能说出某个能力的来源。在它们的工具第一次被调用时启动——发现过程会问每个服务器一次然后关掉它，所以没人用的服务器不花任何代价——走服务器自己的 stdin/stdout，每个请求有截止时间，stderr 被排空，ccj 退出时进程被杀掉。每次调用都走和 `bash` 同一个审批者：服务器自己认为它可以做什么，不是这个程序认为的。见 [docs/MCP.md](docs/MCP.md) |
| 退回 | 每个改了文件的回合都能撤回：`POST /api/undo`（或 REPL 里的 `/undo`，或输入框的退回按钮）把上一个回合的文件恢复成该回合最初看到的样子——包括删除该回合新建的文件——每按一次就再往前退回一个回合。快照（检查点）放在会话旁边（`<id>.checkpoints/<turn>/`），保留最近的二十个，超过 4 MB 的文件跳过不复制，会话目录之外的文件不归这个对话退回。这是审批的另一半：审批回答「它可以跑吗」，退回回答「它可以被撤回吗」，只有后者才让人敢把防线留着。 |
| 前缀缓存 | Anthropic 请求带三个 `cache_control` 断点——系统提示词、工具集、对话末尾——所以第 *n+1* 个回合为第 *n* 个回合已经发过的前缀付一次缓存读取，而不是再写一遍。请求的其它部分不变，而 OpenAI 形状的那条路径完全不需要：它的前缀缓存是自动的，命中已经体现在面板的缓存率里。 |
| 自动压缩 | 修剪请求的那同一个 `maxContextTokens` 现在也会在*对话*长过它时压缩对话：只在回合之间，绝不在回合内部，而且只在摘要确实更小时。另一种做法就是投影自己会做的事——省略工具输出、丢掉整个来回，外加一条通知说丢了多少、却没有摘要说它们是什么。没配预算就没有自动压缩；一次拒绝会按两倍大小记住，因为实测发现推理模型能写出比它替换的来回更大的摘要，而每几个回合就重试一次的拒绝，是一笔永远失败的付费调用。 |
| 排队消息 | 回合运行期间打字会把消息排队，而不是被拒：输入框保持可用，提示会说明有多少条在等。回合结束后下一条自己开始，作为它自己的回合、有自己的 `done`。Abort 会停止该回合**并丢掉排在它后面的东西**，同时说明丢了多少——停止正是那个按钮的用途。一个对话最多容纳十六条；再多服务器会带着原因拒绝，而不是无上限地增长。 |
| 审批规则 | 四种回答而不是两种：拒绝、允许一次、**本会话内允许**、**从现在起允许**。最后一种会把一条规则写进 `<home>/approvals.json`，按项目为键；这条规则对下一个一模一样的请求不再发问，直接回答：`{"tool": "bash", "command": "mvn -q -o test"}`，或对同一条命令加更多参数用 `git diff *`，或 `{"tool": "edit", "path": "src/**"}`。拒绝依然优先，通配符绝不覆盖含 `;`、`&&`、`\|`、替换或重定向的命令，路径规则不能走出项目，每个自动回答都以 `allowed by rule — …` 出现在转录里。要点在于那个谁都不敢碰的开关：「别再问我这个了」曾经意味着「别再问我任何事了」。见 [SECURITY.md](SECURITY.md)。 |
| 检查 | 在编辑之后自动运行的命令，在配置文件里声明：`"checks": [{"glob": "**/*.java", "command": "mvn -q -o -DskipTests compile"}]`。第一个 glob 匹配刚写入文件的检查会在同一次 `edit`/`write` 调用里运行，它的判定随结果一起回来——通过时一行（`exit 0`，不是 "clean"：见下文），失败时一段有界的摘录。**glob 决定检查何时运行，不决定命令看什么**——实测：`**/*.java` 配上 `mvn -q -o -DskipTests compile`，仓库根目录下的一个坏文件返回的是 `exit 0`，因为 Maven 只编译 `src/main/java`，压根没看到它。把 glob 和一条覆盖 glob 所选范围的命令配成一对。这就是编译错误出现在造成它的那一步、还是三个回合之后模型想起来去找才出现之间的差别，代价是每次编辑一条命令：每次编辑一个检查、4 KiB 报告、每个检查有超时、失败的编辑什么都不跑、会话目录之外的文件也什么都不跑。运行的配置命令不经提示意味着什么，见 [SECURITY.md](SECURITY.md)。 |
| 图片 | 输入框的图片按钮——它先问**相机**还是**相册**，所以你能当场拍一张（或从图库里选）——或 `POST /api/attachment`，最大 8 MB，PNG/JPEG/WebP/GIF，类型从字节里读而不是从文件名。一个**独立的视觉模型**描述它，而这段描述**等着**：它和你的下一句话拼成一条消息（`[picture photo.jpg] …` 描述在前，你的要求在后，再加一行路径，这样描述丢掉的细节还能 `read` 读回来），所以模型醒来时已经同时知道图片里有什么和你要它做什么。主模型从不接收图像，任何会话文件也不保存图像，所以没有哪种线上格式、渲染器或压缩步骤长出图像分支。自己的端点、自己的密钥、自己的模型和自己的补全预算（`maxTokens`，默认 8192），在**设置**（*图片*一节）、配置文件的 `vision` 块里设置，或用 `--vision-base-url`、`--vision-model`、`--vision-api-key`/`--vision-api-key-env`、`--vision-max-tokens`：缺席就表示该功能关闭，关闭期间发送图片会被拒绝并说明该设什么。预算是上限而不是花费——一次描述花多少由模型写多少决定——但推理模型在写出第一个字之前就会把它花掉，所以默认给得宽裕：设成 1500 时一张手机截图返回空白，每一个 token 都花在思考上。图片本身存放在 `<sessions>/<session-id>.attachments/`——紧挨着会话，所以删掉对话就删掉它——绝不放进你的项目。见 [docs/VISION.md](docs/VISION.md)。 |
| effort 档位 | 消息框上方的一个选择器：提供方 → 模型 → `default`/`low`/`high`/`max`，按协议各自翻译（OpenAI 形状的 API 用 `reasoning_effort`，Anthropic 用扩展思考预算）。`default` 什么都不发，所以普通模型不受影响。 |
| 文件夹选择器 | 「添加工作区」*就是*文件夹选择器：点一下打开桌面自己的选择器（`zenity`、`kdialog` 或 Swing），回来的文件夹就以它自己的名字成为工作区，重名时加后缀。没有选择器的机器退回同一次点击打开的表单，所以手输路径——或用同一个选择器浏览到某个路径——总是可行的。 |
| 工作区 | VS Code 风格的侧栏：每个工作区是一个文件夹，展开就是它自己的会话，带懒加载、按行删除和按节点移除。一行用当初问它的第一件事作标签，整行都是一个点击目标，回合结束时重新读取列表，所以你刚进行的对话就在最上面。带各自会话历史的具名目录——`ccj` 启动时用的工作区保留原来的会话目录，新增的在 `<home>/workspaces/<name>/` 下拥有自己的。切换会一步同时改变工作目录*和*历史，当前工作区就是工具运行的地方，无论进程是从哪里启动的，而读一个折叠的文件夹绝不会移动你所在的会话。 |
| Web UI | `ccj` 把同一个循环作为单独一个页面来服务：流式转录、工具卡片、阻塞的审批提示、会话切换，以及运行时配置模型的设置面板。没有框架，没有构建步骤。在手机上两个面板变成盖在转录上的抽屉。它在 loopback 上服务；当机器在某个 tailnet 里时，也在它的 tailnet 地址上服务——所以同一个页面距离运行它的笔记本和兜里的手机都只有一条命令，网络那一侧唯一需要的就是一个生成的 token。 |
| 同时进行多个对话 | 一个会话里运行的回合不会锁住服务器：第一个还在干活时可以在另一个对话里开始第二个任务，侧栏会标出正在运行的会话（■ 可以从任何地方停掉一个，不用打开它）。每个会话仍然只能*一次一个*回合——两个写者写同一份转录就是它损坏的方式——而改变每个对话所依赖之物的操作（模型、配置文件、工作区）会等到没有东西在跑。消息的载荷带有它们的会话 id，所以来自后台回合的 delta 绝不可能被渲染进你正在读的转录里。 |
| 自举 | 代理可以重新构建这个项目并安装结果：`mvn -q -DskipTests -Djar.name=ccj-next package` 写出一个临时 jar，不碰正在使用的那个（截断一个 JVM 正在执行的 jar，就是进程在构建中途死掉的方式），然后 `restart` 把它改名到位，启动器运行新代码——**落回你原来那个对话**：结束的进程把那个会话 id 记下来，下一个进程打开它，所以重启不再给你一份空转录。见 [docs/BOOTSTRAP.md](docs/BOOTSTRAP.md)。 |

## 用法

```
    用法: ccj [options]

模式:
      (无 flag)                启动 web UI，并在浏览器中打开它
          --repl               在本终端里开启交互式 REPL
          --web                默认值，只是显式写出来（供脚本使用）
          --no-open            启动 web UI 但不打开浏览器
      -p, --print <prompt>     运行单个回合，打印答案后退出

演示:
      --demo                   不需要模型也不需要密钥：read/run/list/search 变成真实的工具
                               调用，因此可以把主循环、工具和审批提示都试一遍

模型:
          --provider <name>    要使用的提供方（openai、anthropic……）
          --model <name>       模型标识符
          --base-url <url>     覆盖提供方的 base URL
          --api-key <key>      API 密钥字面值
          --api-key-env <var>  保存 API 密钥的环境变量
          --temperature <n>    采样温度
          --max-tokens <n>     响应 token 上限
          --max-context-tokens <n>
                               提示词预算：超过它，旧的工具结果会被省略，更早的往来会在请求
                               发出之前被丢弃
          --reasoning <level>  模型该思考多少：low、high 或 max
          --system <text>      本次运行的系统提示词
          --language <name>    无论用户用什么语言书写，都用这种语言思考和回答
                               （"auto" 交给模型决定）；见「设置」里的列表

视觉（图片先由这个模型描述，然后才进入对话）:
          --vision-base-url <url>    视觉模型的端点
          --vision-model <name>      该端点上的模型标识符
          --vision-api-key <key>     它的 API 密钥字面值
          --vision-api-key-env <var> 保存那把密钥的环境变量
          --vision-max-tokens <n>    一次描述可以占用的空间（默认 8192）。推理模型会先把它
                               花完才开始写任何东西，所以给得太少会返回空答案，而不是短答案
                               以上都不设置时，图片描述功能关闭。不会复用主提供方：看截图
                               和写代码是不同的取舍，而私密照片用本地模型才合适。把这些设置
                               写进 config.json 的 "vision" 块即可保留。

会话:
          --resume <id>        按 id 重新打开一个会话
          --continue           重新打开最近的一个会话
          --list-sessions      打印会话后退出
          --workspace <name>   使用某个工作区：它的目录，以及它自己的会话

Web:
          --port <n>           web UI 端口（默认 6767）
          --host <addr>        在何处提供服务（默认：127.0.0.1，且当本机位于 tailnet 上时，
                               连它的 tailnet 地址一起监听；'tailscale' 表示只监听 tailnet
                               地址；除 loopback 之外的任何地址都需要 token）
          --web-token <token>  要求每个 web 请求都带上这个 token
                               （也可以设置 CCJ_WEB_TOKEN；flag 优先）
          --subagents          允许代理委派给子代理（会额外消耗 token）
          --wallpapers <dir>   页面轮换作为背景的图片
                               （默认：~/Pictures/ccj-backgrounds，或 CCJ_WALLPAPERS）

    运行:
          --config <file>      配置文件（默认：<home>/config.json）
          --home <dir>         应用主目录（默认：~/.oh-my-ccj）
      -C, --cwd <dir>          工具解析相对路径时依据的工作目录
          --tools              打印可用的工具后退出
          --yolo               批准每一个工具调用，别名 --auto-approve
      -h, --help               打印这份帮助
      -v, --version            打印版本号

    web UI 是默认前端；--repl 提供终端前端。在 REPL 里输入 /help 查看命令。模型设置位于
    web UI 中，可以在运行时于那里更改。
```

REPL 命令：`/help`、`/exit`、`/clear`、`/new`、`/resume <id>`、`/sessions`、`/model <name>`、
`/compact`、`/undo`、`/tools`、`/config`、`/yolo`。

### 配置

优先级从低到高：内置默认值 → `~/.oh-my-ccj/config.json` → `CCJ_*` 环境变量 → 命令行 flag。

```json
{
  "provider": "openai",
  "model": "gpt-4o-mini",
  "baseUrl": "https://api.openai.com/v1",
  "apiKeyEnv": "OPENAI_API_KEY",
  "temperature": 0.2,
  "maxTokens": 4096,
  "autoApprove": false,
  "outputLimitBytes": 32768,
  "systemPrompt": "optional override"
}
```

| 环境变量 | 含义 |
|---|---|
| `CCJ_PROVIDER`, `CCJ_MODEL`, `CCJ_BASE_URL`, `CCJ_API_KEY`, `CCJ_API_KEY_ENV` | 模型选择与凭据 |
| `CCJ_TEMPERATURE`, `CCJ_MAX_TOKENS`, `CCJ_OUTPUT_LIMIT_BYTES` | 采样与输出上限 |
| `CCJ_REASONING`, `CCJ_MAX_CONTEXT_TOKENS` | 推理强度、提示词预算 |
| `CCJ_AUTO_APPROVE`, `CCJ_SYSTEM_PROMPT` | 审批模式与提示词覆盖 |
| `CCJ_VISION_BASE_URL`, `CCJ_VISION_MODEL`, `CCJ_VISION_API_KEY`, `CCJ_VISION_API_KEY_ENV`, `CCJ_VISION_MAX_TOKENS` | 描述图片的那个模型（它自己的端点、密钥和补全预算；未设置表示该功能关闭——预算除外，它有默认值） |
| `CCJ_WALLPAPERS` | 页面轮换作为背景的图片 |
| `CCJ_WEB_TOKEN` | 网络地址必须携带的 token（不传 `--web-token` 时；`--web-token` 优先，否则用 `~/.oh-my-ccj/web-token`） |
| `CCJ_HOME` | 应用主目录 |

对 `openai` 和 `anthropic`，`model` 可以省略：默认分别是 `gpt-4o-mini` 和 `claude-sonnet-4-5`。当
`baseUrl` 指向你自己的端点时，它们被刻意**不**应用——中继和本地服务器怎么命名模型都行，在那里猜会把一个
清楚的配置错误换成一个晦涩的 404。这种情况下 `--model` / `CCJ_MODEL` 是必需的，错误信息也这么说。

密钥来自配置文件里的 `apiKey`，或 `apiKeyEnv` 指定的环境变量（OpenAI 兼容默认 `OPENAI_API_KEY`，
Anthropic 默认 `ANTHROPIC_API_KEY`）；把它留在环境里就意味着它不会落到文件里。密钥从不打印——`/config`
和错误输出最多显示 `***1234`。

### 工具

| 工具 | 参数 | 说明 |
|---|---|---|
| `read` | `path`, `offset?`, `limit?` | 行带编号，可用 `offset` 续读，拒绝二进制文件 |
| `write` | `path`, `content` | 创建父目录，报告字节数；审批时显示覆盖预览 |
| `edit` | `path`, `old_string`, `new_string`, `replace_all?` — 或 `edits: [{old_string, new_string, replace_all?}]` | 精确匹配；有歧义就报错并给出匹配数，失败的 hunk 会带着该文件中预期位置附近的行一起返回。`edits` 形式会**一起**改动同一个文件里的多处：一次审批、一次写入，而且除非每个 hunk 都匹配，否则什么都不写（与另一块重叠的 hunk，或匹配不上的 hunk，都会拒绝整个改动）。审批显示的是整份文件的一份差异 |
| `bash` | `command`, `cwd?`, `timeout_seconds?` | `/bin/bash -lc`，stderr 合并，报告退出码，输出用 head+tail 封顶 |
| `glob` | `pattern`, `path?` | 相对路径的 glob，含 `**`，最新的在前，跳过 `target/`、`.git/`、`node_modules/`、`.idea/` |
| `grep` | `pattern`, `path?`, `glob?`, `ignore_case?`, `max_results?` | Java 正则，跳过二进制文件和超过 2 MiB 的文件 |
| `fetch` | `url`, `max_bytes?` | 第一个离开本机的工具：只允许 `http`/`https`（其它 scheme 在被拨号之前就按名字拒绝），只允许文本内容类型，读取的正文默认封顶 200 KB、最多 1 MiB，重定向手工跟随，所以每一跳都做 scheme 检查。它像其它工具一样请求审批，URL 就是规则匹配的对象——`{"tool": "fetch", "command": "https://docs.example.com/*"}`。它不是浏览器：没有 JavaScript、没有 cookie、没有认证、没有 POST |
| `restart` | `built` | 把一份临时构建安装到正在使用的 jar 上并在它上面重启；结束本次运行。见 [docs/BOOTSTRAP.md](docs/BOOTSTRAP.md) |

工具失败绝不会杀死会话：错误的参数、未知的工具名、缺失的文件和抛出的异常都会作为错误结果回来，模型能读到
并改正。`restart` 是唯一会故意结束本次运行的工具——而且只在它成功时才这样。

### 安全模型

- `read`、`glob` 和 `grep` 自由运行；`write`、`edit` 和 `bash` 需要审批。
- 有终端时，审批会问四个回答——`y` 只允许这一次、`s` 本会话都允许、`a` 始终允许、`N` 拒绝——并显示工具、完整命令、解析后的工作目录和超时；等待期间暂停 spinner。
- 没有终端时（管道、CI），有副作用的调用会被**拒绝**并给出解释，除非传了 `--yolo`。失败时收紧才是重点。
- 相对路径按 `--cwd` 解析；它之外的路径会在审批提示里被标出。

## 自定义提供方

内置名字是编译进去的；其余的都是你的：

```bash
curl -X POST localhost:6767/api/providers -H 'content-type: application/json' -d '{
  "name": "myrelay", "kind": "openai",
  "baseUrl": "https://relay.example.com/v1",
  "apiKeyEnv": "MY_KEY", "models": ["deepseek-v4-flash"]}'
ccj --provider myrelay --model deepseek-v4-flash
```

`kind` 是线上协议——任何讲 `/chat/completions` 的都算 `openai`，messages API 算 `anthropic`。定义存放
在 `~/.oh-my-ccj/providers.json`，存储前会先校验，并可以在设置面板里管理。UI 读的目录来自
`ModelCatalog` 接口，而不是直接来自配置，所以一个清楚自己模型列表的网关可以自己作答——见
[docs/ROUTER.md](docs/ROUTER.md)。

## 工作区

一个工作区就是一个目录加上属于它的会话，因为对话只有和它所谈的文件放在一起才有意义：

```bash
ccj --workspace api            # this run works in api's directory, with api's sessions
```

当前工作区就是工具运行的地方，无论 `ccj` 是从哪个目录启动的——启动目录只在注册表为空时用来挑一个工作区，
从不用来移动工作目录。`-C <dir>` 是唯一的例外，一个单次运行的覆盖，使用它的会话会说出来，而不是让工作区
树暗示另一种情况。

在浏览器里工作区切换器做同样的事，从那里添加只要点一下：**添加工作区**打开桌面自己的文件夹选择器，回来的
文件夹就成为工作区——名字取自文件夹，重名时加数字后缀，已经在列表里的目录会被拒绝，而不是给它第二份历史。
目录不存在时就创建。注册表是 `~/.oh-my-ccj/workspaces.json`；你第一次启动 `ccj` 时所在的工作区仍然映射
到顶层的 `~/.oh-my-ccj/sessions`，所以在工作区出现之前的会话还在那里。模型设置刻意**不**按工作区区分
——密钥和模型属于账号，不属于文件夹。忘记一个工作区永远不会删除对话文件。

## 提供方、端点和密钥

`baseUrl`、`apiKey` 和 `apiKeyEnv` 描述的是一个*提供方*，而不是会话，所以 `config.json` 为每个提供方保留
一对：顶层是生效中的那些字段（用 `settingsFor` 标出），另外为每一个你输入过的其它提供方保留一个
`remembered` 条目。

```json
{
  "provider": "CommandCode",
  "model": "deepseek/deepseek-v4.1-flash",
  "apiKey": "…",
  "settingsFor": "CommandCode",
  "remembered": { "deepseek": { "apiKey": "…", "baseUrl": "https://api.deepseek.com" } }
}
```

切换提供方会把要离开的那一对移进 `remembered`，再把新提供方的那一对取出来，所以两个方向的切换都是免费的，
密钥也绝不搬家：它只被发送给当初为它输入的那个提供方，配那个提供方的端点。没有记住配对的提供方由它自己的
定义（`providers.json`）或内置默认值提供。选中某个提供方时清空密钥字段，就是忘掉那个提供方的密钥；其它
提供方的都不受影响。设置表单按提供方显示「已保存」（`rememberedProviders` 只带名字，从不带密钥），因为
一个承诺了却不会发送的密钥，正是错误端点被再次写下来的方式。文件只记录你选了什么——默认得到的端点或密钥
变量名会被省略，在加载时重新推导。

## 架构

```
cli/       argument parsing, wiring, REPL, exit codes   (the only place that calls System.exit)
ui/        AgentListener implementation: streaming text, tool cards, spinner, ANSI
web/       the same loop behind HTTP: agent hub, SSE stream, approval handshake, single page
workspace/ the workspace registry: named directories and where their sessions live
demo/      a tool-routing stand-in for a model, so the CLI can be demonstrated with no key
session/   JSONL message codec, FileSession, SessionStore
tool/      read write edit bash glob grep + approval-aware helpers
provider/  OpenAI-compatible and Anthropic providers over java.net.http, plus the SSE reader
core/      Message, Provider, Tool, ToolRegistry, AgentLoop, Session, Config, AppPaths
```

`core` 是契约；其它一切都插到它上面。循环只看见一个 `Provider` 和一个 `ToolRegistry`，对 HTTP 或终端
一无所知，所以同一个循环能在测试里无头运行、在脚本化的提供方之后运行、或对着一个 mock HTTP 服务器运行。
消息模型、循环算法、线上映射和设计背后的推理见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 开发

```bash
./mvnw test                               # the whole suite: no network, no API key
mvn -Dtest=CliEndToEndTest test           # end-to-end through the CLI only
mvn -Dtest=WebApiTest test                # HTTP + SSE + approval handshake only
mvn -DskipTests package                   # fat jar
```

所有测试都是离线的。提供方测试和端到端测试会起一个脚本化的 HTTP 服务器（`com.sun.net.httpserver`），它用
分块传输编码和刻意打散的帧讲两种线上格式，所以 SSE 拼装是在真实中继会产生的那种边界上被测试的。端到端测试
驱动真正的 CLI：请求经 HTTP 发出、SSE 被解析、工具被执行、文件落到磁盘、会话被写入。

| 领域 | 测试数 |
|---|---|
| providers（SSE 解析、两种线上映射、重试策略、自定义定义、effort 档位、目录） | 75 |
| tools（匹配、截断、超时、取消、拒绝路径） | 41 |
| core（循环行为、工具重叠、中止触达流式模型调用、项目自己的 CCJ.md 规则领起提示词、上下文预算、token 估算、配置优先级、按提供方设置、被中断历史的修复、思考语言提示、压缩） | 108 |
| session（编解码往返含 thinking、追加/重开、列表缓存、generation 文件、重启交接备注） | 49 |
| CLI（参数解析、模式选择、端口建议） | 13 |
| end-to-end（CLI → HTTP → 工具 → 磁盘、推理、预算、工具在哪里运行、CCJ.md 到达请求、重启落在同一个对话） | 26 |
| web API（HTTP、SSE、审批熬过会话切换、按会话拒绝、会话并行运行、打开忙碌的会话、token 闸门、host 守卫、设置、工作区、删除、模型、提供方、压缩） | 88 |
| web rendering（markdown、会话行、审批、重放、添加工作区、压缩等用例，在 node 下运行） | 20 |
| restart（替换、它的拒绝，以及没有审批时什么都不会发生） | 8 |
| folder chooser（子进程管线、超时、单对话框守卫） | 5 |
| wallpapers（数字排序、类型取自字节、包含关系、没有目录） | 8 |
| tailnet（`--host tailscale` 解析出的地址，以及什么会被拒绝） | 5 |
| workspaces（注册表规则、持久化、隔离、给选中的文件夹命名） | 11 |
| demo provider（路由、终止） | 8 |

上面的数字来自维护者环境里的最后一次运行。CI 在 Linux 和 macOS 上实际跑的是 `./mvnw -B -ntp verify`
——见 [.github/workflows/build.yml](.github/workflows/build.yml)。工作要往哪去，见
[docs/ROADMAP.md](docs/ROADMAP.md)；这里的代码、测试和文档是怎么写的，见
[docs/CONVENTIONS.md](docs/CONVENTIONS.md)。

## 限制

- 每个会话同一时刻只跑一个对话。多个对话可以并行，而且代理可以委派给**子代理**——子代理在自己的会话里读，
  只回报一份摘要——除非传了 `--subagents`，否则关闭，因为一次受委派的运行会花掉用户并没有为某条消息而敲
  的 token。在一个对话内部，只有只读工具并发运行：只有它们之间的重叠不会改变转录的含义。见
  [docs/SUBAGENTS.md](docs/SUBAGENTS.md)。
- 图片是单向的，而且隔了一层：图像由视觉模型描述，*描述*加入对话。主模型从不接收图像，所以「这个错误说的
  是什么」和「把这块白板转录下来」能用，而「这个像素是不是正确的蓝色」不能——就描述丢掉的细节再问一次，
  或者 `read` 那个文件，因为它被告诉去看那里。一次一张图片，最多 8 MB；第二次上传取代还在等着的那一张，而发送之后它就跟着消息走了。
- 没有插件、没有沙箱、没有多用户账号——审批是唯一的防线，`--yolo` 会把它拿掉。
- Bash 以当前用户身份、带着你的完整环境运行。
- 磁盘上的会话会无上限地增长。放不进上下文预算的内容会被省略或丢掉；你用 `/compact` *要求*总结的会被
  总结，只有到那时它才是一份你能看见、能退回的摘要——它替换掉的回合仍然在旁边的 generation 文件里，
  摘要会点出那个文件，使模型能读回某个细节，而不是相信自己的转述。
- 回合没有步数上限，所以卡住的模型会一直调用工具，直到你中止它。这就是那个取舍：长任务绝不会在某个别人
  猜的数字上被切断，而停掉一个卡住的任务是你在看着它时做的决定，不是事先猜的。
- 除非提供方报告缓存数字，否则 `cacheHitRate` 是 `n/a`——本地模型没有缓存可命中，也不会被报成 0%。
  用量总计覆盖一个会话（它们存在对话旁边，每个回合一条记录），不是一个项目或一个账单周期。
- OpenAI 那条路径针对的是 chat-completions API，不是更新的 Responses API。
- 当你让 UI 保存 API 密钥时，它是以明文存储的（文件是 `0600`）；把密钥留在环境里只差一个下拉框。
- Web UI 是一个本地控制台：多个对话可以同时干活，但一个浏览器页面显示一份转录，而且没有账号。它在具名
  地址上服务——loopback，加上这台机器有 tailnet 时的 tailnet 地址——从不绑通配地址，所以这个端口在用户
  没有指名的任何网络上都不存在。loopback 什么都不需要；其它每个地址都需要 token，因为代理能跑 shell
  命令。在 tailnet 内部，token 是区分设备的东西：网络决定谁在附近，token 决定谁被放进来。
- 同一个工作区里可以有多个对话在跑，这意味着它们的工具能写同样的文件，彼此之间没有任何先后顺序。这和同
  一个目录里开两个终端是同一种暴露面，而且是刻意不做串行化的——要点就是开始一个任务然后继续干别的。不交给
  运气的是*单个*工具：`edit` 拒绝已经变动的文本，`write` 拒绝在它的提示还开着时出现或改变过的文件，
  所以被写下去的正是你审批过的那份差异。
- `abort` 在步骤之间、每次工具调用之前停止一个回合，所以一个卡在模型自己的网络调用上的回合会先把那次调用
  跑完再停。等待审批的回合会立即停止，并且停在它所属的那个对话里。
