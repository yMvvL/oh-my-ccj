package com.ccj.agent.web;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 表单与用户之间的那层翻译：字段怎么读，异常怎么说成人话。
 *
 * <p>规则只有一条，而它值一个文件：**空文本与缺失是一回事**（都是 null），而类型不对是一个错误，不是 null。一个
 * 把 `"n": "12"` 悄悄当成 12 的表单，会让人以为自己改了什么，实际上什么都没改。
 *
 * <p>它从前是 {@code AgentHub} 的私有静态方法；表单往返搬去 {@link HubForms} 的时候，{@link SettingsFiles} 用
 * 的还是同一条规则——两个地方各留一份，就等于两份「什么算空」的定义。所以它单独成文件。
 */
final class FormFields {

  private FormFields() {}

  static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw new IllegalArgumentException("字段 '" + field + "' 必须是字符串");
    }
    String text = value.asText().strip();
    return text.isEmpty() ? null : text;
  }

  static Integer integer(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isIntegralNumber()) {
      throw new IllegalArgumentException("字段 '" + field + "' 必须是整数");
    }
    return value.asInt();
  }

  static Double number(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isNumber()) {
      throw new IllegalArgumentException("字段 '" + field + "' 必须是数字");
    }
    return value.asDouble();
  }

  /**
   * 一个异常说成给用户看的一句话。
   *
   * <p>异常自己没说的话，就用类名——空的消息比类名更难查，而「刚才那步失败了」加上一个类名，至少指得出哪里。
   */
  static String message(Throwable e) {
    String message = e.getMessage();
    return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
  }
}
