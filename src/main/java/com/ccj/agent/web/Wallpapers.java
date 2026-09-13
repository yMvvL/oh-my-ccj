package com.ccj.agent.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The pictures the page may use as a background: one directory, read only.
 *
 * <p>A wallpaper is not a tool and has no approval gate — the page asks the server it is already
 * talking to for a file, and the server hands back a regular image from this one directory or
 * nothing at all. Everything else about a name is refused: one carrying a separator, one that
 * resolves out of the directory through a link, an absolute path, a file that is not an image. It
 * is the same rule the session store applies to session ids, for the same reason — the name arrives
 * from the browser, so it is input and not a fact.
 *
 * <p>SVG is deliberately left out: it is a document that can carry script, and a background image
 * is not worth that hole. Raster formats only, and the bytes decide which one — a file named
 * {@code .jpg} that really holds a PNG is served as the PNG it is.
 */
public final class Wallpapers {

  /** The environment variable that points somewhere else. */
  public static final String ENV = "CCJ_WALLPAPERS";

  /** Where they live when nothing says otherwise, relative to {@code $HOME}. */
  public static final String DEFAULT_SUBDIR = "Pictures/ccj-backgrounds";

  /** How many leading bytes are enough to name a raster format. */
  private static final int SNIFF_BYTES = 16;

  private static final Comparator<String> NATURAL = Wallpapers::natural;

  private final Path dir;

  public Wallpapers(Path dir) {
    this.dir = dir == null ? null : dir.toAbsolutePath().normalize();
  }

  /** A flag, then the environment, then the usual place: {@code ~/Pictures/ccj-backgrounds}. */
  public static Wallpapers from(Map<String, String> env, String flag) {
    if (flag != null && !flag.isBlank()) {
      return new Wallpapers(Path.of(flag.strip()));
    }
    String configured = env == null ? null : env.get(ENV);
    if (configured != null && !configured.isBlank()) {
      return new Wallpapers(Path.of(configured.strip()));
    }
    String home = env == null ? null : env.get("HOME");
    Path base =
        home == null || home.isBlank()
            ? Path.of(System.getProperty("user.home", "."))
            : Path.of(home);
    return new Wallpapers(base.resolve(DEFAULT_SUBDIR));
  }

  public Path directory() {
    return dir;
  }

  /** True when there is a directory to read: a missing one simply means nobody has any pictures. */
  public boolean available() {
    return dir != null && Files.isDirectory(dir);
  }

  /** The images in the directory, numbered ones in the order a person reads them: 1, 2, … 10. */
  public List<String> names() {
    if (!available()) {
      return List.of();
    }
    try (Stream<Path> entries = Files.list(dir)) {
      List<String> names = new ArrayList<>();
      for (Path entry : entries.toList()) {
        String name = entry.getFileName().toString();
        // The same test the page's request will get: listed here means servable there.
        if (resolve(name).isPresent()) {
          names.add(name);
        }
      }
      names.sort(NATURAL);
      return List.copyOf(names);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read the wallpaper directory " + dir, e);
    }
  }

  /** The file behind a name the page asked for, or empty when it is not one of ours. */
  public Optional<Path> resolve(String name) {
    if (!available() || name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
      return Optional.empty();
    }
    Path file = dir.resolve(name).normalize();
    if (!dir.equals(file.getParent())
        || !Files.isRegularFile(file)
        || contentType(file).isEmpty()) {
      return Optional.empty();
    }
    try {
      // A link that sits in this directory but points out of it is not this directory's picture.
      if (!file.toRealPath().startsWith(dir.toRealPath())) {
        return Optional.empty();
      }
    } catch (IOException e) {
      return Optional.empty();
    }
    return Optional.of(file);
  }

  /** The media type of an image, read from its own leading bytes. */
  public Optional<String> contentType(Path file) {
    byte[] head;
    try (InputStream in = Files.newInputStream(file)) {
      head = in.readNBytes(SNIFF_BYTES);
    } catch (IOException e) {
      return Optional.empty();
    }
    return Optional.ofNullable(sniff(head));
  }

  private static String sniff(byte[] b) {
    if (at(b, 0, 0x89, 'P', 'N', 'G')) {
      return "image/png";
    }
    if (at(b, 0, 0xFF, 0xD8, 0xFF)) {
      return "image/jpeg";
    }
    if (at(b, 0, 'G', 'I', 'F', '8')) {
      return "image/gif";
    }
    // RIFF is a container shared with other formats; the form identifier is what says WebP.
    if (at(b, 0, 'R', 'I', 'F', 'F') && at(b, 8, "WEBP")) {
      return "image/webp";
    }
    if (at(b, 0, 'B', 'M')) {
      return "image/bmp";
    }
    // ISO base media files all start with an ftyp box; the brand says whether it is an AVIF.
    if (at(b, 4, "ftyp") && (at(b, 8, "avif") || at(b, 8, "avis"))) {
      return "image/avif";
    }
    return null;
  }

  private static boolean at(byte[] bytes, int offset, int... values) {
    if (bytes.length < offset + values.length) {
      return false;
    }
    for (int i = 0; i < values.length; i++) {
      if (bytes[offset + i] != (byte) values[i]) {
        return false;
      }
    }
    return true;
  }

  private static boolean at(byte[] bytes, int offset, String text) {
    if (bytes.length < offset + text.length()) {
      return false;
    }
    for (int i = 0; i < text.length(); i++) {
      if (bytes[offset + i] != (byte) text.charAt(i)) {
        return false;
      }
    }
    return true;
  }

  /** {@code 2.png} before {@code 10.jpg}: a numbered set is read by its numbers. */
  private static int natural(String a, String b) {
    int i = 0;
    int j = 0;
    while (i < a.length() && j < b.length()) {
      char ca = a.charAt(i);
      char cb = b.charAt(j);
      if (Character.isDigit(ca) && Character.isDigit(cb)) {
        int startA = i;
        int startB = j;
        while (i < a.length() && Character.isDigit(a.charAt(i))) {
          i++;
        }
        while (j < b.length() && Character.isDigit(b.charAt(j))) {
          j++;
        }
        int compared = compareDigits(a.substring(startA, i), b.substring(startB, j));
        if (compared != 0) {
          return compared;
        }
        continue;
      }
      int compared = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
      if (compared != 0) {
        return compared;
      }
      i++;
      j++;
    }
    return Integer.compare(a.length() - i, b.length() - j);
  }

  /** Numeric order for two digit runs, without parsing: leading zeros are not a magnitude. */
  private static int compareDigits(String a, String b) {
    String x = stripLeadingZeros(a);
    String y = stripLeadingZeros(b);
    return x.length() != y.length() ? Integer.compare(x.length(), y.length()) : x.compareTo(y);
  }

  private static String stripLeadingZeros(String digits) {
    int i = 0;
    while (i < digits.length() - 1 && digits.charAt(i) == '0') {
      i++;
    }
    return digits.substring(i);
  }
}
