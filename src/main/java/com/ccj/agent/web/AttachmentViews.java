package com.ccj.agent.web;

import com.ccj.agent.core.Json;
import com.ccj.agent.session.AttachmentStore;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 一个**还没发出去**的附件长什么样。
 *
 * <p>它有两个读者，而它们必须看到同一个形状：状态表（刷新之后那条缩略条还在，而不是变成一张看不见、却会跟着
 * 下一句话发出去的图片）与事件流（描述好了的那一刻，页面立刻把它画出来）。两个地方各写一份，就等于两份「待发
 * 附件」的定义。
 *
 * <p>名字里的「附件」比「图片」宽，是因为图片只是第一种走通这条路的文件——日志、PDF、一张巨大的表格符合同一
 * 个形状：独立模型 → 文本描述 → 描述进对话，文件留在会话旁边。
 */
final class AttachmentViews {

  private AttachmentViews() {}

  /** 描述好了但还没发出去的那一个：名字、路径、类型、描述。 */
  static ObjectNode pending(AttachmentStore.Saved saved, String description, String mediaType) {
    return Json.object()
        .put("name", saved.path().getFileName().toString())
        .put("file", saved.path().toString())
        .put("mediaType", mediaType)
        .put("description", description);
  }
}
