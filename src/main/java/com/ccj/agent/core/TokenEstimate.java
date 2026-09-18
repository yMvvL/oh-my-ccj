package com.ccj.agent.core;

import java.util.List;

/**
 * 对一段对话要花多少 token 的廉价估算，用于做预算，而不是计费。
 *
 * <p>它是启发式的，这一点摆在明面上：每个提供方的分词方式都不同，而要精确计数就得来回调用一个本运行时刻意
 * 不带的分词器。它必须算对的是量级——代码、JSON 和英文大致每 token 四个字符；CJK、谚文、假名和 emoji 大致
 * 每 token 一个字符——因为一个错到三倍的估算会让预算比没有预算还糟。
 */
public final class TokenEstimate {

  /** 近似 ASCII 文本每 token 的字符数，这是 BPE 词表大致收敛到的比率。 */
  private static final int CHARS_PER_TOKEN = 4;

  /** 一条消息在内容之外的开销：角色、分隔符、工具的管道部分。 */
  private static final int MESSAGE_OVERHEAD = 4;

  private TokenEstimate() {}

  /** 一个字符串的估算 token 数；非空字符串永远不会是零。 */
  public static int of(String text) {
    if (text == null || text.isEmpty()) {
      return 0;
    }
    int ascii = 0;
    int wide = 0;
    int narrow = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c < 0x80) {
        ascii++;
        continue;
      }
      if (Character.isHighSurrogate(c)
          && i + 1 < text.length()
          && Character.isLowSurrogate(text.charAt(i + 1))) {
        wide++; // 一个 emoji：一个 token，两个字符
        i++;
        continue;
      }
      if (isWide(c)) {
        wide++;
      } else {
        narrow++;
      }
    }
    int tokens = (ascii + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN + wide + (narrow + 1) / 2;
    return Math.max(1, tokens);
  }

  /** 一条消息的估算 token 数，包含线路格式在它周围添加的部分。 */
  public static int of(Message message) {
    if (message == null) {
      return 0;
    }
    return switch (message) {
      case Message.System system -> MESSAGE_OVERHEAD + of(system.text());
      case Message.User user -> MESSAGE_OVERHEAD + of(user.text());
      case Message.Summary summary -> MESSAGE_OVERHEAD + of(summary.text());
      case Message.ToolResult result ->
          MESSAGE_OVERHEAD + of(result.content()) + of(result.toolName()) + of(result.toolCallId());
      case Message.Assistant assistant -> {
        int tokens = MESSAGE_OVERHEAD + of(assistant.text());
        for (Message.Thinking block : assistant.thinking()) {
          // `data` 是被涂改块的全部负载，而块会按它到达时的样子原样回到线上——只算 text 和 signature
          // 会把一段很长的被涂改对话按「块都是空的」来预算。
          tokens +=
              MESSAGE_OVERHEAD + of(block.text()) + of(block.signature()) + of(block.data());
        }
        for (Message.ToolCall call : assistant.toolCalls()) {
          tokens += MESSAGE_OVERHEAD + of(call.id()) + of(call.name()) + of(call.arguments());
        }
        yield tokens;
      }
    };
  }

  /** 整段对话的估算 token 数。 */
  public static int of(List<Message> messages) {
    if (messages == null || messages.isEmpty()) {
      return 0;
    }
    int tokens = 0;
    for (Message message : messages) {
      tokens += of(message);
    }
    return tokens;
  }

  /** 当该文字系统的字符大致各值一个 token 时为 true。 */
  private static boolean isWide(char c) {
    return (c >= 0x3040 && c <= 0x30FF) // 平假名、片假名
        || (c >= 0x3400 && c <= 0x4DBF) // CJK 扩展 A
        || (c >= 0x4E00 && c <= 0x9FFF) // CJK 统一表意文字
        || (c >= 0x3000 && c <= 0x303F) // CJK 标点
        || (c >= 0xAC00 && c <= 0xD7AF) // 谚文音节
        || (c >= 0xF900 && c <= 0xFAFF) // CJK 兼容表意文字
        || (c >= 0xFF00 && c <= 0xFF60); // 全角形式
  }
}
