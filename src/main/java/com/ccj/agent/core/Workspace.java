package com.ccj.agent.core;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * A working directory plus the sessions that belong to it.
 *
 * <p>A session only makes sense next to the files it was talking about, so the two travel together:
 * switching a workspace changes where tools resolve relative paths and which history the front end
 * shows.
 */
public record Workspace(String name, Path path, Path sessionsDir) {

  /** Names double as directory names, so the character set stays boring on purpose. */
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,39}");

  public Workspace {
    name = name == null ? "" : name.strip();
    path = path == null ? null : path.toAbsolutePath().normalize();
    sessionsDir = sessionsDir == null ? null : sessionsDir.toAbsolutePath().normalize();
  }

  public static boolean validName(String name) {
    return name != null && NAME.matcher(name.strip()).matches();
  }

  public static String requireValidName(String name) {
    if (!validName(name)) {
      throw new IllegalArgumentException(
          "a workspace name must be 1-40 characters of letters, digits, dot, dash or underscore");
    }
    return name.strip();
  }
}
