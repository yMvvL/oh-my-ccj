package com.ccj.agent.session;

import com.ccj.agent.core.Message;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 侧栏需要的、关于每个会话的东西，而不必读每个会话。
 *
 * <p>列表过去靠解析每个会话文件来找它的标题，也就是第一条用户消息，因此它位于文件的中间某处。这与
 * 磁盘上的字节数成正比，而页面在每个回合结束后都会重读一次列表——于是装满长会话的目录让每个回合结束时
 * 都卡上几百毫秒，卡在一条没人看得见的路径上。
 *
 * <p>这是缓存，不是第二个真相来源。这里的东西都不碰磁盘：索引从未被告知过的会话被读一次（由
 * {@link SessionStore} 读）然后被记住，而条目在它下面的文件一变时就被丢掉——正是这一点让被手动删掉或
 * 截断的文件表现得和从前一模一样，因为索引本来会给出的答案已经和那里的实际内容对不上了。
 *
 * <p>检查的是文件的修改时间与大小合在一起，而不是其中单独一个：一个收到消息并在同一毫秒内写出的会话
 * 保持它的 mtime，只有大小会出卖它。两者都只是一次 stat，这正是这件事值得做的原因。
 *
 * <p>不是碰巧线程安全的：这张 map 是并发的，因为浏览器一次刷新和一个刚结束的回合可能同时来问，而一个
 * 键下的值是不可变的。
 */
final class SessionIndex {

  /** 一行需要的全部东西：它描述的文件，以及列表从该文件推导出的东西。 */
  record Entry(Instant modified, long size, SessionStore.Summary summary) {}

  private final Map<Path, Entry> entries = new ConcurrentHashMap<>();

  /**
   * {@code file} 的摘要：文件没动过时来自索引，否则来自 {@code derive}——后者是唯一读文件的路径。
   *
   * @param id 会话 id，文件名不必再为了它被解析
   * @param derive 读文件并构建它的摘要；每次变化至多被调用一次
   */
  SessionStore.Summary summaryFor(Path file, String id, Instant modified, long size, Derive derive) {
    Entry known = entries.get(file);
    if (known != null && known.modified().equals(modified) && known.size() == size) {
      return known.summary();
    }
    SessionStore.Summary summary = derive.derive(id, file, modified);
    entries.put(file, new Entry(modified, size, summary));
    return summary;
  }

  /** 忘掉 {@code file}，好让下次列表重新读它。 */
  void forget(Path file) {
    entries.remove(file);
  }

  /**
   * 忘掉 {@code directory} 之下所有已不在磁盘上的条目，好让一个在没有列表运行时被删掉的会话，不会
   * 靠一条过期的条目把自己的行一直留着。
   */
  void retain(Path directory, List<Path> present) {
    if (entries.isEmpty()) {
      return;
    }
    List<Path> live = new ArrayList<>(present);
    entries.keySet().removeIf(path -> path.startsWith(directory) && !live.contains(path));
  }

  /** 构建一条缺失条目所需的摘要；单独拎出来是为了让测试能数它跑了几次。 */
  @FunctionalInterface
  interface Derive {
    SessionStore.Summary derive(String id, Path file, Instant modified);
  }

  /** 一次列表从文件里需要的消息，且不超过第一条用户消息。 */
  static String titleOf(List<Message> messages) {
    for (Message message : messages) {
      if (message instanceof Message.User user) {
        return SessionStore.flatten(user.text(), SessionStore.TITLE_LIMIT);
      }
    }
    return "";
  }
}
