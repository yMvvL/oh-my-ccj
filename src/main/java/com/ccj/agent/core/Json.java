package com.ccj.agent.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Single entry point for JSON handling.
 *
 * <p>Everything that touches JSON goes through this class so the rest of the code base never
 * has to think about {@link ObjectMapper} configuration or checked exceptions.
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

  /** Parses {@code text}; blank input yields an empty object so tools can treat it as "no args". */
  public static JsonNode parse(String text) {
    if (text == null || text.isBlank()) {
      return MAPPER.createObjectNode();
    }
    try {
      return MAPPER.readTree(text);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("invalid JSON: " + e.getOriginalMessage(), e);
    }
  }

  public static String write(JsonNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("failed to serialize JSON", e);
    }
  }

  public static String writePretty(JsonNode node) {
    try {
      return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("failed to serialize JSON", e);
    }
  }
}
