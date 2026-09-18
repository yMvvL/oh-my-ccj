package com.ccj.agent.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JSON 处理的唯一入口。
 *
 * <p>所有涉及 JSON 的地方都走这个类，好让代码库的其他部分再也不必操心 {@link ObjectMapper} 的配置或受检
 * 异常。
 */
public final class Json {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private Json() {}

  public static ObjectMapper mapper() {
    return MAPPER;
  }

  public static ObjectNode object() {
    return MAPPER.createObjectNode();
  }

  /** 解析 {@code text}；空白输入得到一个空对象，方便工具把它当作「没有参数」。 */
  public static JsonNode parse(String text) {
    if (text == null || text.isBlank()) {
      return MAPPER.createObjectNode();
    }
    try {
      return MAPPER.readTree(text);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("JSON 无效：" + e.getOriginalMessage(), e);
    }
  }

  public static String write(JsonNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("序列化 JSON 失败", e);
    }
  }

  public static String writePretty(JsonNode node) {
    try {
      return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("序列化 JSON 失败", e);
    }
  }
}
