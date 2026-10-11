package com.ccj.agent.web;

import com.ccj.agent.core.Config;
import com.ccj.agent.core.Provider;
import com.ccj.agent.core.ToolRegistry;
import com.ccj.agent.session.FileSession;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一个服务器里那几样**共享**的东西：谁在跑、跑在哪个会话上、现在用什么配置与提供方、事件往哪发。
 *
 * <p>这是 4.8 那把「把 {@link AgentHub} 拆开」的刀。那三个块（表单往返、状态渲染、回合执行）不是各自持有状态
 * 的小东西——它们**共用**服务器的字段，所以从前它们只能是内部类，才能随手抓到那些字段。这个接口把那些字段变成
 * 一组**具名的、能一眼读完的**入口：搬出去的那一块收到的是它，而不是整个服务器。
 *
 * <p>它窄，而窄是重点。{@code submit}、{@code close}、{@code abort} 都不在它上面：一块代码能不能**开始**一个
 * 回合、能不能**关掉**服务器，是这一层要回答的问题，而不是搬出去那几块可以随手做的动作。一个内部类能抓到整个
 * 服务器的时候，这个问题根本没有答案。
 *
 * <p>由 {@link AgentHub} 实现——状态仍然住在它那里（那是唯一一个「只有一个」的地方），它只是**交出几个名字**。
 */
interface HubState {

  /** 这个服务器的设置：工作区、模型目录、提供方工厂，以及它们背后那些文件。 */
  AgentHub.Settings settings();

  /** 工具表：一个回合能碰到的一切。 */
  ToolRegistry tools();

  /** 现在生效的配置。 */
  Config config();

  /**
   * 换掉生效的配置。
   *
   * <p>不给「写进文件」这件事：文件那一半属于 {@link SettingsFiles}，而改了配置之后要不要发布状态，属于改它的
   * 那一段代码（它比任何人都清楚自己改了什么）。
   */
  void setConfig(Config next);

  /**
   * 提供方本身，而不是它的值。
   *
   * <p>因为改设置就是**换掉**它，而不是就地改它（见 {@code applyConfig}），所以这里给的是那个引用——一个取值
   * 的方法会让「换」这件事没有地方落地。
   */
  AtomicReference<Provider> provider();

  /** 屏幕上正看着的那个会话。 */
  FileSession session();

  /** 把屏幕换到另一个会话上；它顺带发布用量与状态，并关掉离开屏幕的那个（如果没人在用它）。 */
  void useSession(FileSession next);

  /** 现在长什么样：工作区、会话、模型、工具的完整状态表。 */
  ObjectNode status();

  /** 会话列表，{@code workspace} 为 null 表示活动工作区。 */
  ArrayNode sessionsJson(String workspace);

  /** 按 id 找那个正在跑的对话；没有（或 id 为空）时为 null。 */
  Conversation conversation(String sessionId);

  /**
   * 这条线程正在跑其回合的那个会话 id。
   *
   * <p>从调用线程解析出来，而不是当参数传：工具把审批人当作一个普通的 {@code Approver}，只有回合自己的会话
   * 说得清是哪个对话在问。
   */
  String currentTurnSession();

  /** 见 {@link EventStream#publish}. */
  void publish(String type, ObjectNode payload);

  /** 见 {@link EventStream#publish}. */
  void publish(String sessionId, String type, ObjectNode payload);

  /** 发布一次状态，外加跟着状态走的两个仪表。 */
  void publishStatus();

  void publishUsage();

  /** 一个会话的用量，也就是一个回合在自己的账本变化时发布的东西。 */
  void publishUsage(String sessionId);

  /** 工具解析相对路径的基准，也是本次运行的会话存放的地方。 */
  Path cwd();

  Path sessionsDir();

  /** null 表示活动工作区；给了名字则它必须存在。 */
  String normaliseWorkspace(String workspace);

  Path sessionsDirOf(String workspace);

  /**
   * 为 {@code id} 放到屏幕上的会话对象。
   *
   * <p>正在跑的对话已经有一个 {@link FileSession}，而在这个文件上再开一个就会是两个写入者。
   */
  FileSession openForDisplay(String id);

  /**
   * 拒绝一个其对象正在运行的回合的操作。
   *
   * <p>这就是取代「只要有东西在跑就什么都不许跑」的那条规则：一个对话忙，不能挡住用户在另一个对话里工作，但它
   * 仍然必须挡住用户把正在干活的那个对话脚下的地抽走。
   */
  void requireNotRunning(String sessionId, String what);

  /** 任何对话在干活时拒绝：每个对话所立足的东西正在变。 */
  void requireEverythingIdle(String what);

  /** 屏幕上的会话在干活时拒绝。 */
  void requireShownIdle(String what);

  /** 会话 id，空会话给空串——发布事件时用得上。 */
  static String idOf(FileSession session) {
    return session == null ? "" : session.id();
  }
}
