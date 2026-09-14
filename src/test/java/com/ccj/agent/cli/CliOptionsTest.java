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
    // The flag wins, so a one-off run can use a different token without unsetting anything; the
    // environment is what makes a phone-to-laptop setup possible without pasting a secret into a
    // command line, which is a thing `ps` and shell history both read back.
    assertEquals("flag", Cli.webToken(CliOptions.parse(new String[] {"--web-token", "flag"}), Map.of()));
    assertEquals(
        "env", Cli.webToken(CliOptions.parse(new String[0]), Map.of(Cli.ENV_WEB_TOKEN, "env")));
    assertEquals(
        "flag",
        Cli.webToken(
            CliOptions.parse(new String[] {"--web-token=flag"}), Map.of(Cli.ENV_WEB_TOKEN, "env")));

    // Blank is "not set" from either source, not an empty password: serving a network bind with a
    // token nobody can mistype is the same as serving it with none, and that is refused.
    assertNull(Cli.webToken(CliOptions.parse(new String[0]), Map.of()));
    assertNull(Cli.webToken(CliOptions.parse(new String[0]), Map.of(Cli.ENV_WEB_TOKEN, "   ")));
    assertNull(Cli.webToken(CliOptions.parse(new String[] {"--web-token", "  "}), Map.of()));
    assertNull(Cli.webToken(CliOptions.parse(new String[0]), null));

    // Surrounding space from a shell (`export CCJ_WEB_TOKEN="$(cat token)"`) is not part of it.
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

    // The web picker is not the only way to set them: both reach the configuration layer.
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

    assertFalse(bare.web(), "the web UI is the default, so --web is only ever explicit");
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
    assertFalse(demo.repl(), "the front end still comes from the mode flags");
    assertNull(demo.print());
  }
}
