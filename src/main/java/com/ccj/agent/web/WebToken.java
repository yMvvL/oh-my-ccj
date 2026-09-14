package com.ccj.agent.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * The web token this installation keeps between runs, in {@code <home>/web-token}.
 *
 * <p>It exists because the point of the whole feature is one word: {@code ccj}, and then the page is
 * reachable from the phone. A token that had to be passed every time would put a secret in shell
 * history, in {@code ps}, and in whatever shell alias was written to avoid the first two — so the
 * first run that needs one generates 32 random bytes, keeps them in a file only the user can read,
 * and prints the URL that carries them. Later runs reuse them, which is what makes the phone's
 * cookie survive a restart.
 *
 * <p>Generated rather than asked for: a token a human invents is the weak link in a 256-bit design,
 * and there is nothing for a human to choose here — the value is never typed, only clicked.
 */
public final class WebToken {

  /** The file inside the application home; the name says what it is so a reader of a directory
   * listing can tell. */
  public static final String FILE_NAME = "web-token";

  private static final int BYTES = 32;

  private static final SecureRandom RANDOM = new SecureRandom();

  private WebToken() {}

  /**
   * The token for this installation, generating and storing one when there is not one yet.
   *
   * @throws IOException when the home directory cannot be written, which the caller reports rather
   *     than serving a network bind with a token nobody can guess — and nobody can read either
   */
  public static String from(Path home) throws IOException {
    Path file = home.resolve(FILE_NAME);
    if (Files.isRegularFile(file)) {
      String existing = Files.readString(file, StandardCharsets.UTF_8).strip();
      if (!existing.isEmpty()) {
        return existing;
      }
      // An empty file is a mistake, not a policy: rewrite it rather than serving with no token.
    }
    String created = generate();
    Files.createDirectories(home);
    Files.writeString(
        file,
        created + System.lineSeparator(),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE);
    restrictToOwner(file);
    return created;
  }

  /** 32 random bytes as hex: 256 bits, and nothing in it to remember. */
  static String generate() {
    byte[] bytes = new byte[BYTES];
    RANDOM.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  /** Best effort: a filesystem without POSIX permissions keeps its default rather than failing. */
  private static void restrictToOwner(Path file) {
    try {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    } catch (UnsupportedOperationException | IOException ignored) {
      // The token is stored either way; this only narrows who can read it.
    }
  }
}
