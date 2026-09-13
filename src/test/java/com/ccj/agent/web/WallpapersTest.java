package com.ccj.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The wallpaper directory: what it offers, and what a name from a browser can reach.
 *
 * <p>Two properties matter more than the rest and are tested first: the order a numbered set is
 * listed in, and that a name arriving from the page cannot leave the directory.
 */
class WallpapersTest {

  @TempDir Path dir;

  private static final byte[] PNG =
      new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0, 0, 0, 0, 0};

  private void write(String name, byte[] bytes) throws IOException {
    Files.write(dir.resolve(name), bytes);
  }

  private static byte[] jpeg() {
    return new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I',
        'F', 0, 0, 0, 0, 0, 0};
  }

  private static byte[] webp() {
    byte[] bytes = new byte[16];
    System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, bytes, 0, 4);
    System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, bytes, 8, 4);
    return bytes;
  }

  private static byte[] avif() {
    byte[] bytes = new byte[16];
    System.arraycopy("ftyp".getBytes(StandardCharsets.US_ASCII), 0, bytes, 4, 4);
    System.arraycopy("avif".getBytes(StandardCharsets.US_ASCII), 0, bytes, 8, 4);
    return bytes;
  }

  @Test
  void numberedPicturesAreListedInTheOrderAPersonReadsThem() throws IOException {
    // A set called 1..16 must not be read 1, 10, 11, … 2: the rotation follows the list, so the
    // order of the list is the order the pictures appear in.
    write("2.png", PNG);
    write("10.jpg", PNG);
    write("1.png", PNG);
    write("16.png", PNG);
    write("3.png", PNG);

    assertEquals(List.of("1.png", "2.png", "3.png", "10.jpg", "16.png"), new Wallpapers(dir).names());
  }

  @Test
  void theBytesDecideTheTypeRatherThanTheName() throws IOException {
    // A file named .jpg holding a PNG is a PNG — the browser is told what it actually is.
    write("10.jpg", PNG);

    Wallpapers wallpapers = new Wallpapers(dir);

    assertEquals("image/png", wallpapers.contentType(dir.resolve("10.jpg")).orElseThrow());
  }

  @Test
  void everyRasterFormatItShipsIsRecognised() throws IOException {
    write("a.png", PNG);
    write("b.jpg", jpeg());
    write("c.webp", webp());
    write("d.avif", avif());

    assertEquals(List.of("a.png", "b.jpg", "c.webp", "d.avif"), new Wallpapers(dir).names());
  }

  @Test
  void onlyImagesAreOffered() throws IOException {
    write("1.png", PNG);
    write("notes.txt", "hello".getBytes(StandardCharsets.UTF_8));
    // An SVG is a document that can carry script; it is not a background picture here.
    write("logo.svg", "<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes(StandardCharsets.UTF_8));

    assertEquals(List.of("1.png"), new Wallpapers(dir).names());
  }

  @Test
  void aNameFromThePageCannotLeaveTheDirectory() throws IOException {
    write("1.png", PNG);
    Files.writeString(dir.resolveSibling("secret.png"), "not for the page");

    Wallpapers wallpapers = new Wallpapers(dir);

    assertTrue(wallpapers.resolve("1.png").isPresent());
    assertTrue(wallpapers.resolve("../secret.png").isEmpty(), "a separator is refused outright");
    assertTrue(wallpapers.resolve("../secret.png".replace("/", "\\")).isEmpty());
    assertTrue(wallpapers.resolve("/etc/passwd").isEmpty(), "an absolute path is not a name here");
    assertTrue(wallpapers.resolve(".").isEmpty());
    assertTrue(wallpapers.resolve("").isEmpty());
    assertTrue(wallpapers.resolve(null).isEmpty());
    assertTrue(wallpapers.resolve("1.png/../../etc/passwd").isEmpty());
  }

  @Test
  void aLinkOutOfTheDirectoryIsNotOneOfItsPictures() throws IOException {
    Path outside = Files.createDirectories(dir.resolveSibling(dir.getFileName() + "-elsewhere"));
    Files.write(outside.resolve("secret.png"), PNG);
    Files.createSymbolicLink(dir.resolve("link.png"), outside.resolve("secret.png"));

    Wallpapers wallpapers = new Wallpapers(dir);

    assertTrue(wallpapers.resolve("link.png").isEmpty(), "the link leaves the directory");
    assertFalse(wallpapers.names().contains("link.png"), "and it is not listed either: " + wallpapers.names());
  }

  @Test
  void aDirectoryThatIsNotThereSimplyHasNothing() {
    Wallpapers wallpapers = new Wallpapers(dir.resolve("nope"));

    assertFalse(wallpapers.available());
    assertTrue(wallpapers.names().isEmpty());
    assertTrue(wallpapers.resolve("1.png").isEmpty());
  }

  @Test
  void theDirectoryIsTheFlagThenTheEnvironmentThenTheUsualPlace() {
    Path home = Path.of("/home/someone");

    assertEquals(
        Path.of("/pictures/here"),
        Wallpapers.from(
                Map.of("HOME", home.toString(), "CCJ_WALLPAPERS", "/from/env"), "/pictures/here")
            .directory());
    assertEquals(
        Path.of("/from/env"),
        Wallpapers.from(Map.of("HOME", home.toString(), "CCJ_WALLPAPERS", "/from/env"), null)
            .directory());
    assertEquals(
        home.resolve("Pictures/ccj-backgrounds"),
        Wallpapers.from(Map.of("HOME", home.toString()), null).directory());
  }
}
