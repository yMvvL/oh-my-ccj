package com.ccj.agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 在问任何人之前就替审批作答的规则。
 *
 * <pre>
 * { "projects": {
 *     "/home/you/api": {
 *       "allow": [ {"tool": "bash", "command": "mvn -q -o test"},
 *                  {"tool": "bash", "command": "git diff *"},
 *                  {"tool": "edit", "path": "src/**"} ],
 *       "deny":  [ {"tool": "bash", "command": "rm -rf *"} ] } } }
 * </pre>
 *
 * <p>为什么要有它：在只有布尔关卡的时候，答案只有「可以，就这一次」和「可以，什么都行，整个会话」，所以
 * 用户真正面对的选择是：每次写入、每条命令都被打断，或者把防线关掉。在一个小任务上实测——修好一个坏掉的
 * 文件——五次工具调用带来了两次打断，而走下去的办法是打开自动批准开关。这就是它要消除的摩擦，而且是在不给
 * 那些确实该有防线的操作放宽防线的前提下消除的。
 *
 * <p>按项目分键，存放在应用主目录里，而不是仓库里。放在仓库里的规则文件，会是克隆能一起带来的规则文件：
 * `git clone` 之后，一个项目就自己决定了 `rm -rf *` 不需要审批。规则是用户的，按项目存放，放在用户自己的
 * 目录里。
 *
 * <p>每次决策都从文件读取，而不是在启动时持有，理由与检查相同：会话运行期间添加的规则必须立刻生效，而一个
 * 看起来毫无作用的文件，是用户会不再信任的文件。格式错乱的规则会抛异常，而不是被忽略——一条静默地永不匹配
 * 的规则，与一条没什么可反对的规则无从区分。
 */
public final class ApprovalRules {

  /** 一条规则：一个工具，以及它所要匹配的、关于该工具请求的东西。 */
  public record Rule(String tool, String command, String path) {}

  /**
   * 这些字符能让一条命令变成好几条，或让一个词变成另一个词。
   *
   * <p>放行规则是关于一条用户认识的命令的陈述，所以一条能变成别的东西的命令就不是那条命令：
   * `git status; rm -rf /` 以 `git status` 开头，但它不是它。这里是纯文本判断，且刻意如此——去解析引号的
   * 检查是那种聪明错了地方的做法——所以引号里的分号也会被拒绝。`~` 不在名单里：它展开成一个目录，串不起
   * 任何东西。
   */
  private static final String COMPOUNDING = ";|&><`$(){}[]*?!\\\n\r";

  /**
   * 「*参数*才是风险」的那些工具，所以关于它们的规则必须点名参数。
   *
   * <p>只点工具名的规则意思是「这个工具，无论让它干什么」，对 `restart` 或某个 MCP 工具来说这么说是合理的
   * ——名字就是全部请求。对这三个工具，名字什么也没说：`{"tool": "bash"}` 会放行以后要跑的每一条命令，那
   * 不过是隔了一个文件的自动批准。
   */
  private static final Set<String> CONTENT_BEARING = Set.of("bash", "edit", "write");

  private final Path file;
  private final Path project;
  /** 由 {@link ApprovalAnswer#ALLOW_SESSION} 答复添加的规则，仅对本进程有效。 */
  private final List<Rule> sessionAllows = new ArrayList<>();

  private ApprovalRules(Path file, Path project) {
    this.file = file;
    this.project = project.toAbsolutePath().normalize();
  }

  /** {@code project} 的规则，存放在 {@code file} 里。 */
  public static ApprovalRules open(Path file, Path project) {
    return new ApprovalRules(file, project);
  }

  /**
   * 规则怎么说，如果有说的话：{@code true} 表示不问就放行，{@code false} 表示不问就拒绝，空表示去问人。
   *
   * <p>先看拒绝，而它压过一切，包括会话内的放行——否则一次「本会话内放行」的答复就会盖过用户写来专门拦住
   * 那条命令的规则，文件也就什么都禁止不了。
   */
  public Optional<Boolean> verdict(ApprovalRequest request) {
    if (matchesAny(deny(), request)) {
      return Optional.of(false);
    }
    if (matchesAny(allow(), request) || matchesAny(sessionAllows, request)) {
      return Optional.of(true);
    }
    return Optional.empty();
  }

  /** 为本进程余下的时间记下一条放行，这就是「本会话内放行」的意思。 */
  public void rememberForSession(ApprovalRequest request) {
    Rule rule = ruleFor(request);
    sessionAllows.add(rule);
  }

  /** 为这个项目把一条放行规则写进文件，并保留它原有的其他内容。 */
  public void remember(ApprovalRequest request) {
    remember(request, false);
  }

  /**
   * 为这个项目把一条拒绝规则写进文件，并保留它原有的其他内容。
   *
   * <p>与 {@link #remember} 对称到主语为止：同一条命令、同一个工具名、同一个路径 glob，写进同一个文件的
   * 另一个列表。这不是顺手为之的对称——两个答复回答的是同一个问题，所以谁都不该在文件里记下比眼前这件事
   * 更多或更少的东西。没有它的时候，被一个反复出现的提示烦到的人只剩下把整个会话的审批关掉这一条路。
   */
  public void rememberDeny(ApprovalRequest request) {
    remember(request, true);
  }

  /**
   * 一条规则的主体：确切的规则，绝不扩大。命令按它运行时的样子写，路径按它被触及时的样子写。一句悄悄放行了
   * 比用户眼前这件事更多东西的「一直允许」，与一个不说清自己在批准什么的审批提示是同一种缺陷。
   *
   * @param deny true 写进拒绝列表，false 写进允许列表
   */
  private void remember(ApprovalRequest request, boolean deny) {
    change(deny, ruleFor(request), true);
  }

  /**
   * 为这个项目写入一条规则，并保留文件里其他一切。设置面板的「添加」走的就是这里，所以它写出的东西与
   * 「始终允许」「以后都拒绝」写下的完全一样——同一条写入、同一个文件、同一个列表，而不是第二套规则格式。
   *
   * <p>写之前按读取时那把同样的尺子校验。理由不是整洁：一条无效规则写进去以后，要等到下一次工具调用读它时
   * 才会炸出来，而那已经不是用户按下按钮的那一刻了，错误也就无从指向引起它的那个字段。所以它带着同一句话在
   * 这里出现，而文件保持原样。
   */
  public void remember(boolean deny, Rule rule) {
    validate(rule, file);
    change(deny, rule, true);
  }

  /**
   * 按身份删掉一条规则——效果、工具、主语全都一致的那一条。
   *
   * @return 删掉了时为 true；文件里本来就没有这条规则时为 false，此时一个字节都不动
   */
  public boolean forget(boolean deny, Rule rule) {
    return change(deny, rule, false);
  }

  /**
   * 往文件里加一条规则，或从文件里删一条，并保留其他一切。
   *
   * <p>加与删共用这一处读改写：它们的分歧只在列表上的那一处，而写入、路径规范化与仅属主权限没有理由出现
   * 第二遍。加一条已经在里面的规则不再重写文件——想要的状态已经成立。
   */
  private boolean change(boolean deny, Rule rule, boolean add) {
    Map<String, ProjectRules> all = read();
    ProjectRules mine = all.getOrDefault(project.toString(), ProjectRules.empty());
    List<Rule> rules = new ArrayList<>(deny ? mine.deny() : mine.allow());
    if (add) {
      if (rules.contains(rule)) {
        return true;
      }
      rules.add(rule);
    } else if (!rules.remove(rule)) {
      return false;
    }
    all.put(
        project.toString(),
        deny ? new ProjectRules(mine.allow(), rules) : new ProjectRules(rules, mine.deny()));
    write(all);
    return true;
  }

  /** 本进程知道的每一条规则，供用户索取的那份报告使用。 */
  public List<String> describe() {
    List<String> lines = new ArrayList<>();
    deny().forEach(rule -> lines.add("拒绝   " + render(rule)));
    allow().forEach(rule -> lines.add("允许  " + render(rule)));
    sessionAllows.forEach(rule -> lines.add("允许  " + render(rule) + "（本会话）"));
    return lines;
  }

  /** 本项目规则所在的文件，无论它是否已经存在。 */
  public Path file() {
    return file;
  }

  /**
   * 这些规则所属的项目路径。规则按项目分键存放，所以设置面板得让人看出自己正在编辑的是哪一层——一个
   * 目录的规则不是每个目录的规则。
   */
  public Path project() {
    return project;
  }

  private record ProjectRules(List<Rule> allow, List<Rule> deny) {
    static ProjectRules empty() {
      return new ProjectRules(List.of(), List.of());
    }
  }

  /** 用户为这个项目写下的放行规则，按文件里的顺序。会话内的放行不在其中，它们不写进文件。 */
  public List<Rule> allow() {
    return read().getOrDefault(project.toString(), ProjectRules.empty()).allow();
  }

  /** 用户为这个项目写下的拒绝规则，按文件里的顺序。 */
  public List<Rule> deny() {
    return read().getOrDefault(project.toString(), ProjectRules.empty()).deny();
  }

  private boolean matchesAny(List<Rule> rules, ApprovalRequest request) {
    for (Rule rule : rules) {
      if (matches(rule, request)) {
        return true;
      }
    }
    return false;
  }

  private boolean matches(Rule rule, ApprovalRequest request) {
    if (rule.tool() == null || !toolMatches(rule.tool(), request.tool())) {
      return false;
    }
    if (rule.command() != null) {
      return request.command() != null
          && commandMatches(rule.command(), request.command(), "bash".equals(request.tool()));
    }
    if (rule.path() != null) {
      if (request.path() == null) {
        return false;
      }
      String relative = PathGlobs.relative(request.path(), project);
      return relative != null && PathGlobs.matches(rule.path(), relative);
    }
    // 两者都没有：这是一条关于工具本身的规则，`restart` 就是这种。
    return true;
  }

  /**
   * 当规则里的命令描述了请求里的命令时为 true。
   *
   * <p>两种形状，而它们之间的差别正是元字符规则存在的理由。
   *
   * <p>普通命令按<em>原样</em>匹配，不管它含多少分号或重定向。这正是「放行这条命令」和「一直放行它」记下
   * 的东西——用户眼前的那串词——而相等判断不会因为它中间的 `>` 或 `;` 被放宽，所以没有什么要防的。实测：
   * 最早的版本把元字符规则用在了两种形状上，于是为 `printf a > one.txt` 记住的规则永远匹配不上它被记住时
   * 的那条命令，「本会话内放行」恰恰对人们最想不再被问的那些命令悄悄失效了。
   *
   * <p>以 {@code " *"} 结尾的规则匹配它之前的那些词再加后续参数，而<em>那</em>才是一个模式：它能吞掉后面的
   * 东西，所以两边都必须不含任何能把一条命令变成好几条的东西。`git diff *` 覆盖 `git diff`、
   * `git diff HEAD`、`git diff --stat`，而永远不匹配 `git diff; rm -rf /`。模式里其他任何位置都不允许
   * 出现 `*`——读取文件时就拒绝——因为一个含义取决于星号位置的模式，是没人能审计的模式。
   */
  static boolean commandMatches(String pattern, String command) {
    return commandMatches(pattern, command, true);
  }

  /**
   * 同上，用于「command」不是 shell 命令的请求。
   *
   * @param compoundable 该字符串是否由 shell 运行；这是元字符之所以要紧的唯一理由。URL 不是：
   *     `?a=1&amp;b=2` 是带查询串的一个地址，拒绝匹配它会让「放行这个文档站点」无从写起，只剩下精确 URL
   *     规则这一种可行形式。
   */
  static boolean commandMatches(String pattern, String command, boolean compoundable) {
    if (pattern.endsWith(" *")) {
      if (!compoundable) {
        // 为 shell 命令写的模式与为 URL 写的模式不是一回事，反之亦然：两种写法含义不同，所以写错的哪种
        // 不会碰巧匹配上。
        return false;
      }
      // 模式自己的那颗星就是通配符，所以在这里不是要拒绝的元字符——必须是纯粹的是它前面的那些词。
      String head = pattern.substring(0, pattern.length() - 2);
      if (compoundingCharacter(head) != null || compoundingCharacter(command) != null) {
        return false;
      }
      String prefix = head + " ";
      return command.startsWith(prefix) && command.length() > prefix.length();
    }
    if (pattern.endsWith("*")) {
      // 路径前缀之后允许零个字符，词前缀之后则不然：星号前的分隔符在加载时就被要求了，所以
      // `https://example.com/*` 意味着「这个主机上的任意路径——包括根」，写它的人也是这个意思。实测：它
      // 起初匹配不到 `https://example.com/`，规则就此悄悄什么都没放行。
      String head = pattern.substring(0, pattern.length() - 1);
      return command.startsWith(head);
    }
    return pattern.equals(command);
  }

  /**
   * 当规则里的工具点名了请求里的工具时为 true。
   *
   * <p>这里允许结尾的 `*`，不必满足命令模式所带的分隔符要求：{@code mcp__fs__read_file} 最后一个词之前
   * 没有分隔符可以挂这项检查，而这个前缀不可能碰巧成为*另一个*名字的前缀——这些名字是我们的，不是地址。
   * 所以 `mcp__fs__*` 表示一个服务端的工具，那正是用户信任某个服务端之后想写下的东西。
   */
  private static boolean toolMatches(String pattern, String tool) {
    if (pattern.endsWith("*")) {
      return tool.startsWith(pattern.substring(0, pattern.length() - 1));
    }
    return pattern.equals(tool);
  }

  /**
   * 这条规则若无效，说明它为什么无效；有效时为 null。位置由调用方补上，因为读文件时它落在某一行上，而从
   * 设置面板进来时它落在某个字段上——同一句话，两种归属。
   *
   * <p>这也是读取那条路径用来报错的同一段文字：校验只有一份，否则「一条规则得说清它拦住什么」这件事会在两个
   * 地方各有一个说法。
   */
  private static String problem(Rule rule) {
    if (rule.tool() == null) {
      return "审批规则需要 'tool'";
    }
    if (rule.command() == null && rule.path() == null && CONTENT_BEARING.contains(rule.tool())) {
      return "针对 '"
          + rule.tool()
          + "' 的规则必须指明 'command' 或 'path'：只写 '"
          + rule.tool()
          + "' 会放行它的每一次调用，那是自动批准该做的事";
    }
    return null;
  }

  /** 按读取时那把尺子校验一条规则；无效时抛出带着原因与位置的异常。 */
  private static void validate(Rule rule, Path file) {
    String why = problem(rule);
    if (why != null) {
      throw new IllegalArgumentException(why + "，位置：" + file);
    }
    if (rule.command() != null) {
      trailingWildcard(rule.command(), file);
    }
  }

  /**
   * 拒绝通配符不在结尾的模式，或星号会吞掉相邻名字的模式。
   *
   * <p>两种写法，因为用户想放行的两类东西形状不同。`git diff *` 意思是「这些词再加后续参数」，且只匹配
   * 不含 shell 元字符的命令。`https://docs.example.com/*` 意思是「这个主机上的这个路径前缀」——而在那里，
   * 星号前的分隔符是承重的：星号直接跟在一个名字后面写成 `https://docs.example.com*`，它会连
   * `https://docs.example.com.evil/` 一起匹配，而那是完全不同的主机。要求星号前有 `/`、`?`、`=` 或
   * `:`，才能让「放行这个站点」不至于意味着「放行所有以它开头的名字的主机」。
   */
  private static void trailingWildcard(String command, Path file) {
    int star = command.indexOf('*');
    if (star < 0) {
      return;
    }
    if (star != command.length() - 1) {
      throw new IllegalArgumentException(
          "审批规则唯一允许使用的通配符是结尾的 '*'（或表示后续参数的 ' *'）："
              + command
              + "，位置："
              + file);
    }
    if (command.endsWith(" *")) {
      return;
    }
    String head = command.substring(0, command.length() - 1);
    if (head.isEmpty() || "/?=:".indexOf(head.charAt(head.length() - 1)) < 0) {
      throw new IllegalArgumentException(
          "写成 '"
              + command
              + "' 的规则也会放行所有以 '"
              + head
              + "' 开头的名字。请在星号前放一个分隔符——'/'、'?'、'=' 或 ':'——让它表示「这个前缀"
              + "及其后面的内容」："
              + file);
    }
  }

  /**
   * {@code command} 中第一个可能让它变成不止一条命令的字符，没有则为 null。
   *
   * <p>公开是因为拒绝必须能解释得清：规则没有匹配上的用户，有权知道问题出在那个分号上，而不是那些词上。
   */
  public static String compoundingCharacter(String command) {
    if (command == null) {
      return null;
    }
    for (int i = 0; i < command.length(); i++) {
      char c = command.charAt(i);
      if (COMPOUNDING.indexOf(c) >= 0) {
        return String.valueOf(c);
      }
    }
    return null;
  }

  private Rule ruleFor(ApprovalRequest request) {
    String path =
        request.path() == null ? null : PathGlobs.relative(request.path(), project);
    return new Rule(request.tool(), request.command(), path);
  }

  private static String render(Rule rule) {
    if (rule.command() != null) {
      return rule.tool() + " " + rule.command();
    }
    return rule.path() != null ? rule.tool() + " " + rule.path() : rule.tool();
  }

  // ------------------------------------------------------------------ the file

  /**
   * 文件的内容，形式是调用方可以往里加东西的 map。
   *
   * <p>刻意可变，这个理由值得留着：它过去在没有文件时返回 {@code Map.of()}，于是「今后一直允许」——用户
   * 授予的第一条规则，面对的还是一个尚不存在的文件，而这正是常见情形——把
   * {@link UnsupportedOperationException} 抛进了工具调用。没有任何东西响亮地失败：规则只是从未被写下，
   * 而唯一可见的症状是同一个问题又被问了一遍。覆盖「记住」的那三个测试跑的都是已经含有某个项目的文件，所以
   * 它们谁都没发现。
   */
  private Map<String, ProjectRules> read() {
    if (file == null || !Files.isRegularFile(file)) {
      return new LinkedHashMap<>();
    }
    JsonNode root;
    try {
      root = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException("无法读取 " + file, e);
    }
    if (!root.isObject()) {
      throw new IllegalArgumentException("审批文件必须是 JSON 对象：" + file);
    }
    JsonNode projects = root.get("projects");
    if (projects == null || projects.isNull()) {
      return new LinkedHashMap<>();
    }
    if (!projects.isObject()) {
      throw new IllegalArgumentException(file + " 中的 'projects' 必须是一个以项目为键的对象");
    }
    Map<String, ProjectRules> all = new LinkedHashMap<>();
    projects
        .fields()
        .forEachRemaining(
            entry ->
                all.put(
                    key(entry.getKey()),
                    new ProjectRules(
                        rules(entry.getValue(), "allow"), rules(entry.getValue(), "deny"))));
    return all;
  }

  private List<Rule> rules(JsonNode project, String kind) {
    JsonNode entries = project == null ? null : project.get(kind);
    if (entries == null || entries.isNull()) {
      return List.of();
    }
    if (!entries.isArray()) {
      throw new IllegalArgumentException(
          file + " 中的 '" + kind + "' 必须是一组规则");
    }
    List<Rule> rules = new ArrayList<>();
    for (JsonNode entry : entries) {
      if (!entry.isObject()) {
        throw new IllegalArgumentException("每条审批规则都必须是对象，位置：" + file + "：" + entry);
      }
      String tool = text(entry, "tool");
      String command = text(entry, "command");
      String path = text(entry, "path");
      Rule rule = new Rule(tool, command, path);
      String why = problem(rule);
      if (why != null) {
        throw new IllegalArgumentException(why + "，位置：" + file + "：" + entry);
      }
      if (command != null) {
        trailingWildcard(command, file);
      }
      rules.add(rule);
    }
    return List.copyOf(rules);
  }

  /**
   * 规则归档所用的项目键：绝对路径且已规范化。
   *
   * <p>手写的文件可能写成 `/home/you/api/`，或写成含符号链接的形式。进来时规范化意味着它们就是它们显然
   * 所是的那个项目，而不是永远匹配不上的第二条记录——一条悄悄不生效的规则比没有规则更糟，因为用户以为自己
   * 受到了保护。
   */
  private static String key(String project) {
    try {
      return Path.of(project).toAbsolutePath().normalize().toString();
    } catch (RuntimeException notAPath) {
      return project.strip();
    }
  }

  private void write(Map<String, ProjectRules> all) {
    ObjectNode root = Json.object();
    ObjectNode projects = root.putObject("projects");
    all.forEach(
        (project, rules) -> {
          ObjectNode projectNode = projects.putObject(project);
          writeRules(projectNode, "allow", rules.allow());
          writeRules(projectNode, "deny", rules.deny());
        });
    try {
      Path parent = file.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(
          file,
          Json.writePretty(root) + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING);
      // 仅限属主，与配置文件遵循同一条规则：它不是秘密，但它确实说明了这台机器会不问就做什么，这不是可以
      // 交给机器上每个用户的东西。
      try {
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
      } catch (UnsupportedOperationException | IOException ignored) {
        // 不是 POSIX 文件系统；规则已经写下了，而那是被要求的事。
      }
    } catch (IOException e) {
      throw new UncheckedIOException("无法写入 " + file, e);
    }
  }

  private static void writeRules(ObjectNode project, String kind, List<Rule> rules) {
    if (rules.isEmpty()) {
      project.remove(kind);
      return;
    }
    ArrayNode array = project.putArray(kind);
    for (Rule rule : rules) {
      ObjectNode entry = array.addObject();
      entry.put("tool", rule.tool());
      if (rule.command() != null) {
        entry.put("command", rule.command());
      }
      if (rule.path() != null) {
        entry.put("path", rule.path());
      }
    }
  }

  private static String text(JsonNode entry, String field) {
    JsonNode node = entry.get(field);
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isTextual()) {
      throw new IllegalArgumentException("审批规则 '" + field + "' 必须是字符串：" + node);
    }
    String value = node.asText().strip();
    return value.isEmpty() ? null : value;
  }
}
