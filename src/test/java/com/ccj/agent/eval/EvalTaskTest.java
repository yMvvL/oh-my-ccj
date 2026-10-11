package com.ccj.agent.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 一行 JSON 变成一个任务：文件、剧本、以及要求看到的结果。
 *
 * <p>最要紧的一条是「不写」与「写了个空的」之间的区别：{@code tools} 不写是不检查调用序列，写了
 * {@code []} 是要求一次工具都不调。把两者读成同一件事，会让一整类任务悄悄地什么都不检查——而一个什么都
 * 不检查的任务，永远是通过的。
 */
class EvalTaskTest {

  @TempDir Path tmp;

  @Test
  void aLineBecomesATaskWithItsFilesScriptAndExpectations() {
    EvalTask task =
        EvalTask.fromJson(
            """
            {"id": "read-and-answer", "description": "读一个文件", "prompt": "读 notes.txt",
             "files": {"notes.txt": "hello\\n"},
             "script": [{"tool": {"name": "read", "arguments": {"path": "notes.txt"}}},
                        {"text": "它说的是 hello。", "usage": [700, 40, 512]}],
             "expect": {"files": {"out.txt": "done\\n"}, "absent": ["nope.txt"],
                        "finalTextContains": ["hello"], "tools": ["read"], "maxSteps": 3}}
            """);

    assertEquals("read-and-answer", task.id());
    assertEquals("读 notes.txt", task.prompt());
    assertEquals("hello\n", task.files().get("notes.txt"));
    assertTrue(task.approve(), "默认是全批准");
    assertFalse(task.needsAModel(), "有剧本就不需要模型");

    assertEquals(2, task.script().size());
    assertEquals("read", task.script().get(0).toolCalls().get(0).name());
    assertEquals("{\"path\":\"notes.txt\"}", task.script().get(0).toolCalls().get(0).arguments());
    assertEquals("call_1_1", task.script().get(0).toolCalls().get(0).id(), "id 是算出来的，不是随机的");
    assertEquals("它说的是 hello。", task.script().get(1).text());
    assertEquals(512, task.script().get(1).usage()[2]);

    assertEquals("done\n", task.expect().files().get("out.txt"));
    assertEquals(List.of("nope.txt"), task.expect().absent());
    assertEquals(List.of("hello"), task.expect().finalTextContains());
    assertEquals(List.of("read"), task.expect().tools());
    assertEquals(3, task.expect().maxSteps());
  }

  @Test
  void oneStepCanAskForSeveralToolsInOrder() {
    EvalTask task =
        EvalTask.fromJson(
            """
            {"id": "two", "prompt": "建两个文件",
             "script": [{"tools": [{"name": "write", "arguments": {"path": "a.txt", "content": "a\\n"}},
                                    {"name": "write", "arguments": {"path": "b.txt", "content": "b\\n"}}]},
                        {"text": "好了"}],
             "expect": {"tools": ["write", "write"]}}
            """);

    assertEquals(2, task.script().get(0).toolCalls().size());
    assertEquals("call_1_1", task.script().get(0).toolCalls().get(0).id());
    assertEquals("call_1_2", task.script().get(0).toolCalls().get(1).id());
    assertEquals(
        "b.txt",
        Json.parse(task.script().get(0).toolCalls().get(1).arguments()).path("path").asText());
  }

  @Test
  void anEmptyToolListMeansNoToolsRatherThanNotChecked() {
    EvalTask checked =
        EvalTask.fromJson("{\"id\": \"talk\", \"prompt\": \"hi\", \"expect\": {\"tools\": []}}");
    assertEquals(List.of(), checked.expect().tools(), "写了个空的，就是要求一次都不调");

    EvalTask unchecked = EvalTask.fromJson("{\"id\": \"talk\", \"prompt\": \"hi\"}");
    assertNull(unchecked.expect().tools(), "不写这一栏就是不检查它");
    assertNull(unchecked.expect().maxSteps());
  }

  @Test
  void aTaskCanSayItMustNotBeApproved() {
    EvalTask task = EvalTask.fromJson("{\"id\": \"x\", \"prompt\": \"p\", \"approve\": false}");
    assertFalse(task.approve());
  }

  @Test
  void commentsAndBlankLinesAreSkipped() throws IOException {
    Path file = tmp.resolve("suite.jsonl");
    Files.writeString(
        file,
        """
        # 这是给读的人看的，不是任务
        {"id": "first", "prompt": "a"}

        {"id": "second", "prompt": "b"}
        """);
    List<EvalTask> tasks = EvalTask.read(file);
    assertEquals(List.of("first", "second"), tasks.stream().map(EvalTask::id).toList());
  }

  @Test
  void aDirectoryIsReadAsEveryJsonlInsideIt() throws IOException {
    Files.writeString(tmp.resolve("one.jsonl"), "{\"id\": \"one\", \"prompt\": \"a\"}\n");
    Files.writeString(tmp.resolve("two.jsonl"), "{\"id\": \"two\", \"prompt\": \"b\"}\n");
    Files.writeString(tmp.resolve("notes.md"), "not a task\n");

    assertEquals(
        List.of("one", "two"),
        EvalTask.read(tmp).stream().map(EvalTask::id).toList().stream().sorted().toList());

    Path empty = Files.createDirectories(tmp.resolve("empty"));
    assertThrows(IllegalArgumentException.class, () -> EvalTask.read(empty));
  }

  @Test
  void aBadLineNamesTheFileAndTheLine() throws IOException {
    Path file = tmp.resolve("broken.jsonl");
    Files.writeString(file, "{\"id\": \"ok\", \"prompt\": \"a\"}\n{\"prompt\": \"no id\"}\n");

    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> EvalTask.read(file));
    assertTrue(failure.getMessage().contains("broken.jsonl:2"), failure.getMessage());
    assertTrue(failure.getMessage().contains("id"), failure.getMessage());
  }
}
