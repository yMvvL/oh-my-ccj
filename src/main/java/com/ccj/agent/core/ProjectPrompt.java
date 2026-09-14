package com.ccj.agent.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The rules a directory keeps for the agent working in it, read from {@value #FILE_NAME}.
 *
 * <p>A system prompt can only say what is true everywhere. What is true about <em>this</em> project —
 * the build command, the module that must not be touched, how its tests are run — belongs next to the
 * code it is about, so it moves with the project instead of living in one machine's config file and
 * being forgotten on the next machine.
 *
 * <p>Two decisions shape the reading:
 *
 * <ul>
 *   <li><b>Only the working directory itself is read.</b> A file higher up the tree is deliberately
 *       <em>not</em> consulted. The rule is "the agent uses the rules in the directory it is working
 *       in, and the configured prompt everywhere else", which is a statement a user can predict from
 *       looking at one folder. A walk to the filesystem root would mean a stray file in a home
 *       directory silently redefining the agent for every project beneath it, and the user would have
 *       to search upwards to find out why.
 *   <li><b>When it exists, it leads the prompt.</b> The file is the project's own statement about how
 *       work is done here, so it is read first and the built-in rules follow it — see
 *       {@link Prompts#system(String, String, Path)} for the ordering and why.
 * </ul>
 *
 * <p>It is a help, never a dependency: a missing file, an unreadable file, or an empty one all mean
 * "no project rules", which is exactly how a run behaved before this existed.
 */
public final class ProjectPrompt {

  /**
   * The name this project reads. One name, deliberately: guessing at other tools' file names would
   * put rules in front of the model that the user never wrote for ccj.
   */
  public static final String FILE_NAME = "CCJ.md";

  /**
   * How much of the rules file may reach the prompt.
   *
   * <p>The system prompt is sent on <em>every</em> request and, unlike the conversation, it is not
   * part of anything that trims: {@link ContextBudget} projects the messages, so an oversized prompt
   * is a request that grows with nothing able to cut it. The limit is generous for rules meant to be
   * read every turn (about 8k tokens of English), and a file past it is cut with a marker rather than
   * silently accepted or silently dropped.
   */
  public static final int LIMIT_CHARS = 32_000;

  /** What the cut says, so a model knows the rules it is reading are incomplete. */
  static final String CUT_MARKER =
      "\n\n... (cut short: this rules file did not fit the prompt limit) ...";

  private ProjectPrompt() {}

  /**
   * The rules {@code directory} keeps, or {@code ""} when it keeps none.
   *
   * <p>The text is returned as it was written. No heading naming the file is added: with a single file
   * at a single known location there is nothing to disambiguate, and a heading would spend prompt on
   * every request to say which of one file the text came from.
   *
   * @return the rules as prompt text, or {@code ""} when there are none
   */
  public static String from(Path directory) {
    if (directory == null) {
      return "";
    }
    Path file = directory.toAbsolutePath().normalize().resolve(FILE_NAME);
    if (!isReadableRegularFile(file)) {
      return "";
    }
    String text;
    try {
      text = Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException | RuntimeException e) {
      // Unreadable is the same answer as absent: the prompt is a help, and a file the process cannot
      // read must not be a reason for it to refuse to start.
      return "";
    }
    if (text.isBlank()) {
      // A file that exists but says nothing is not rules. This matters more here than it did for the
      // walk: saving an empty CCJ.md — an editor that has not been typed into yet, a placeholder
      // somebody is about to fill in — must not be read as "this project has no rules of any kind",
      // because the built-in ones still follow and the run would otherwise be indistinguishable from
      // a configured one.
      return "";
    }
    String trimmed = text.strip();
    if (trimmed.length() > LIMIT_CHARS) {
      return trimmed.substring(0, Math.max(0, LIMIT_CHARS - CUT_MARKER.length())) + CUT_MARKER;
    }
    return trimmed;
  }

  private static boolean isReadableRegularFile(Path file) {
    try {
      return Files.isRegularFile(file) && Files.isReadable(file);
    } catch (RuntimeException e) {
      return false;
    }
  }
}
