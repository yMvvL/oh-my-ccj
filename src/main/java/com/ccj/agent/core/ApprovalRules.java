package com.ccj.agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The rules that answer an approval before anybody is asked.
 *
 * <pre>
 * { "projects": {
 *     "/home/you/api": {
 *       "allow": [ {"tool": "bash", "command": "mvn -q -o test"},
 *                  {"tool": "bash", "command": "git diff *"},
 *                  {"tool": "edit", "path": "src/**"} ],
 *       "deny":  [ {"tool": "bash", "command": "rm -rf *"} ] } } }
 * </pre>
 *
 * <p>Why this exists: with a boolean gate the only answers were "yes, once" and "yes, everything,
 * all session", so the choice a user actually faced was being interrupted on every write and every
 * command, or turning the guard off. Measured on a small task — fix one broken file — five tool calls
 * cost two interruptions, and the way through was the auto-approve toggle. That is the friction this
 * removes, and it removes it without widening the guard for the things that deserve one.
 *
 * <p>Keyed by project, and kept in the application home rather than in the repository. A rule file in
 * the repository would be a rule file a clone could bring with it: `git clone` and a project has
 * decided on its own that `rm -rf *` needs no approval. Rules are the user's, per project, in the
 * user's own directory.
 *
 * <p>Read on every decision rather than held at startup, for the same reason the checks are: a rule
 * added while a session runs has to take effect now, and a file that looks like it did nothing is a
 * file the user stops trusting. A malformed rule throws instead of being ignored — a rule that
 * silently never matches is indistinguishable from a rule with nothing to object to.
 */
public final class ApprovalRules {

  /** One rule: a tool, and the thing about that tool's request it matches. */
  public record Rule(String tool, String command, String path) {}

  /**
   * The characters that let one command become several, or one word become another.
   *
   * <p>An allow rule is a statement about a command the user knows, so a command that can turn into
   * something else is not that command: `git status; rm -rf /` starts with `git status` and is not it.
   * Textual, and deliberately so — a check that tried to parse quoting would be the wrong kind of
   * clever — which is why a semicolon inside quotes is refused too. `~` is not on the list: it
   * expands to a directory, and cannot chain anything.
   */
  private static final String COMPOUNDING = ";|&><`$(){}[]*?!\\\n\r";

  private final Path file;
  private final Path project;
  /** Rules added by an {@link ApprovalAnswer#ALLOW_SESSION} answer, for this process only. */
  private final List<Rule> sessionAllows = new ArrayList<>();

  private ApprovalRules(Path file, Path project) {
    this.file = file;
    this.project = project.toAbsolutePath().normalize();
  }

  /** The rules for {@code project}, kept in {@code file}. */
  public static ApprovalRules open(Path file, Path project) {
    return new ApprovalRules(file, project);
  }

  /**
   * What the rules say, if anything: {@code true} to allow without asking, {@code false} to refuse
   * without asking, empty to ask a person.
   *
   * <p>Deny is consulted first and wins over everything, including a session allow — otherwise
   * answering "allow for this session" once would outrank a rule the user wrote to stop exactly that
   * command, and the file would be unable to forbid anything.
   */
  public Optional<Boolean> verdict(ApprovalRequest request) {
    if (matchesAny(deny(), request)) {
      return Optional.of(false);
    }
    if (matchesAny(allow(), request) || matchesAny(sessionAllows, request)) {
      return Optional.of(true);
    }
    return Optional.empty();
  }

  /** Records an allow for the rest of this process, which is what "allow for this session" means. */
  public void rememberForSession(ApprovalRequest request) {
    Rule rule = ruleFor(request);
    sessionAllows.add(rule);
  }

  /**
   * Writes an allow rule into the file, for this project, keeping whatever else it holds.
   *
   * <p>An exact rule and never a widened one: a command is written as it was run, and a path as it
   * was touched. "Always allow this" that quietly allowed more than the thing in front of the user
   * would be the same defect as an approval prompt that does not say what it is approving.
   */
  public void remember(ApprovalRequest request) {
    Rule rule = ruleFor(request);
    Map<String, ProjectRules> all = read();
    ProjectRules mine = all.getOrDefault(project.toString(), ProjectRules.empty());
    List<Rule> allow = new ArrayList<>(mine.allow());
    if (!allow.contains(rule)) {
      allow.add(rule);
    }
    all.put(project.toString(), new ProjectRules(allow, mine.deny()));
    write(all);
  }

  /** Every rule this process knows about, for the report a user can ask for. */
  public List<String> describe() {
    List<String> lines = new ArrayList<>();
    deny().forEach(rule -> lines.add("deny   " + render(rule)));
    allow().forEach(rule -> lines.add("allow  " + render(rule)));
    sessionAllows.forEach(rule -> lines.add("allow  " + render(rule) + " (this session)"));
    return lines;
  }

  /** The file this project's rules live in, whether or not it exists yet. */
  public Path file() {
    return file;
  }

  private record ProjectRules(List<Rule> allow, List<Rule> deny) {
    static ProjectRules empty() {
      return new ProjectRules(List.of(), List.of());
    }
  }

  private List<Rule> allow() {
    return read().getOrDefault(project.toString(), ProjectRules.empty()).allow();
  }

  private List<Rule> deny() {
    return read().getOrDefault(project.toString(), ProjectRules.empty()).deny();
  }

  private boolean matchesAny(List<Rule> rules, ApprovalRequest request) {
    for (Rule rule : rules) {
      if (matches(rule, request)) {
        return true;
      }
    }
    return false;
  }

  private boolean matches(Rule rule, ApprovalRequest request) {
    if (rule.tool() == null || !rule.tool().equals(request.tool())) {
      return false;
    }
    if (rule.command() != null) {
      return request.command() != null
          && commandMatches(rule.command(), request.command(), "bash".equals(request.tool()));
    }
    if (rule.path() != null) {
      if (request.path() == null) {
        return false;
      }
      String relative = PathGlobs.relative(request.path(), project);
      return relative != null && PathGlobs.matches(rule.path(), relative);
    }
    // Neither: a rule about the tool itself, which is what `restart` is.
    return true;
  }

  /**
   * True when the rule's command describes the request's.
   *
   * <p>Two shapes, and the difference between them is what the metacharacter rule is for.
   *
   * <p>A plain command matches <em>verbatim</em>, however many semicolons and redirects it contains.
   * That is what "allow this command" and "allow it always" record — the words that were in front of
   * the user — and an equality check cannot be widened by a `>` or a `;` in the middle of it, so
   * there is nothing to defend against. Measured: the first version applied the metacharacter rule to
   * both shapes, so a rule remembered for `printf a > one.txt` could never match the command it was
   * remembered from, and "allow for this session" silently did nothing for exactly the commands
   * people most want to stop being asked about.
   *
   * <p>A rule ending in {@code " *"} matches the words before it plus further arguments, and
   * <em>that</em> is a pattern: it can swallow what follows, so both sides must be free of anything
   * that could turn one command into several. `git diff *` covers `git diff`, `git diff HEAD`,
   * `git diff --stat` and never `git diff; rm -rf /`. No other `*` is allowed anywhere in a pattern —
   * refused when the file is read — because a pattern whose meaning depends on where the star is, is
   * a pattern nobody can audit.
   */
  static boolean commandMatches(String pattern, String command) {
    return commandMatches(pattern, command, true);
  }

  /**
   * The same, for a request whose "command" is not a shell command.
   *
   * @param compoundable whether the string is run by a shell, which is the only reason the
   *     metacharacters matter. A URL is not: `?a=1&amp;b=2` is one address with a query string, and
   *     refusing to match it would make "allow this documentation site" impossible to write while
   *     leaving the exact-URL rule as the only workable form.
   */
  static boolean commandMatches(String pattern, String command, boolean compoundable) {
    if (pattern.endsWith(" *")) {
      if (!compoundable) {
        // A pattern written for a shell command is not one for a URL and the other way round: the
        // two spellings mean different things, so the wrong one does not match by accident.
        return false;
      }
      // The pattern's own star is the wildcard, so it is not a metacharacter to refuse here — the
      // words before it are what have to be plain.
      String head = pattern.substring(0, pattern.length() - 2);
      if (compoundingCharacter(head) != null || compoundingCharacter(command) != null) {
        return false;
      }
      String prefix = head + " ";
      return command.startsWith(prefix) && command.length() > prefix.length();
    }
    if (pattern.endsWith("*")) {
      // Zero characters are allowed after a path prefix, unlike after a word prefix: the separator
      // before the star is required at load time, so `https://example.com/*` means "this host, any
      // path — including the root", which is what a user writing it means. Measured: it did not
      // match `https://example.com/` at first, and the rule silently allowed nothing.
      String head = pattern.substring(0, pattern.length() - 1);
      return command.startsWith(head);
    }
    return pattern.equals(command);
  }

  /**
   * Refuses a pattern whose wildcard is anywhere but the end, or whose star would swallow a
   * neighbouring name.
   *
   * <p>Two spellings, because the two things a user wants to allow are different shapes. `git diff *`
   * means "those words and further arguments", and only matches a command with no shell
   * metacharacters in it. `https://docs.example.com/*` means "this path prefix on this host" — and
   * there the separator before the star is load-bearing: with the star written straight after a
   * name, `https://docs.example.com*` would also match `https://docs.example.com.evil/`, which is a
   * different host entirely. Requiring a `/`, `?`, `=` or `:` before the star is what keeps "allow
   * this site" from meaning "allow any host whose name begins with this one".
   */
  private static void trailingWildcard(String command, Path file) {
    int star = command.indexOf('*');
    if (star < 0) {
      return;
    }
    if (star != command.length() - 1) {
      throw new IllegalArgumentException(
          "the only wildcard an approval rule may use is a trailing '*' (or ' *' for further"
              + " arguments): "
              + command
              + " in "
              + file);
    }
    if (command.endsWith(" *")) {
      return;
    }
    String head = command.substring(0, command.length() - 1);
    if (head.isEmpty() || "/?=:".indexOf(head.charAt(head.length() - 1)) < 0) {
      throw new IllegalArgumentException(
          "a rule written '"
              + command
              + "' would also allow every name that begins with '"
              + head
              + "'. Put a separator before the star — '/', '?', '=' or ':' — so it means 'this prefix"
              + " and what follows it': "
              + file);
    }
  }

  /**
   * The first character of {@code command} that could make it more than one command, or null.
   *
   * <p>Public because the refusal has to be explainable: a user whose rule did not match deserves to
   * know that it was the semicolon, not the words.
   */
  public static String compoundingCharacter(String command) {
    if (command == null) {
      return null;
    }
    for (int i = 0; i < command.length(); i++) {
      char c = command.charAt(i);
      if (COMPOUNDING.indexOf(c) >= 0) {
        return String.valueOf(c);
      }
    }
    return null;
  }

  private Rule ruleFor(ApprovalRequest request) {
    String path =
        request.path() == null ? null : PathGlobs.relative(request.path(), project);
    return new Rule(request.tool(), request.command(), path);
  }

  private static String render(Rule rule) {
    if (rule.command() != null) {
      return rule.tool() + " " + rule.command();
    }
    return rule.path() != null ? rule.tool() + " " + rule.path() : rule.tool();
  }

  // ------------------------------------------------------------------ the file

  /**
   * The file's contents, as a map the caller may add to.
   *
   * <p>Mutable on purpose, and the reason is worth keeping: this returned {@code Map.of()} when there
   * was no file, and "allow from now on" — the first rule a user ever grants, against a file that
   * does not exist yet, which is the common case — threw {@link UnsupportedOperationException} into
   * the tool call. Nothing failed loudly: the rule was simply never written, and the only visible
   * symptom was being asked the same question again. The three tests that covered "remember" all ran
   * against a file with a project in it already, which is why none of them noticed.
   */
  private Map<String, ProjectRules> read() {
    if (file == null || !Files.isRegularFile(file)) {
      return new LinkedHashMap<>();
    }
    JsonNode root;
    try {
      root = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + file, e);
    }
    if (!root.isObject()) {
      throw new IllegalArgumentException("approvals file must be a JSON object: " + file);
    }
    JsonNode projects = root.get("projects");
    if (projects == null || projects.isNull()) {
      return new LinkedHashMap<>();
    }
    if (!projects.isObject()) {
      throw new IllegalArgumentException("'projects' in " + file + " must be an object of projects");
    }
    Map<String, ProjectRules> all = new LinkedHashMap<>();
    projects
        .fields()
        .forEachRemaining(
            entry ->
                all.put(
                    key(entry.getKey()),
                    new ProjectRules(
                        rules(entry.getValue(), "allow"), rules(entry.getValue(), "deny"))));
    return all;
  }

  private List<Rule> rules(JsonNode project, String kind) {
    JsonNode entries = project == null ? null : project.get(kind);
    if (entries == null || entries.isNull()) {
      return List.of();
    }
    if (!entries.isArray()) {
      throw new IllegalArgumentException(
          "'" + kind + "' must be an array of rules in " + file);
    }
    List<Rule> rules = new ArrayList<>();
    for (JsonNode entry : entries) {
      if (!entry.isObject()) {
        throw new IllegalArgumentException("each approval rule must be an object in " + file + ": " + entry);
      }
      String tool = text(entry, "tool");
      String command = text(entry, "command");
      String path = text(entry, "path");
      if (tool == null) {
        throw new IllegalArgumentException("an approval rule needs a 'tool' in " + file + ": " + entry);
      }
      if (command == null && path == null && !tool.equals("restart")) {
        throw new IllegalArgumentException(
            "an approval rule for '" + tool + "' needs a 'command' or a 'path' in " + file + ": " + entry);
      }
      if (command != null) {
        trailingWildcard(command, file);
      }
      rules.add(new Rule(tool, command, path));
    }
    return List.copyOf(rules);
  }

  /**
   * The project key the rules are filed under: absolute and normalised.
   *
   * <p>A hand-written file may say `/home/you/api/`, or say it with a symlink in it. Normalising on
   * the way in means those are the project they plainly are, rather than a second entry that never
   * matches — a rule that silently does not apply is worse than no rule, because the user believes
   * they are protected.
   */
  private static String key(String project) {
    try {
      return Path.of(project).toAbsolutePath().normalize().toString();
    } catch (RuntimeException notAPath) {
      return project.strip();
    }
  }

  private void write(Map<String, ProjectRules> all) {
    ObjectNode root = Json.object();
    ObjectNode projects = root.putObject("projects");
    all.forEach(
        (project, rules) -> {
          ObjectNode projectNode = projects.putObject(project);
          writeRules(projectNode, "allow", rules.allow());
          writeRules(projectNode, "deny", rules.deny());
        });
    try {
      Path parent = file.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(
          file,
          Json.writePretty(root) + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING);
      // Owner-only, the same rule the config file follows: it is not a secret, but it does say what
      // this machine will do without asking, which is not a thing to hand to every user on the box.
      try {
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
      } catch (UnsupportedOperationException | IOException ignored) {
        // Not a POSIX filesystem; the rules were written, which is what was asked for.
      }
    } catch (IOException e) {
      throw new UncheckedIOException("cannot write " + file, e);
    }
  }

  private static void writeRules(ObjectNode project, String kind, List<Rule> rules) {
    if (rules.isEmpty()) {
      project.remove(kind);
      return;
    }
    ArrayNode array = project.putArray(kind);
    for (Rule rule : rules) {
      ObjectNode entry = array.addObject();
      entry.put("tool", rule.tool());
      if (rule.command() != null) {
        entry.put("command", rule.command());
      }
      if (rule.path() != null) {
        entry.put("path", rule.path());
      }
    }
  }

  private static String text(JsonNode entry, String field) {
    JsonNode node = entry.get(field);
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isTextual()) {
      throw new IllegalArgumentException("approval rule '" + field + "' must be a string: " + node);
    }
    String value = node.asText().strip();
    return value.isEmpty() ? null : value;
  }
}
