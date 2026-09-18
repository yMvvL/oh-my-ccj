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
 * 壁纸目录：它提供什么，以及一个来自浏览器的名字能够到哪儿。
 *
 * <p>有两条性质比其余的都重要，所以先测：一组编号的图片列出来的顺序，以及一个从页面来的名字
 * 出不了这个目录。
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
    // 一组叫 1..16 的图片不能被读成 1, 10, 11, … 2：轮换跟着列表走，所以列表的顺序就是图片
    // 出现的顺序。
    write("2.png", PNG);
    write("10.jpg", PNG);
    write("1.png", PNG);
    write("16.png", PNG);
    write("3.png", PNG);

    assertEquals(List.of("1.png", "2.png", "3.png", "10.jpg", "16.png"), new Wallpapers(dir).names());
  }

  @Test
  void theBytesDecideTheTypeRatherThanTheName() throws IOException {
    // 一个名叫 .jpg 却装着 PNG 的文件就是 PNG——浏览器被告知的是它实际是什么。
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
    // SVG 是一份能携带脚本的文档；在这里它不是一张背景图片。
    write("logo.svg", "<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes(StandardCharsets.UTF_8));

    assertEquals(List.of("1.png"), new Wallpapers(dir).names());
  }

  @Test
  void aNameFromThePageCannotLeaveTheDirectory() throws IOException {
    write("1.png", PNG);
    Files.writeString(dir.resolveSibling("secret.png"), "not for the page");

    Wallpapers wallpapers = new Wallpapers(dir);

    assertTrue(wallpapers.resolve("1.png").isPresent());
    assertTrue(wallpapers.resolve("../secret.png").isEmpty(), "分隔符直接被拒");
    assertTrue(wallpapers.resolve("../secret.png".replace("/", "\\")).isEmpty());
    assertTrue(wallpapers.resolve("/etc/passwd").isEmpty(), "绝对路径在这里不是一个名字");
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

    assertTrue(wallpapers.resolve("link.png").isEmpty(), "这个链接离开了目录");
    assertFalse(wallpapers.names().contains("link.png"), "而且它也不在列表里：" + wallpapers.names());
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
