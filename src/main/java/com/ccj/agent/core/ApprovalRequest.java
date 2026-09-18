package com.ccj.agent.core;

import java.nio.file.Path;

/**
 * What a tool is asking permission to do, in the terms a rule can be written about.
 *
 * <p>Before this existed the request was two strings — a title and a paragraph of detail — which is
 * exactly the shape a human needs and exactly the shape a rule cannot use: "may I run this command"
 * and "may I write this path" are different questions, and a rule that matched on the prose of a
 * diff would be a rule that matched by accident. The prose is still here, because the prompt still
 * has to be readable.
 *
 * @param tool the tool's name, which is what a rule names first
 * @param command the shell command, for tools that run one, or null
 * @param path the file being written, for tools that write one, or null
 * @param title what the prompt is about ("bash", "edit")
 * @param detail the full human-readable request, shown to whoever answers
 */
public record ApprovalRequest(String tool, String command, Path path, String title, String detail) {

  public ApprovalRequest {
    if (tool == null || tool.isBlank()) {
      throw new IllegalArgumentException("an approval request has to name the tool");
    }
    title = title == null || title.isBlank() ? tool : title;
    detail = detail == null ? "" : detail;
  }

  /** A request to run a shell command. */
  public static ApprovalRequest command(String command, String detail) {
    return new ApprovalRequest("bash", command, null, "bash", detail);
  }

  /** A request to write one file, wholesale or in part. */
  public static ApprovalRequest file(String tool, Path path, String detail) {
    return new ApprovalRequest(tool, null, path, tool, detail);
  }

  /** A request that is neither: `restart` ends the run, and it has nothing to match on but its name. */
  public static ApprovalRequest tool(String tool, String detail) {
    return new ApprovalRequest(tool, null, null, tool, detail);
  }

  /** One line for the transcript, saying what was allowed and by which rule. */
  public String summary() {
    if (command != null) {
      return tool + ": " + command;
    }
    return path != null ? tool + ": " + path : tool;
  }
}
