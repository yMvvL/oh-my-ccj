package com.ccj.agent.session;

import com.ccj.agent.core.Message;
import com.ccj.agent.core.Session;
import com.ccj.agent.core.UsageTotals;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 由一个只追加的 JSONL 文件支撑的会话。
 *
 * <p>每次 {@link #append} 写一行并立即 flush：一个在回合中途被杀掉的会话（Ctrl-C、崩溃、机器休眠）
 * 仍然拥有已经交给循环的每一条消息，正是这一点让 {@code --resume} 可信。以追加模式懒打开写入方，意味着
 * 文件会保留上一个进程写下的东西。
 */
public final class FileSession implements Session {

  public static final String EXTENSION = ".jsonl";

  private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

  /**
   * 一个被压缩的代的文件所带的后缀：{@code <id>.g1.jsonl} 是一段会话在第一次压缩之后的样子。
   *
   * <p>第 0 代就是普通的 {@code <id>.jsonl}——原件——它刻意不叫 {@code .g0}：在压缩存在之前写下的
   * 会话保持它们已有的名字，不知道「代」这回事的读取方仍然看到一个它打得开的会话文件。
   */
  private static final Pattern GENERATION = Pattern.compile("\\.g([1-9][0-9]*)\\.jsonl$");

  /** 行前缀，标记一条记账记录而不是一条会话消息。 */
  public static final String USAGE_TYPE = "usage";

  private final String id;
  /** 最新的一代：被读取、被追加、被一次压缩替换掉的就是它。 */
  private Path file;
  private final List<Message> history;
  private OutputStream writer;
  private UsageTotals totals = UsageTotals.empty();

  public FileSession(String id, Path file, List<Message> history) {
    this(id, file, history, UsageTotals.empty());
  }

  public FileSession(String id, Path file, List<Message> history, UsageTotals totals) {
    this.id = id;
    this.file = file;
    this.history = new ArrayList<>(history == null ? List.of() : history);
    this.totals = totals == null ? UsageTotals.empty() : totals;
  }

  /**
   * 为 {@code sessionsDir} 生成一个新的 id，把文件留给 {@link #append}。
   *
   * <p>急着创建文件会让每次前端启动或用户按下「新建会话」时，会话列表都被空会话铺满——id 不花什么
   * 代价，而一个文件是一句「有人说过话」的声称。
   */
  public static FileSession create(Path sessionsDir) {
    String id = newId();
    return new FileSession(id, fileFor(sessionsDir, id), List.of());
  }

  /**
   * 对于可以安全变成文件名的 id 为 true。删除也要过这一关：id 是通过 HTTP 由用户提供的，
   * {@code ../} 绝不能到达文件系统。
   */
  public static boolean isValidId(String id) {
    return id != null && SAFE_ID.matcher(id).matches();
  }

  /** 重新打开一个已存在的会话，恢复它的完整历史。 */
  public static FileSession open(Path sessionsDir, String id) {
    if (!isValidId(id)) {
      throw new IllegalArgumentException("无效的会话 id：" + id);
    }
    Path file = newestFile(sessionsDir, id);
    if (!Files.isRegularFile(file)) {
      throw new IllegalArgumentException("在 " + sessionsDir + " 中没有会话 '" + id + "'");
    }
    // 最新的一代才是这段会话：更早的那些仍在磁盘上、也仍可读，但它们只是这段会话曾经的样子。打开最新的
    // 那一代，正是这一点让一次压缩看起来是同一段会话在继续，而不是冒出了另一段。
    return new FileSession(id, file, readAll(file), readTotals(file));
  }

  /** 时间戳加一个随机后缀，好让同一秒里创建的会话彼此不同。 */
  public static String newId() {
    return ID_TIME.format(LocalDateTime.now())
        + "-"
        + String.format("%04x", RANDOM.nextInt(0x10000));
  }

  /** 第 0 代：在有什么压缩它之前，一个会话写入的文件。 */
  public static Path fileFor(Path sessionsDir, String id) {
    return sessionsDir.resolve(id + EXTENSION);
  }

  /** 保存第 {@code generation} 代的文件，该参数至少为 1。 */
  public static Path generationFile(Path sessionsDir, String id, int generation) {
    if (generation < 1) {
      throw new IllegalArgumentException("代文件从 1 开始，实际为 " + generation);
    }
    return sessionsDir.resolve(id + ".g" + generation + EXTENSION);
  }

  /**
   * 一段会话在磁盘上的每一代，升序，第 0 代在最前。
   *
   * <p>从目录读出来而不是留在内存里，因为代文件的意义就在于它们比进程活得久：明天重新打开的会话必须
   * 找到更早一次运行留下的那些代，而手工复制或删除的文件也必须被采信。
   */
  public static List<Integer> generations(Path sessionsDir, String id) {
    if (!isValidId(id)) {
      throw new IllegalArgumentException("无效的会话 id：" + id);
    }
    if (sessionsDir == null || !Files.isDirectory(sessionsDir)) {
      return List.of();
    }
    List<Integer> found = new ArrayList<>();
    if (Files.isRegularFile(fileFor(sessionsDir, id))) {
      found.add(0);
    }
    try (var entries = Files.list(sessionsDir)) {
      for (Path entry : entries.toList()) {
        String name = entry.getFileName().toString();
        if (!name.startsWith(id + ".g")) {
          continue;
        }
        var matcher = GENERATION.matcher(name);
        if (matcher.find()) {
          found.add(Integer.parseInt(matcher.group(1)));
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("无法读取 " + sessionsDir + " 中 " + id + " 的各代", e);
    }
    found.sort(Integer::compareTo);
    return List.copyOf(found);
  }

  /**
   * 一个会话当前按哪一代在读、在写：已存在的最新那一代。
   *
   * <p>磁盘上还什么都没有时为 0，这正是从未被写过的会话的样子——{@code create} 造一个 id，把文件留给
   * 第一次追加。
   */
  public static int newestGeneration(Path sessionsDir, String id) {
    List<Integer> all = generations(sessionsDir, id);
    return all.isEmpty() ? 0 : all.get(all.size() - 1);
  }

  /** 最新一代所在的路径，无论它是否已经被写过。 */
  public static Path newestFile(Path sessionsDir, String id) {
    int generation = newestGeneration(sessionsDir, id);
    return generation == 0 ? fileFor(sessionsDir, id) : generationFile(sessionsDir, id, generation);
  }

  /** 对于一个属于 {@code id} 某一代的文件名（含第 0 代）为 true。 */
  public static boolean isSessionFile(String fileName, String id) {
    if (fileName.equals(id + EXTENSION)) {
      return true;
    }
    return fileName.startsWith(id + ".g") && GENERATION.matcher(fileName).find();
  }

  /**
   * 按顺序读出 {@code file} 里的每一条消息。文件不存在读作空历史；格式错误的行会带着行号失败，而不是悄悄
   * 把这段会话截断。
   */
  public static List<Message> readAll(Path file) {
    List<String> lines;
    try {
      lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (NoSuchFileException e) {
      return List.of();
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read session file " + file, e);
    }
    List<Message> messages = new ArrayList<>(lines.size());
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      if (line.isBlank() || isUsageLine(line)) {
        continue;
      }
      try {
        messages.add(MessageCodec.fromJson(line));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(file + ":" + (i + 1) + ": " + e.getMessage(), e);
      }
    }
    return messages;
  }

  /** 对一条记账行为 true，它不是会话的一部分。 */
  private static boolean isUsageLine(String line) {
    return line.contains("\"type\":\"" + USAGE_TYPE + "\"");
  }

  /** 文件里最后一条记账记录，该会话从未记录过时什么也没有。 */
  public static UsageTotals readTotals(Path file) {
    List<String> lines;
    try {
      lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (NoSuchFileException e) {
      return UsageTotals.empty();
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read session file " + file, e);
    }
    UsageTotals found = UsageTotals.empty();
    for (String line : lines) {
      if (isUsageLine(line)) {
        found = MessageCodec.totalsFromJson(line);
      }
    }
    return found;
  }

  @Override
  public UsageTotals totals() {
    return totals;
  }

  @Override
  public void totals(UsageTotals updated) {
    this.totals = updated == null ? UsageTotals.empty() : updated;
    writeLine(MessageCodec.totalsToJson(this.totals));
  }

  @Override
  public String id() {
    return id;
  }

  public Path file() {
    return file;
  }

  @Override
  public List<Message> messages() {
    return List.copyOf(history);
  }

  @Override
  public void append(Message message) {
    history.add(message);
    writeLine(MessageCodec.toJson(message));
  }

  /** 把磁盘上和内存里的会话清空；会话 id 还在。 */
  public void clear() {
    close();
    history.clear();
    totals = UsageTotals.empty();
    if (!Files.exists(file)) {
      return; // 磁盘上还什么都没有；文件会在下次追加时出现
    }
    try {
      Files.writeString(
          file,
          "",
          StandardCharsets.UTF_8,
          StandardOpenOption.TRUNCATE_EXISTING);
    } catch (IOException e) {
      throw new UncheckedIOException("无法清空会话文件 " + file, e);
    }
  }

  /**
   * 把这段会话替换为 {@code messages}，作为下一代。
   *
   * <p>写一个新文件而不是改写这一个，正是这一点让压缩可逆：被替换掉的那一代原样保留，于是一条事后发现
   * 丢信息太多的摘要，可以靠打开旧文件找回来。它也保住了「只追加」这个故事——压到一半被杀的压缩留下的
   * 是一个完整的文件，而不是被截断的一个。
   *
   * <p>id 不变，于是所有以它为键的东西（侧栏、{@code --resume}、工作区注册表）仍然指向同一段会话；移动
   * 的只是「哪段文件才是这段会话」。
   *
   * <p>经由临时文件写入再改名，因为写了一半的一代会是一段解析出来比自己更短的会话——那是读取方唯一察觉
   * 不到的故障。
   *
   * @return 新一代的路径
   */
  public Path compactInto(List<Message> messages, UsageTotals updated) {
    close();
    Path directory = file.getParent();
    List<Integer> existing = generations(directory, id);
    int next = (existing.isEmpty() ? 0 : existing.get(existing.size() - 1)) + 1;
    Path target = generationFile(directory, id, next);
    Path staged = directory.resolve(target.getFileName() + ".tmp");
    try {
      Files.createDirectories(directory);
      StringBuilder body = new StringBuilder();
      for (Message message : messages) {
        body.append(MessageCodec.toJson(message)).append('\n');
      }
      if (updated != null && !updated.isEmpty()) {
        body.append(MessageCodec.totalsToJson(updated)).append('\n');
      }
      Files.writeString(
          staged,
          body.toString(),
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING);
      // 同一个目录，所以这是一次原子改名：读取方要么看到完整的一代，要么看不到文件，绝不会看到半个。
      Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      try {
        Files.deleteIfExists(staged);
      } catch (IOException ignored) {
        // 暂存文件反正都是残留物；下面那个异常才是要紧的。
      }
      throw new UncheckedIOException("无法压缩会话 " + id, e);
    }
    this.file = target;
    this.history.clear();
    this.history.addAll(messages);
    this.totals = updated == null ? UsageTotals.empty() : updated;
    return target;
  }

  @Override
  public void close() {
    if (writer == null) {
      return;
    }
    try {
      writer.close();
    } catch (IOException e) {
      throw new UncheckedIOException("无法关闭会话文件 " + file, e);
    } finally {
      writer = null;
    }
  }

  /**
   * 用一次 {@code write} 调用追加一行。
   *
   * <p>换成带缓冲的写法，会让超过缓冲区的行被拆到多次系统调用里，而恰好在中间读这个文件的列表（一个回合
   * 跑着时的 <code>GET /api/sessions</code>）会看到半个 JSON 对象并因此在它上面失败。每行一次写，让读取方
   * 永远不会观察到一行还在写；flush 则保住那个持久性承诺：被杀掉的进程不会丢掉已经交给它的东西。
   */
  private void writeLine(String line) {
    byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
    try {
      OutputStream out = writer();
      out.write(bytes);
      out.flush();
    } catch (IOException e) {
      throw new UncheckedIOException("无法追加到会话文件 " + file, e);
    }
  }

  private OutputStream writer() throws IOException {
    if (writer == null) {
      Files.createDirectories(file.getParent());
      writer =
          Files.newOutputStream(
              file,
              StandardOpenOption.CREATE,
              StandardOpenOption.WRITE,
              StandardOpenOption.APPEND);
    }
    return writer;
  }
}
