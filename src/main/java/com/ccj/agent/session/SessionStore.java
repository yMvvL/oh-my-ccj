package com.ccj.agent.session;

import com.ccj.agent.core.Message;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 会话目录的入口：创建、重新打开、列出历史。
 *
 * <p>列表读的仍然是磁盘上实际的东西——没有单独的索引文件会与目录脱节，手动删掉一个会话文件也依然是
 * 遗忘它的受支持方式。缓存的是这次读里昂贵的那部分：推导一行的标题意味着解析文件直到第一条用户消息，
 * 而侧栏在每个回合结束后都要索要整份列表。{@link SessionIndex} 按文件保存这些推导结果，文件一变就丢掉
 * 对应的那条，于是这份开销每个会话只付一次，而不是每次列表都付。
 */
public final class SessionStore {

  /** 预览长度；{@code --list-sessions} 为它留了一列。 */
  private static final int PREVIEW_LIMIT = 60;

  /** 标题长度：足以在侧栏里把两个会话区分开的第一句请求。 */
  static final int TITLE_LIMIT = 64;

  /** 文件读不出来的会话在列表里被叫作什么。 */
  private static final String UNREADABLE = "（无法读取：会话文件已损坏）";

  /**
   * 按文件保存的推导结果，以路径为键。
   *
   * <p>之所以是 static，是因为每个调用方都通过这个类访问会话，而按实例缓存会被 CLI、web hub 和测试
   * 各自持有一份而失效。
   */
  private static final SessionIndex INDEX = new SessionIndex();

  /**
   * {@code --list-sessions} 的一行。
   *
   * @param title 第一条用户消息说了什么，供一份必须被人读、而不是按 id 扫的列表使用；从未写过用户
   *     消息时为空
   * @param preview 第一条用户消息，压成截断后的单行
   * @param lastModified 文件修改时间，每次追加都会推进
   */
  public record Summary(
      String id, String title, String preview, int messageCount, Instant lastModified, Path file) {

    public Summary {
      id = id == null ? "" : id;
      title = title == null ? "" : title;
      preview = preview == null ? "" : preview;
      lastModified = lastModified == null ? Instant.EPOCH : lastModified;
    }
  }

  private SessionStore() {}

  public static FileSession create(Path sessionsDir) {
    return FileSession.create(sessionsDir);
  }

  public static FileSession open(Path sessionsDir, String id) {
    return FileSession.open(sessionsDir, id);
  }

  /** 删除一个会话文件。没有东西可删时返回 false。 */
  public static boolean delete(Path sessionsDir, String id) {
    if (!FileSession.isValidId(id)) {
      throw new IllegalArgumentException("无效的会话 id：" + id);
    }
    // 每一代都删，不只是最新的那一代：旧的也是这段会话，把它们留下会让它在下次列表时以同一个 id 复活。
    boolean removed = false;
    try {
      for (int generation : FileSession.generations(sessionsDir, id)) {
        Path file =
            generation == 0
                ? FileSession.fileFor(sessionsDir, id)
                : FileSession.generationFile(sessionsDir, id, generation);
        removed |= Files.deleteIfExists(file);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("无法删除会话 " + id, e);
    }
    // 它的图片也跟着走。删掉会话却把它收到过的照片留下，正是那个堆满再也不会有人读的未列出文件的目录的
    // 成因。
    removed |= AttachmentStore.deleteFor(FileSession.fileFor(sessionsDir, id));
    return removed;
  }

  /**
   * 删除一个目录里的每个会话——一堆测试跑完后的清理路径。
   *
   * @return 删掉了多少个文件
   */
  public static int deleteAll(Path sessionsDir) {
    if (sessionsDir == null || !Files.isDirectory(sessionsDir)) {
      return 0;
    }
    int deleted = 0;
    try (Stream<Path> entries = Files.list(sessionsDir)) {
      for (Path file : entries.toList()) {
        String name = file.getFileName().toString();
        if (name.endsWith(AttachmentStore.DIRECTORY_SUFFIX)) {
          // 会话的图片：它们不是会话，要和所属的那个一起算，留下它们就等于把这次清理的意义留下了。
          AttachmentStore.deleteDirectory(file);
          continue;
        }
        if (!idOf(name).isEmpty()) {
          Files.deleteIfExists(file);
          deleted++;
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("无法清空以下目录中的会话 " + sessionsDir, e);
    }
    return deleted;
  }

  /**
   * 最新的在前；目录不存在就是一段空历史。
   *
   * <p>一个会话无论有多少个代文件，都只有<em>一行</em>：一次压缩会写出新文件，但并不创建新会话，所以
   * 按文件列出会让每次压缩都看起来像是凭空多出第二段会话。这一行报告的是最新一代的内容、大小和时间戳，
   * 因为 {@link FileSession#open} 读的正是它。
   *
   * <p>每次调用仍然会对每个代文件做 stat，正是这一点让答案保持诚实：自上次列表以来长大过、被压缩过或
   * 被删掉的会话，报告的是它现在的样子，而不是曾经为真的样子。
   */
  public static List<Summary> list(Path sessionsDir) {
    if (sessionsDir == null || !Files.isDirectory(sessionsDir)) {
      return List.of();
    }
    // id -> 它最新的那一代，也就是这一行所描述的文件。
    Map<String, Path> newest = new LinkedHashMap<>();
    List<Path> present = new ArrayList<>();
    try (Stream<Path> entries = Files.list(sessionsDir)) {
      for (Path file : entries.toList()) {
        String id = idOf(file.getFileName().toString());
        if (id.isEmpty()) {
          continue;
        }
        int generation = generationOf(file.getFileName().toString(), id);
        Path known = newest.get(id);
        if (known == null || generation > generationOf(known.getFileName().toString(), id)) {
          newest.put(id, file);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("无法列出以下目录中的会话 " + sessionsDir, e);
    }

    List<Summary> summaries = new ArrayList<>(newest.size());
    for (Map.Entry<String, Path> entry : newest.entrySet()) {
      String id = entry.getKey();
      Path file = entry.getValue();
      Instant modified;
      long size;
      try {
        modified = Files.getLastModifiedTime(file).toInstant();
        size = Files.size(file);
      } catch (IOException e) {
        // 在列表和 stat 之间消失了：不再是一个会话，为一个已经不在的文件报告一行，比少一行更糟。
        INDEX.forget(file);
        continue;
      }
      present.add(file);
      summaries.add(INDEX.summaryFor(file, id, modified, size, SessionStore::derive));
    }
    // 中间没有经历过列表就被删掉的文件的推导结果会保留到这一步，所以已经不在的那些在这里被丢掉，而不是
    // 让这张 map 在整个进程的生命周期里一直长下去。
    INDEX.retain(sessionsDir, present);
    summaries.sort(
        Comparator.comparing(Summary::lastModified)
            .reversed()
            .thenComparing(Summary::id, Comparator.reverseOrder()));
    return List.copyOf(summaries);
  }

  /**
   * 一个文件名属于哪个会话 id，名字不是会话文件时为空。
   *
   * <p>普通的 {@code <id>.jsonl} 和某一代 {@code <id>.g1.jsonl} 都映射到 {@code <id>}，正是这一点让
   * 列表能把它们并成一行。
   */
  static String idOf(String fileName) {
    if (!fileName.endsWith(FileSession.EXTENSION)) {
      return "";
    }
    String stem = fileName.substring(0, fileName.length() - FileSession.EXTENSION.length());
    int marker = stem.lastIndexOf(".g");
    if (marker > 0) {
      String digits = stem.substring(marker + 2);
      if (!digits.isEmpty() && digits.chars().allMatch(Character::isDigit)) {
        stem = stem.substring(0, marker);
      }
    }
    return FileSession.isValidId(stem) ? stem : "";
  }

  /** 一个会话文件属于哪一代：普通名字为 0，{@code <id>.gn.jsonl} 为 n。 */
  private static int generationOf(String fileName, String id) {
    String stem = fileName.substring(0, fileName.length() - FileSession.EXTENSION.length());
    if (stem.equals(id)) {
      return 0;
    }
    try {
      return Integer.parseInt(stem.substring(stem.lastIndexOf(".g") + 2));
    } catch (RuntimeException e) {
      return 0;
    }
  }

  /**
   * 一个目录里有多少个会话，从目录本身数起，而不是从 {@link #list} 数起。
   *
   * <p>工作区树会在它认识的每个工作区旁边显示这个数字，而靠构建完整摘要来得出它，会为了给每个文件夹产出
   * 一个整数而解析这台机器上的每一个会话文件。目录里的一个条目并不等于一个会话——半个文件、编辑器备份、
   * 改错名字的东西都会跑进去——所以由扩展名来决定。
   */
  public static int count(Path sessionsDir) {
    if (sessionsDir == null || !Files.isDirectory(sessionsDir)) {
      return 0;
    }
    // 按 id 计数，所以工作区旁边的数字是多少段会话，而不是它们占了多少个文件——压缩过的会话是一个会话，
    // 不是两个。
    Set<String> ids = new HashSet<>();
    try (Stream<Path> entries = Files.list(sessionsDir)) {
      for (Path file : entries.toList()) {
        String id = idOf(file.getFileName().toString());
        if (!id.isEmpty()) {
          ids.add(id);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("无法列出以下目录中的会话 " + sessionsDir, e);
    }
    return ids.size();
  }

  /**
   * 列表里需要真读一次的那部分：有多少条消息，第一条用户消息说了什么。只有文件是新的、或自上次被问过之后
   * 动过时才会走到这里。
   */
  private static Summary derive(String id, Path file, Instant modified) {
    List<Message> messages;
    try {
      messages = FileSession.readAll(file);
    } catch (IllegalArgumentException e) {
      // 一个读不了的文件不能把其它会话都藏起来。它仍然是一个会话，也仍然是列表存在的理由——所以它以
      // 「已损坏」被列出，打开它时会报出同一个错误，附带失败的行号。
      return new Summary(id, UNREADABLE, UNREADABLE, 0, modified, file);
    }
    return new Summary(id, title(messages), preview(messages), messages.size(), modified, file);
  }

  private static String preview(List<Message> messages) {
    for (Message message : messages) {
      if (message instanceof Message.User user) {
        return truncate(user.text());
      }
    }
    return "（暂无消息）";
  }

  /**
   * 与 {@link #preview} 相同的第一条用户消息，一行，稍长一些：一份以时间戳 id 为键的列表是谁也挑不出
   * 东西的列表，所以侧栏读的是这个，而不是 id。
   */
  private static String title(List<Message> messages) {
    for (Message message : messages) {
      if (message instanceof Message.User user) {
        return flatten(user.text(), TITLE_LIMIT);
      }
    }
    return "";
  }

  private static String truncate(String text) {
    String flat = flatten(text, PREVIEW_LIMIT);
    return flat.isEmpty() ? "（空消息）" : flat;
  }

  /** 一行，最多 {@code limit} 个字符，有截断时带省略号。 */
  static String flatten(String text, int limit) {
    String flat = text == null ? "" : text.replaceAll("\\s+", " ").strip();
    return flat.length() <= limit ? flat : flat.substring(0, limit).stripTrailing() + "…";
  }
}
