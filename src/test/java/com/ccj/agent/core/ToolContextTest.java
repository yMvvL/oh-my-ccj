package com.ccj.agent.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 审批提示所依据的工作目录检查：一个路径是否留在里面，问的是它真正指向哪里，而不是它的名字
 * 读起来如何。
 */
class ToolContextTest {

  @TempDir Path dir;

  @Test
  void aPathThatDoesNotExistYetIsStillInside() throws Exception {
    ToolContext ctx = ToolContext.of(dir);

    assertTrue(ctx.insideCwd(dir.resolve("new-file.txt")), "一个即将创建的文件还没有真实路径");
    assertFalse(ctx.insideCwd(dir.resolve("../elsewhere.txt")));
    assertFalse(ctx.insideCwd(dir.getParent().resolve("sibling.txt")));
  }

  @Test
  void aSymlinkOutOfTheWorkingDirectoryCountsAsOutside() throws Exception {
    // 名字说的正好相反，这正是人们批准之前所读的那句话不能只靠词法检查的全部理由。
    Path outside = Files.createDirectories(dir.resolveSibling(dir.getFileName() + "-elsewhere"));
    Files.writeString(outside.resolve("secret.txt"), "top secret\n");
    Files.createSymbolicLink(dir.resolve("escape"), outside);

    ToolContext ctx = ToolContext.of(dir);

    assertFalse(ctx.insideCwd(dir.resolve("escape/secret.txt")), "这个链接离开了工作区");
    assertFalse(ctx.insideCwd(dir.resolve("escape")));
    assertTrue(ctx.insideCwd(dir.resolve("plain.txt")), "普通路径留在里面");
  }

  @Test
  void aWorkingDirectoryReachedThroughASymlinkIsNotAFalseAlarm() throws Exception {
    // /tmp 在 macOS 上是一个符号链接，而用户的项目也常常位于某个链接之后；两侧都比较真实路径，
    // 才能让每个路径都不至于看起来像外来的。
    Path real = Files.createDirectories(dir.resolve("real"));
    Files.writeString(real.resolve("file.txt"), "content\n");
    Path link = dir.resolve("link");
    Files.createSymbolicLink(link, real);

    ToolContext ctx = ToolContext.of(link);

    assertTrue(ctx.insideCwd(link.resolve("file.txt")), "无论怎么到达这个目录，都在里面");
    assertTrue(ctx.insideCwd(real.resolve("file.txt")), "同一个文件用它的真实名字也一样");
  }
}
