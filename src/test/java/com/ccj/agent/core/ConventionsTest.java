package com.ccj.agent.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The spelling half of {@code docs/CONVENTIONS.md}, made checkable.
 *
 * <p>A convention that only a reviewer can enforce is one that drifts, and this one had: the tree
 * spells its own words the British way ({@code normalise}, {@code summarise}, {@code serialise}) while
 * a handful of sites had drifted American — {@code ToolSummary.summarize}, "failed to serialize JSON",
 * {@code Config.normalizeLanguage} sitting next to {@code Config.normaliseReasoning} in the same class.
 * Each of those was invisible to every reader who was not looking for it.
 *
 * <p>What is checked is the source tree, not the shipped output, and only the words this house owns.
 * A platform's spelling is quoted rather than translated: {@code Path.normalize}, {@code
 * overscroll-behavior} and {@code text-align: center} are the names of things somebody else wrote, and
 * the exemption list below is exactly the set of them this tree uses.
 */
class ConventionsTest {

  private static final Path MAIN = Path.of("src", "main", "java");

  /**
   * American spellings of words this house writes the British way.
   *
   * <p>{@code normalize} is deliberately absent from this list: {@link Path#normalize()} is the JDK's
   * own method and is called thirty-odd times, so the word appears in the tree legitimately and a
   * rule against it would be a rule against the standard library.
   */
  private static final List<String> AMERICAN = List.of(
      "summarize", "summarizes", "summarized", "summarizing", "summarization",
      "serialize", "serializes", "serialized", "serializing", "serialization",
      "organize", "organized", "organizing", "organization",
      "recognize", "recognized", "recognizing",
      "initialize", "initialized", "initializing", "initialization",
      // `synchronized` is a Java keyword rather than a word this tree chose, so it is not on the
      // list: a rule against it would be a rule against the language.
      "behavior", "color", "center", "flavor", "license");

  /** Where a borrowed spelling is the correct one: names inside quotes, or a platform API. */
  private static final List<String> BORROWED = List.of(
      "overscroll-behavior",
      "blockedEntities",
      "Path.normalize",
      "normalize()",
      "text-align: center",
      "align-items: center",
      "justify-content: center",
      "background-color",
      "border-color",
      "accent-color",
      "currentColor",
      "color-scheme",
      "getColor",
      "setColor",
      "color:");

  @Test
  void theTreeSpellsItsOwnWordsTheBritishWay() throws IOException {
    assertTrue(Files.isDirectory(MAIN), "run from the repository root: " + MAIN.toAbsolutePath());

    List<String> offenders = new ArrayList<>();
    try (Stream<Path> files = Files.walk(MAIN)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        String[] lines = source.split("\n", -1);
        for (int line = 0; line < lines.length; line++) {
          for (String american : AMERICAN) {
            Matcher matcher = Pattern.compile("\\b" + american + "\\b", Pattern.CASE_INSENSITIVE)
                .matcher(lines[line]);
            while (matcher.find()) {
              if (!borrowed(lines[line])) {
                offenders.add(file + ":" + (line + 1) + " — '" + matcher.group() + "': "
                    + lines[line].strip());
              }
            }
          }
        }
      }
    }

    assertTrue(
        offenders.isEmpty(),
        "the British spelling belongs in this tree (docs/CONVENTIONS.md, *Spelling and voice*); "
            + "borrow a platform's spelling only where the platform owns the word:\n"
            + String.join("\n", offenders));
  }

  /** True when the line quotes a name somebody else owns, so its spelling is not ours to change. */
  private static boolean borrowed(String line) {
    for (String name : BORROWED) {
      if (line.contains(name)) {
        return true;
      }
    }
    return false;
  }
}
