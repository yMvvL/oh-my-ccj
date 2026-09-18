package com.ccj.agent.tool;

import com.ccj.agent.core.ApprovalRequest;
import com.ccj.agent.core.Tool;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolResult;
import com.ccj.agent.session.CheckpointStore;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 精确字符串编辑器。
 *
 * <p>匹配是字面匹配，从不用正则表达式，所以模型必须按文件真实的样子引用它。有意思的是各种失败方式：
 * 匹配不到时会报告该文本在空白不同的情况下是否存在，匹配有歧义时会报告出现次数——两者都是模型成功重试
 * 所需要的东西，而不是盲猜。
 */
public final class EditTool implements Tool {

  private static final long MAX_EDIT_BYTES = 32L * 1024 * 1024;
  /** 文件的先前内容放到哪里，以便退回这个回合。 */
  private final CheckpointStore checkpoints;

  /** 成功的「edit」之后运行的检查：除非用户声明过，否则什么也不做。 */
  private final PostEditCheck check;

  public EditTool() {
    this(com.ccj.agent.core.Checks.none(), CheckpointStore.none());
  }

  public EditTool(com.ccj.agent.core.Checks checks) {
    this(checks, CheckpointStore.none());
  }

  public EditTool(com.ccj.agent.core.Checks checks, CheckpointStore checkpoints) {
    this.check = new PostEditCheck(checks);
    this.checkpoints = checkpoints == null ? CheckpointStore.none() : checkpoints;
  }

  private static final int PREVIEW_CONTEXT = 3;
  private static final int PREVIEW_MAX_LINES = 30;

  @Override
  public String name() {
    return "edit";
  }

  @Override
  public String description() {
    return "Replace exact strings in a UTF-8 file. Either one change (old_string/new_string), or "
        + "several at once (edits: [{old_string, new_string, replace_all}]) which are matched, "
        + "approved and written together — all of them or none. Text must match the file literally. "
        + "If the project configures a check for this file type, it runs after the write and its "
        + "verdict is appended to this result.";
  }

  @Override
  public String parametersJson() {
    return """
        {
          "type": "object",
          "properties": {
            "path": {
              "type": "string",
              "description": "File to edit, relative to the session working directory or absolute."
            },
            "old_string": {
              "type": "string",
              "description": "Exact text to replace, including indentation. Must be unique unless replace_all is true."
            },
            "new_string": {
              "type": "string",
              "description": "Replacement text."
            },
            "replace_all": {
              "type": "boolean",
              "description": "Replace every occurrence instead of requiring exactly one. Defaults to false."
            },
            "edits": {
              "type": "array",
              "description": "Several changes to one file, applied together: one approval, one write, and nothing written unless every one of them matches. Use this instead of calling edit once per change. Each entry is {old_string, new_string, replace_all?} and must not overlap another.",
              "items": {
                "type": "object",
                "properties": {
                  "old_string": {
                    "type": "string",
                    "description": "Exact text to replace, including indentation."
                  },
                  "new_string": {
                    "type": "string",
                    "description": "Replacement text."
                  },
                  "replace_all": {
                    "type": "boolean",
                    "description": "Replace every occurrence of this hunk. Defaults to false."
                  }
                },
                "required": ["old_string", "new_string"],
                "additionalProperties": false
              }
            }
          },
          "required": ["path"],
          "additionalProperties": false
        }""";
  }

  @Override
  public ToolResult execute(String argumentsJson, ToolContext ctx) throws IOException {
    JsonNode args = ToolSupport.args(argumentsJson);
    Path file = ctx.resolve(ToolSupport.requireText(args, "path"));
    List<Hunk> hunks;
    try {
      hunks = hunks(args);
    } catch (IllegalArgumentException badRequest) {
      return ToolResult.error(badRequest.getMessage());
    }
    String label = ToolSupport.display(ctx, file);

    if (!Files.exists(file)) {
      return ToolResult.error("找不到文件: " + label);
    }
    if (Files.isDirectory(file)) {
      return ToolResult.error(label + " 是目录");
    }
    for (Hunk hunk : hunks) {
      if (hunk.oldString().isEmpty()) {
        return ToolResult.error(
            "old_string 不能为空；传入要替换的确切文本，或者用 write 从零创建内容");
      }
    }
    long size = Files.size(file);
    if (size > MAX_EDIT_BYTES) {
      return ToolResult.error(
          "无法编辑 " + label + "：文件 " + size + " 字节，超过 " + MAX_EDIT_BYTES
              + " 字节的编辑上限");
    }
    String text = ToolSupport.decodeText(Files.readAllBytes(file));
    if (text == null) {
      return ToolResult.error("无法编辑 " + label + "：不是 UTF-8 文本文件（二进制内容）");
    }

    List<int[]> ranges = new ArrayList<>();
    List<String> replacements = new ArrayList<>();
    List<Integer> owners = new ArrayList<>();
    for (int index = 0; index < hunks.size(); index++) {
      Hunk hunk = hunks.get(index);
      List<int[]> found = ToolSupport.findAll(text, hunk.oldString());
      if (found.isEmpty()) {
        return ToolResult.error(
            "未写入任何内容："
                + failure(text, hunks, index, "未找到精确匹配")
                + "\n"
                + neighbourhood(text, hunk));
      }
      if (found.size() > 1 && !hunk.replaceAll()) {
        return ToolResult.error(
            "未写入任何内容："
                + failure(text, hunks, index, "匹配 " + found.size() + " 次")
                + "；补充更多上下文让它唯一，或者对该块设置 replace_all=true");
      }
      for (int[] range : found) {
        ranges.add(range);
        replacements.add(hunk.newString());
        owners.add(index);
      }
    }
    String overlap = overlap(ranges, owners, hunks);
    if (overlap != null) {
      return ToolResult.error("未写入任何内容：" + overlap);
    }

    String updated = applyAll(text, ranges, replacements);
    StringBuilder detail =
        new StringBuilder(label)
            .append(" (")
            .append(hunks.size())
            .append(" 个块，")
            .append(ranges.size())
            .append(" 处替换");
    if (!ctx.insideCwd(file)) {
      detail.append("；在会话工作区之外 ").append(ctx.cwd());
    }
    // 整次改动给一个差异，而不是每个块一个：审批的人是在对这之后产出的那个文件做决定，把一个文件拆成
    // 三份差异，等于邀请对同一个问题给出三个不同的答案。
    detail
        .append(")\n")
        .append(
            hunks.size() == 1
                ? DiffPreview.replacements(
                    text, ranges, replacements.get(0), PREVIEW_CONTEXT, PREVIEW_MAX_LINES)
                : DiffPreview.unified(text, updated, PREVIEW_CONTEXT, PREVIEW_MAX_LINES));
    String refusal = ctx.refusal(ApprovalRequest.file("edit", file, detail.toString()));
    if (refusal != null) {
      return ToolResult.error(refusal);
    }

    // 写之前重读一遍，因为审批是一扇窗口，别人可能在这期间改动该文件——用户在编辑器里、另一个对话的
    // 回合、保存时运行的格式化器。上面的区间是按展示差异时的文本算出来的，把它们套用到磁盘上现在的
    // 内容，会写回一个早于那次改动的版本：别人的编辑悄无声息地没了，而审批提示展示的差异谁也说不出它
    // 已经过期。
    String current;
    try {
      current = ToolSupport.decodeText(Files.readAllBytes(file));
    } catch (IOException | RuntimeException e) {
      return ToolResult.error(label + " 在编辑前无法重新读取: " + e.getMessage());
    }
    if (!current.equals(text)) {
      return ToolResult.error(
          "已拒绝："
              + label
              + " 在这次编辑等待审批期间被改动了。磁盘上现在的内容不是差异所展示的那个，套用它就会"
              + "丢掉另一处改动。请重新读取该文件，并针对现在的内容重做这次编辑。");
    }

    // 在写入之前、审批之后记录：值得退回去的状态是这个回合开始时的状态，而被拒绝写入的文件没有什么
    // 可退回的。
    checkpoints.record(file, text);
    // 先写到旁边的临时文件再移动就位，这样崩溃或磁盘写满都不会留下写了一半的文件：对源文件做部分写入
    // 的失败方式，比干脆不编辑它更糟。
    writeAtomically(file, updated);
    return ToolResult.ok(
        "在 "
            + label
            + " 中替换了 "
            + ranges.size()
            + " 处"
            + (hunks.size() == 1 ? "" : "（跨 " + hunks.size() + " 个块）")
            + "；文件现在有 "
            + ToolSupport.lineCount(updated)
            + " 行"
            + check.afterEditing(file, ctx));
  }

  /** 要做的一处改动：匹配什么、在那里放什么，以及是否每一次出现都算数。 */
  private record Hunk(String oldString, String newString, boolean replaceAll) {}

  /**
   * 这次请求所要的块：多改动的形式，或者单改动的形式。
   *
   * <p>两种形式都留着，因为它们是不同的活儿。单处改动是常见情况，扁平参数是提出它最短的方式；一个
   * 文件上的多处改动是模型一次修五个地方时做的事，而把那个做成五次调用，就是五次审批、五次写入，以及
   * 四次让文件在一个已经展示过的审批之下被改动。
   */
  private static List<Hunk> hunks(JsonNode args) {
    JsonNode edits = args.get("edits");
    boolean flat = args.hasNonNull("old_string") || args.hasNonNull("new_string");
    if (edits != null && !edits.isNull()) {
      if (flat) {
        throw new IllegalArgumentException(
            "一次改动就传 old_string/new_string，多处改动就传 edits，两者不要同时传");
      }
      if (!edits.isArray() || edits.isEmpty()) {
        throw new IllegalArgumentException("edits 必须是非空的改动数组");
      }
      List<Hunk> hunks = new ArrayList<>();
      for (JsonNode entry : edits) {
        if (!entry.isObject()) {
          throw new IllegalArgumentException("edits 的每一项都必须是对象: " + entry);
        }
        hunks.add(
            new Hunk(
                ToolSupport.requireText(entry, "old_string"),
                ToolSupport.requireText(entry, "new_string"),
                ToolSupport.optionalBool(entry, "replace_all", false)));
      }
      return hunks;
    }
    return List.of(
        new Hunk(
            ToolSupport.requireText(args, "old_string"),
            ToolSupport.requireText(args, "new_string"),
            ToolSupport.optionalBool(args, "replace_all", false)));
  }

  /** 哪个块失败了、为什么失败，用的是模型成功重试所需要的说法。 */
  private static String failure(String text, List<Hunk> hunks, int index, String why) {
    String where = hunks.size() == 1 ? "该编辑" : "第 " + (index + 1) + "/" + hunks.size() + " 个块";
    Hunk hunk = hunks.get(index);
    int soft = ToolSupport.countNormalisedMatches(text, hunk.oldString());
    String hint =
        soft == 0
            ? "折叠空白后也没有任何区域匹配"
            : "折叠空白后有 " + soft + " 个区域匹配，说明缩进或换行与文件不同";
    return where + why + "：" + hint;
  }

  /** 失败的块原本预期出现的位置附近那几行，这样重试不必再单独读一次。 */
  private static String neighbourhood(String text, Hunk hunk) {
    int line = ToolSupport.lineOfFirstLine(text, hunk.oldString());
    if (line < 0) {
      return "(它的首行在文件里哪儿都找不到)";
    }
    return "(它首行出现的位置附近，第 " + line + " 行左右)\n"
        + ToolSupport.excerpt(text, line, PREVIEW_CONTEXT);
  }

  /**
   * 拒绝互相重叠的块，并把两个都点出来。
   *
   * <p>重叠的块没有确定的结果——后应用的那个会套用到前一个已经替换掉的文本上——而半途生效的补丁是这个
   * 工具绝不能产出的东西。
   */
  private static String overlap(List<int[]> ranges, List<Integer> owners, List<Hunk> hunks) {
    List<Integer> order = new ArrayList<>();
    for (int i = 0; i < ranges.size(); i++) {
      order.add(i);
    }
    order.sort((left, right) -> Integer.compare(ranges.get(left)[0], ranges.get(right)[0]));
    for (int i = 1; i < order.size(); i++) {
      int previous = order.get(i - 1);
      int current = order.get(i);
      if (ranges.get(current)[0] < ranges.get(previous)[1]) {
        return "第 "
            + (owners.get(previous) + 1)
            + " 个和第 "
            + (owners.get(current) + 1)
            + " 个块在它们匹配的文本上重叠；把它们合并成一个块，或者重新排序，让它们不再覆盖"
            + "同一段文本";
      }
    }
    return null;
  }

  /**
   * 每一处替换，从文件末尾往前应用。
   *
   * <p>从后往前正是让偏移保持有效的原因：一处替换会改变它之后文本的长度，所以更靠前的那些必须最后
   * 应用。要紧的一步是按位置排序，而不是相信各个块到达的顺序——模型先写靠下的那处改动是正常的，而且
   * 实测过，按到达顺序应用会让一次两个块都匹配上的编辑产出 `class Notes implint a = 2;neable {`。
   */
  private static String applyAll(String text, List<int[]> ranges, List<String> replacements) {
    List<Integer> order = new ArrayList<>();
    for (int i = 0; i < ranges.size(); i++) {
      order.add(i);
    }
    order.sort((left, right) -> Integer.compare(ranges.get(right)[0], ranges.get(left)[0]));
    StringBuilder builder = new StringBuilder(text);
    for (int index : order) {
      int[] range = ranges.get(index);
      builder.replace(range[0], range[1], replacements.get(index));
    }
    return builder.toString();
  }

  /**
   * 经由同目录下的临时文件写入，然后把它重命名就位。
   *
   * <p>在同一个文件系统内，重命名是原子的，所以读取者看到的要么是旧文件要么是新文件——绝不会是截断
   * 的混合物。之所以要同目录，是因为跨文件系统的重命名其实是一次复制，而那个非原子的东西正是这里要
   * 避免的。
   */
  private static void writeAtomically(Path file, String content) throws IOException {
    Path parent = file.getParent();
    Path staged =
        Files.createTempFile(parent == null ? Path.of(".") : parent, ".ccj-edit", ".tmp");
    try {
      Files.write(staged, content.getBytes(StandardCharsets.UTF_8));
      // 复制到旁边的文件上，让替换品保留原文件的权限：新建的临时文件是 0600，而悄悄收紧一个用户
      // 原本设为可读的文件，是没人要求过的改动。
      try {
        Files.setPosixFilePermissions(staged, Files.getPosixFilePermissions(file));
      } catch (UnsupportedOperationException | IOException ignored) {
        // 不是 POSIX 文件系统，或者权限读不出来：内容才是要紧的。
      }
      Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      Files.deleteIfExists(staged);
      throw e;
    }
  }

  /** 从后往前应用替换，让更早的偏移保持有效。 */
  private static String replaceAll(String text, List<int[]> ranges, String newString) {
    StringBuilder builder = new StringBuilder(text);
    for (int i = ranges.size() - 1; i >= 0; i--) {
      int[] range = ranges.get(i);
      builder.replace(range[0], range[1], newString);
    }
    return builder.toString();
  }
}
