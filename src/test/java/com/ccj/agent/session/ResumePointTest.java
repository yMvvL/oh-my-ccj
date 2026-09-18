package com.ccj.agent.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 一个进程留给下一个进程的便条：再次打开哪段会话。
 *
 * <p>重启会换掉进程，而新进程会开一段新会话——于是用户正看着的那份工作，恰好在他们要求更新它的那一刻从
 * 屏幕上消失。这是交接中唯一必须活下来的事实。它是 home 目录里的一个普通文件，而不是环境变量或命令行
 * 开关，因为启动器只是重跑同一条命令，对会话一无所知。
 */
class ResumePointTest {

  @TempDir Path home;

  @Test
  void nothingIsRememberedUntilSomethingRemembersIt() {
    assertTrue(ResumePoint.read(home).isEmpty());
  }

  @Test
  void whatWasWrittenIsWhatIsRead() {
    ResumePoint.write(home, "20260913-133000-abcd");

    assertEquals(Optional.of("20260913-133000-abcd"), ResumePoint.read(home));
  }

  @Test
  void writingAgainReplacesThePreviousAnswer() {
    ResumePoint.write(home, "20260913-133000-abcd");
    ResumePoint.write(home, "20260913-134500-1234");

    assertEquals(Optional.of("20260913-134500-1234"), ResumePoint.read(home));
  }

  @Test
  void clearingItMeansNone() {
    ResumePoint.write(home, "20260913-133000-abcd");

    ResumePoint.clear(home);

    assertTrue(ResumePoint.read(home).isEmpty());
    assertFalse(Files.exists(ResumePoint.file(home)));
  }

  @Test
  void aDamagedNoteIsIgnoredRatherThanFatal() throws Exception {
    // 便条是一份方便，不是记录：进程绝不能因为读不了它、或它被别人覆盖过就拒绝启动。
    Path file = ResumePoint.file(home);
    Files.writeString(file, "not a session id at all\nsecond line\n", StandardCharsets.UTF_8);
    assertTrue(ResumePoint.read(home).isEmpty());

    Files.writeString(file, "", StandardCharsets.UTF_8);
    assertTrue(ResumePoint.read(home).isEmpty());

    Files.delete(file);
    Files.createDirectories(file);
    assertTrue(ResumePoint.read(home).isEmpty(), "便条该在的位置上是个目录，那也只是等于不存在");
  }

  @Test
  void anIdThatCouldNotBeAFileNameIsNeverStored() {
    // 存下来就等于声称下一个进程能打开它，而它打不开：id 是一个文件名，而这个是路径。
    ResumePoint.write(home, "../../etc/passwd");
    ResumePoint.write(home, "");
    ResumePoint.write(home, null);

    assertTrue(ResumePoint.read(home).isEmpty());
    assertFalse(Files.exists(home.getParent().resolve("etc").resolve("passwd")));
  }

  @Test
  void onlyTheFirstLineCounts() throws Exception {
    Files.writeString(
        ResumePoint.file(home), "20260913-133000-abcd\ngarbage after it\n", StandardCharsets.UTF_8);

    assertEquals(Optional.of("20260913-133000-abcd"), ResumePoint.read(home));
  }

  @Test
  void surroundingWhitespaceIsNotPartOfTheId() throws Exception {
    Files.writeString(
        ResumePoint.file(home), "  20260913-133000-abcd  \n", StandardCharsets.UTF_8);

    assertEquals(Optional.of("20260913-133000-abcd"), ResumePoint.read(home));
  }
}
