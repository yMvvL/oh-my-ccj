package com.ccj.agent.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 子代理交回来的东西：一个结论，而不是它的对话。
 *
 * <p>子代理的全部意义就在于它读过的内容不会进入主对话，所以它的报告是一个固定的形状，而不是自由散文。
 * 其中有两部分撑得起自己的位置：
 *
 * <ul>
 *   <li><b>发现。</b>答案本身，连同它所在的路径和行号——足够据以行动，而不必回去查。
 *   <li><b>文件清单，每一项标为 final 或 disposable。</b>正是它让主代理<em>不读任何东西</em>就能决定留下
 *       什么。如果它得逐个打开文件才能判断，这个功能本来要省下的上下文就会花在判断上。
 * </ul>
 *
 * <p>解析刻意宽容。一个只写了四个标题里的三个、或把某个拼得不一样的模型，工作仍然是做了的——因为格式上的
 * 小疏漏就退回它的报告，等于扔掉一次花了真金白银的运行。任何无法识别的内容都归入发现，而那本来也是人要读的
 * 部分。
 */
public final class SubAgentReport {

  /** 报告如何开始一个分节。不区分大小写匹配，冒号可有可无。 */
  private static final String STATUS = "status";
  private static final String SUMMARY = "summary";
  private static final String FILES = "files";
  private static final String FINDINGS = "findings";

  /**
   * 报告进入主对话时的上限。
   *
   * <p>子代理存在的意义，是别让一段对话被另一段对话的阅读材料填满，而一份啰嗦的报告就是这个失败从另一扇
   * 门进来。超过上限时文本会被截断并留下标记——和 {@link ContextBudget} 一样的诚实——好让主代理知道自己
   * 读到的是部分答案，而不是一份完整但简短的答案。
   */
  public static final int LIMIT_CHARS = 8_000;

  /** 截断时写的话。 */
  static final String CUT_MARKER =
      "\n...（已截短：这份报告装不下；请改成更窄的后续请求）...";

  /**
   * 一次运行产出的一个文件。
   *
   * @param path 它在哪里，相对于给子代理的工作目录
   * @param disposable 只是为了方便工作而留下的中间产物为 true，完成的工作为 false
   * @param note 一行说明它是什么，这样决定是否保留它时不必打开文件
   */
  public record Artifact(String path, boolean disposable, String note) {

    /** 这是完成的工作、而不是副产品时为 true。 */
    public boolean keepable() {
      return !disposable;
    }
  }

  private final String status;
  private final String summary;
  private final String findings;
  private final List<Artifact> artifacts;
  private final UsageTotals usage;
  private final String directory;
  private final String model;

  private SubAgentReport(
      String status,
      String summary,
      String findings,
      List<Artifact> artifacts,
      UsageTotals usage,
      String directory,
      String model) {
    this.status = status;
    this.summary = summary;
    this.findings = findings;
    this.artifacts = List.copyOf(artifacts);
    this.usage = usage == null ? UsageTotals.empty() : usage;
    this.directory = directory == null ? "" : directory;
    this.model = model;
  }

  /**
   * 这次运行花了多少。
   *
   * <p>带回来是为了让委派工作的那段对话能把它算进去。藏起子代理的转录才是要点；藏起它的花销就是另一回事
   * 了——一个悄悄漏掉被委派那一半的 token 计数，是用户没法信任的数字。
   */
  public UsageTotals usage() {
    return usage;
  }

  /** 同一份报告，附上这次运行的花销。 */
  public SubAgentReport withUsage(UsageTotals spent) {
    return new SubAgentReport(status, summary, findings, artifacts, spent, directory, model);
  }

  /**
   * 同一份报告，点名它是在哪个模型上跑出来的。
   *
   * <p>只有被钉到主对话以外某个模型上的角色才需要它：读报告的人就是主模型，所以当两者是同一个时点名等于
   * 什么都没说，而一个没人选过的名字正是它需要被告知的事。空白与缺席同义。
   */
  public SubAgentReport withModel(String ranOn) {
    return new SubAgentReport(
        status,
        summary,
        findings,
        artifacts,
        usage,
        directory,
        ranOn == null || ranOn.isBlank() ? null : ranOn);
  }

  /**
   * 所报告的路径相对于哪个目录。
   *
   * <p>由产出这份报告的那次运行设置，因为那些路径只有挨着它才有意义：子代理在会话自己的工作目录里干活，而
   * 一份把别处说成所在地的报告，会把主代理引到文件并不在的地方。
   */
  public String directory() {
    return directory;
  }

  /** 同一份报告，说明它的路径相对于哪里。 */
  public SubAgentReport in(String where) {
    return new SubAgentReport(status, summary, findings, artifacts, usage, where, model);
  }

  public String status() {
    return status;
  }

  public String summary() {
    return summary;
  }

  public String findings() {
    return findings;
  }

  public List<Artifact> artifacts() {
    return artifacts;
  }

  /** 值得提升为成果的文件，按它们被报告的顺序。 */
  public List<Artifact> keepable() {
    List<Artifact> keep = new ArrayList<>();
    for (Artifact artifact : artifacts) {
      if (artifact.keepable()) {
        keep.add(artifact);
      }
    }
    return List.copyOf(keep);
  }

  /** 副产品：只是为了方便工作而留下的，不是答案。 */
  public List<Artifact> disposable() {
    List<Artifact> out = new ArrayList<>();
    for (Artifact artifact : artifacts) {
      if (artifact.disposable()) {
        out.add(artifact);
      }
    }
    return List.copyOf(out);
  }

  /**
   * 从子代理说的话里读出一份报告。
   *
   * <p>从不抛异常，也从不返回 null：完全没有任何标题的文本，就是一份「发现即全部」的报告，因为那正是一个
   * 无视了格式的模型实际产出的东西，而它仍然是主代理需要的答案。
   */
  public static SubAgentReport parse(String text) {
    String body = text == null ? "" : text.strip();
    Map<String, StringBuilder> sections = new LinkedHashMap<>();
    String current = FINDINGS;
    sections.put(current, new StringBuilder());
    for (String line : body.split("\n", -1)) {
      // 只有那四个已知的词能结束一个分节。像 `src/Parser.java  final  ...` 这样的路径里含冒号，所以「有
      // 冒号就是标题」的规则会把它本该读到的文件清单吞掉——这四个词就是全部的判据，而且只有四个。
      String heading = headingOf(line);
      if (heading != null) {
        current = heading;
        sections.computeIfAbsent(current, key -> new StringBuilder());
        // 标题行里冒号后面的内容是那个分节的第一段正文：「SUMMARY: found it」是一行、一句话，把冒号后的
        // 一半丢掉，就会丢掉每一个这么写的模型的摘要。
        String rest = afterHeading(line);
        if (!rest.isEmpty()) {
          sections.get(current).append(rest).append('\n');
        }
        continue;
      }
      sections.get(current).append(line).append('\n');
    }
    String status = clean(sections.get(STATUS));
    String summary = clean(sections.get(SUMMARY));
    String findings = clean(sections.get(FINDINGS));
    List<Artifact> artifacts = parseArtifacts(sections.get(FILES));
    if (status.isEmpty()) {
      // 没有 STATUS 标题：一份毕竟回来了的报告，就是一次跑完了的运行。把这件事说出来，比报一个调用方还得
      // 特判的空状态更有用。
      status = body.isEmpty() ? "failed" : "done";
    }
    if (summary.isEmpty()) {
      // 退回正文的第一行，那正是跳过格式的模型最先写的东西：说明发生了什么的那个句子。
      summary = firstLine(body);
    }
    if (findings.isEmpty() && !summary.isEmpty() && !summary.equals(firstLine(body))) {
      findings = summary;
    }
    return new SubAgentReport(status, summary, findings, artifacts, null, null, null);
  }

  /** 标题行在冒号之后携带的内容，也就是该分节的第一行正文。 */
  private static String afterHeading(String line) {
    String bare = line.strip().startsWith("#")
        ? line.strip().replaceAll("^#+\\s*", "")
        : line.strip();
    int colon = bare.indexOf(':');
    return colon < 0 ? "" : bare.substring(colon + 1).strip();
  }

  /** 这一行是什么标题；它就是普通文本时为 null。 */
  private static String headingOf(String line) {
    String trimmed = line.strip();
    if (trimmed.isEmpty()) {
      return null;
    }
    // 「STATUS: done」和「## Findings」都算数。被告知要用标题的模型会用其中一种写法，很少两种都用；两种
    // 都接受，代价是一行代码，救回的是一次运行。
    String bare = trimmed.startsWith("#") ? trimmed.replaceAll("^#+\\s*", "") : trimmed;
    int colon = bare.indexOf(':');
    if (colon < 0) {
      // 光秃秃的一个词只有在短得足以当标题时才算标题：「STATUS」算，而一个碰巧只有一个词加句号的句子不值
      // 得冒险去猜。
      String whole = bare.strip().toLowerCase(java.util.Locale.ROOT);
      if (whole.contains(" ")) {
        return null;
      }
    }
    String word = (colon >= 0 ? bare.substring(0, colon) : bare).strip().toLowerCase(java.util.Locale.ROOT);
    return switch (word) {
      case STATUS -> STATUS;
      case SUMMARY -> SUMMARY;
      case FILES, "file", "artifacts", "artefacts" -> FILES;
      case FINDINGS, "finding", "result", "results", "answer" -> FINDINGS;
      default -> null;
    };
  }

  /**
   * 文件清单。
   *
   * <p>既接受提示要求的形状——{@code path  final|disposable  note}——也接受模型顺手写成的其他形状：
   * 开头的短横线、路径后的冒号、标记出现在行内任何位置。没有标记的行按完成的工作处理，因为把一个其实是中间
   * 产物的文件提升上来，比删掉一个其实是答案的文件错得更轻。
   */
  private static List<Artifact> parseArtifacts(StringBuilder section) {
    if (section == null) {
      return List.of();
    }
    List<Artifact> found = new ArrayList<>();
    for (String raw : section.toString().split("\n")) {
      String line = raw.strip();
      if (line.isEmpty()) {
        continue;
      }
      line = line.replaceAll("^[-*+]\\s*", "");
      if (line.isEmpty()) {
        continue;
      }
      int mark = markIndex(line);
      boolean disposable = mark >= 0 && !line.substring(mark).toLowerCase(java.util.Locale.ROOT)
          .startsWith("final");
      String path;
      String note = "";
      int colon = line.indexOf(':');
      if (colon > 0 && !line.substring(0, colon).contains(" ")) {
        path = line.substring(0, colon).strip();
        note = line.substring(colon + 1).strip();
      } else {
        // 提示要求的是对齐成列（两个及以上空格，或一个制表符），但用单个空格隔开三个字段的模型相当常见，
        // 把整行读成一个路径就会丢掉它点名的文件。所以拆分的锚点是那个标记：它之前是路径，之后是注记。
        if (mark >= 0) {
          path = line.substring(0, mark).strip();
          note = line.substring(mark).strip();
        } else {
          String[] parts = line.split("\\s{2,}|\\t", 2);
          path = parts[0].strip();
          note = parts.length > 1 ? parts[1].strip() : "";
        }
      }
      // 标记本身可能就在注记里；把「final」留在注记里会被读成一句描述。
      note = note.replaceAll("(?i)^(final|disposable|intermediate)\\s*[:,-]?\\s*", "").strip();
      if (!path.isEmpty()) {
        found.add(new Artifact(path, disposable, note));
      }
    }
    return List.copyOf(found);
  }

  /**
   * 一行里 final/disposable 标记的起始位置；该行没有标记时为 -1。
   *
   * <p>按整词查找，所以一个叫 {@code temporary-output/} 的目录不会把文件标成 disposable，一句只是后来
   * 提到该词的注记也不会。
   */
  private static int markIndex(String line) {
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile(
                "(?i)(?<![\\w.-])(final|disposable|intermediate|temporary|temp)(?![\\w.-])")
            .matcher(line);
    return matcher.find() ? matcher.start() : -1;
  }

  private static String clean(StringBuilder section) {
    return section == null ? "" : section.toString().strip();
  }

  private static String firstLine(String body) {
    for (String line : body.split("\n")) {
      String trimmed = line.strip();
      if (!trimmed.isEmpty() && headingOf(trimmed) == null) {
        return trimmed;
      }
    }
    return "";
  }

  /**
   * 报告发回主对话时的样子，有界且带标签。
   *
   * @param workspace 所报告的路径相对于哪个目录；只在这次运行没有自己点名时使用。它是否正确很重要：一份
   *     点着文件并不在的目录的报告，会把主代理引向错误的地方。
   */
  public String render(String workspace) {
    String where = directory.isEmpty() ? workspace : directory;
    StringBuilder out = new StringBuilder();
    out.append("STATUS: ").append(status).append('\n');
    if (!summary.isEmpty()) {
      out.append("SUMMARY: ").append(summary).append('\n');
    }
    // 转录只说某个任务跑过，不说它花了多少。这一行就是要让读到它的人知道账单：一次读了三十个文件才回来一
    // 句话的运行，和一个来回就完事的运行，花的不是同一笔钱。提供方一个数字都没报时不写它——凭空造一行账，
    // 比没有这一行更糟。
    if (spentAnything()) {
      out.append("USAGE: ").append(spendingLine()).append('\n');
    }
    if (!artifacts.isEmpty()) {
      out.append("FILES (in ").append(where).append("):\n");
      for (Artifact artifact : artifacts) {
        out.append("  ").append(artifact.path())
            .append(artifact.disposable() ? "  [disposable]" : "  [final]");
        if (!artifact.note().isEmpty()) {
          out.append(" — ").append(artifact.note());
        }
        out.append('\n');
      }
    }
    if (!findings.isEmpty()) {
      out.append('\n').append(findings).append('\n');
    }
    String text = out.toString().strip();
    if (text.length() > LIMIT_CHARS) {
      text = text.substring(0, Math.max(0, LIMIT_CHARS - CUT_MARKER.length())) + CUT_MARKER;
    }
    return text;
  }

  /**
   * 提供方报过数字时为 true。
   *
   * <p>不看 {@link UsageTotals#isEmpty()}：一次委派的任务文本本身就算一个用户回合，所以每一份来自运行的报告
   * 在那层意义上都「不空」，而这里要问的是另一件事——有没有真的花掉 token。
   */
  private boolean spentAnything() {
    return usage.inputTokens() > 0 || usage.outputTokens() > 0 || usage.cachedInputTokens() > 0;
  }

  /**
   * 这一行账本身：进去的、出来的、命中缓存的，加上问了模型几次。
   *
   * <p>用和 STATUS/FILES 一样的光秃标签与短字段，因为读它的是模型：一句话读起来没问题的散文，在这里会挤掉
   * 报告里本可以有的发现。模型回合数跟 token 并排，因为「花了多少」有两半——一次反复问了二十遍的运行，和
   * 一次问一遍就完事的运行，账单不像但都会疼。
   */
  private String spendingLine() {
    StringBuilder line = new StringBuilder();
    line.append(usage.inputTokens()).append(" in / ")
        .append(usage.outputTokens()).append(" out / ")
        .append(usage.cachedInputTokens()).append(" cached / ")
        .append(usage.modelTurns()).append(" model turns");
    if (model != null) {
      line.append(", model ").append(model);
    }
    return line.toString();
  }

  /** 为一次从未产出报告的运行准备的报告：被取消、超时，或模型拒绝。 */
  public static SubAgentReport failed(String reason) {
    return new SubAgentReport(
        "failed", reason == null ? "" : reason, "", List.of(), null, null, null);
  }

  /** 跑完了，但什么都没写、也没说出任何可用内容的运行。 */
  public static SubAgentReport empty() {
    return new SubAgentReport(
        "done", "（子代理没有产出报告）", "", List.of(), null, null, null);
  }
}
