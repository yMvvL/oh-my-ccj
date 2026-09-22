# oh-my-ccj

[![build](https://github.com/yMvvL/oh-my-ccj/actions/workflows/build.yml/badge.svg)](https://github.com/yMvvL/oh-my-ccj/actions/workflows/build.yml)

**A coding agent in plain Java 21, written from scratch.** No agent framework, no HTTP client library,
no CLI library, no build step for the page it serves, and exactly one runtime dependency (Jackson).
It streams from the model, runs tools on your machine, and asks you before anything runs.

**Status: a personal tool, not a product.** Built for one person and used daily: no support, no release
cadence, no backwards-compatibility promise. Everything below, and the UI itself, is in Chinese — that
is the language the project is written in. To see what it is before reading any of it:

```
./mvnw -DskipTests package && ./ccj --demo     # no API key, no network
```

Requires JDK 21+ on Linux or macOS. MIT licensed ([LICENSE](LICENSE)). It is deliberately *not* a
sandbox: the approval prompt is the only guard, and `--yolo` removes it.

---

从零开始用纯 Java 21 写成的编码代理运行时——22.7k 行 Java 加一个 8.8k 行的原生页面，旁边还有 21.7k 行
测试。它是每个编码代理都围绕的那个循环的一个小巧、可读的实现。

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

## 它和别的代理不一样的地方

- **图片是从手机进来的。** 拍一张、粘一张截图、拖进窗口，或者让手机上那个页面来做——一个**独立的视觉
  模型**把它写成描述，然后**描述**（不是图像）和你的下一句话拼成一条消息进入对话。主模型从不接收图像，
  所以没有任何线上格式、渲染器或压缩步骤需要长出图像分支；转录里它被摆成图：缩略图、可展开的描述，然后
  才是你说的话。[docs/VISION.md](docs/VISION.md)
- **同一个 jar 也是网页控制台，手机可以直接用。** 笔记本和兜里的手机说的是**同一份本地文件**：没有账号、
  没有云、没有前端构建步骤。它只在具名地址上服务——loopback，加上这台机器有 tailnet 时的 tailnet 地址
  ——从不绑通配地址，而除 loopback 外每个地址都要一个 token。[docs/WEBUI.md](docs/WEBUI.md)
- **不要密钥也跑得起来。** `--demo` 自带一个本地替身模型：不要账号、不要网络、不要第二个进程，就能把一整个
  回合、工具调用和审批提示走一遍。别家都要一把密钥，或者一个自备的兼容端点。
- **审批是唯一的防线，而它可以被记住。** 五个答案——拒绝 / 只允许这一次 / 本会话都允许 / 始终允许 / 以后
  都拒绝——两个「从现在起」各写一条规则，主语一模一样。子代理问的是同一个人，它的写入也一样要审批，而且
  它不能再往下委派：`task` 根本不在它的注册表里。[SECURITY.md](SECURITY.md)
- **每个主张都带着它的测量结果。** 743 个测试离线跑，其中一条经 CDP 驱动一个**真浏览器**跑完一个回合；
  [CHANGELOG](docs/CHANGELOG.md) 记着每次改动的测量、代价，以及**没有**验证的部分。

## 两分钟跑起来

```bash
./mvnw -DskipTests package                 # builds target/ccj.jar
./ccj --demo                               # a real turn: no API key, no network
./ccj                                      # the same page with a real model — configure it in the UI
./ccj --repl                               # terminal front end instead of the browser
./ccj -p "what does src/Main.java do?"     # one-shot

ln -sfn "$PWD/ccj" ~/.local/bin/ccj        # use `ccj` from anywhere
```

首次运行还没有配置模型，所以页面会打开设置面板索要一个（提供方、模型、base URL、密钥）；保存写入
`~/.oh-my-ccj/config.json` 并立即生效。启动器在 jar 过期时自己重建，会话也放在 `~/.oh-my-ccj` 下。

**JDK 21+，Linux 或 macOS。** 不需要 Maven、不需要 Node（Node 只在跑页面用例时需要；缺席时那些用例被
**报告**为跳过，而不是悄悄通过）。**Windows 的机制就位，但没有任何人验证过**——shell 可配置，`ccj.cmd`
在仓库根，可 CI 上没有 Windows，所以「一个回合在 Windows 上跑起来」是目标而不是一次被观察到的运行。细节见
[docs/USAGE.md](docs/USAGE.md)。

## 运行之前请先读这一节

`ccj` 会在你的机器上执行 shell 命令。这正是它的意义，也意味着下面这些不是小字条款：

- 它以**你的身份、带着你的环境**运行，`bash` 没有沙箱：你能敲的东西它都能敲。
- **审批是唯一的防线。** 会写入或执行的工具先发问；没有终端可问时，它们是被*拒绝*，而不是被当成默认同意。
  `--yolo`（或 UI 里的自动批准）把这层防线整个拿掉。
- **网页版是一个能经 HTTP 够到的 shell 提示符。** 它只在你指名的地址上服务、从不绑通配地址；能连上那个
  端口与能执行命令之间，只有那个 token。把带 token 的 URL 当密码对待。
- **不要把它暴露给你不掌控的网络。** tailnet 地址是合理的（能连上它的设备由你管理）；公网接口——VPS、
  端口转发、咖啡店网络——不合理，token 也改变不了这一点。

## 限制

- 没有沙箱、没有账号、没有云、没有插件市场——审批是唯一的防线。
- 图片是单向的，而且隔了一层：主模型看到的是描述，不是像素。
- 回合没有步数上限，所以一个卡住的模型会一直调用工具，直到你中止它。
- 每个会话同时只有一个回合；同一工作区里多个对话的工具之间没有先后顺序。
- 会话文件会无上限地增长：放不进上下文预算的内容只是被**省略**，不是被删掉。
- 完整清单——缓存命中率的诚实报法、花费上限的语义、`abort` 停在哪里、密钥以明文存在一个 `0600` 文件里、
  以及一条**未验证**的 Windows——见 [docs/USAGE.md](docs/USAGE.md)。

## 更多

- 手册：**[docs/USAGE.md](docs/USAGE.md)**（flag、REPL 命令、配置与环境变量、每个工具、全部能力、限制的完整清单）｜**[docs/WEBUI.md](docs/WEBUI.md)**（页面的每一部分，以及从别的设备够到它）
- 提供方与扩展：**[docs/ROUTER.md](docs/ROUTER.md)**（四种协议、自定义提供方）｜**[docs/MCP.md](docs/MCP.md)**（外部工具经同一个审批器进来）
- 内部：**[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**｜**[docs/BOOTSTRAP.md](docs/BOOTSTRAP.md)**（重启并落回同一个对话）｜**[docs/COMPACT.md](docs/COMPACT.md)**｜**[docs/SUBAGENTS.md](docs/SUBAGENTS.md)**
- 关于信任与写法：**[SECURITY.md](SECURITY.md)**｜**[docs/CONVENTIONS.md](docs/CONVENTIONS.md)**
- 关于这个项目：**[CONTRIBUTING.md](CONTRIBUTING.md)**｜**[docs/ROADMAP.md](docs/ROADMAP.md)**｜**[docs/CHANGELOG.md](docs/CHANGELOG.md)**｜**[docs/VISION.md](docs/VISION.md)**｜**[docs/RESUME.md](docs/RESUME.md)**
