package com.ccj.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Approver;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code restart} is the one tool that ends the process it runs in, so what it refuses matters as
 * much as what it does: every path that is not exactly "a scratch build, ready to be installed"
 * has to come back as an error rather than a swap.
 */
class RestartToolTest {

  @TempDir Path dir;

  private final RestartTool tool = new RestartTool();

  private static ToolContext context(Path cwd, boolean approve, AtomicBoolean ended) {
    return new ToolContext(
        cwd, (title, detail) -> approve, 0, () -> false, () -> ended.set(true));
  }

  /** A directory shaped like the project: an installed jar and a scratch build beside it. */
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
    assertFalse(Files.exists(project.resolve("target/ccj-next.jar")), "the scratch build is moved");
    assertTrue(ended.get(), "the run has to end: the process is being replaced");
    assertTrue(RestartTool.restartRequested(), "the launcher is told to start the new jar");
  }

  @Test
  void aRejectedRestartChangesNothing() throws Exception {
    Path project = project();
    AtomicBoolean ended = new AtomicBoolean();

    ToolResult result =
        tool.execute("{\"built\":\"target/ccj-next.jar\"}", context(project, false, ended));

    assertTrue(result.error(), result.content());
    assertTrue(result.content().contains("rejected"), result.content());
    assertEquals("installed", Files.readString(project.resolve("target/ccj.jar")));
    assertEquals("next", Files.readString(project.resolve("target/ccj-next.jar")));
    assertFalse(ended.get(), "nothing happened, so nothing ends");
  }

  @Test
  void refusesAJarThatDoesNotExist() throws Exception {
    Path project = project();
    AtomicBoolean ended = new AtomicBoolean();

    ToolResult result = tool.execute("{\"built\":\"target/ccj-gone.jar\"}", context(project, true, ended));

    assertTrue(result.error(), result.content());
    assertTrue(result.content().contains("build this project first"), result.content());
    assertFalse(ended.get());
  }

  @Test
  void refusesToInstallTheJarItIsAlreadyRunningFrom() throws Exception {
    Path project = project();
    AtomicBoolean ended = new AtomicBoolean();

    ToolResult result = tool.execute("{\"built\":\"target/ccj.jar\"}", context(project, true, ended));

    assertTrue(result.error(), result.content());
    assertTrue(result.content().contains("already the installed jar"), result.content());
    assertFalse(ended.get(), "a no-op swap must not end the run");
  }

  @Test
  void refusesToInstallSomethingThatIsNotTheScratchBuild() throws Exception {
    Path project = project();
    Files.writeString(project.resolve("target/unrelated.jar"), "whatever");
    AtomicBoolean ended = new AtomicBoolean();

    ToolResult result =
        tool.execute("{\"built\":\"target/unrelated.jar\"}", context(project, true, ended));

    assertTrue(result.error(), result.content());
    assertTrue(result.content().contains("is not the jar a build stages"), result.content());
    assertFalse(ended.get());
  }

  @Test
  void refusesWhenThereIsNoInstalledJarToReplace() throws Exception {
    Files.createDirectories(dir.resolve("target"));
    Files.writeString(dir.resolve("target/ccj-next.jar"), "next");
    AtomicBoolean ended = new AtomicBoolean();

    ToolResult result = tool.execute("{\"built\":\"target/ccj-next.jar\"}", context(dir, true, ended));

    assertTrue(result.error(), result.content());
    assertTrue(result.content().contains("no ccj.jar"), result.content());
    assertFalse(ended.get());
  }

  @Test
  void theApprovalDetailNamesBothFiles() throws Exception {
    Path project = project();
    StringBuilder detail = new StringBuilder();
    ToolContext ctx =
        new ToolContext(
            project,
            (title, text) -> {
              detail.append(text);
              return false;
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
    // 0 success, 1 runtime failure, 2 usage error — the restart signal must be distinguishable from
    // all of them, because the launcher keys on it.
    assertFalse(
        RestartTool.RESTART_EXIT == 0 || RestartTool.RESTART_EXIT == 1 || RestartTool.RESTART_EXIT == 2,
        "the restart code collides with an ordinary exit status: " + RestartTool.RESTART_EXIT);
  }
}
