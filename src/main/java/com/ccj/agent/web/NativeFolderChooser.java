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
 * Opens whatever folder chooser the desktop actually has.
 *
 * <p>Order of preference: {@code zenity}, then {@code kdialog} — both are native dialogs and need no
 * toolkit initialisation — and finally Swing's {@link JFileChooser}, which is always available with
 * a JDK but looks less native. A machine with no display gets a clear refusal instead of a hang,
 * because typing the path is a perfectly good fallback.
 */
public final class NativeFolderChooser implements FolderChooser {

  private static final String NO_DESKTOP =
      "no desktop session available, so ccj cannot open a folder chooser — type the path instead";

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

  /** @param timeout how long a chooser may stay open before it is dismissed */
  public NativeFolderChooser(Duration timeout) {
    this(timeout, null);
  }

  /**
   * Runs {@code command} instead of looking for a desktop chooser. Exists so the subprocess
   * plumbing — output parsing, exit codes, the watchdog — can be tested without a human clicking a
   * dialog, which is otherwise the only way to exercise it.
   */
  NativeFolderChooser(Duration timeout, List<String> command) {
    this.timeout = timeout;
    this.commandOverride = command;
  }

  @Override
  public Optional<Path> choose(String title) throws IOException {
    if (!open.compareAndSet(false, true)) {
      throw new IOException("a folder chooser is already open");
    }
    try {
      // The command override is checked before the desktop test, not after: it exists to exercise
      // this class's subprocess plumbing without a human clicking a dialog, and a headless JVM is
      // exactly where that is needed — the test that runs it otherwise fails wherever there is no
      // display, which is every CI runner and every ssh session.
      if (commandOverride != null) {
        return run(commandOverride);
      }
      if (java.awt.GraphicsEnvironment.isHeadless()) {
        throw new IOException(NO_DESKTOP);
      }
      String label = title == null || title.isBlank() ? "Choose a folder" : title;
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
   * Runs a chooser command and reads its answer. The output stream is drained on this thread, which
   * doubles as the wait: EOF arrives when the process exits, so a chatty dialog cannot fill its pipe
   * and block, and a watchdog kills one that is simply never answered.
   */
  private Optional<Path> run(List<String> command) throws IOException {
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    ScheduledFuture<?> kill =
        WATCHDOG.schedule(process::destroyForcibly, timeout.toMillis(), TimeUnit.MILLISECONDS);
    try {
      String output;
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        output = reader.lines().collect(Collectors.joining("\n")).strip();
      }
      // EOF only means the chooser stopped writing: the watchdog may have destroyed it, and a
      // destroyed process still needs reaping before its exit code can be read.
      if (!process.waitFor(2, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return Optional.empty();
      }
      if (process.exitValue() != 0 || output.isEmpty()) {
        return Optional.empty(); // cancelled, or the watchdog dismissed it
      }
      Path chosen = Path.of(output);
      return Files.isDirectory(chosen) ? Optional.of(chosen) : Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } finally {
      kill.cancel(false);
      if (process.isAlive()) {
        process.destroyForcibly();
      }
    }
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
      throw new IOException("the folder chooser failed: " + cause.getMessage(), cause);
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
