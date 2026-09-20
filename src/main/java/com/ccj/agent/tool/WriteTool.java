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

/**
 * 整文件写入器，受审批闸门管控。
 *
 * <p>悄悄替换一个文件是编码代理所做的最具破坏性的事，所以审批详情总会写明将写入内容的大小、是否正在
 * 覆盖什么，以及——当确实在覆盖时——一份针对当前内容的有界差异。
 */
public final class WriteTool implements Tool {

  /** 文件的先前内容放到哪里，以便退回这个回合。 */
  private final CheckpointStore checkpoints;

  /** 成功的「write」之后运行的检查：除非用户声明过，否则什么也不做。 */
  private final PostEditCheck check;

  public WriteTool() {
    this(com.ccj.agent.core.Checks.none(), CheckpointStore.none());
  }

  public WriteTool(com.ccj.agent.core.Checks checks) {
    this(checks, CheckpointStore.none());
  }

  public WriteTool(com.ccj.agent.core.Checks checks, CheckpointStore checkpoints) {
    this(checks, checkpoints, null);
  }

  /**
   * 同上，另外指明用哪个程序跑编辑后检查。
   *
   * @param shell 检查命令用哪个程序跑；null 或空白表示按平台默认，见 {@link ProcessRunner#resolve}
   */
  public WriteTool(com.ccj.agent.core.Checks checks, CheckpointStore checkpoints, String shell) {
    this.check = new PostEditCheck(checks, shell);
    this.checkpoints = checkpoints == null ? CheckpointStore.none() : checkpoints;
  }

  private static final int PREVIEW_CONTEXT = 3;
  private static final int PREVIEW_MAX_LINES = 24;
  private static final long PREVIEW_MAX_BYTES = 1024L * 1024;

  @Override
  public String name() {
    return "write";
  }

  @Override
  public String description() {
    return "Write a UTF-8 file, creating parent directories as needed. Overwrites the whole file; "
        + "prefer edit for changing part of an existing file. If the project configures a check for "
        + "this file type, it runs after the write and its verdict is appended to this result.";
  }

  @Override
  public String parametersJson() {
    return """
        {
          "type": "object",
          "properties": {
            "path": {
              "type": "string",
              "description": "File to write, relative to the session working directory or absolute."
            },
            "content": {
              "type": "string",
              "description": "Complete new contents of the file."
            }
          },
          "required": ["path", "content"],
          "additionalProperties": false
        }""";
  }

  @Override
  public ToolResult execute(String argumentsJson, ToolContext ctx) throws IOException {
    JsonNode args = ToolSupport.args(argumentsJson);
    Path file = ctx.resolve(ToolSupport.requireText(args, "path"));
    String content = ToolSupport.requireText(args, "content");
    String label = ToolSupport.display(ctx, file);
    if (Files.isDirectory(file)) {
      return ToolResult.error(label + " 是目录");
    }

    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    boolean replacing = Files.exists(file);
    StringBuilder detail =
        new StringBuilder(label)
            .append(" (")
            .append(ToolSupport.lineCount(content))
            .append(" 行，")
            .append(bytes.length)
            .append(" 字节，")
            .append(replacing ? "覆盖现有内容" : "新建文件");
    if (!ctx.insideCwd(file)) {
      detail.append("；在会话工作区之外 ").append(ctx.cwd());
    }
    detail.append(')');
    String existing = replacing ? readForPreview(file) : null;
    // 提示展示时这个文件是什么样。只有大小和修改时间还不够——在同一个时间戳粒度内保存的编辑器，或者
    // 写出同样长度的格式化器，都会溜过去——所以对内容取哈希。流式处理，绝不整块持有：对大文件的写入
    // 不能把它读进堆两次。
    String before = replacing ? fingerprint(file) : null;
    if (existing != null && !existing.equals(content)) {
      detail
          .append('\n')
          .append(DiffPreview.unified(existing, content, PREVIEW_CONTEXT, PREVIEW_MAX_LINES));
    }
    String refusal = ctx.refusal(ApprovalRequest.file("write", file, detail.toString()));
    if (refusal != null) {
      return ToolResult.error(refusal);
    }

    Path parent = file.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    // 审批等待期间冒出来的文件，是提示里没有提到的变化：批准者同意的是「新建文件」，而现在会覆盖掉
    // 别人的工作。反过来则无所谓——原本在那儿、后来被删掉的文件，意味着这次写入确实在建它所声称要建的
    // 东西——所以只拒绝「冒出来」这一种情况，消息里也会说明该怎么办。
    if (!replacing && Files.exists(file)) {
      return ToolResult.error(
          "已拒绝："
              + label
              + " 在这次写入被批准时还不存在，现在存在了。写下去会丢掉这期间出现的东西。先读一下"
              + "它，再做决定。");
    }
    // 反方向也一样要拒绝，而这一条之前是缺的：文件在审批等待期间*变了*。两个对话可以写同一条路径，
    // 用户的编辑器也可以——提示里的差异是按文件当时的样子算出来的，而覆盖现在的内容会丢掉一个谁都
    // 没看到过的改动。这条是从 `edit` 工具有过的那种 bug 的形态里报出来的：`write` 先答应了，然后
    // 把它当时找到的东西写了下去。
    if (replacing) {
      String now;
      try {
        now = fingerprint(file);
      } catch (IOException e) {
        return ToolResult.error(
            "已拒绝：" + label + " 在写入前无法重新读取: " + e.getMessage());
      }
      if (!now.equals(before)) {
        return ToolResult.error(
            "已拒绝："
                + label
                + " 在这次写入等待审批期间被改动了。磁盘上现在的内容不是差异所展示的那个，写下去会"
                + "丢掉另一处改动——另一个对话、你的编辑器，或者保存时运行的格式化器。请重新读取该"
                + "文件，并针对现在的内容重做这次写入。");
      }
    }
    // 先暂存再重命名，而不是就地写入，这样崩溃不会留下被截断的文件：写了一半的源文件比没有更糟，
    // 因为关于它没有任何东西说明它是不完整的。在写入之前、审批之后记录。有三种情况，中间那种要紧：
    // 存在但读不出来的文件——二进制，或超过预览上限——*不*记作「原本不存在」，因为那样退回就会把它
    // 删掉。这次调用没有持有的状态，不做任何声称。
    if (!replacing) {
      checkpoints.record(file, null);
    } else if (existing != null) {
      checkpoints.record(file, existing);
    }
    // 先暂存再重命名，而不是就地写入，这样崩溃不会留下被截断的文件：写了一半的源文件比没有更糟，
    // 因为关于它没有任何东西说明它是不完整的。
    writeAtomically(file, bytes);
    return ToolResult.ok(
        "向 "
            + label
            + " 写入了 "
            + bytes.length
            + " 字节"
            + (replacing ? "（替换了已有文件）" : "（新建了文件）")
            + check.afterEditing(file, ctx));
  }

  /**
   * 经由同目录下的临时文件写入并把它重命名就位。
   *
   * <p>在同一个文件系统内，重命名是原子的，所以读取者看到的要么是旧文件要么是新文件，绝不会是截断的
   * 混合物。同目录，是因为跨文件系统的重命名会退化成一次复制，而那个非原子的东西正是这里要避免的。
   */
  private static void writeAtomically(Path file, byte[] bytes) throws IOException {
    Path parent = file.getParent();
    Path staged = Files.createTempFile(parent == null ? Path.of(".") : parent, ".ccj-write", ".tmp");
    try {
      Files.write(staged, bytes);
      if (Files.exists(file)) {
        // 沿用原文件的权限，这样替换文件不会悄悄改变谁能读它。
        try {
          Files.setPosixFilePermissions(staged, Files.getPosixFilePermissions(file));
        } catch (UnsupportedOperationException | IOException ignored) {
          // 不是 POSIX 文件系统，或者读不出来：内容才是要紧的。
        }
      }
      Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      Files.deleteIfExists(staged);
      throw e;
    }
  }

  /** 用于覆盖预览的当前内容；文件太大或不是文本时为 {@code null}。 */
  /**
   * 文件内容的指纹：大小、修改时间，以及全部字节的哈希。
   *
   * <p>在审批前后各取一次并比较，回答一个问题——这还是差异所展示的那个文件吗？哈希让答案可信；保留
   * 大小和时间戳，是因为它们能让不匹配在拒绝消息里读得明白。
   */
  private static String fingerprint(Path file) throws IOException {
    java.security.MessageDigest digest;
    try {
      digest = java.security.MessageDigest.getInstance("SHA-256");
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("Java 平台必须提供 SHA-256", impossible);
    }
    try (var in = Files.newInputStream(file)) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) != -1) {
        digest.update(buffer, 0, read);
      }
    }
    return Files.size(file) + ":" + Files.getLastModifiedTime(file).toMillis() + ":"
        + java.util.HexFormat.of().formatHex(digest.digest());
  }

  private static String readForPreview(Path file) throws IOException {
    if (Files.size(file) > PREVIEW_MAX_BYTES) {
      return null;
    }
    return ToolSupport.decodeText(Files.readAllBytes(file));
  }
}
