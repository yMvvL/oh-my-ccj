package com.ccj.agent.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AttachmentStoreTest {

  @TempDir Path dir;

  @Test
  void pngRoundTripsIntoTheDirectoryBesideTheSession() throws Exception {
    FileSession session = SessionStore.create(dir);
    AttachmentStore store = AttachmentStore.forSession(session.file());
    byte[] png = image("png", 1, 1);

    AttachmentStore.Saved saved = store.save("IMG_0001.png", png);

    assertEquals("image/png", saved.mediaType());
    assertArrayEquals(png, Files.readAllBytes(saved.path()));
    assertEquals(store.directory(), saved.path().getParent());
    assertEquals(session.file().getParent(), store.directory().getParent());
    assertEquals(
        session.id() + AttachmentStore.DIRECTORY_SUFFIX,
        store.directory().getFileName().toString());
  }

  @Test
  void jpegRoundTripsAndIsSniffedFromItsMagicNumber() throws Exception {
    AttachmentStore store = AttachmentStore.forSession(SessionStore.create(dir).file());
    byte[] jpeg = image("jpeg", 3, 2);

    AttachmentStore.Saved saved = store.save("holiday", jpeg);

    assertEquals("image/jpeg", saved.mediaType());
    assertEquals("holiday.jpg", saved.path().getFileName().toString());
    assertArrayEquals(jpeg, Files.readAllBytes(saved.path()));
  }

  @Test
  void textNamedPngIsRefusedAndWritesNothing() throws Exception {
    AttachmentStore store = AttachmentStore.forSession(SessionStore.create(dir).file());
    byte[] text = "this is text, not a picture".getBytes(StandardCharsets.UTF_8);

    IllegalArgumentException refusal =
        assertThrows(IllegalArgumentException.class, () -> store.save("receipt.png", text));

    assertTrue(refusal.getMessage().contains("74 68 69 73"), refusal.getMessage());
    assertTrue(refusal.getMessage().contains("PNG"), refusal.getMessage());
    assertFalse(Files.exists(store.directory()), "a refused upload must not create the directory");
    assertEquals(List.of(), entries(dir));
  }

  @Test
  void riffContainerThatIsNotWebpIsRefused() {
    AttachmentStore store = AttachmentStore.forSession(SessionStore.create(dir).file());
    // A RIFF container, but WAVE rather than WEBP: the four bytes at offset 8 are what decide.
    byte[] wave = "RIFF\0\0\0\0WAVEfmt ".getBytes(StandardCharsets.ISO_8859_1);

    assertThrows(IllegalArgumentException.class, () -> store.save("sound.webp", wave));
  }

  @Test
  void twoUploadsWithTheSameNameBothSurvive() throws Exception {
    AttachmentStore store = AttachmentStore.forSession(SessionStore.create(dir).file());
    byte[] first = image("png", 1, 1);
    byte[] second = image("png", 4, 4);

    AttachmentStore.Saved firstSaved = store.save("IMG_0001.png", first);
    AttachmentStore.Saved secondSaved = store.save("IMG_0001.png", second);

    assertNotEquals(firstSaved.path(), secondSaved.path());
    assertArrayEquals(first, Files.readAllBytes(firstSaved.path()));
    assertArrayEquals(second, Files.readAllBytes(secondSaved.path()));
    assertEquals(2, entries(store.directory()).size());
  }

  @Test
  void aPathInTheNameStaysInsideTheAttachmentDirectory() throws Exception {
    AttachmentStore store = AttachmentStore.forSession(SessionStore.create(dir).file());
    byte[] png = image("png", 2, 2);
    Path pointingOutside = dir.resolve("outside.png").toAbsolutePath();

    AttachmentStore.Saved traversal = store.save("../../evil.png", png);
    AttachmentStore.Saved absolute = store.save(pointingOutside.toString(), png);

    for (AttachmentStore.Saved saved : List.of(traversal, absolute)) {
      assertEquals(store.directory(), saved.path().getParent());
      assertTrue(saved.path().startsWith(store.directory()), saved.path().toString());
    }
    assertFalse(Files.exists(dir.resolve("evil.png")));
    assertFalse(Files.exists(pointingOutside));
    assertEquals(1, entries(dir).size(), "only the attachment directory is under the session's dir");
  }

  @Test
  void generationFilesShareTheOneAttachmentDirectory() {
    String id = SessionStore.create(dir).id();
    AttachmentStore plain = AttachmentStore.forSession(FileSession.fileFor(dir, id));
    AttachmentStore compacted = AttachmentStore.forSession(FileSession.generationFile(dir, id, 1));

    assertEquals(plain.directory(), compacted.directory());
  }

  @Test
  void aPathThatIsNotASessionFileIsRefused() {
    assertThrows(
        IllegalArgumentException.class, () -> AttachmentStore.forSession(dir.resolve("notes.txt")));
  }

  @Test
  void readBoundedRefusesADeclaredOversizedBodyWithoutReadingIt() {
    InputStream mustNotBeRead =
        new InputStream() {
          @Override
          public int read() {
            throw new AssertionError("the body was read despite a declared length over the limit");
          }

          @Override
          public int read(byte[] buffer, int offset, int length) {
            throw new AssertionError("the body was read despite a declared length over the limit");
          }
        };

    IOException refusal =
        assertThrows(
            IOException.class,
            () -> AttachmentStore.readBounded(mustNotBeRead, 9L * 1024 * 1024));

    assertTrue(refusal.getMessage().contains("9437184"), refusal.getMessage());
  }

  @Test
  void readBoundedRefusesABodyThatOverrunsTheLimitWhileReading() {
    long over = AttachmentStore.MAX_BYTES + 1024;

    // No length at all, and a length that understates the body: the read is what has to stop it.
    assertThrows(IOException.class, () -> AttachmentStore.readBounded(repeating(over), -1));
    assertThrows(IOException.class, () -> AttachmentStore.readBounded(repeating(over), 16));
  }

  @Test
  void readBoundedTakesExactlyTheLimitAndRefusesTheNextByte() throws Exception {
    byte[] atLimit = AttachmentStore.readBounded(repeating(AttachmentStore.MAX_BYTES), -1);

    assertEquals(AttachmentStore.MAX_BYTES, atLimit.length);
    assertThrows(
        IOException.class,
        () -> AttachmentStore.readBounded(repeating(AttachmentStore.MAX_BYTES + 1), -1));
  }

  @Test
  void readBoundedReturnsAShortBodyAndUsesTheDeclaredLengthOnlyAsAHint() throws Exception {
    byte[] png = image("png", 1, 1);

    assertArrayEquals(png, AttachmentStore.readBounded(new ByteArrayInputStream(png), png.length));
    assertArrayEquals(png, AttachmentStore.readBounded(new ByteArrayInputStream(png), -1));
    assertArrayEquals(png, AttachmentStore.readBounded(new ByteArrayInputStream(png), 5000));
  }

  /** Real encoder output, so what the sniffer is handed is an image and not a typed-out header. */
  private static byte[] image(String format, int width, int height) throws IOException {
    BufferedImage pixels = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    pixels.setRGB(0, 0, 0x3366CC);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    if (!ImageIO.write(pixels, format, out)) {
      throw new IllegalStateException("this JDK has no " + format + " writer");
    }
    return out.toByteArray();
  }

  /** A stream of {@code count} bytes that reads in chunks, as a socket body does. */
  private static InputStream repeating(long count) {
    return new InputStream() {
      private long remaining = count;

      @Override
      public int read() {
        if (remaining <= 0) {
          return -1;
        }
        remaining--;
        return 'A';
      }

      @Override
      public int read(byte[] buffer, int offset, int length) {
        if (remaining <= 0) {
          return -1;
        }
        int taken = (int) Math.min(length, remaining);
        Arrays.fill(buffer, offset, offset + taken, (byte) 'A');
        remaining -= taken;
        return taken;
      }
    };
  }

  private static List<Path> entries(Path directory) throws IOException {
    if (!Files.isDirectory(directory)) {
      return List.of();
    }
    try (var listing = Files.list(directory)) {
      return listing.toList();
    }
  }
}
