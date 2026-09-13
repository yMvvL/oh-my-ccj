package com.ccj.agent.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
 *   <li><b>The file is looked for upwards, not only in the working directory.</b> The usual layout is
 *       one rules file at the top of a tree of projects with the agent started somewhere inside it;
 *       reading only the working directory would silently drop the rules in exactly that case. The
 *       walk stops at the filesystem root, and every file found is included.
 *   <li><b>The closest file comes last.</b> A general rule stated first, then the specific one that
 *       narrows it — the order a reader expects, and the only one where a subdirectory can override
 *       its parent rather than be contradicted by it. Each block is introduced by its path, so a model
 *       looking at two rules that disagree can tell which file each came from.
 * </ul>
 *
 * <p>It is a help, never a dependency: a missing file, an unreadable directory or a walk that runs
 * out of parents all mean "no extra rules", which is exactly how a run behaved before this existed.
 */
public final class ProjectPrompt {

  /**
   * The name this project reads. One name, deliberately: guessing at other tools' file names would
   * put rules in front of the model that the user never wrote for ccj.
   */
  public static final String FILE_NAME = "CCJ.md";

  /**
   * How much of all the rules files together may reach the prompt.
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
   * The rules that apply to {@code directory}: every {@value #FILE_NAME} from the filesystem root
   * down to it, most distant first, each under a heading naming the file it came from.
   *
   * @return the rules as one block of prompt text, or {@code ""} when there are none
   */
  public static String from(Path directory) {
    if (directory == null) {
      return "";
    }
    Path start = directory.toAbsolutePath().normalize();
    List<String> blocks = new ArrayList<>();
    int used = 0;
    for (Path found : filesFromRootTo(start)) {
      String text;
      try {
        text = Files.readString(found, StandardCharsets.UTF_8);
      } catch (IOException | RuntimeException e) {
        // Unreadable is the same answer as absent: the prompt is a help, and a directory the process
        // cannot read must not be a reason for it to refuse to start.
        continue;
      }
      if (text.isBlank()) {
        continue;
      }
      String block = heading(found) + "\n" + text.strip() + "\n";
      int room = LIMIT_CHARS - used;
      if (room <= 0) {
        // The budget is spent. Saying so once is better than a model that believes it has read all
        // the rules when it has read the first few files.
        if (blocks.isEmpty() || !blocks.get(blocks.size() - 1).endsWith(CUT_MARKER)) {
          blocks.add(CUT_MARKER.strip() + "\n");
        }
        break;
      }
      if (block.length() > room) {
        blocks.add(block.substring(0, Math.max(0, room - CUT_MARKER.length())) + CUT_MARKER + "\n");
        break;
      }
      blocks.add(block);
      used += block.length();
    }
    if (blocks.isEmpty()) {
      return "";
    }
    return String.join("\n", blocks).strip();
  }

  /**
   * Every {@value #FILE_NAME} from the filesystem root down to {@code start}, so the caller can put
   * the closest one last.
   *
   * <p>Collected upwards and reversed rather than recursed downwards: the walk only ever visits the
   * directories between the working directory and the root, and the root is where it has to stop.
   */
  private static List<Path> filesFromRootTo(Path start) {
    List<Path> found = new ArrayList<>();
    for (Path dir = start; dir != null; dir = dir.getParent()) {
      Path candidate = dir.resolve(FILE_NAME);
      if (isReadableRegularFile(candidate)) {
        found.add(candidate);
      }
    }
    // Closest first is what the walk produced; the caller wants root-first.
    Collections.reverse(found);
    return found;
  }

  private static boolean isReadableRegularFile(Path file) {
    try {
      return Files.isRegularFile(file) && Files.isReadable(file);
    } catch (RuntimeException e) {
      return false;
    }
  }

  /** The line that says which file the rules under it came from. */
  private static String heading(Path file) {
    return "Rules from " + file + ":";
  }
}
