package com.ccj.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The token a run keeps between restarts: generated once, reused after that, and never world-readable.
 *
 * <p>What it is for is the part worth pinning. A token that changed on every start would log the
 * phone out every time ccj was restarted, and one that a human chose would be the weak link in a
 * 256-bit design — so "the same value comes back" and "a fresh value is unguessable" are the two
 * properties here, not the mechanics of writing a file.
 */
class WebTokenTest {

  @TempDir Path home;

  @Test
  void theTokenIsGeneratedOnceAndThenReused() throws Exception {
    String first = WebToken.from(home);
    String second = WebToken.from(home);

    assertEquals(first, second, "a restart must not invalidate the phone's cookie");
    assertEquals(first, Files.readString(home.resolve(WebToken.FILE_NAME)).strip());
    // 32 random bytes as hex: long enough that guessing is not a strategy, and the trailing newline
    // is not part of it.
    assertEquals(64, first.length());
    assertTrue(first.chars().allMatch(c -> Character.digit(c, 16) >= 0), first);
  }

  @Test
  void twoInstallationsDoNotShareAToken() throws Exception {
    Path other = Files.createDirectories(home.resolve("other-home"));

    assertNotEquals(WebToken.from(home), WebToken.from(other));
    assertNotEquals(WebToken.generate(), WebToken.generate());
  }

  @Test
  void theFileIsNotReadableByAnyoneElse() throws Exception {
    WebToken.from(home);
    Path file = home.resolve(WebToken.FILE_NAME);

    try {
      // Skipped rather than failed on a filesystem without POSIX permissions: the file is still
      // stored, and the platform decides.
      assertEquals(
          "rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    } catch (UnsupportedOperationException ignored) {
      assertTrue(Files.isRegularFile(file));
    }
  }

  @Test
  void anEmptyFileIsReplacedRatherThanObeyed() throws Exception {
    // An empty file is a mistake, not a policy: serving a network bind with a blank token would be
    // serving it with none.
    Files.writeString(home.resolve(WebToken.FILE_NAME), "\n");

    String token = WebToken.from(home);

    assertFalse(token.isBlank());
    assertEquals(token, Files.readString(home.resolve(WebToken.FILE_NAME)).strip());
  }

  @Test
  void anUnwritableHomeIsReportedRatherThanIgnored() throws Exception {
    // The caller has to be able to fail closed, which means this must throw rather than return
    // something the caller would then serve without a token.
    Path file = Files.writeString(home.resolve("not-a-directory"), "x");

    assertTrue(assertThrowsIOException(() -> WebToken.from(file)), "a file is not a home directory");
  }

  private static boolean assertThrowsIOException(ThrowingRunnable runnable) {
    try {
      runnable.run();
      return false;
    } catch (IOException e) {
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private interface ThrowingRunnable {
    void run() throws Exception;
  }
}
