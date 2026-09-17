package com.ccj.agent.session;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HexFormat;

/**
 * The pictures a session owns, on disk beside it.
 *
 * <p>An attachment directory is {@code <sessionsDir>/<session-id>.attachments}, a sibling of
 * {@code <session-id>.jsonl} and derived from the session file's own path rather than from a second
 * idea of where sessions live. That placement is what lets a session be deleted together with its
 * pictures — leaving them behind would grow a directory nobody lists and nobody ever cleans — and it
 * keeps them out of the user's project, which is the other half of the point: a photo of a receipt is
 * not project content, and a file that shows up in {@code git status} because somebody photographed
 * something is a surprise nobody asked for.
 *
 * <p>What gets stored is decided by the first bytes of the upload, never by its name or by the
 * {@code Content-Type} it arrived with. A name is a claim and a header is a claim; the magic number
 * is the file itself, and an upload that is not one of the four accepted images is refused with what
 * was actually found.
 */
public final class AttachmentStore {

  /**
   * The largest upload this class will hold, and the largest body it will read.
   *
   * <p>Eight megabytes is a phone camera photo with room to spare: the picture this was measured
   * against is a 2.1 MB PNG, whose base64 request body came to 2.9 MB. It is deliberately not the
   * server's general body limit, which stays at a megabyte because a megabyte is right for JSON
   * commands — raising that one would widen every endpoint to accommodate a single upload.
   */
  public static final long MAX_BYTES = 8L * 1024 * 1024;

  /** Suffix of the directory that sits beside the session file. */
  public static final String DIRECTORY_SUFFIX = ".attachments";

  /** Chunk of the read; large enough to be cheap, small enough that a short upload is not padded. */
  private static final int CHUNK_BYTES = 8 * 1024;

  /** What a name sanitises down to when nothing usable is left of it. */
  private static final String DEFAULT_STEM = "image";

  /**
   * Longest base name stored, before the {@code -n} suffix and the media type's own extension.
   *
   * <p>A name is carried over from the upload so it stays recognisable in a transcript, but it is a
   * label and not a path: some file names arriving from a browser are hundreds of characters of
   * percent-decoded junk, and reserving this much room is what keeps the suffixed name short too.
   */
  private static final int MAX_STEM_LENGTH = 64;

  /** How many {@code -n} suffixes to try before giving up on a name. */
  private static final int MAX_NAME_ATTEMPTS = 1000;

  private static final String PNG = "image/png";
  private static final String JPEG = "image/jpeg";
  private static final String GIF = "image/gif";
  private static final String WEBP = "image/webp";

  private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
  private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
  private static final byte[] GIF87A = {'G', 'I', 'F', '8', '7', 'a'};
  private static final byte[] GIF89A = {'G', 'I', 'F', '8', '9', 'a'};
  private static final byte[] RIFF_MAGIC = {'R', 'I', 'F', 'F'};
  private static final byte[] WEBP_MAGIC = {'W', 'E', 'B', 'P'};

  private static final HexFormat HEX = HexFormat.ofDelimiter(" ").withUpperCase();

  private final Path directory;

  private AttachmentStore(Path directory) {
    this.directory = directory;
  }

  /**
   * The store for one session, addressed by the session file it belongs to.
   *
   * @throws IllegalArgumentException when the file is not a session file, so that a name which would
   *     put the directory somewhere else — a stem full of dots or a name that is not a session id at
   *     all — is refused here rather than turned into a directory path
   */
  public static AttachmentStore forSession(Path sessionFile) {
    Path file = sessionFile.toAbsolutePath().normalize();
    String id = SessionStore.idOf(file.getFileName().toString());
    if (id.isEmpty()) {
      throw new IllegalArgumentException(
          "not a session file, so it has no attachment directory: " + sessionFile);
    }
    return new AttachmentStore(file.resolveSibling(id + DIRECTORY_SUFFIX));
  }

  /**
   * Where the pictures go. Made by the first {@link #save}, so a session that never received one has
   * no empty directory to explain.
   */
  public Path directory() {
    return directory;
  }

  /**
   * One picture on disk.
   *
   * @param path where it was actually written; the name in it is not the name that was uploaded
   * @param mediaType what the bytes are, sniffed rather than believed
   */
  public record Saved(Path path, String mediaType) {}

  /**
   * Stores one upload under the session's directory and reports where it landed.
   *
   * <p>The name is kept because a transcript that says {@code IMG_0001.jpg} is readable and one that
   * says {@code a3f9c1} is not, but it is only ever a name: any path in it is discarded, {@code ..}
   * cannot survive, and the rest is reduced to letters, digits, dot, dash and underscore. The
   * extension is replaced with the one the sniffed media type calls for, so a stored file never
   * disagrees with its own contents about what it is.
   *
   * <p>A name already in use is not overwritten — a second {@code IMG_0001.jpg} is a second photo —
   * so the write is a {@code CREATE_NEW} and the attempt that collides is retried with a suffix. That
   * is a claim on the name made by the file system rather than a check followed by a write, which two
   * uploads arriving together would both pass.
   *
   * @throws IllegalArgumentException when there are no bytes to identify or the bytes are not PNG,
   *     JPEG, WebP or GIF
   * @throws UncheckedIOException when the bytes could not be written
   */
  public Saved save(String name, byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      throw new IllegalArgumentException(
          "cannot store '" + name + "': it has no bytes, so there is nothing to identify");
    }
    String mediaType = sniff(bytes);
    if (mediaType == null) {
      throw new IllegalArgumentException(
          "cannot store '"
              + name
              + "': the upload starts with "
              + HEX.formatHex(bytes, 0, Math.min(bytes.length, 8))
              + ", which is not the magic number of a PNG, JPEG, WebP or GIF image (the type is read"
              + " from the bytes, not from the name or a Content-Type)");
    }
    String stem = sanitizedStem(name);
    String extension = extensionFor(mediaType);
    try {
      Files.createDirectories(directory);
      for (int attempt = 1; attempt <= MAX_NAME_ATTEMPTS; attempt++) {
        Path target = directory.resolve(fileName(stem, extension, attempt));
        try {
          Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
          return new Saved(target, mediaType);
        } catch (FileAlreadyExistsException taken) {
          // Somebody else has this name; the next attempt takes a suffixed one.
        } catch (IOException failed) {
          // A half-written picture would be indistinguishable from a whole one later, so it goes.
          deleteQuietly(target);
          throw failed;
        }
      }
      throw new IOException(
          "all " + MAX_NAME_ATTEMPTS + " names derived from '" + stem + extension + "' are taken");
    } catch (IOException e) {
      throw new UncheckedIOException("cannot store '" + name + "' in " + directory, e);
    }
  }

  /**
   * Reads one request body, refusing anything past {@link #MAX_BYTES}.
   *
   * <p>The limit is only a limit if it applies to the read. A declared length may be absent, and when
   * present it is the client's claim about the client's own body — so the length is checked first,
   * because a body that announces 9 MB needs no further attention, and the read is then capped so
   * that a body which lies, or streams with no length at all, stops at the limit <em>while</em> it is
   * being read. Buffering the whole thing and measuring it afterwards would hold exactly the bytes the
   * limit exists to refuse, and an upload of any size would cost a heap the size of the upload.
   *
   * @param declaredLength {@code Content-Length} when the caller knows it, or a negative number when
   *     the body arrives chunked
   * @throws IOException when the upload is over the limit, whether it said so or not
   */
  public static byte[] readBounded(InputStream body, long declaredLength) throws IOException {
    if (declaredLength > MAX_BYTES) {
      throw new IOException(
          "the upload declares "
              + declaredLength
              + " bytes, over the "
              + limitDescription()
              + " attachment limit");
    }
    ByteArrayOutputStream out =
        new ByteArrayOutputStream(
            declaredLength > 0 ? (int) Math.min(declaredLength, MAX_BYTES) : CHUNK_BYTES);
    byte[] chunk = new byte[CHUNK_BYTES];
    long total = 0;
    while (true) {
      // One byte past the limit is enough to know the upload is over it, and no more is ever held.
      int room = (int) Math.min(chunk.length, MAX_BYTES + 1 - total);
      int read = body.read(chunk, 0, room);
      if (read < 0) {
        return out.toByteArray();
      }
      total += read;
      if (total > MAX_BYTES) {
        throw new IOException(
            "the upload is still arriving " + total + " bytes in, past the " + limitDescription()
                + " attachment limit");
      }
      out.write(chunk, 0, read);
    }
  }

  /** The media type of {@code bytes}, or null when it is not one this class stores. */
  public static String mediaTypeOf(byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      throw new IllegalArgumentException(
          "cannot identify an upload with no bytes, so there is nothing to store");
    }
    String mediaType = sniff(bytes);
    if (mediaType == null) {
      throw new IllegalArgumentException(
          "the upload starts with "
              + HEX.formatHex(bytes, 0, Math.min(bytes.length, 8))
              + ", which is not the magic number of a PNG, JPEG, WebP or GIF image (the type is read"
              + " from the bytes, not from the name or a Content-Type)");
    }
    return mediaType;
  }

  /** The magic-number read behind {@link #mediaTypeOf}: the type for those bytes, or null. */
  private static String sniff(byte[] bytes) {
    if (matches(bytes, 0, PNG_MAGIC)) {
      return PNG;
    }
    if (matches(bytes, 0, JPEG_MAGIC)) {
      return JPEG;
    }
    if (matches(bytes, 0, GIF87A) || matches(bytes, 0, GIF89A)) {
      return GIF;
    }
    // WebP is a RIFF container, and RIFF holds WAV and AVI too — the four bytes at 8 are what say
    // which one this is, so RIFF alone must not be enough.
    if (matches(bytes, 0, RIFF_MAGIC) && matches(bytes, 8, WEBP_MAGIC)) {
      return WEBP;
    }
    return null;
  }

  private static boolean matches(byte[] bytes, int offset, byte[] magic) {
    if (bytes.length < offset + magic.length) {
      return false;
    }
    for (int i = 0; i < magic.length; i++) {
      if (bytes[offset + i] != magic[i]) {
        return false;
      }
    }
    return true;
  }

  private static String extensionFor(String mediaType) {
    return switch (mediaType) {
      case PNG -> ".png";
      case JPEG -> ".jpg";
      case GIF -> ".gif";
      case WEBP -> ".webp";
      default -> throw new IllegalStateException("no extension for " + mediaType);
    };
  }

  /**
   * The uploaded name reduced to something safe to write: one path element, an extension that is not
   * believed, and a character set with no meaning to the file system.
   */
  private static String sanitizedStem(String name) {
    String stem = name == null ? "" : name;
    // Anything up to the last separator goes, which is what makes "../../evil.png" and "/etc/evil.png"
    // ordinary names rather than paths.
    int separator = Math.max(stem.lastIndexOf('/'), stem.lastIndexOf('\\'));
    if (separator >= 0) {
      stem = stem.substring(separator + 1);
    }
    int extension = stem.lastIndexOf('.');
    if (extension > 0) {
      stem = stem.substring(0, extension);
    }
    // A traversal needs a separator, and there is none left — but the stored name is read back by
    // people and by the model, so a sequence that still looks like a path is not kept.
    stem = stem.replaceAll("\\.{2,}", ".").replaceAll("[^A-Za-z0-9._-]+", "_");
    stem = stem.replaceAll("^\\.+|\\.+$", "");
    if (stem.isEmpty()) {
      return DEFAULT_STEM;
    }
    return stem.length() <= MAX_STEM_LENGTH ? stem : stem.substring(0, MAX_STEM_LENGTH);
  }

  /** The unsuffixed name for the first attempt, then {@code stem-1}, {@code stem-2}, and so on. */
  private static String fileName(String stem, String extension, int attempt) {
    return attempt == 1 ? stem + extension : stem + "-" + (attempt - 1) + extension;
  }

  /** A best-effort removal; a failure here must not replace the error that caused it. */
  private static void deleteQuietly(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException ignored) {
      // The write already failed and is about to be reported; a leftover file is the lesser problem.
    }
  }

  private static String limitDescription() {
    return (MAX_BYTES / (1024 * 1024)) + " MB";
  }

  /**
   * Removes one session's pictures along with the directory that holds them.
   *
   * <p>This is the other half of storing them beside the session: a deleted session that left its
   * photographs on disk would grow a directory nobody lists, nobody reads and nobody ever cleans,
   * which is exactly the pile the placement was chosen to avoid.
   *
   * @return true when there was something to remove
   */
  public static boolean deleteFor(Path sessionFile) {
    return deleteDirectory(forSession(sessionFile).directory());
  }

  /**
   * Removes a directory this class owns, contents and all.
   *
   * <p>Depth-first and best effort about the walk's errors: a directory that cannot be emptied is
   * reported as "not removed" rather than thrown, because it is the caller's cleanup path and a
   * failure there must not be the thing that stops a session from being deleted.
   *
   * @return true when a directory was there and is now gone
   */
  public static boolean deleteDirectory(Path directory) {
    if (directory == null || !Files.isDirectory(directory)) {
      return false;
    }
    boolean removed = true;
    try (var entries = Files.list(directory)) {
      for (Path entry : entries.toList()) {
        if (Files.isDirectory(entry)) {
          removed &= deleteDirectory(entry);
        } else {
          try {
            Files.deleteIfExists(entry);
          } catch (IOException stuck) {
            removed = false;
          }
        }
      }
    } catch (IOException unreadable) {
      return false;
    }
    try {
      Files.deleteIfExists(directory);
      return removed;
    } catch (IOException stuck) {
      return false;
    }
  }
}
