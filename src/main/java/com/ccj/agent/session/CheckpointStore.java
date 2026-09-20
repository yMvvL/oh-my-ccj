package com.ccj.agent.session;

import com.ccj.agent.core.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 一个回合改了什么，留着好把这个回合撤回去。
 *
 * <p>审批回答的是一个问题——「这个能不能跑」——检查点回答的是另一个，也是人们在放一个代理去干活之前
 * 犹豫时真正在意的那个：*能不能撤回来*。没有它，对一段会话诚实的描述是「代理写了文件，你批准了每一次
 * 写入」，这对按一次按钮来说承诺太多；有了它，承诺是有边的，人可以把守卫留在它该在的地方，而不必为了
 * 补偿它去关掉审批。
 *
 * <p>布局：与会话并排，按会话 id 命名：
 *
 * <pre>
 * &lt;sessions&gt;/&lt;id&gt;.checkpoints/&lt;turn&gt;/manifest.json
 * &lt;sessions&gt;/&lt;id&gt;.checkpoints/&lt;turn&gt;/0.blob
 * </pre>
 *
 * <p>manifest 记下动过什么（相对于项目），连同保存其先前内容的 blob，以及它当时到底存不存在——一个
 * 创建了文件的回合，撤销方式是把它删掉，这与把原来就在的东西写回去是不同的操作。
 *
 * <p><strong>线程作用域，这是设计而不是细节。</strong> 一个回合跑在一条线程上；写文件的工具也跑在它
 * 上面；可能同时有好几段会话在跑，各用各的。用一个字段说「当前进行中的回合」，会把一段会话的写入记到
 * 另一段的检查点里，而 thread-local 说的恰恰是真话：这个回合就是此刻、在这里跑着的那个。
 */
public final class CheckpointStore {

  /** 超过这个大小的文件不做快照，该回合会以省略来表示：一部电影的快照不值得留。 */
  static final long MAX_FILE_BYTES = 4L * 1024 * 1024;

  /** 保留多少个回合。更旧的会在一个回合开始时被修剪掉。 */
  static final int KEEP_TURNS = 20;

  /** 这条线程正在跑的回合，没在跑时为 null。 */
  private static final ThreadLocal<Turn> CURRENT = new ThreadLocal<>();

  private final boolean recording;

  private CheckpointStore(boolean recording) {
    this.recording = recording;
  }

  /** 什么都不记录的存储：没人接进来时工具拿到的就是它。 */
  public static CheckpointStore none() {
    return new CheckpointStore(false);
  }

  /** 真家伙。 */
  public static CheckpointStore recording() {
    return new CheckpointStore(true);
  }

  /** 这条线程上进行中的一个回合。 */
  private static final class Turn {
    private final Path directory;
    private final Path project;
    private final List<Entry> entries = new ArrayList<>();
    private int blobs;

    Turn(Path directory, Path project) {
      this.directory = directory;
      this.project = project;
    }

    /**
     * 这个回合的目录，在有东西要放进去时才创建。
     *
     * <p>之所以懒创建，是因为一个什么都没改的回合必须什么都不留下：无论里面有没有快照都按回合建一个
     * 目录，就等于在「可撤销的东西」的计数里每个回合都算一个，而只有其中一个改过文件却说「三个回合可以
     * 撤销」，是用户按下按钮才会发现的谎话。
     */
    Path directory() throws IOException {
      Files.createDirectories(directory);
      return directory;
    }
  }

  /** 一个文件在这个回合动它之前的样子。 */
  private record Entry(String path, String blob, boolean existed) {}

  /**
   * 在这条线程上打开一个回合，对应会话文件为这个文件的会话。
   *
   * <p>由开始一个回合的人调用——web hub、REPL——并由 {@link #endTurn()} 关闭。两端之间除非有工具
   * 要求，否则什么都不记录；在没有打开回合的情况下跑的工具不记录任何东西：后台线程不是这个回合，假装它
   * 是，正是检查点最后装错会话状态的原因。
   */
  public void beginTurn(Path sessionFile, Path project) {
    endTurn();
    if (!recording || sessionFile == null || project == null) {
      return;
    }
    Path root = sessionFile.toAbsolutePath().normalize();
    Path directory =
        root.resolveSibling(root.getFileName().toString().replace(".jsonl", "") + ".checkpoints");
    // 写不了的检查点不能拦住这个回合：被要求的是那份工作，能撤销它只是顺手的好意。为什么这里不创建任何
    // 东西，见 Turn.directory。
    CURRENT.set(
        new Turn(directory.resolve(Integer.toString(nextTurn(directory))),
            project.toAbsolutePath().normalize()));
    prune(directory);
  }

  /** 关闭这条线程的回合，把它持有的内容写出去。 */
  public void endTurn() {
    Turn turn = CURRENT.get();
    CURRENT.remove();
    if (turn == null || turn.entries.isEmpty()) {
      return;
    }
    ObjectNode manifest = Json.object();
    manifest.put("project", turn.project.toString());
    ArrayNode files = manifest.putArray("files");
    for (Entry entry : turn.entries) {
      ObjectNode node = files.addObject();
      node.put("path", entry.path());
      node.put("blob", entry.blob());
      node.put("existed", entry.existed());
    }
    try {
      Path directory = turn.directory();
      Files.writeString(
          directory.resolve("manifest.json"), Json.writePretty(manifest) + "\n",
          StandardCharsets.UTF_8);
      // 这里也修剪一次，和开头一样，因为一个回合正是加目录的地方：只在开头修剪，会让它比上限多出一个回合，
      // 直到下一个回合开始。
      prune(directory.getParent());
    } catch (IOException e) {
      // 同上：失去撤销一个回合的能力，不值得让这个回合失败。
    }
  }

  /**
   * 在工具覆盖它之前，记下这个文件原来的样子。
   *
   * @param file 即将被写入的文件
   * @param previous 它现在的内容，还不存在时为 null
   */
  public void record(Path file, String previous) {
    Turn turn = CURRENT.get();
    if (turn == null || file == null) {
      return;
    }
    String relative = relative(file, turn.project);
    if (relative == null) {
      return; // 在项目之外：不是这段会话该撤销的事
    }
    for (Entry entry : turn.entries) {
      if (entry.path().equals(relative)) {
        return; // 这个回合已经快照过了：要紧的状态是回合开始时的那个
      }
    }
    if (previous != null && previous.length() > MAX_FILE_BYTES) {
      return;
    }
    String blob = turn.blobs++ + ".blob";
    try {
      Files.writeString(turn.directory().resolve(blob), previous == null ? "" : previous,
          StandardCharsets.UTF_8);
    } catch (IOException e) {
      return;
    }
    turn.entries.add(new Entry(relative, blob, previous != null));
  }

  /**
   * 把最近一个记录过的回合改过的东西放回去，并忘掉那个回合。
   *
   * @return 被恢复的、相对于项目的路径，仅限最新的那个回合
   */
  public List<String> undoLastTurn(Path sessionFile, Path project) {
    Path directory = directoryFor(sessionFile);
    if (directory == null || !Files.isDirectory(directory)) {
      return List.of();
    }
    List<Integer> turns = turnNumbers(directory);
    if (turns.isEmpty()) {
      return List.of();
    }
    Path turn = directory.resolve(Integer.toString(turns.get(turns.size() - 1)));
    JsonNode manifest = readManifest(turn);
    if (manifest == null) {
      // 一个什么都没记录的回合：没有可撤销的，它的目录也删掉，好让下一次撤销够得着它前面的那个回合。
      deleteRecursively(turn);
      return List.of();
    }
    List<String> restored = new ArrayList<>();
    for (JsonNode entry : manifest.path("files")) {
      Path target = targetOf(entry, project);
      if (target == null) {
        continue; // 指到别处的 manifest 不该照做
      }
      try {
        if (restores(entry)) {
          Path blob = turn.resolve(entry.path("blob").asText());
          Files.createDirectories(target.getParent());
          Files.writeString(target, Files.readString(blob, StandardCharsets.UTF_8),
              StandardCharsets.UTF_8);
        } else {
          Files.deleteIfExists(target);
        }
        restored.add(entry.path("path").asText());
      } catch (IOException e) {
        // 一个恢复不了的文件不能拦住其它的。
      }
    }
    deleteRecursively(turn);
    return List.copyOf(restored);
  }

  /**
   * 下一次退回会动哪些文件、还能退回几个回合——只读。
   *
   * <p>退回是唯一一个会覆盖磁盘的按钮，所以清单要在按下去之前看得见：「按下去再发现」比「先说会改什么」
   * 贵得多。这个方法因此什么都不改——不建目录、不写文件、不删回合；一个自己动了磁盘的预览，没人敢先看
   * 一眼。
   *
   * @param sessionFile 会话文件，检查点与它并排；没有会话文件时回到空清单
   * @param project 项目根目录，清单里的路径相对它给出，和 {@link #undoLastTurn} 收得下的是同一套
   * @return {@code {"files":[{"path":…,"action":"restore"|"delete"}],"turns":n}}；没有可退回的回合时
   *     files 为空、turns 为 0。turns 包括这一次在内：它和 {@link #undoableTurns} 说的是同一个数
   */
  public ObjectNode previewUndo(Path sessionFile, Path project) {
    Path directory = directoryFor(sessionFile);
    List<Integer> turns = directory == null ? List.of() : turnNumbers(directory);
    ObjectNode preview = Json.object();
    ArrayNode files = preview.putArray("files");
    if (!turns.isEmpty()) {
      // 取的是 undoLastTurn 会动手的那个回合：预览说的必须就是下一次退回真会做的那件事。
      int newest = turns.get(turns.size() - 1);
      JsonNode manifest = readManifest(directory.resolve(Integer.toString(newest)));
      if (manifest != null) {
        for (JsonNode entry : manifest.path("files")) {
          if (targetOf(entry, project) == null) {
            continue; // 退回不照做的东西，预览也不该承诺
          }
          ObjectNode file = files.addObject();
          file.put("path", entry.path("path").asText());
          file.put("action", actionOf(entry));
        }
      }
    }
    preview.put("turns", turns.size());
    return preview;
  }

  /** 有多少个回合可以撤销，最新的在前，给想说这句话的调用方用。 */
  public int undoableTurns(Path sessionFile) {
    Path directory = directoryFor(sessionFile);
    return directory == null ? 0 : turnNumbers(directory).size();
  }

  /** 一个会话的检查点所在的目录，没有会话文件时为 null。 */
  public static Path directoryFor(Path sessionFile) {
    if (sessionFile == null) {
      return null;
    }
    Path root = sessionFile.toAbsolutePath().normalize();
    return root.resolveSibling(root.getFileName().toString().replace(".jsonl", "") + ".checkpoints");
  }

  /** 那个回合的 manifest，不在了或者读不出来时为 null。 */
  private static JsonNode readManifest(Path turn) {
    Path file = turn.resolve("manifest.json");
    if (!Files.isRegularFile(file)) {
      return null;
    }
    try {
      return Json.parse(Files.readString(file, StandardCharsets.UTF_8));
    } catch (IOException | IllegalArgumentException e) {
      return null;
    }
  }

  /** 回合开始时就存在的文件是放回原样；回合新建的是删掉——退回与预览都从这里取答案。 */
  private static boolean restores(JsonNode entry) {
    return entry.path("existed").asBoolean(false);
  }

  /** 上面那个判断说给人听的样子。 */
  private static String actionOf(JsonNode entry) {
    return restores(entry) ? "restore" : "delete";
  }

  /** 条目指向的文件，落在项目之外时为 null（那种条目连退回都不照做）。 */
  private static Path targetOf(JsonNode entry, Path project) {
    Path target = project.resolve(entry.path("path").asText()).normalize();
    return target.startsWith(project.toAbsolutePath().normalize()) ? target : null;
  }

  private static String relative(Path file, Path project) {
    Path target = file.toAbsolutePath().normalize();
    if (!target.startsWith(project)) {
      return null;
    }
    return project.relativize(target).toString().replace('\\', '/');
  }

  private static int nextTurn(Path directory) {
    List<Integer> turns = turnNumbers(directory);
    return turns.isEmpty() ? 1 : turns.get(turns.size() - 1) + 1;
  }

  private static List<Integer> turnNumbers(Path directory) {
    List<Integer> numbers = new ArrayList<>();
    if (!Files.isDirectory(directory)) {
      return numbers;
    }
    try (var entries = Files.list(directory)) {
      entries.forEach(
          path -> {
            try {
              numbers.add(Integer.parseInt(path.getFileName().toString()));
            } catch (NumberFormatException notATurn) {
              // 目录里别的东西；不归我们解释。
            }
          });
    } catch (IOException e) {
      throw new UncheckedIOException("无法读取 " + directory, e);
    }
    numbers.sort(Integer::compareTo);
    return numbers;
  }

  private static void prune(Path directory) {
    List<Integer> turns = turnNumbers(directory);
    for (int i = 0; i < turns.size() - KEEP_TURNS; i++) {
      deleteRecursively(directory.resolve(Integer.toString(turns.get(i))));
    }
  }

  private static void deleteRecursively(Path path) {
    if (!Files.exists(path)) {
      return;
    }
    try (var walk = Files.walk(path)) {
      for (Path entry : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(entry);
      }
    } catch (IOException ignored) {
      // 尽力而为：剩一个目录，比一个失败的回合或撤销是更小的问题。
    }
  }
}
