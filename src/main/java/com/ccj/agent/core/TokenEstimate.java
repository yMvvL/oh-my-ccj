package com.ccj.agent.core;

import java.util.List;

/**
 * A cheap estimate of what a conversation costs in tokens, for budgeting rather than billing.
 *
 * <p>It is a heuristic and says so: every provider tokenises differently, and an exact count means a
 * round trip to a tokeniser this runtime deliberately does not ship. What it has to get right is the
 * shape — code, JSON and English at roughly four characters per token; CJK, Hangul, kana and emoji at
 * roughly one — because an estimate that is wrong by a factor of three would make the budget worse
 * than no budget at all.
 */
public final class TokenEstimate {

  /** Characters of ASCII-ish text per token, which is the rate BPE vocabularies settle around. */
  private static final int CHARS_PER_TOKEN = 4;

  /** Tokens a message costs before its content: role, delimiters, tool plumbing. */
  private static final int MESSAGE_OVERHEAD = 4;

  private TokenEstimate() {}

  /** Estimated tokens for a string, never zero for a non-empty one. */
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
        wide++; // an emoji: one token, two chars
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

  /** Estimated tokens for one message, including what the wire format adds around it. */
  public static int of(Message message) {
    if (message == null) {
      return 0;
    }
    return switch (message) {
      case Message.System system -> MESSAGE_OVERHEAD + of(system.text());
      case Message.User user -> MESSAGE_OVERHEAD + of(user.text());
      case Message.ToolResult result ->
          MESSAGE_OVERHEAD + of(result.content()) + of(result.toolName()) + of(result.toolCallId());
      case Message.Assistant assistant -> {
        int tokens = MESSAGE_OVERHEAD + of(assistant.text());
        for (Message.Thinking block : assistant.thinking()) {
          // `data` is the whole payload of a redacted block, and a block goes back on the wire
          // exactly as it arrived — counting only text and signature would budget a long redacted
          // conversation as if the blocks were empty.
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

  /** Estimated tokens for a whole conversation. */
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

  /** True for the scripts whose characters are worth about a token each. */
  private static boolean isWide(char c) {
    return (c >= 0x3040 && c <= 0x30FF) // hiragana, katakana
        || (c >= 0x3400 && c <= 0x4DBF) // CJK extension A
        || (c >= 0x4E00 && c <= 0x9FFF) // CJK unified ideographs
        || (c >= 0x3000 && c <= 0x303F) // CJK punctuation
        || (c >= 0xAC00 && c <= 0xD7AF) // hangul syllables
        || (c >= 0xF900 && c <= 0xFAFF) // CJK compatibility ideographs
        || (c >= 0xFF00 && c <= 0xFF60); // fullwidth forms
  }
}
