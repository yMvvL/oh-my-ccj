package com.ccj.agent.core;

import java.util.List;

/**
 * What a sub-agent is for, which decides what it may touch.
 *
 * <p>The role chooses the tools rather than the caller choosing both, because the two are one
 * decision: a verifier that can fix what it finds is not verifying, and an explorer that can write is
 * a hidden agent editing the user's files. Stating the role states the boundary.
 */
public enum SubAgentRole {

  /**
   * Finds things out: reads, searches, follows a lead through a codebase.
   *
   * <p>Read-only, so nothing it does can change anything and nothing it does needs asking. This is
   * the default because it is the one whose value does not depend on trust — the context it saves is
   * saved whether or not anybody is watching it.
   */
  EXPLORE(
      "explore",
      "You investigate and report. You cannot change anything, so do not offer to: find the answer,"
          + " name the files and line numbers it lives at, and stop.",
      false),

  /**
   * Checks work that exists: a standard solution against a brute force, an implementation against
   * its spec, a claim against the code.
   *
   * <p>Takes the same tools as {@link #EXPLORE} on purpose — they differ in what the prompt asks for,
   * not in what they may touch. Splitting them by capability would invent a constraint the work does
   * not have. The point of the role is that it <em>reports</em>: a verifier that quietly repairs what
   * it finds has replaced the evidence with its own work, and no one learns that the thing was ever
   * wrong.
   */
  VERIFY(
      "verify",
      "You check work that already exists and report what you find. You cannot change anything, and"
          + " you must not propose edits as if you had. When something is wrong, give the exact"
          + " input, the expected result and the actual one.",
      false),

  /**
   * Produces artefacts: code, tests, data, documents.
   *
   * <p>The only role that writes. Its changes go through the same approval the main agent asks for,
   * because a hidden agent editing files with no prompt is the thing this feature must not become.
   */
  BUILD(
      "build",
      "You produce the artefact you were asked for, at final quality: complete, compiling, and"
          + " ready to be used as it stands. Write it into the working directory you were given, and"
          + " say in your report which files are finished work and which are intermediates you kept"
          + " only to work with.",
      true);

  private final String wireName;
  private final String instruction;
  private final boolean writes;

  SubAgentRole(String wireName, String instruction, boolean writes) {
    this.wireName = wireName;
    this.instruction = instruction;
    this.writes = writes;
  }

  /** The name the model passes to the {@code task} tool. */
  public String wireName() {
    return wireName;
  }

  /** What this role is told about itself. */
  public String instruction() {
    return instruction;
  }

  /** True for the one role that may write. */
  public boolean writes() {
    return writes;
  }

  /** The tool names this role may use, in the order they are offered. */
  public List<String> toolNames() {
    return writes
        ? List.of("read", "glob", "grep", "write", "edit")
        : List.of("read", "glob", "grep");
  }

  /** The role for a name, or empty when the model asked for one that does not exist. */
  public static java.util.Optional<SubAgentRole> of(String name) {
    if (name == null) {
      return java.util.Optional.empty();
    }
    String wanted = name.strip().toLowerCase(java.util.Locale.ROOT);
    for (SubAgentRole role : values()) {
      if (role.wireName.equals(wanted)) {
        return java.util.Optional.of(role);
      }
    }
    return java.util.Optional.empty();
  }

  /** The names to offer a model that asked for something else. */
  public static String names() {
    StringBuilder out = new StringBuilder();
    for (SubAgentRole role : values()) {
      if (!out.isEmpty()) {
        out.append(", ");
      }
      out.append(role.wireName);
    }
    return out.toString();
  }
}
