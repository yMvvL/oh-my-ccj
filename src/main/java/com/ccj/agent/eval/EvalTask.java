package com.ccj.agent.eval;

import com.ccj.agent.core.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 一个评测任务：给它一个空目录和一句话，看它做出来的东西对不对。
 *
 * <p>格式是每行一个 JSON 对象的 JSONL，与这个仓库的会话文件同一条规矩：能用普通工具读、能 diff、能 grep，
 * 一行坏了不会把整份文件带走。
 *
 * <pre>
 * {"id": "read-and-answer",
 *  "description": "读一个文件并回答问题",
 *  "prompt": "读 notes.txt，告诉我它说了什么",
 *  "files": {"notes.txt": "hello\n"},
 *  "script": [{"tool": {"name": "read", "arguments": {"path": "notes.txt"}}},
 *             {"text": "它说的是 hello。"}],
 *  "expect": {"finalTextContains": ["hello"], "tools": ["read"], "maxSteps": 3}}
 * </pre>
 *
 * <p>有 {@code script} 的任务是离线的、可重复的：模型那一侧的每一步都被写下来了。没有剧本的任务要一个真
 * 模型，而它的断言也必须更宽——一个真模型不会两次走出同一条路，所以那些任务通常只断言结果，不断言过程。
 *
 * <p>{@code approve} 默认是 true，也就是这一整套都跑在「什么都批准」下面。<strong>这是一句必须写在明面上
 * 的话：评测任务会真的跑工具，包括 shell。</strong>它们跑在一个临时目录里，而它们不该被用来放任何你没看过
 * 的命令——与这个工具的任何其它部分一样，护栏是审批，而这里为了跑得动，审批是关掉的。
 */
public record EvalTask(
    String id, String description, String prompt, Map<String, String> files, List<ScriptedModel.Step> script,
    boolean approve, Expect expect) {

  public EvalTask {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("评测任务需要一个 id");
    }
    description = description == null ? "" : description;
    prompt = prompt == null ? "" : prompt;
    files = files == null ? Map.of() : Map.copyOf(files);
    script = script == null ? List.of() : List.copyOf(script);
    expect = expect == null ? Expect.none() : expect;
  }

  /** 一个任务要求看到的结果。每一项都可以不写，不写就是不检查它。 */
  public record Expect(
      Map<String, String> files,
      List<String> absent,
      List<String> finalTextContains,
      List<String> tools,
      Integer maxSteps) {

    public static Expect none() {
      return new Expect(Map.of(), List.of(), List.of(), null, null);
    }

    /**
     * 从 {@code expect} 那一块读出来。
     *
     * <p>{@code tools} 与「不写」是两件事：不写是不检查调用序列，写一个空数组是要求<em>一次工具也不调</em>
     * ——一个只该开口回答的任务，正是后者。
     */
    static Expect fromJson(JsonNode node) {
      Map<String, String> files = new LinkedHashMap<>();
      JsonNode expectedFiles = node.path("files");
      if (expectedFiles.isObject()) {
        expectedFiles
            .fields()
            .forEachRemaining(entry -> files.put(entry.getKey(), entry.getValue().asText("")));
      }
      List<String> absent = new ArrayList<>();
      node.path("absent").forEach(entry -> absent.add(entry.asText()));
      List<String> contains = new ArrayList<>();
      node.path("finalTextContains").forEach(entry -> contains.add(entry.asText()));
      List<String> tools = null;
      if (node.path("tools").isArray()) {
        tools = new ArrayList<>();
        for (JsonNode entry : node.path("tools")) {
          tools.add(entry.asText());
        }
      }
      Integer maxSteps = node.path("maxSteps").isInt() ? node.path("maxSteps").asInt() : null;
      return new Expect(files, absent, contains, tools, maxSteps);
    }
  }

  /** 从一行 JSON 读一个任务。 */
  public static EvalTask fromJson(String line) {
    JsonNode node = Json.parse(line);
    Map<String, String> files = new LinkedHashMap<>();
    JsonNode initial = node.path("files");
    if (initial.isObject()) {
      initial.fieldNames().forEachRemaining(name -> files.put(name, initial.path(name).asText("")));
    }
    List<ScriptedModel.Step> script = new ArrayList<>();
    JsonNode steps = node.path("script");
    for (int i = 0; i < steps.size(); i++) {
      script.add(ScriptedModel.Step.fromJson(steps.get(i), i));
    }
    return new EvalTask(
        node.path("id").asText(""),
        node.path("description").asText(""),
        node.path("prompt").asText(""),
        files,
        script,
        !node.has("approve") || node.path("approve").asBoolean(true),
        Expect.fromJson(node.path("expect")));
  }

  /** {@code path} 是目录时，读出里面每一个 {@code *.jsonl}；是文件时，只读它。 */
  public static List<EvalTask> read(Path path) {
    if (Files.isDirectory(path)) {
      List<Path> files;
      try (Stream<Path> entries = Files.list(path)) {
        files = entries.filter(entry -> entry.getFileName().toString().endsWith(".jsonl")).sorted().toList();
      } catch (IOException e) {
        throw new UncheckedIOException("无法列出评测目录 " + path, e);
      }
      if (files.isEmpty()) {
        throw new IllegalArgumentException("评测目录 " + path + " 里没有任何 .jsonl");
      }
      List<EvalTask> tasks = new ArrayList<>();
      for (Path file : files) {
        tasks.addAll(readOne(file));
      }
      return tasks;
    }
    return readOne(path);
  }

  private static List<EvalTask> readOne(Path file) {
    List<String> lines;
    try {
      lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("无法读取评测文件 " + file, e);
    }
    List<EvalTask> tasks = new ArrayList<>();
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      if (line.isBlank() || line.stripLeading().startsWith("#")) {
        continue;
      }
      try {
        tasks.add(fromJson(line));
      } catch (RuntimeException e) {
        // 文件加行号：一份坏掉的评测与一份坏掉的会话文件一样，读者需要知道去哪一行看。
        throw new IllegalArgumentException(file + ":" + (i + 1) + ": " + e.getMessage(), e);
      }
    }
    return tasks;
  }

  /** 这个任务要一个真模型（它没有剧本）。 */
  public boolean needsAModel() {
    return script.isEmpty();
  }
}
