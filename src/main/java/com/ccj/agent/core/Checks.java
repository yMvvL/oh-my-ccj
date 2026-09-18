package com.ccj.agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 用户在自己的配置文件中声明的、编辑之后自动运行的命令。
 *
 * <pre>
 * "checks": [
 *   {"glob": "**&#47;*.java", "command": "mvn -q -o -DskipTests compile", "timeoutSeconds": 120}
 * ]
 * </pre>
 *
 * <p>为什么要有它：这个代理与「背靠编辑器的代理」最大的差别，在于编译器什么时候能开口。没有检查时，一次
 * 改坏的编辑要等模型自己想到去看——晚一个回合，甚至永远发现不了——所以弱模型需要三四轮才能做完一轮就够的
 * 事。有了检查，错误与造成它的那次编辑在同一步到达，修它就成了模型接下来要做的事。
 *
 * <p>命令是用户的，不是模型的：它写在配置文件里，这与把它敲进终端是同一个行为，因此运行时也不弹提示。这是
 * 对审批模型的一次刻意放宽，并在 {@code SECURITY.md} 里写明。模型不能做的是自己添加一条检查——尽管它能写
 * 配置文件，而一个它能写的配置文件就是一条它只差一次审批就能加的检查。与 {@code CCJ.md} 是同一种暴露面。
 *
 * <p>每次使用都从文件读取，而不是在启动时持有：检查是用户在会话运行期间随时添加的东西，启动时取一份快照会
 * 让配置文件看起来毫无作用，直到下次重启。
 *
 * <p><strong>glob 决定检查何时运行，不决定命令去看什么。</strong>这是两个不同的作用域，这里写的只是前者。
 * 实测：用 {@code {"glob": "**&#47;*.java", "command": "mvn -q -o -DskipTests compile"}} 时，编辑仓库
 * 根部一个坏掉的文件，返回的是 {@code exit 0}——Maven 编译的是 {@code src/main/java}，根本没看到那个
 * 文件。这不是这个类的 bug，而是钩子这种形态本身如此，但这是用户必须知道的形态：给 glob 配上一条覆盖它所选
 * 范围的命令，否则检查会就一个它没读过的文件报告成功。
 */
public final class Checks {

  /** 一条检查是什么：何时适用、运行什么、可以跑多久。 */
  public record Check(String glob, String command, int timeoutSeconds) {}

  private static final int DEFAULT_TIMEOUT_SECONDS = 120;
  private static final int MAX_TIMEOUT_SECONDS = 600;

  private final Path configFile;

  private Checks(Path configFile) {
    this.configFile = configFile;
  }

  /** {@code configFile} 中声明的检查；文件为 null 或不存在时没有任何检查。 */
  public static Checks from(Path configFile) {
    return new Checks(configFile);
  }

  /** 什么都没配置；没有配置文件的调用方拿到的就是它。 */
  public static Checks none() {
    return new Checks(null);
  }

  /**
   * glob 与 {@code edited} 匹配的第一条检查；都不匹配时为空。
   *
   * <p>与相对于 {@code cwd} 的路径做匹配，用正斜杠，这样检查在每个平台上读起来都一样，也和 {@code glob}
   * 工具的模式用法一致。会话工作目录之外的文件从不匹配：检查是关于本项目的陈述，而因为代理改了
   * `~/.config` 里的东西就去跑本项目的构建，那是惊吓，不是服务。
   */
  public Optional<Check> forPath(Path edited, Path cwd) {
    Path file = edited.toAbsolutePath().normalize();
    Path root = cwd.toAbsolutePath().normalize();
    if (!file.startsWith(root)) {
      return Optional.empty();
    }
    String relative = root.relativize(file).toString().replace('\\', '/');
    for (Check check : declared()) {
      if (matches(check.glob(), relative)) {
        return Optional.of(check);
      }
    }
    return Optional.empty();
  }

  /**
   * {@code glob} 与项目相对路径匹配时为 true。
   *
   * <p>以 {@code **&#47;} 开头的模式会被尝试两次：按原样，以及去掉该前缀。第二次不是图方便——它是「能用的
   * 检查」与「静默地永不运行的检查」之间的差别。{@code PathMatcher} 把 {@code **&#47;*.java} 读作「某个
   * 目录里的 java 文件」，所以匹配不到项目根部的 {@code Foo.java}；而用户见过的其他工具（gitignore、
   * `.editorconfig`、ripgrep 的 {@code --glob}）都把 {@code **&#47;foo} 读作「任意深度（包括零层）的
   * foo」。用户写下自己熟悉的模式却发现检查从不触发时，无从分辨它与「一条没什么可报告的检查」的差别，而这
   * 正是这个类要避免的失败形态。
   */
  private static boolean matches(String glob, String relative) {
    if (matcher(glob).matches(Path.of(relative))) {
      return true;
    }
    return glob.startsWith("**/") && matcher(glob.substring(3)).matches(Path.of(relative));
  }

  private static PathMatcher matcher(String glob) {
    return FileSystems.getDefault().getPathMatcher("glob:" + glob);
  }

  /**
   * 文件此刻声明的检查；没有文件或没有相应的块时为空。
   *
   * <p>格式错乱的块会抛异常，而不是被忽略。另一种做法——因为其中一条有笔误就悄悄一条检查都不跑——正是这个
   * 类要防的失败，只不过它是静默到来的。
   *
   * @throws IllegalArgumentException 当该块不是一串带 command 的对象时
   * @throws UncheckedIOException 当文件存在但读不出来时
   */
  public List<Check> declared() {
    if (configFile == null || !Files.isRegularFile(configFile)) {
      return List.of();
    }
    JsonNode root;
    try {
      root = Json.parse(Files.readString(configFile, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException("无法读取 " + configFile, e);
    }
    if (!root.isObject()) {
      return List.of();
    }
    JsonNode block = root.get("checks");
    if (block == null || block.isNull()) {
      return List.of();
    }
    if (!block.isArray()) {
      throw new IllegalArgumentException(
          "配置字段 'checks' 必须是一组 {glob, command} 对象，位置：" + configFile);
    }
    List<Check> checks = new ArrayList<>();
    for (JsonNode entry : block) {
      if (!entry.isObject()) {
        throw new IllegalArgumentException(
            "配置字段 'checks' 的每一项都必须是对象，位置：" + configFile + "：" + entry);
      }
      String command = text(entry, "command", configFile);
      if (command == null) {
        throw new IllegalArgumentException(
            configFile + " 中的一条检查没有 'command'，因此没有可运行的东西");
      }
      String glob = text(entry, "glob", configFile);
      JsonNode timeout = entry.get("timeoutSeconds");
      int seconds = DEFAULT_TIMEOUT_SECONDS;
      if (timeout != null && !timeout.isNull()) {
        if (!timeout.isInt()) {
          throw new IllegalArgumentException(
              "检查的 'timeoutSeconds' 必须是整数，位置：" + configFile + "：" + timeout);
        }
        seconds = Math.max(1, Math.min(MAX_TIMEOUT_SECONDS, timeout.asInt()));
      }
      checks.add(new Check(glob == null ? "**" : glob, command, seconds));
    }
    return List.copyOf(checks);
  }

  private static String text(JsonNode entry, String field, Path file) {
    JsonNode node = entry.get(field);
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isTextual()) {
      throw new IllegalArgumentException(
          "检查的 '" + field + "' 必须是字符串，位置：" + file + "：" + node);
    }
    String value = node.asText().strip();
    return value.isEmpty() ? null : value;
  }
}
