package com.ccj.agent.web;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;

/**
 * 打开桌面实际拥有的那个文件夹选择器。
 *
 * <p>优先顺序：{@code zenity}，然后是 {@code kdialog}——两者都是原生对话框，不需要初始化工具包——最后
 * 是 Swing 的 {@link JFileChooser}，它有 JDK 就一定在，只是看起来没那么原生。没有显示器的机器得到的是
 * 明确的拒绝而不是卡住，因为手动输入路径是完全够用的退路。
 */
public final class NativeFolderChooser implements FolderChooser {

  private static final String NO_DESKTOP =
      "没有可用的桌面会话，ccj 无法打开文件夹选择器——请改为直接输入路径";

  private static final ScheduledExecutorService WATCHDOG =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "ccj-chooser-watchdog");
            thread.setDaemon(true);
            return thread;
          });

  private final Duration timeout;
  private final List<String> commandOverride;
  private final AtomicBoolean open = new AtomicBoolean();

  public NativeFolderChooser() {
    this(Duration.ofSeconds(120));
  }

  /** @param timeout 选择器可以停留多久，超时即被关闭 */
  public NativeFolderChooser(Duration timeout) {
    this(timeout, null);
  }

  /**
   * 运行 {@code command}，而不是去找桌面选择器。它存在，是为了让子进程那套管线——输出解析、退出码、看门狗
   * ——能在没有人点击对话框的情况下被测到，否则那是唯一能走到这条路径的办法。
   */
  NativeFolderChooser(Duration timeout, List<String> command) {
    this.timeout = timeout;
    this.commandOverride = command;
  }

  @Override
  public Optional<Path> choose(String title) throws IOException {
    if (!open.compareAndSet(false, true)) {
      throw new IOException("已经有一个文件夹选择器处于打开状态");
    }
    try {
      // 命令覆盖在桌面检查之前判断，而不是之后：它存在的意义就是在没有人点击对话框的情况下走通本类的子
      // 进程管线，而无头 JVM 正是需要它的地方——否则运行它的测试在任何没有显示器的地方都会失败，而每个 CI
      // runner 和每个 ssh 会话都是这种情况。
      if (commandOverride != null) {
        return run(commandOverride);
      }
      if (java.awt.GraphicsEnvironment.isHeadless()) {
        throw new IOException(NO_DESKTOP);
      }
      String label = title == null || title.isBlank() ? "选择文件夹" : title;
      if (onPath("zenity")) {
        return run(
            List.of("zenity", "--file-selection", "--directory", "--title=" + label));
      }
      if (onPath("kdialog")) {
        return run(
            List.of(
                "kdialog",
                "--getexistingdirectory",
                System.getProperty("user.home", "."),
                "--title",
                label));
      }
      return swing(label);
    } finally {
      open.set(false);
    }
  }

  /**
   * 运行一个选择器命令并读取它的回答。
   *
   * <p>输出抽在一个自己的线程上，而**等多久由我们决定，管道决定不了**：一个被我们杀掉的选择器，它的后代
   * 可能仍然握着管道那一端，于是 EOF 永远不来，一次「没人回答的对话框」就会拖满子进程自己的寿命。这在
   * ubuntu 的 CI 上实测到过——1 秒的截止时间等了 30 秒，正好是那个替身对话框的寿命。所以读到截止时间就
   * 收手，杀掉整棵进程树，然后当作没有答案——这也正是「没人应答的对话框必须被关掉」要说的事。
   */
  private Optional<Path> run(List<String> command) throws IOException {
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    long deadline = System.nanoTime() + timeout.toNanos();
    ScheduledFuture<?> kill =
        WATCHDOG.schedule(() -> killTree(process), timeout.toMillis(), TimeUnit.MILLISECONDS);
    StringBuilder collected = new StringBuilder();
    try {
      Thread reader =
          Thread.ofVirtual()
              .name("ccj-chooser-reader")
              .start(
                  () -> {
                    try (BufferedReader source =
                        new BufferedReader(
                            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                      String line;
                      while ((line = source.readLine()) != null) {
                        synchronized (collected) {
                          collected.append(line).append('\n');
                        }
                      }
                    } catch (IOException e) {
                      // 进程被杀掉时读端会断开，而那正是这条路上会发生的事：答案就是没有答案。
                    }
                  });
      reader.join(Math.max(0, (deadline - System.nanoTime()) / 1_000_000));
      boolean answered = !reader.isAlive();
      if (!answered) {
        killTree(process);
        reader.join(1_000);
      }
      String output;
      synchronized (collected) {
        output = collected.toString().strip();
      }
      if (!answered) {
        return Optional.empty();
      }
      // EOF 只说明选择器停止写入了：它可能已被看门狗销毁，而被销毁的进程在其退出码可读之前仍需被回收。
      if (!process.waitFor(2, TimeUnit.SECONDS)) {
        killTree(process);
        return Optional.empty();
      }
      if (process.exitValue() != 0 || output.isEmpty()) {
        return Optional.empty(); // 用户取消，或者看门狗把它关掉了
      }
      Path chosen = Path.of(output);
      return Files.isDirectory(chosen) ? Optional.of(chosen) : Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      killTree(process);
      return Optional.empty();
    } finally {
      kill.cancel(false);
      if (process.isAlive()) {
        killTree(process);
      }
    }
  }

  /**
   * 杀掉这棵树，而不是只杀根。
   *
   * <p>一个选择器脚本常常再起一个进程去画那个窗口，而那个孩子同样握着管道的写端：只杀根，读端就等不到
   * EOF。
   */
  private static void killTree(Process process) {
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
  }

  private Optional<Path> swing(String title) throws IOException {
    AtomicReference<Path> chosen = new AtomicReference<>();
    try {
      SwingUtilities.invokeAndWait(
          () -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle(title);
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setAcceptAllFileFilterUsed(false);
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION
                && chooser.getSelectedFile() != null) {
              chosen.set(chooser.getSelectedFile().toPath());
            }
          });
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause() == null ? e : e.getCause();
      throw new IOException("文件夹选择器出错：" + cause.getMessage(), cause);
    }
    return Optional.ofNullable(chosen.get());
  }

  private static boolean onPath(String binary) {
    String path = System.getenv("PATH");
    if (path == null || path.isBlank()) {
      return false;
    }
    for (String directory : path.split(File.pathSeparator)) {
      if (!directory.isBlank() && Files.isExecutable(Path.of(directory, binary))) {
        return true;
      }
    }
    return false;
  }
}
