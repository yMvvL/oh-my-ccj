# 用法

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
          --max-total-tokens <n>
                               花费上限：这条会话累计用掉的 token（输入加输出）超过它，就不再
                               开始新的回合；不设置表示不设上限
          --reasoning <level>  模型该思考多少：low、high 或 max
          --system <text>      本次运行的系统提示词

子代理（用 --subagents 打开委派之后）:
          --subagent-model <角色>=<模型>
                               让一个角色用它自己的模型：explore 可以用便宜的那个，build 用强的那个
                               （角色是 explore、verify、build；可以重复给出）
          --subagent-reasoning <角色>=<档位>
                               让一个角色用它自己的思考档位：low、high 或 max（可以重复给出）

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
          --shell <file>       跑命令与编辑后检查的那个程序（默认：/bin/bash）
          --tools              打印可用的工具后退出
          --yolo               批准每一个工具调用，别名 --auto-approve
      -h, --help               打印这份帮助
      -v, --version            打印版本号

    web UI 是默认前端；--repl 提供终端前端。在 REPL 里输入 /help 查看命令。模型设置位于
    web UI 中，可以在运行时于那里更改。
```

REPL 命令：`/help`、`/exit`、`/clear`、`/new`、`/resume <id>`、`/sessions`、`/model <name>`、
`/compact`、`/undo`、`/tools`、`/config`、`/yolo`。

## 配置

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
| `CCJ_REASONING`, `CCJ_MAX_CONTEXT_TOKENS`, `CCJ_MAX_TOTAL_TOKENS` | 推理强度、提示词预算、花费上限 |
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

## 工具

| 工具 | 参数 | 说明 |
|---|---|---|
| `read` | `path`, `offset?`, `limit?` | 行带编号，可用 `offset` 续读，拒绝二进制文件 |
| `write` | `path`, `content` | 创建父目录，报告字节数；审批时显示覆盖预览 |
| `edit` | `path`, `old_string?`, `new_string?`, `replace_all?`, `edits?` | 要么给 `old_string` 与 `new_string`，要么给 `edits`——schema 因此没有把其中一个标成必填。精确匹配；有歧义就报错并给出匹配数，失败的 hunk 会带着该文件中预期位置附近的行一起返回。`edits: [{old_string, new_string, replace_all?}]` 会**一起**改动同一个文件里的多处：一次审批、一次写入，而且除非每个 hunk 都匹配，否则什么都不写（与另一块重叠的 hunk，或匹配不上的 hunk，都会拒绝整个改动）。审批显示的是整份文件的一份差异 |
| `bash` | `command`, `cwd?`, `timeout_seconds?` | 用配置里的那个 shell 跑（默认 `/bin/bash -lc`；`cmd.exe` 收到 `/c`），stderr 合并，报告退出码，输出用 head+tail 封顶 |
| `glob` | `pattern`, `path?` | 相对路径的 glob，含 `**`，最新的在前，跳过 `target/`、`.git/`、`node_modules/`、`.idea/` |
| `grep` | `pattern`, `path?`, `glob?`, `ignore_case?`, `max_results?` | Java 正则，跳过二进制文件和超过 2 MiB 的文件 |
| `fetch` | `url`, `max_bytes?`, `as?` | 第一个离开本机的工具：只允许 `http`/`https`（其它 scheme 在被拨号之前就按名字拒绝），只允许文本内容类型，读取的正文默认封顶 200 KB、最多 1 MiB，重定向手工跟随，所以每一跳都做 scheme 检查。它像其它工具一样请求审批，URL 就是规则匹配的对象——`{"tool": "fetch", "command": "https://docs.example.com/*"}`。可选参数 `as` **只接受 `"text"`**：指定时把响应体的 HTML 剥成文本（去标签与注释、去 `script`/`style` 的内容、块级标签换行、连续空白压成一个、解开常见实体），并在结果开头先说明它剥过标记、而且这是一次**由调用方要求的猜测**；别的取值当场拒绝，不传则按原样返回、行为一字不变。它不是浏览器：没有 JavaScript、没有 cookie、没有认证、没有 POST |
| `restart` | `built` | 把一份临时构建安装到正在使用的 jar 上并在它上面重启；结束本次运行。见 [BOOTSTRAP.md](BOOTSTRAP.md) |

工具失败绝不会杀死会话：错误的参数、未知的工具名、缺失的文件和抛出的异常都会作为错误结果回来，模型能读到
并改正。`restart` 是唯一会故意结束本次运行的工具——而且只在它成功时才这样。

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

## 想要一个自带运行时的包？

默认不发安装包，就是那个 2.9 MB 的 jar：任何 JDK 21+ 都能跑，不需要构建工具，拷到哪台机器都行。如果要的
是「连运行时一起带走」的东西，`jpackage`（JDK 自带）一行就够：

```bash
jpackage --type app-image --name ccj --input target --main-jar ccj.jar \
  --main-class com.ccj.agent.cli.Main --dest dist \
  --add-modules java.net.http,jdk.httpserver,java.desktop
```

在一台 Linux 上实测：产物 98 MB（里面是一份 `jlink` 出来的运行时），`dist/ccj/bin/ccj --version` 与一个
真实的 `--demo` 回合都跑得通。**没有**做成 `deb`/`rpm`/`msi`/`dmg` 那样的安装包，因为那需要目标平台自己的
工具链（`dpkg-deb`、`rpmbuild`、WiX、Xcode），而 `jpackage` **不能跨平台构建**——为三个平台出包要在三台
机器上各跑一次，这个项目没有那三台机器。
