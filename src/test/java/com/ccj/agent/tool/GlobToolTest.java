package com.ccj.agent.tool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GlobToolTest {

  @TempDir Path dir;

  @Test
  void doubleStarMatchesNestedFilesAndSkipsNoiseDirectories() throws Exception {
    Files.createDirectories(dir.resolve("a"));
    Files.createDirectories(dir.resolve("nested/deep"));
    Files.createDirectories(dir.resolve("target"));
    Files.createDirectories(dir.resolve("node_modules"));
    Files.createDirectories(dir.resolve(".git"));
    Files.writeString(dir.resolve("Top.java"), "class Top {}");
    Files.writeString(dir.resolve("a/A.java"), "class A {}");
    Files.writeString(dir.resolve("nested/deep/B.java"), "class B {}");
    Files.writeString(dir.resolve("target/C.java"), "class C {}");
    Files.writeString(dir.resolve("node_modules/D.java"), "class D {}");
    Files.writeString(dir.resolve(".git/E.java"), "class E {}");

    ToolResult result = new GlobTool().execute("{\"pattern\":\"**/*.java\"}", ToolContext.of(dir));

    assertFalse(result.error(), result.content());
    assertTrue(result.content().contains("Top.java"), result.content());
    assertTrue(result.content().contains("a/A.java"), result.content());
    assertTrue(result.content().contains("nested/deep/B.java"), result.content());
    assertFalse(result.content().contains("target/C.java"), result.content());
    assertFalse(result.content().contains("node_modules/D.java"), result.content());
    assertFalse(result.content().contains(".git/E.java"), result.content());
  }

  @Test
  void doesNotFollowSymlinkedDirectories() throws Exception {
    Path outside = Files.createTempDirectory("ccj-glob-outside");
    try {
      Files.writeString(outside.resolve("Linked.java"), "class Linked {}");
      Files.createSymbolicLink(dir.resolve("link"), outside);

      ToolResult result = new GlobTool().execute("{\"pattern\":\"**/*.java\"}", ToolContext.of(dir));

      assertFalse(result.content().contains("Linked.java"), result.content());
      assertTrue(result.content().contains("没有任何匹配的文件"), result.content());
    } finally {
      Files.deleteIfExists(outside.resolve("Linked.java"));
      Files.deleteIfExists(outside);
    }
  }

  @Test
  void capsResultsAndReportsOmissions() throws Exception {
    Path many = Files.createDirectories(dir.resolve("many"));
    for (int i = 0; i < 210; i++) {
      Files.writeString(many.resolve("f" + i + ".txt"), "x");
    }

    ToolResult result = new GlobTool().execute("{\"pattern\":\"many/*.txt\"}", ToolContext.of(dir));

    assertFalse(result.error(), result.content());
    assertTrue(result.content().contains("省略了 10 个匹配"), result.content());
  }

  @Test
  void reportsInvalidPatterns() throws Exception {
    ToolResult result = new GlobTool().execute("{\"pattern\":\"[unclosed\"}", ToolContext.of(dir));

    assertTrue(result.error(), result.content());
    assertTrue(result.content().contains("glob 模式 '[unclosed' 无效"), result.content());
  }
}
