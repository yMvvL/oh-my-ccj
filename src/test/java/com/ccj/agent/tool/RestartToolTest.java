package com.ccj.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.ApprovalAnswer;
import com.ccj.agent.core.Approver;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code restart} 是唯一一个会结束自己所处进程的工具，所以它拒绝什么和它做什么同样要紧：每一条不是
 * 恰好「一份临时构建、准备安装」的路径，都必须以错误而不是一次替换收场。
 */
class RestartToolTest {

  @TempDir Path dir;

  private final RestartTool tool = new RestartTool();

  private static ToolContext context(Path cwd, boolean approve, AtomicBoolean ended) {
    return new ToolContext(
        cwd,
        request -> approve ? ApprovalAnswer.ALLOW_ONCE : ApprovalAnswer.DENY,
        0,
        () -> false,
        () -> ended.set(true));
  }

  /** 一个长得像这个项目的目录：一个已安装的 jar，旁边放着一份临时构建。 */
  private Path project() throws Exception {
    Files.createDirectories(dir.resolve("target"));
    Files.writeString(dir.resolve("target/ccj.jar"), "installed");
    Files.writeString(dir.resolve("target/ccj-next.jar"), "next");
    return dir;
  }

  @Test
  void installsTheScratchBuildOverTheJarInUse() throws Exception {
    Path project = project();
    AtomicBoolean ended = new AtomicBoolean();

    ToolResult result =
        tool.execute("{\"built\":\"target/ccj-next.jar\"}", context(project, true, ended));

    assertFalse(result.error(), result.content());
    assertEquals("next", Files.readString(project.resolve("target/ccj.jar")));
    assertFalse(Files.exists(project.resolve("target/ccj-next.jar")), "临时构建被移走了");
    assertTrue(ended.get(), "这次运行必须结束：本进程正在被替换");
    assertTrue(RestartTool.restartRequested(), "启动器被告知去启动新的 jar");
  }

  @Test
  void aRejectedRestartChangesNothing() throws Exception {
    Path project = project();
    AtomicBoolean ended = new AtomicBoolean();

    ToolResult result =
        tool.execute("{\"built\":\"target/ccj-next.jar\"}", context(project, false, ended));

    assertTrue(result.error(), result.content());
    assertTrue(result.content().contains("拒绝"), result.content());
    assertEquals("installed", Files.readString(project.resolve("target/ccj.jar")));
    assertEquals("next", Files.readString(project.resolve("target/ccj-next.jar")));
    assertFalse(ended.get(), "什么都没发生，所以什么都不结束");
  }

  @Test
  void refusesAJarThatDoesNotExist() throws Exception {
    Path project = project();
    AtomicBoolean ended = new AtomicBoolean();

    ToolResult result = tool.execute("{\"built\":\"target/ccj-gone.jar\"}", context(project, true, ended));

    assertTrue(result.error(), result.content());
    assertTrue(result.content().contains("先构建这个项目"), result.content());
    assertFalse(ended.get());
  }

  @Test
  void refusesToInstallTheJarItIsAlreadyRunningFrom() throws Exception {
    Path project = project();
    AtomicBoolean ended = new AtomicBoolean();

    ToolResult result = tool.execute("{\"built\":\"target/ccj.jar\"}", context(project, true, ended));

    assertTrue(result.error(), result.content());
    assertTrue(result.content().contains("已经是当前安装的 jar"), result.content());
    assertFalse(ended.get(), "一次没有效果的替换不能结束这次运行");
  }

  @Test
  void refusesToInstallSomethingThatIsNotTheScratchBuild() throws Exception {
    Path project = project();
    Files.writeString(project.resolve("target/unrelated.jar"), "whatever");
    AtomicBoolean ended = new AtomicBoolean();

    ToolResult result =
        tool.execute("{\"built\":\"target/unrelated.jar\"}", context(project, true, ended));

    assertTrue(result.error(), result.content());
    assertTrue(result.content().contains("不是构建所暂存的那个 jar"), result.content());
    assertFalse(ended.get());
  }

  @Test
  void refusesWhenThereIsNoInstalledJarToReplace() throws Exception {
    Files.createDirectories(dir.resolve("target"));
    Files.writeString(dir.resolve("target/ccj-next.jar"), "next");
    AtomicBoolean ended = new AtomicBoolean();

    ToolResult result = tool.execute("{\"built\":\"target/ccj-next.jar\"}", context(dir, true, ended));

    assertTrue(result.error(), result.content());
    assertTrue(result.content().contains("没有 ccj.jar"), result.content());
    assertFalse(ended.get());
  }

  @Test
  void theApprovalDetailNamesBothFiles() throws Exception {
    Path project = project();
    StringBuilder detail = new StringBuilder();
    ToolContext ctx =
        new ToolContext(
            project,
            request -> {
              detail.append(request.detail());
              return ApprovalAnswer.DENY;
            },
            0,
            () -> false,
            () -> {});

    tool.execute("{\"built\":\"target/ccj-next.jar\"}", ctx);

    assertTrue(detail.toString().contains("ccj-next.jar"), detail.toString());
    assertTrue(detail.toString().contains("ccj.jar"), detail.toString());
  }

  @Test
  void theExitCodeIsOneTheRestOfTheCliNeverRaises() {
    // 0 成功、1 运行时失败、2 用法错误——重启信号必须能与它们全部区分开，因为启动器就靠它来判定。
    assertFalse(
        RestartTool.RESTART_EXIT == 0 || RestartTool.RESTART_EXIT == 1 || RestartTool.RESTART_EXIT == 2,
        "重启状态码与一个普通的退出状态撞车了: " + RestartTool.RESTART_EXIT);
  }
}
