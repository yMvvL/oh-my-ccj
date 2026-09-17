package com.ccj.agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The commands that run by themselves after an edit, as declared by the user in the config file.
 *
 * <pre>
 * "checks": [
 *   {"glob": "**&#47;*.java", "command": "mvn -q -o -DskipTests compile", "timeoutSeconds": 120}
 * ]
 * </pre>
 *
 * <p>Why this exists: the largest difference between this agent and one backed by an editor is when
 * the compiler gets to speak. Without a check, a broken edit is discovered when the model decides to
 * go looking — a turn later, if at all — so a weak model needs three or four passes where one would
 * do. With one, the error arrives in the same step as the edit that caused it, and the fix is the
 * next thing the model does.
 *
 * <p>The command is the user's, not the model's: it is written into the config file, which is the
 * same act as typing it into a terminal, and it runs without a prompt for that reason. That is a
 * deliberate widening of the approval model and it is written down as one in {@code SECURITY.md}.
 * What the model may not do is add one — although it can write the config file, and a config file it
 * can write is a check it can add, one approval away. The same exposure as {@code CCJ.md}.
 *
 * <p>Read from the file on every use rather than held at startup: a check is something a user adds
 * while the session is running, and a copy taken at startup would make the config file look like it
 * did nothing until the next restart.
 *
 * <p><strong>The glob decides when the check runs, not what the command looks at.</strong> Those are
 * two different scopes and only the first is written here. Measured: with
 * {@code {"glob": "**&#47;*.java", "command": "mvn -q -o -DskipTests compile"}}, an edit to a broken
 * file at the repository root comes back {@code exit 0} — Maven compiles {@code src/main/java} and
 * never saw the file. That is not a bug in this class, it is the shape of a hook, but it is the shape
 * a user has to know: pair the glob with a command that covers what the glob selects, or the check
 * reports success about a file it did not read.
 */
public final class Checks {

  /** What one check is: when it applies, what it runs, and how long it may take. */
  public record Check(String glob, String command, int timeoutSeconds) {}

  private static final int DEFAULT_TIMEOUT_SECONDS = 120;
  private static final int MAX_TIMEOUT_SECONDS = 600;

  private final Path configFile;

  private Checks(Path configFile) {
    this.configFile = configFile;
  }

  /** The checks declared in {@code configFile}; a null or missing file declares none. */
  public static Checks from(Path configFile) {
    return new Checks(configFile);
  }

  /** Nothing configured, which is what every caller that has no config file gets. */
  public static Checks none() {
    return new Checks(null);
  }

  /**
   * The first check whose glob matches {@code edited}, or empty when none does.
   *
   * <p>Matched against the path relative to {@code cwd}, with forward slashes, so a check reads the
   * same on every platform and the same way the {@code glob} tool's patterns do. A file outside the
   * session's working directory never matches: a check is a statement about this project, and
   * running the project's build because the agent edited something in `~/.config` would be a
   * surprise, not a service.
   */
  public Optional<Check> forPath(Path edited, Path cwd) {
    Path file = edited.toAbsolutePath().normalize();
    Path root = cwd.toAbsolutePath().normalize();
    if (!file.startsWith(root)) {
      return Optional.empty();
    }
    String relative = root.relativize(file).toString().replace('\\', '/');
    for (Check check : declared()) {
      if (matches(check.glob(), relative)) {
        return Optional.of(check);
      }
    }
    return Optional.empty();
  }

  /**
   * True when {@code glob} matches the project-relative path.
   *
   * <p>A pattern beginning {@code **&#47;} is tried twice: as written, and with that prefix removed.
   * The second try is not a convenience — it is the difference between a check that works and one
   * that silently never runs. {@code PathMatcher} reads {@code **&#47;*.java} as "a java file inside
   * some directory", so it does not match {@code Foo.java} at the top of the project, while every
   * other tool a user has met (gitignore, `.editorconfig`, ripgrep's {@code --glob}) treats
   * {@code **&#47;foo} as "foo, at any depth, including none". A user who writes the pattern they
   * know and finds that the check never fires has no way to tell that from a check with nothing to
   * report, which is the failure mode this class exists to avoid.
   */
  private static boolean matches(String glob, String relative) {
    if (matcher(glob).matches(Path.of(relative))) {
      return true;
    }
    return glob.startsWith("**/") && matcher(glob.substring(3)).matches(Path.of(relative));
  }

  private static PathMatcher matcher(String glob) {
    return FileSystems.getDefault().getPathMatcher("glob:" + glob);
  }

  /**
   * The checks as the file declares them right now, or none when there is no file or no block.
   *
   * <p>A malformed block throws rather than being ignored. The alternative — quietly running no
   * checks because one of them has a typo — is the failure this class exists to prevent, arriving
   * silently.
   *
   * @throws IllegalArgumentException when the block is not an array of objects carrying a command
   * @throws UncheckedIOException when the file exists but cannot be read
   */
  public List<Check> declared() {
    if (configFile == null || !Files.isRegularFile(configFile)) {
      return List.of();
    }
    JsonNode root;
    try {
      root = Json.parse(Files.readString(configFile, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + configFile, e);
    }
    if (!root.isObject()) {
      return List.of();
    }
    JsonNode block = root.get("checks");
    if (block == null || block.isNull()) {
      return List.of();
    }
    if (!block.isArray()) {
      throw new IllegalArgumentException(
          "config field 'checks' must be an array of {glob, command} objects in " + configFile);
    }
    List<Check> checks = new ArrayList<>();
    for (JsonNode entry : block) {
      if (!entry.isObject()) {
        throw new IllegalArgumentException(
            "each entry of config field 'checks' must be an object in " + configFile + ": " + entry);
      }
      String command = text(entry, "command", configFile);
      if (command == null) {
        throw new IllegalArgumentException(
            "a check in " + configFile + " has no 'command', so there is nothing to run");
      }
      String glob = text(entry, "glob", configFile);
      JsonNode timeout = entry.get("timeoutSeconds");
      int seconds = DEFAULT_TIMEOUT_SECONDS;
      if (timeout != null && !timeout.isNull()) {
        if (!timeout.isInt()) {
          throw new IllegalArgumentException(
              "check 'timeoutSeconds' must be an integer in " + configFile + ": " + timeout);
        }
        seconds = Math.max(1, Math.min(MAX_TIMEOUT_SECONDS, timeout.asInt()));
      }
      checks.add(new Check(glob == null ? "**" : glob, command, seconds));
    }
    return List.copyOf(checks);
  }

  private static String text(JsonNode entry, String field, Path file) {
    JsonNode node = entry.get(field);
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isTextual()) {
      throw new IllegalArgumentException(
          "check '" + field + "' must be a string in " + file + ": " + node);
    }
    String value = node.asText().strip();
    return value.isEmpty() ? null : value;
  }
}
