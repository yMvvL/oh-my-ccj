package com.ccj.agent.web;

import com.ccj.agent.core.Config;
import com.ccj.agent.core.Json;
import com.ccj.agent.provider.VisionClient;
import com.ccj.agent.core.Provider;
import com.ccj.agent.core.VisionConfig;
import com.ccj.agent.session.AttachmentStore;
import com.ccj.agent.session.TextPreview;
import com.ccj.agent.session.Thumbnails;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import com.ccj.agent.core.Message;
import com.ccj.agent.session.FileSession;
/**
 * 一个文件怎么**进**对话：先描述成文本，再把描述发出去，文件留在会话旁边。
 *
 * <p>4.8 搬出来的第四块，4.7 把它从"图片那条路"推广成三条：**文本**在本地概括（不需要模型）、**图片**与
 * 其余读不出文字的字节交给视觉模型、而**两者缺的描述者都是同一个**（所以"没有配置视觉模型"这句话只有一份）。
 * 一张日志、一份 PDF、一张巨大的表格，进来的时候都是同一个形状。
 *
 * <p>它拿到的是 {@link HubState} 一个参数，而它要的那几样东西正好是窄面里最窄的那几个：屏幕上
 * 的会话、当前配置、以及「说一句话」的出口。往对话里**发**一条消息是 {@code submit} 的事，不在
 * 这个面上——这一块只负责把文件变成描述。
 */
final class HubAttachments {

  private final HubState state;

  HubAttachments(HubState state) {
    this.state = state;
  }


  /**
   * 把一个文件存到会话旁边，描述它，并把它拿在手里等用户说出想让它做什么。
   *
   * <p>主模型从不收到字节。它收到的是文本——一段描述，以及文件在哪里，这样一个被描述漏掉的细节可以用
   * {@code read} 读回来——而磁盘上的会话仍是可读的文本，旁边多了一个文件。
   *
   * <p>描述者按**字节**挑。文本（日志、一段堆栈、一张 CSV）在本地概括，不需要任何模型：一个被问到"这张图
   * 里有什么"的模型不会比四十行原文加上行数更懂一份日志。其余的一切（图片、PDF、以及任何读不出文字的字节）
   * 交给视觉模型，那是一次单独的调用——全部理由就在这里：两种线上协议都不必长出图片分支，而 {@code Message}、
   * 两个提供方、压缩以及每一个渲染器都保持原样。
   *
   * <p>这里失败就拒绝这个文件：不启动回合，也不写任何东西。什么都还没到达主模型，所以一个"它无法被描述"的
   * 回合会是用户没有要求过的回合，而且关于一个没人看过的东西。
   */
  public ObjectNode describeAttachment(String name, byte[] bytes) {
    if (state.closed()) {
      throw new IllegalStateException("web 会话正在关闭");
    }
    FileSession current = state.session();
    if (current == null) {
      throw new IllegalStateException("没有打开的会话");
    }
    // 字节说了算，而且先由它说了算：不是文本、又不是能看的东西的上传，在任何东西被写入之前、在任何端点被
    // 调用之前就被拒绝。
    String mediaType = AttachmentStore.mediaTypeOf(bytes);
    String description;
    if (AttachmentStore.isText(mediaType)) {
      description = TextPreview.describe(bytes);
    } else {
      // 缺的东西是同一个——一个能看字节的模型——所以这句话只有一份，而它说的是"这个文件"，不是"这个文件"。
      VisionConfig vision = state.config().vision();
      if (vision == null || !vision.isConfigured()) {
        throw visionMissing();
      }
      try (VisionClient client = VisionClient.from(vision, state.settings().env())) {
        description = client.describe(bytes, mediaType);
      }
    }
    // 没有回合，也不占位：这里没有任何东西开始运行。一个已经在跑自己的回合的对话仍然可以接收一个文件，
    // 因为描述它不是那个对话的工作——它是一次辅助调用，结果就停在这里等着，而旧的"先中止它"正是把一次上传
    // 变成一条不通的路。
    //
    // 一次只留一个，所以第二次上传取代第一次。被取代的那个文件留在会话的附件目录里，随之一起删除——它
    // 从来没有进过对话，所以也不该假装它被送出去过。
    Conversation conversation = state.conversationFor(current);
    AttachmentStore.Saved saved = AttachmentStore.forSession(current.file()).save(name, bytes);
    Conversation.Held previous =
        conversation.pendingAttachment.getAndSet(
            new Conversation.Held(saved, description, mediaType));
    state.publish(
        current.id(),
        "attachment",
        AttachmentViews.pending(saved, description, mediaType));
    return Json.object()
        .put("accepted", true)
        .put("attachment", saved.path().toString())
        .put("mediaType", saved.mediaType())
        .put("description", description)
        // 说清楚它现在处于什么状态：一个 202 说的是"收到了"，而页面需要知道它还没有被送出去。
        .put("held", true)
        .put("replaced", previous != null);
  }

  /**
   * "没有配置视觉模型"那一句。
   *
   * <p>它只有一份，因为缺的是同一样东西：图片是这样，一段读不出文字的字节也是这样。**同形**在这里是一条
   * 具体的规矩——同一条路径、同一句话、同一个出口（前端拿到的是同一个错），所以两边的体验不会因为一个是
   * 图片、另一个是 PDF 而分岔。
   */
  private IllegalStateException visionMissing() {
    return new IllegalStateException(
        "没有配置视觉模型，所以这个文件没法被描述——请设置 "
            + state.settings().configFile()
            + " 里的 \"vision\" 块（baseUrl、model 和一个密钥），"
            + "或者传入 --vision-base-url 和 --vision-model");
  }

  /**
   * 这条会话附件目录里某个文件的小图，或者 {@code null}（那里没有这个文件）。
   *
   * @throws IllegalArgumentException 名字不是一个文件名的形状时抛出——它是调用方给的路径参数，而
   *     {@code ../} 会让它变成「读服务器上的任意文件」
   */
  public AgentHub.Thumbnail attachmentThumbnail(String name) {
    FileSession current = state.session();
    if (current == null || name == null || name.isBlank()) {
      return null;
    }
    Path file = AttachmentStore.forSession(current.file()).resolve(name);
    if (file == null) {
      return null;
    }
    byte[] bytes;
    try {
      bytes = Files.readAllBytes(file);
    } catch (IOException e) {
      throw new IllegalStateException("读不出这个文件：" + e.getMessage());
    }
    // 只有图片有小图：一份日志的"缩略图"没有意义，而页面在拿到 null 时会画一条写着文件名的窄条。
    if (!AttachmentStore.isImage(AttachmentStore.mediaTypeOf(bytes))) {
      return null;
    }
    byte[] thumb = Thumbnails.of(bytes);
    return new AgentHub.Thumbnail(thumb, AttachmentStore.mediaTypeOf(thumb));
  }

  public String discardAttachment() {
    FileSession current = state.session();
    if (current == null) {
      return null;
    }
    Conversation conversation = state.conversation(current.id());
    if (conversation == null) {
      return null;
    }
    Conversation.Held dropped = conversation.pendingAttachment.getAndSet(null);
    if (dropped == null) {
      return null;
    }
    state.publish(current.id(), "attachment", Json.object().putNull("name"));
    state.publishStatus();
    return dropped.saved().path().getFileName().toString();
  }
}
