package com.ccj.agent.eval;

import com.ccj.agent.core.AgentListener;
import com.ccj.agent.core.AgentLoop;
import com.ccj.agent.core.AgentOptions;
import com.ccj.agent.core.Approver;
import com.ccj.agent.core.MemorySession;
import com.ccj.agent.core.Message;
import com.ccj.agent.core.Provider;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.obs.Metric;
import com.ccj.agent.obs.Metrics;
import com.ccj.agent.obs.Recorder;
import com.ccj.agent.tool.Tools;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 把一组任务跑一遍，交出每个任务的结果。
 *
 * <p>每个任务拿到一个**空目录**：任务自己那些文件写进去，工具的相对路径以它为基准，跑完就删掉。所以一个
 * 任务没法看见另一个任务的残留，也没法看见这个仓库——「它刚才还好好的」因此不是一个可能的解释。
 *
 * <p>每一步都是真的：真的循环、真的工具注册表、真的文件系统、真的审批（默认全批准），只有模型那一侧可以是
 * 一段写下来的剧本。这正是它作为「回归门」有用的原因——它钉住的是这个工具的行为，而不是一段文本。
 *
 * <p>数字来自 {@link Recorder}，与 web 控制台上那些数字是同一套：评测报告里的 token 与一个会话面板里的
 * token 由此不可能对不上，因为它们本来就是同一个计数器的两次读取。
 */
public final class EvalRunner {

  /** 工具批量输出的上限，与配置里的默认值一致：评测不该因为一个上限更松而通过。 */
  private static final int OUTPUT_LIMIT_BYTES = 32 * 1024;

  private EvalRunner() {}

  /**
   * 跑一遍。
   *
   * @param model 没有剧本的那些任务用它；一个都不需要时为 null
   * @throws IllegalArgumentException 有任务需要模型而没给
   */
  public static EvalReport run(List<EvalTask> tasks, Provider model) {
    List<String> needModel =
        tasks.stream().filter(EvalTask::needsAModel).map(EvalTask::id).toList();
    if (!needModel.isEmpty() && model == null) {
      throw new IllegalArgumentException(
          "这些任务没有剧本，需要一个模型来跑：" + String.join("、", needModel) + "（用 --eval 时配好模型，或给它们写一段 script）");
    }
    List<EvalReport.TaskResult> results = new ArrayList<>(tasks.size());
    for (EvalTask task : tasks) {
      results.add(runOne(task, model));
    }
    return new EvalReport(results);
  }

  private static EvalReport.TaskResult runOne(EvalTask task, Provider model) {
    Path directory = null;
    try {
      directory = Files.createTempDirectory("ccj-eval-" + safe(task.id()) + "-");
      for (Map.Entry<String, String> file : task.files().entrySet()) {
        Path target = inside(directory, file.getKey());
        if (target.getParent() != null) {
          Files.createDirectories(target.getParent());
        }
        Files.writeString(target, file.getValue(), StandardCharsets.UTF_8);
      }
      Provider provider = task.needsAModel() ? model : new ScriptedModel(task.script());
      Metrics metrics = new Metrics();
      MemorySession session = new MemorySession(task.id());
      ToolContext context =
          new ToolContext(
              directory, task.approve() ? Approver.ALWAYS : Approver.NEVER, OUTPUT_LIMIT_BYTES);
      AgentLoop loop =
          new AgentLoop(
              provider,
              Tools.standard(),
              session,
              AgentOptions.defaults(),
              context,
              new Recorder(metrics, task.id(), null, AgentListener.NOOP));

      AgentLoop.Result result = null;
      Throwable failure = null;
      long started = System.nanoTime();
      try {
        result = loop.run(task.prompt());
      } catch (RuntimeException | Error e) {
        // 一个抛出异常的运行要按失败报，而不是让整套评测挂掉：一份报告里有十九条通过、一条「运行结束于
        // 异常：…」，比一次在第一行就停下的运行有用得多。
        failure = e;
      }
      long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
      List<String> failures = check(task, result, failure, session.messages(), directory);
      return new EvalReport.TaskResult(
          task.id(),
          task.description(),
          failures.isEmpty(),
          failures,
          result == null ? 0 : result.steps(),
          (int) metrics.total(Metric.TOOL_CALLS),
          (long) metrics.value(Metric.TOKENS, "direction", "input"),
          (long) metrics.value(Metric.TOKENS, "direction", "output"),
          tokenOrNull(metrics, "cached"),
          elapsedMillis,
          task.needsAModel() ? "model" : "scripted",
          result == null ? "" : result.finalText());
    } catch (IOException e) {
      throw new UncheckedIOException("评测任务 " + task.id() + " 无法准备它的目录", e);
    } finally {
      delete(directory);
    }
  }

  /** 这个任务要求看到的结果，逐条对一遍；返回没对上的那些，一条一行。 */
  private static List<String> check(
      EvalTask task,
      AgentLoop.Result result,
      Throwable failure,
      List<Message> messages,
      Path directory) {
    List<String> failures = new ArrayList<>();
    if (failure != null) {
      failures.add("运行以异常结束：" + message(failure));
      // 结果断言没有结果可以对，而继续往下走会产出「文件内容不对」这种次要的结论，掩盖真正的原因。
      return failures;
    }
    if (result.aborted()) {
      failures.add("这次运行被中止了，但它不该被中止");
    }
    task.expect().files().forEach((path, expected) -> {
      Path target = inside(directory, path);
      if (!Files.exists(target)) {
        failures.add(path + " 没有被建出来");
        return;
      }
      try {
        String actual = Files.readString(target, StandardCharsets.UTF_8);
        if (!actual.equals(expected)) {
          failures.add(path + " 的内容不对：期待 " + quote(expected) + "，实际是 " + quote(actual));
        }
      } catch (IOException e) {
        failures.add(path + " 无法读取：" + message(e));
      }
    });
    for (String path : task.expect().absent()) {
      if (Files.exists(inside(directory, path))) {
        failures.add(path + " 本来不该存在，但它被建出来了");
      }
    }
    for (String needle : task.expect().finalTextContains()) {
      if (!result.finalText().contains(needle)) {
        failures.add("最后的回答里没有 " + quote(needle) + "：" + quote(result.finalText()));
      }
    }
    if (task.expect().tools() != null) {
      List<String> actual = requestedTools(messages);
      if (!actual.equals(task.expect().tools())) {
        failures.add(
            "工具调用序列不对：期待 " + task.expect().tools() + "，实际是 " + actual);
      }
    }
    Integer maxSteps = task.expect().maxSteps();
    if (maxSteps != null && result.steps() > maxSteps) {
      failures.add("用了 " + result.steps() + " 步，超过了这个任务允许的 " + maxSteps);
    }
    return failures;
  }

  /** 模型要求过的工具名，按它在对话里出现的顺序，重复的也留着。 */
  private static List<String> requestedTools(List<Message> messages) {
    List<String> names = new ArrayList<>();
    for (Message message : messages) {
      if (message instanceof Message.Assistant assistant) {
        for (Message.ToolCall call : assistant.toolCalls()) {
          names.add(call.name());
        }
      }
    }
    return names;
  }

  private static Integer tokenOrNull(Metrics metrics, String direction) {
    // 「没有信息」与「零」是两件事，而报告里那一栏也一样：一个从不报缓存的模型，不该被画成一个 0 命中率。
    long counted = (long) metrics.value(Metric.TOKENS, "direction", direction);
    return counted == 0 ? null : (int) counted;
  }

  /**
   * 任务里的路径解析到工作目录之下。
   *
   * <p>任务是代码，而这个仓库对「一个文件名可以走去哪里」一贯是检查而不是假定：一个写着 {@code ../} 的
   * 任务会在别人的机器上写到临时目录之外，而那是这一整套里唯一一处会碰到真实文件系统的地方。
   */
  private static Path inside(Path directory, String path) {
    Path resolved = directory.resolve(path).normalize();
    if (!resolved.startsWith(directory)) {
      throw new IllegalArgumentException("评测任务里的路径跑出了它的工作目录：" + path);
    }
    return resolved;
  }

  private static String safe(String id) {
    return id.replaceAll("[^A-Za-z0-9._-]", "-");
  }

  private static String quote(String text) {
    String flat = text == null ? "" : text.replace("\n", "\\n");
    return "\"" + (flat.length() > 120 ? flat.substring(0, 120) + "…" : flat) + "\"";
  }

  private static String message(Throwable e) {
    String message = e.getMessage();
    return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
  }

  private static void delete(Path directory) {
    if (directory == null || !Files.exists(directory)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(directory)) {
      for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    } catch (IOException ignored) {
      // 一个临时目录没删掉，是这次运行里最不值得报警的一件事。
    }
  }
}
