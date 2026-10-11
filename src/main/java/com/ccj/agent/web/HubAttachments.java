package com.ccj.agent.web;

import com.ccj.agent.core.Config;
import com.ccj.agent.core.Json;
import com.ccj.agent.provider.VisionClient;
import com.ccj.agent.core.Provider;
import com.ccj.agent.core.VisionConfig;
import com.ccj.agent.session.AttachmentStore;
import com.ccj.agent.session.Thumbnails;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import com.ccj.agent.core.Message;
import com.ccj.agent.session.FileSession;
/**
 * 一个文件怎么**进**对话：先由独立模型描述成文本，再把描述发出去，文件留在会话旁边。
 *
 * <p>4.8 搬出来的第四块。它是图片那条路，而它有意写得不像「图片专用」——一道描述、一句拒绝、
 * 一个缩略图，对日志、PDF、一张巨大的表格都是同一个形状（见 4.7）。
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
   * 把一张图片存到会话旁边，让视觉模型描述它，并基于那段描述启动一个回合。
   *
   * <p>主模型从不收到图片。它收到的是文本——描述，以及文件在哪里，这样一个被描述漏掉的细节可以用
   * {@code read} 工具读回来——而磁盘上的会话仍是可读的文本，旁边多了一张图片。视觉模型之所以是一次
   * 单独的调用，全部理由就在这里：两种线上协议都不必长出图片分支，而 {@code Message}、两个提供方、压缩
   * 以及每一个渲染器都保持原样。
   *
   * <p>这里失败就拒绝这张图片，不启动任何回合。什么都还没到达主模型，所以一个「图片无法被描述」的回合
   * 会是用户没有要求过的回合，而且关于一张没人看过的图片。
   */
  public ObjectNode describePicture(String name, byte[] bytes) {
    if (state.closed()) {
      throw new IllegalStateException("web 会话正在关闭");
    }
    FileSession current = state.session();
    if (current == null) {
      throw new IllegalStateException("没有打开的会话");
    }
    // 字节说了算，而且先由它说了算：不是图片的上传，在任何东西被写入之前、在视觉端点被调用之前就被拒绝。
    String mediaType = AttachmentStore.mediaTypeOf(bytes);
    Config active = state.config();
    if (active.vision() == null || !active.vision().isConfigured()) {
      throw new IllegalStateException(
          "没有配置视觉模型，所以图片没法被描述——请设置 "
              + state.settings().configFile()
              + " 里的 \"vision\" 块（baseUrl、model 和一个密钥），"
              + "或者传入 --vision-base-url 和 --vision-model");
    }
    Conversation conversation = state.conversationFor(current);
    AttachmentStore.Saved saved;
    String description;
    try (VisionClient client = VisionClient.from(active.vision(), state.settings().env())) {
      description = client.describe(bytes, mediaType);
      saved = AttachmentStore.forSession(current.file()).save(name, bytes);
    }
    // 没有回合，也不占位：这里没有任何东西开始运行。一个已经在跑自己的回合的对话仍然可以接收一张图片，
    // 因为描述这张图片不是那个对话的工作——它是一次辅助调用，结果就停在这里等着，而旧的「先中止它」正是
    // 把一次上传变成一条不通的路。
    //
    // 一次只留一张，所以第二次上传取代第一次。被取代的那个文件留在会话的附件目录里，随之一起删除——它
    // 从来没有进过对话，所以也不该假装它被送出去过。
    Conversation.Held previous =
        conversation.pendingPicture.getAndSet(
            new Conversation.Held(saved, description, mediaType));
    state.publish(
        current.id(),
        "picture",
        AttachmentViews.pending(saved, description, mediaType));
    return Json.object()
        .put("accepted", true)
        .put("attachment", saved.path().toString())
        .put("mediaType", saved.mediaType())
        .put("description", description)
        // 说清楚它现在处于什么状态：一个 202 说的是「收到了」，而页面需要知道它还没有被送出去。
        .put("held", true)
        .put("replaced", previous != null);
  }

  /**
   * 这条会话附件目录里某个文件的小图，或者 {@code null}（那里没有这个文件）。
   *
   * @throws IllegalArgumentException 名字不是一个文件名的形状时抛出——它是调用方给的路径参数，而
   *     {@code ../} 会让它变成「读服务器上的任意文件」
   */
  public AgentHub.PictureFile attachmentThumbnail(String name) {
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
      throw new IllegalStateException("读不出这张图片：" + e.getMessage());
    }
    byte[] thumb = Thumbnails.of(bytes);
    return new AgentHub.PictureFile(thumb, AttachmentStore.mediaTypeOf(thumb));
  }

  public String discardPicture() {
    FileSession current = state.session();
    if (current == null) {
      return null;
    }
    Conversation conversation = state.conversation(current.id());
    if (conversation == null) {
      return null;
    }
    Conversation.Held dropped = conversation.pendingPicture.getAndSet(null);
    if (dropped == null) {
      return null;
    }
    state.publish(current.id(), "picture", Json.object().putNull("name"));
    state.publishStatus();
    return dropped.saved().path().getFileName().toString();
  }
}
