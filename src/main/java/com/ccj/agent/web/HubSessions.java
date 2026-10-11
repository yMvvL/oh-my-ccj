package com.ccj.agent.web;

import com.ccj.agent.core.Json;

import com.ccj.agent.session.FileSession;
import com.ccj.agent.session.SessionStore;
import com.ccj.agent.core.Workspace;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 会话本身：新建、恢复、删除、切换，以及「现在忙不忙」那几条守卫。
 *
 * <p>4.8 搬出来的第五块。它和 {@link HubViews} 是一对：那一块**展示**会话列表，这一块**动**它。分开的理由
 * 就写在两边的 javadoc 里——展示那一块能读到的只是一份列表，而这一块能删掉它——而一条「只读」的保证，只有在
 * 它能被一个文件的范围回答时才值钱。
 *
 * <p>屏幕上那个会话（{@code session} 与它的锁）也住在这里：它从前在 {@link AgentHub} 的字段里，而搬过来之后
 * 「哪一个是屏幕上的那个」和「会话住在哪儿」是同一件事的两半。窄面上的 {@code session()} 与
 * {@code useSession(…)} 就是它。
 */
final class HubSessions {

  private final HubState state;

  /** 浏览前端正在看的那个对话。 */
  private final Object sessionLock = new Object();

  private volatile FileSession session;

  HubSessions(HubState state, FileSession initial, FolderChooser folderChooser) {
    this.state = state;
    this.session = initial;
    this.folderChooser = folderChooser;
  }

  /**
   * 开一个全新的会话——除非当前这个已经是空的，那种情况下再造一个 id 只会产出第二个空会话，把按按钮的
   * 人搞糊涂。
   */
  public void newSession() {
    FileSession current = state.session();
    if (current != null && current.messages().isEmpty()) {
      state.publish(
          "notice",
          Json.object().put("text", "这个会话已经是空的——先随便说点什么"));
      return;
    }
    state.useSession(SessionStore.create(state.sessionsDir()));
  }

  // ------------------------------------------------------------------ sessions


  public void resumeSession(String id) {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("需要一个 session id");
    }
    state.useSession(state.openForDisplay(id));
  }

  /**
   * 为 {@code id} 放到屏幕上的会话对象。
   *
   * <p>正在跑的对话已经有一个 {@link FileSession}——它的回合正往里面追加的那个——页面拿到的就是这
   * 个对象。在同一个文件上再开一个，就会是两个写入者往同一份转录里写，而那正是整套按对话规则保护的东西；
   * 反过来拒绝*展示*这个对话，才是让一个正在跑的回合变得没法看的原因。
   */
  FileSession openForDisplay(String id) {
    Conversation running = state.conversation(id);
    if (running != null) {
      return running.session();
    }
    return SessionStore.open(state.sessionsDir(), id);
  }

  /**
   * 删除一个会话。删除当前活动的那个会在它原来的位置开一个新的，这样页面在它正看着的列表少了一行之后
   * 总还有地方可待。
   */
  public ObjectNode deleteSession(String id) {
    return deleteSession(null, id);
  }

  /**
   * 删除一个会话。{@code workspace} 点名另一个工作区，要删的是那里的会话；读一个折叠文件夹和在它里面
   * 做删除属于同一类操作，所以两者都不切换活动工作区。删除你正待着的会话会开一个新的。
   */
  public ObjectNode deleteSession(String workspace, String id) {
    String target = normaliseWorkspace(workspace);
    Path directory = sessionsDirOf(target);
    FileSession current = state.session();
    boolean active =
        target == null && current != null && current.id().equals(id);
    state.requireNotRunning(id, "删除它");
    if (!SessionStore.delete(directory, id)) {
      throw new IllegalArgumentException(
          "在 " + (target == null ? "本工作区" : "'" + target + "'") + " 里没有会话 '" + id + "'");
    }
    state.publish("notice", Json.object().put("text", "会话已删除"));
    if (active) {
      state.useSession(SessionStore.create(state.sessionsDir()));
    }
    return Json.object().set("sessions", state.sessionsJson(target));
  }

  /** 清空活动工作区的每一个会话——那个「我测试留下了一堆烂摊子」按钮。 */
  public ObjectNode deleteAllSessions() {
    return deleteAllSessions(null);
  }

  public ObjectNode deleteAllSessions(String workspace) {
    String target = normaliseWorkspace(workspace);
    state.requireEverythingIdle("删除全部会话");
    int deleted = SessionStore.deleteAll(sessionsDirOf(target));
    state.publish(
        "notice",
        Json.object()
            .put("text", deleted == 0 ? "没有可删除的会话" : "已删除 " + deleted + " 个会话"));
    if (target == null) {
      state.useSession(SessionStore.create(state.sessionsDir()));
    }
    return Json.object().set("sessions", state.sessionsJson(target));
  }

  /** null 表示活动工作区；给了名字则它必须存在。 */
  String normaliseWorkspace(String workspace) {
    if (workspace == null || workspace.isBlank()) {
      return null;
    }
    String clean = workspace.strip();
    if (clean.equalsIgnoreCase(state.settings().workspaces().activeName())) {
      return null;
    }
    state.settings()
        .workspaces()
        .find(clean)
        .orElseThrow(() -> new IllegalArgumentException("没有名为 '" + clean + "' 的工作区"));
    return clean;
  }

  Path sessionsDirOf(String workspace) {
    return workspace == null
        ? state.sessionsDir()
        : state.settings().workspaces().find(workspace).orElseThrow().sessionsDir();
  }


  /**
   * 拒绝一个其对象正在运行的操作。
   *
   * <p>这就是取代「只要有东西在跑就什么都不许跑」的那条规则。一个对话忙，不能挡住用户在另一个对话里
   * 工作，但它仍然必须挡住用户把正在干活的那个对话脚下的地抽走：你不能在正往会话里写的回合头上恢复那个
   * 会话，也不能删掉它正在追加的那个文件。
   */
  void requireNotRunning(String sessionId, String what) {
    Conversation running = sessionId == null ? null : state.conversation(sessionId);
    if (running != null && running.running()) {
      throw new IllegalStateException(
          "那个会话正在跑一个回合；" + what + "之前请先中止它");
    }
  }

  /** 任何对话在干活时拒绝：每个对话所立足的东西正在变。 */
  void requireEverythingIdle(String what) {
    for (Conversation conversation : state.conversations()) {
      if (conversation.running()) {
        throw new IllegalStateException(
            "会话 "
                + conversation.id()
                + " 里还有回合在跑；"
                + what
                + "之前请先中止它");
      }
    }
  }

  /** 屏幕上的会话在干活时拒绝。 */
  void requireShownIdle(String what) {
    state.requireNotRunning(HubState.idOf(state.session()), what);
  }
  FileSession session() {
    synchronized (sessionLock) {
      return session;
    }
  }
  void useSession(FileSession next) {
    FileSession previous;
    synchronized (sessionLock) {
      previous = session;
      session = next;
    }
    // 离开屏幕的会话，只有在没人在用它时才关闭。正在跑的对话拥有它的文件——那是回合追加的目标，而且它
    // 可能就是正被放回屏幕上的同一个对象——所以在这里关掉它会让一个仍在工作的回合失败。
    if (previous != null && previous != next && state.conversation(previous.id()) == null) {
      previous.close();
    }
    state.publishUsage();
    state.publishStatus();
  }
  /** 工具解析相对路径的基准，也是本次运行的会话存放的地方。 */
  Path cwd() {
    Workspace active = state.settings().workspaces().active();
    Path override = state.settings().cwdOverride();
    if (override != null) {
      return override;
    }
    return active == null ? Path.of("").toAbsolutePath() : active.path();
  }
  Path sessionsDir() {
    Workspace active = state.settings().workspaces().active();
    return active == null ? state.settings().workspaces().home().resolve("sessions") : active.sessionsDir();
  }
  private final FolderChooser folderChooser;

  /** 向桌面要一个目录。阻塞直到用户作答、取消或超时。 */
  Optional<Path> chooseFolder(java.io.IOException[] failure) {
    try {
      return folderChooser.choose("选择工作区文件夹");
    } catch (java.io.IOException e) {
      failure[0] = e;
      return Optional.empty();
    }
  }
}
