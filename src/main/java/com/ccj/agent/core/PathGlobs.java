package com.ccj.agent.core;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;

/**
 * Matching a project-relative path against the patterns users write.
 *
 * <p>Shared by the two features that let a user say "this applies to files like that" — the post-edit
 * checks and the approval rules — because two implementations of "what does `**` mean here" is one
 * more than can stay consistent, and the difference would show up as a check that never fires or a
 * rule that never matches.
 *
 * <p>A pattern beginning {@code **&#47;} is tried twice: as written, and with that prefix removed.
 * The second try is not a convenience. {@link PathMatcher} reads {@code **&#47;*.java} as "a java
 * file inside some directory" and does not match {@code Foo.java} at the top of the project, while
 * every other tool a user has met — gitignore, `.editorconfig`, ripgrep's {@code --glob} — reads
 * {@code **&#47;foo} as "foo at any depth, including none". A pattern that silently matches nothing
 * is indistinguishable from a rule with nothing to object to, which is the failure both callers
 * exist to avoid.
 */
public final class PathGlobs {

  private PathGlobs() {}

  /** True when {@code glob} matches {@code relative}, a path relative to the project root. */
  public static boolean matches(String glob, String relative) {
    if (matcher(glob).matches(Path.of(relative))) {
      return true;
    }
    return glob.startsWith("**/") && matcher(glob.substring(3)).matches(Path.of(relative));
  }

  /**
   * The path as a project-relative, forward-slashed string, or null when it is outside the project.
   *
   * <p>Outside is not "match anyway with the absolute path": a rule is a statement about this
   * project, and a file elsewhere is a different question with a different answer.
   */
  public static String relative(Path file, Path project) {
    Path target = file.toAbsolutePath().normalize();
    Path root = project.toAbsolutePath().normalize();
    if (!target.startsWith(root)) {
      return null;
    }
    return root.relativize(target).toString().replace('\\', '/');
  }

  private static PathMatcher matcher(String glob) {
    return FileSystems.getDefault().getPathMatcher("glob:" + glob);
  }
}
