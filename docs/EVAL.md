# 评测

一套任务，跑一遍，看它还是不是对的。

```bash
./mvnw -o -Dtest=EvalSuiteTest test     # CI 里跑的就是这一条
./ccj --eval evals/                      # 同样的一遍，在命令行上，报告打到 stdout
```

它是一道**回归门**，不是一个分数。改动提示词、工具描述、上下文预算或者模型适配时，「它还是对的吗」这个问题
在此之前只有两种回答方式：读一遍代码，或者手工试一次。现在有第三种：跑一遍任务，看哪一行从 `ok` 变成了
`FAIL`，以及它的每一条理由。

## 任务长什么样

每行一个 JSON 对象（JSONL），与这个仓库的会话文件同一条规矩：能用普通工具读、能 diff、能 grep，一行坏了不会
把整份文件带走。`#` 开头的行为注释。

```json
{"id": "edit-one-line",
 "description": "用精确匹配的 edit 改一行，其余不动",
 "prompt": "把 notes.txt 里的 TODO 改成 DONE",
 "files": {"notes.txt": "first\nTODO\nlast\n"},
 "script": [{"tool": {"name": "edit", "arguments": {"path": "notes.txt", "old_string": "TODO", "new_string": "DONE"}}},
            {"text": "改好了。"}],
 "expect": {"files": {"notes.txt": "first\nDONE\nlast\n"},
            "tools": ["edit"],
            "maxSteps": 3}}
```

| 字段 | 意思 |
|---|---|
| `id` | 这一行在报告里的名字。它出现在失败信息里，所以取一个说得清的名字 |
| `description` | 给人看的一句话 |
| `prompt` | 用户那一句话——整个任务里唯一由「人」说的东西 |
| `files` | 任务开始前写进它的临时工作目录的文件 |
| `script` | 模型那一侧每一步的录像；**有它就离线**。一段里可以用 `tool`（一个）或 `tools`（好几个），加 `text`（一段散文），加 `usage`（`[输入, 输出, 缓存]`，于是 token 也变成一个可以被比对的数字） |
| `approve` | 默认 `true`（什么都批准）。写 `false` 时每一次写入与执行都被拒绝——用来测护栏本身 |
| `expect.files` | 每个路径在跑完之后的内容，一字不差 |
| `expect.absent` | 跑完之后**不该**存在的路径 |
| `expect.finalTextContains` | 最后那段回答里必须出现的片段 |
| `expect.tools` | 工具调用的**序列**。不写这一栏是不检查；写一个空数组是要求一次都不调 |
| `expect.maxSteps` | 步数上限：超过了就是失败，而不是「多跑了两步」 |

每个任务跑在一个**临时目录**里，跑完就删掉。它看不见这个仓库，也看不见上一个任务留下的任何东西——「刚才还
好好的」在这里不是一个可能的解释。

## 离线的那一套与非离线的那一套

发布在 `evals/` 里的任务全部带 `script`，所以它们：

- 不需要模型、不需要网络、不需要密钥；
- 每次跑出同一份结果，包括步数、工具调用、token 与每一行失败的理由；
- 由 `EvalSuiteTest` 守着，于是 `./mvnw test` 与 CI 都在跑它们。

一个**没有** `script` 的任务需要一个真模型（`ccj --eval <file>` 配上模型），而它的断言必须更宽：一个真模型
不会两次走出同一条路，所以那样的任务通常只断言结果，不断言过程。它们不该进 `evals/`：一道有时绿、有时红的
门不是门，是一次抽奖。放在别处、按需跑，是它们该待的地方。

## 报告

```
任务                                           结果    模式            步数    工具      token 入/出        耗时
read-and-answer                              ok    scripted       2     1            0/0      10 ms
create-a-file                                ok    scripted       2     1            0/0       3 ms
edit-one-line                                ok    scripted       2     1            0/0       4 ms
run-a-command                                ok    scripted       3     2            0/0       9 ms
two-writes-in-one-turn                       ok    scripted       2     2            0/0       0 ms
a-refused-command-changes-nothing            ok    scripted       2     1            0/0       0 ms
a-missing-file-is-an-error-the-model-reads   ok    scripted       2     1            0/0       0 ms
an-unknown-tool-does-not-kill-the-turn       ok    scripted       2     1            0/0       0 ms
a-script-can-declare-what-the-model-spent    ok    scripted       2     1        2600/90       0 ms

合计：9/9 通过，19 步，11 次工具调用，2690 token，26 ms
```

（上面这一段是 `./mvnw -DskipTests package && ./ccj --eval evals/` 这一份输出的逐字拷贝；
一张会漂的表不如一句命令。）

不通过时，那一行下面会逐条列出没对上的断言，每一条都带着期望值与实际值：

```
edit-one-line                                FAIL  scripted       2     1            0/0       2 ms
  · notes.txt 的内容不对：期待 "first\nDONE\nlast\n"，实际是 "first\nTODO\nlast\n"
```

退出码：全过是 0，有失败是 1（`--eval` 因此可以直接当 CI 的一步），参数或任务文件本身有问题则是 2。

## 几条自己定下来的规矩

- **任务是代码。** 它们会真的跑工具，包括 shell——`approve` 默认是「什么都批准」，因为它们必须跑得动。它们
  跑在临时目录里，而它们不该被用来放任何你没看过的命令：这个工具的任何一部分里，护栏都是审批，而评测为了让
  自己跑得动，把审批关掉了。
- **任务里的路径不能跑出它的工作目录。** 一个写着 `../` 的任务被拒绝，而不是在别人的机器上写到临时目录之外。
- **运行抛出异常的，是一条失败，不是一场崩溃。** 一份报告里有十九条通过、一条「运行以异常结束：」，比一次
  在第一行就停下的运行有用得多。
- **名字不会过期。** `id` 出现在失败信息与报告里，所以它是一个稳定的名字，不是一个描述。
