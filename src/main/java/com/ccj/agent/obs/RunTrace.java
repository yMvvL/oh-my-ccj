package com.ccj.agent.obs;

import com.ccj.agent.core.Json;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 一次运行留下的痕迹：它属于哪个会话，以及那棵 span 树。
 *
 * <p>它是「一次运行」的完整记录，而不是「一个进程」的：几个对话可以同时跑，所以一份不带会话 id 的痕迹，
 * 在一台有第二个对话正在干活的机器上，就分不清自己说的是谁。
 */
public record RunTrace(String sessionId, Span run) {

  public RunTrace {
    sessionId = sessionId == null ? "" : sessionId;
  }

  /** 一行 JSON，正好够追加到一份 JSONL 里。 */
  public ObjectNode toJson() {
    ObjectNode node = Json.object();
    node.put("sessionId", sessionId);
    node.set("run", run.toJson());
    return node;
  }
}
