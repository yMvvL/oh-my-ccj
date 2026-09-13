package com.ccj.agent.core;

import java.nio.file.Path;
import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * A directory plus the sessions that belong to it.
 *
 * <p>A session only makes sense next to the files it was talking about, so the two travel together:
 * switching a workspace changes where tools resolve relative paths and which history the front end
 * shows.
 *
 * <p>A name doubles as the directory its sessions live in, so it is checked against the characters a
 * single path segment can safely be: what a desktop chooser hands back is a folder, and a folder is
 * called whatever the user called it — {@code 数学}, {@code my project} — so refusing those would make
 * the chooser useless for the people who need it most. Separators, {@code .} and {@code ..}, control
 * characters and leading dashes stay out: those are the ones that change what a path means, or make
 * an argument look like a flag.
 */
public record Workspace(String name, Path path, Path sessionsDir) {

  /** One path segment's worth of name, up to 40 characters. */
  private static final Pattern NAME =
      Pattern.compile("[^/\\\\\\p{Cntrl}\\p{Zl}\\p{Zp}]{1,40}");

  public Workspace {
    name = name == null ? "" : name.strip();
    path = path == null ? null : path.toAbsolutePath().normalize();
    sessionsDir = sessionsDir == null ? null : sessionsDir.toAbsolutePath().normalize();
  }

  public static boolean validName(String name) {
    if (name == null) {
      return false;
    }
    // Composed form so a name typed on macOS (NFD) and the same name typed on Linux (NFC) are one
    // workspace rather than two entries that look identical.
    String clean = Normalizer.normalize(name.strip(), Normalizer.Form.NFC);
    if (clean.isEmpty() || clean.equals(".") || clean.equals("..")) {
      return false;
    }
    // A name is used as a directory name and in `--workspace`; a leading dash would read as a flag.
    return clean.charAt(0) != '-' && NAME.matcher(clean).matches();
  }
  public static String requireValidName(String name) {
    if (!validName(name)) {
      throw new IllegalArgumentException(RULE);
    }
    return Normalizer.normalize(name.strip(), Normalizer.Form.NFC);
  }

  /** The rules, in one sentence, so a refusal under the field reads as the same rule that was broken. */
  public static final String RULE =
      "a workspace name must be 1-40 characters, must not contain a path separator, must not start"
          + " with a dash, and must not be '.' or '..'";
}
