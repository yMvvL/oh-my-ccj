package com.ccj.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 选择器要外调一个桌面对话框，所以子进程那一套是用 {@code /bin/sh} 替身演练的：真对话框需要人
 * 点一下，但它周围的一切——解析答案、拒绝一个非目录、以及关掉一个没人应答的对话框——都不需要。
 */
class NativeFolderChooserTest {

  @TempDir Path tmp;

  private NativeFolderChooser chooser(String shell) {
    return new NativeFolderChooser(Duration.ofSeconds(5), List.of("/bin/sh", "-c", shell));
  }

  @Test
  void returnsTheDirectoryTheDialogPrints() throws IOException {
    Optional<Path> chosen = chooser("echo " + tmp).choose("test");

    assertEquals(Optional.of(tmp), chosen);
  }

  @Test
  void aCancelledDialogYieldsNothing() throws IOException {
    assertEquals(Optional.empty(), chooser("exit 1").choose("test"));
    assertEquals(Optional.empty(), chooser("exit 0").choose("test"), "没有输出就是没有答案");
  }

  @Test
  void anAnswerThatIsNotADirectoryIsRejected() throws IOException {
    Path file = Files.writeString(tmp.resolve("a-file"), "x");

    assertEquals(Optional.empty(), chooser("echo " + file).choose("test"));
  }

  @Test
  void aDialogNobodyAnswersIsDismissedByTheTimeout() throws IOException {
    NativeFolderChooser impatient = new NativeFolderChooser(Duration.ofSeconds(1), List.of("/bin/sh", "-c", "sleep 30"));

    long started = System.nanoTime();
    Optional<Path> chosen = impatient.choose("test");
    long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

    assertEquals(Optional.empty(), chosen);
    assertTrue(elapsedMillis < 10_000, "看门狗必须终结它，耗时 " + elapsedMillis + "ms");
  }

  @Test
  void onlyOneDialogAtATime() throws Exception {
    NativeFolderChooser single = new NativeFolderChooser(Duration.ofSeconds(5), List.of("/bin/sh", "-c", "sleep 2"));
    AtomicReference<Exception> failure = new AtomicReference<>();

    Thread first =
        new Thread(
            () -> {
              try {
                single.choose("first");
              } catch (IOException e) {
                failure.set(e);
              }
            });
    first.start();
    Thread.sleep(300);
    try {
      single.choose("second");
    } catch (IOException e) {
      failure.set(e);
    }
    first.join();

    assertTrue(
        failure.get() != null
            && failure.get().getMessage().contains("已经有一个文件夹选择器处于打开状态"),
        "第二个对话框必须被拒绝：" + failure.get());
  }

}
