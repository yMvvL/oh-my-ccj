package com.ccj.agent.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The working-directory check that the approval prompt is built on: whether a path stays inside is a
 * question about where it really points, not about how its name reads.
 */
class ToolContextTest {

  @TempDir Path dir;

  @Test
  void aPathThatDoesNotExistYetIsStillInside() throws Exception {
    ToolContext ctx = ToolContext.of(dir);

    assertTrue(ctx.insideCwd(dir.resolve("new-file.txt")), "a file about to be created has no real path");
    assertFalse(ctx.insideCwd(dir.resolve("../elsewhere.txt")));
    assertFalse(ctx.insideCwd(dir.getParent().resolve("sibling.txt")));
  }

  @Test
  void aSymlinkOutOfTheWorkingDirectoryCountsAsOutside() throws Exception {
    // The name says otherwise, which is the whole reason a lexical check is not enough for the
    // sentence a human reads before approving.
    Path outside = Files.createDirectories(dir.resolveSibling(dir.getFileName() + "-elsewhere"));
    Files.writeString(outside.resolve("secret.txt"), "top secret\n");
    Files.createSymbolicLink(dir.resolve("escape"), outside);

    ToolContext ctx = ToolContext.of(dir);

    assertFalse(ctx.insideCwd(dir.resolve("escape/secret.txt")), "the link leaves the workspace");
    assertFalse(ctx.insideCwd(dir.resolve("escape")));
    assertTrue(ctx.insideCwd(dir.resolve("plain.txt")), "an ordinary path stays inside");
  }

  @Test
  void aWorkingDirectoryReachedThroughASymlinkIsNotAFalseAlarm() throws Exception {
    // /tmp is a symlink on macOS and a user's project often sits behind one; comparing real paths on
    // both sides is what keeps every path from looking foreign.
    Path real = Files.createDirectories(dir.resolve("real"));
    Files.writeString(real.resolve("file.txt"), "content\n");
    Path link = dir.resolve("link");
    Files.createSymbolicLink(link, real);

    ToolContext ctx = ToolContext.of(link);

    assertTrue(ctx.insideCwd(link.resolve("file.txt")), "inside, however the directory was reached");
    assertTrue(ctx.insideCwd(real.resolve("file.txt")), "and the same file through its real name");
  }
}
