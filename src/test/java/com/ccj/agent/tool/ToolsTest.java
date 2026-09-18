package com.ccj.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Json;
import com.ccj.agent.core.Message;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolRegistry;
import com.ccj.agent.core.ToolResult;
import com.ccj.agent.core.ToolSpec;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolsTest {

  @TempDir Path dir;

  @Test
  void registersTheStandardToolsInAdvertisedOrder() {
    assertEquals(
        List.of("read", "write", "edit", "bash", "glob", "grep", "fetch", "restart"),
        Tools.standard().names());
  }

  @Test
  void everyToolAdvertisesANamedObjectSchema() {
    for (ToolSpec spec : Tools.standard().specs()) {
      assertFalse(spec.description().isBlank(), spec.name());
      assertEquals("object", Json.parse(spec.parametersJson()).path("type").asText(), spec.name());
      assertTrue(Json.parse(spec.parametersJson()).has("properties"), spec.name());
    }
  }

  @Test
  void registryTurnsBadArgumentsIntoErrorResults() {
    ToolRegistry registry = Tools.standard();

    ToolResult missing = registry.execute(new Message.ToolCall("1", "read", "{}"), ToolContext.of(dir));
    ToolResult malformed =
        registry.execute(new Message.ToolCall("2", "glob", "{\"pattern\":"), ToolContext.of(dir));
    ToolResult unknown =
        registry.execute(new Message.ToolCall("3", "nope", "{}"), ToolContext.of(dir));

    assertTrue(missing.error(), missing.content());
    assertTrue(missing.content().contains("缺少必需参数 'path'"), missing.content());
    assertTrue(malformed.error(), malformed.content());
    assertTrue(malformed.content().contains("JSON 无效"), malformed.content());
    assertTrue(unknown.error(), unknown.content());
    assertTrue(unknown.content().contains("未知工具 'nope'"), unknown.content());
  }
}
