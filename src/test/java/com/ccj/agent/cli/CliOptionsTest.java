package com.ccj.agent.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Config;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CliOptionsTest {

  @Test
  void inlineAndSeparateValueFormsAreEquivalent() {
    CliOptions inline = CliOptions.parse(new String[] {"--model=gpt-4o", "--max-tokens=700"});
    CliOptions separate = CliOptions.parse(new String[] {"--model", "gpt-4o", "--max-tokens", "700"});

    assertEquals("gpt-4o", inline.model());
    assertEquals(700, inline.maxTokens());
    assertEquals("gpt-4o", separate.model());
    assertEquals(700, separate.maxTokens());
  }

  @Test
  void everyFlagAdvancesPastItself() {
    // 一个分支忘了移动游标的 flag，会把 parse() 变成一个无限循环：烧掉一个核，什么都不打印
    // ——进程启动了、永远不绑定端口、也不说为什么。这事发生过：`--subagents` 加进来时漏了自增，
    // 而没有任何东西发现它，因为没有测试让这个 flag 走过一次真正的解析。这里的每个用例都把这个
    // flag 放在另一个 flag 前面，因此不前进的分支会让测试挂住，而不是被发出去。
    CliOptions all =
        CliOptions.parse(
            new String[] {
              "--subagents",
              "--model", "m",
              "--provider", "p",
              "--base-url", "http://x",
              "--api-key", "k",
              "--cwd", "/tmp",
              "--workspace", "w",
              "--port", "1234",
              "--host", "127.0.0.1",
              "--web-token", "t",
              "--wallpapers", "none",
              "--language", "English",
              "--max-tokens", "10",
              "--temperature", "0.5",
              "--reasoning", "high",
              "--max-context-tokens", "100",
              "--vision-base-url", "http://x",
              "--vision-model", "vm",
              "--vision-api-key-env", "VV",
              "--vision-api-key", "vk",
              "--vision-max-tokens", "6000",
            });

    assertTrue(all.subAgents(), "--subagents 是个开关，也正是曾经坏掉的那个");
    assertEquals("m", all.model(), "而且它后面的 flag 仍然被走到了");
    assertEquals("p", all.provider());
    assertEquals("w", all.workspace());
    assertEquals(1234, all.port());
    assertEquals("t", all.webToken());
    assertEquals("English", all.language());
    assertEquals(10, all.maxTokens());
    assertEquals(100, all.maxContextTokens());
    assertEquals("http://x", all.visionBaseUrl());
    assertEquals("vm", all.visionModel(), "最后一个也仍然拿到了它的值");
    assertEquals("vk", all.visionApiKey());
    assertEquals(6000, all.visionMaxTokens());
  }

  @Test
  void theVisionFlagsBecomeAVisionBlock() {
    Config overrides =
        CliOptions.parse(
                new String[] {
                  "--vision-base-url", "http://127.0.0.1:8080/v1",
                  "--vision-model", "llava",
                  "--vision-api-key", "sk-vision",
                  "--vision-api-key-env", "MY_VISION_KEY",
                })
            .overrides();

    assertEquals("http://127.0.0.1:8080/v1", overrides.vision().baseUrl());
    assertEquals("llava", overrides.vision().model());
    assertEquals("sk-vision", overrides.vision().apiKey());
    assertEquals("MY_VISION_KEY", overrides.vision().apiKeyEnv());

    assertNull(CliOptions.parse(new String[0]).overrides().vision(), "没有 flag，就没有块");
    // 一个 flag 就是一个只点名某个字段的块，因此它下面文件里的端点会在合并中存活下来，而不是
    // 被一个从未提到它的 flag 清掉。
    Config modelOnly = CliOptions.parse(new String[] {"--vision-model", "internvl"}).overrides();
    assertEquals("internvl", modelOnly.vision().model());
    assertNull(modelOnly.vision().baseUrl());

    // 单独的预算 flag，正是截图回来是空的时候有人会去抓的那个：1500 不够一个推理模型想完并
    // 开始描述。
    Config budgeted =
        CliOptions.parse(new String[] {"--vision-max-tokens", "16384"}).overrides();
    assertEquals(16384, budgeted.vision().maxTokens().intValue());
    assertNull(budgeted.vision().baseUrl(), "那个 flag 只点了预算，别无所指");
    assertNull(budgeted.vision().model(), "它下面文件里的端点没有被动过");
    assertTrue(
        CliOptions.usage().contains("--vision-max-tokens"),
        "没人找得到的 flag，就是没人有的 flag");
  }

  @Test
  void subAgentsAreOffUnlessAskedFor() {
    // 它会把 token 花在一段用户没有输入过任何消息的对话上，所以是选入式的。
    assertFalse(CliOptions.parse(new String[] {}).subAgents());
    assertTrue(CliOptions.parse(new String[] {"--subagents"}).subAgents());
  }

  @Test
  void shortFlagsAndAliasesAreParsed() {
    CliOptions options =
        CliOptions.parse(
            new String[] {
              "-p", "do the thing", "-C", "/tmp", "--auto-approve", "-v", "--max-tokens", "512"
            });

    assertEquals("do the thing", options.print());
    assertEquals("/tmp", options.cwd());
    assertTrue(options.yolo());
    assertTrue(options.version());
    assertEquals(512, options.maxTokens());
  }

  @Test
  void theWebTokenComesFromTheFlagOrTheEnvironment() {
    // flag 优先，因此一次性运行可以换个 token 而无需先取消什么；环境变量则让「手机连笔记本」
    // 的配置成为可能，而不用把秘密粘到命令行上——那是 `ps` 和 shell 历史都会读回去的东西。
    assertEquals("flag", Cli.webToken(CliOptions.parse(new String[] {"--web-token", "flag"}), Map.of()));
    assertEquals(
        "env", Cli.webToken(CliOptions.parse(new String[0]), Map.of(Cli.ENV_WEB_TOKEN, "env")));
    assertEquals(
        "flag",
        Cli.webToken(
            CliOptions.parse(new String[] {"--web-token=flag"}), Map.of(Cli.ENV_WEB_TOKEN, "env")));

    // 空值来自任一来源都表示「未设置」，不是空密码：用一个没人会打错的 token 去服务一个网络
    // 绑定，等同于不设 token 地服务它，而那是被拒绝的。
    assertNull(Cli.webToken(CliOptions.parse(new String[0]), Map.of()));
    assertNull(Cli.webToken(CliOptions.parse(new String[0]), Map.of(Cli.ENV_WEB_TOKEN, "   ")));
    assertNull(Cli.webToken(CliOptions.parse(new String[] {"--web-token", "  "}), Map.of()));
    assertNull(Cli.webToken(CliOptions.parse(new String[0]), null));

    // 来自 shell 的首尾空白（`export CCJ_WEB_TOKEN="$(cat token)"`）不是它的一部分。
    assertEquals(
        "env", Cli.webToken(CliOptions.parse(new String[0]), Map.of(Cli.ENV_WEB_TOKEN, " env\n")));
  }

  @Test
  void homeOverridesTheApplicationDirectory() {
    CliOptions options = CliOptions.parse(new String[] {"--home", "/tmp/ccj-home"});

    assertEquals("/tmp/ccj-home", options.home());
    assertNull(CliOptions.parse(new String[0]).home());
  }

  @Test
  void theReasoningTierAndContextBudgetAreFlagsToo() {
    CliOptions options =
        CliOptions.parse(new String[] {"--reasoning", "high", "--max-context-tokens", "120000"});

    assertEquals("high", options.reasoning());
    assertEquals(120000, options.maxContextTokens());

    // web 里的选择器不是设置它们的唯一途径：两者都会到达配置层。
    Config overrides = options.overrides();
    assertEquals("high", overrides.reasoning());
    assertEquals(120000, overrides.maxContextTokens());
    assertNull(CliOptions.parse(new String[0]).reasoning());
    assertNull(CliOptions.parse(new String[0]).maxContextTokens());
  }

  @Test
  void unknownFlagIsAUsageError() {
    CliOptions.UsageException error =
        assertThrows(
            CliOptions.UsageException.class, () -> CliOptions.parse(new String[] {"--nope"}));

    assertTrue(error.getMessage().contains("--nope"), error.getMessage());
  }

  @Test
  void missingValueIsAUsageError() {
    assertThrows(CliOptions.UsageException.class, () -> CliOptions.parse(new String[] {"--model"}));
    assertThrows(CliOptions.UsageException.class, () -> CliOptions.parse(new String[] {"-p"}));
    assertThrows(
        CliOptions.UsageException.class,
        () -> CliOptions.parse(new String[] {"--model", "--yolo"}));
  }

  @Test
  void nonNumericValuesAreAUsageError() {
    assertThrows(
        CliOptions.UsageException.class,
        () -> CliOptions.parse(new String[] {"--max-tokens", "many"}));
    assertThrows(
        CliOptions.UsageException.class,
        () -> CliOptions.parse(new String[] {"--temperature", "hot"}));
  }

  @Test
  void yoloMapsToAutoApproveAndUnsetStaysNull() {
    Config yolo = CliOptions.parse(new String[] {"--yolo"}).overrides();
    Config alias = CliOptions.parse(new String[] {"--auto-approve"}).overrides();
    Config plain = CliOptions.parse(new String[] {"-p", "hi"}).overrides();

    assertEquals(Boolean.TRUE, yolo.autoApprove());
    assertEquals(Boolean.TRUE, alias.autoApprove());
    assertNull(plain.autoApprove());
  }

  @Test
  void overridesCarryOnlyTheGivenFlags() {
    Config overrides =
        CliOptions.parse(
                new String[] {
                  "--provider",
                  "anthropic",
                  "--model=claude-x",
                  "--temperature",
                  "0.2",
                  "--system",
                  "be brief",
                  "--language",
                  "Simplified Chinese"
                })
            .overrides();

    assertEquals("anthropic", overrides.provider());
    assertEquals("claude-x", overrides.model());
    assertEquals(0.2, overrides.temperature());
    assertEquals("be brief", overrides.systemPrompt());
    assertEquals("Simplified Chinese", overrides.language());
    assertNull(overrides.baseUrl());
    assertNull(overrides.apiKey());
    assertNull(overrides.apiKeyEnv());
    assertNull(overrides.maxTokens());
    assertNull(overrides.outputLimitBytes());
  }

  @Test
  void frontEndFlagsAndTheirDefaults() {
    CliOptions bare = CliOptions.parse(new String[] {});

    assertFalse(bare.web(), "web UI 是默认值，所以 --web 永远只是显式写出来的");
    assertFalse(bare.repl());
    assertFalse(bare.noOpen());

    CliOptions repl = CliOptions.parse(new String[] {"--repl"});
    assertTrue(repl.repl());
    assertFalse(repl.web());

    CliOptions scripted = CliOptions.parse(new String[] {"--web", "--no-open", "--port", "9000"});
    assertTrue(scripted.web());
    assertTrue(scripted.noOpen());
    assertEquals(9000, scripted.port());

    assertThrows(
        CliOptions.UsageException.class,
        () -> CliOptions.parse(new String[] {"--repl", "--web"}));
  }

  @Test
  void demoIsIndependentOfTheFrontEnd() {
    CliOptions demo = CliOptions.parse(new String[] {"--demo"});

    assertTrue(demo.demo());
    assertFalse(demo.repl(), "前端仍然由模式 flag 决定");
    assertNull(demo.print());
  }
}
