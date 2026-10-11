package com.ccj.agent.eval;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 发布出去的这一套评测，跑一遍，全绿才通过。
 *
 * <p>这就是「回归门」这三个字的全部实现：它读的是仓库里那一份 {@code evals/}，用的是与命令行同一条路
 * （{@link EvalRunner}），所以命令行报绿与这里报绿是同一件事。一个只在人看着的时候跑的评测套件，和一个
 * 有断言的评测套件是两件事——而这一个是后者。
 *
 * <p>它同时钉住一条更安静的规矩：**这一份里的每个任务都必须是离线的**。一个需要模型的任务会让整套评测在
 * CI 里（没有密钥、没有网络）变成一次抽奖，而抽奖不是门。所以这里断言「一个需要模型的任务都没有」。
 */
class EvalSuiteTest {

  private static final Path SUITE = Path.of("evals");

  @Test
  void theShippedSuitePassesOffline() {
    List<EvalTask> tasks = EvalTask.read(SUITE);
    assertTrue(tasks.size() >= 8, "发布出去的这一套至少该有八个任务，实际 " + tasks.size());

    EvalReport report = EvalRunner.run(tasks, null);
    System.out.println(report.render());

    assertTrue(report.allPassed(), report.render());
    assertTrue(report.totalToolCalls() >= tasks.size(), "每个任务至少该用一次工具：" + report.render());
  }

  @Test
  void everyShippedTaskRunsWithoutAModel() {
    // 这一条与上面那条是两回事：上面那条在「今天没有需要模型的任务」时才通过，而这一条说的是「永远不该有」。
    List<String> needsAModel =
        EvalTask.read(SUITE).stream().filter(EvalTask::needsAModel).map(EvalTask::id).toList();
    assertTrue(
        needsAModel.isEmpty(),
        "这些任务需要一个模型，因此它们在 CI 里会失败：" + needsAModel + "——给它们写一段 script，或者把它们"
            + "放到单独的目录里，由人按需运行");
  }

  @Test
  void theSuiteIsReadableAsPlainLines() throws Exception {
    // 「每行一个 JSON 对象」是这个格式唯一的要求，而它值得被检查：一个换行写错的任务会让整份文件在
    // 第一个坏行上失败，而那种错在读的时候看不出来。
    List<Path> files;
    try (var entries = Files.list(SUITE)) {
      files = entries.filter(entry -> entry.getFileName().toString().endsWith(".jsonl")).toList();
    }
    assertFalse(files.isEmpty(), SUITE + " 里应当至少有一份任务文件");
    for (Path file : files) {
      for (String line : Files.readAllLines(file)) {
        if (line.isBlank() || line.stripLeading().startsWith("#")) {
          continue;
        }
        assertTrue(line.stripLeading().startsWith("{"), file + " 里这一行不是 JSON：" + line);
      }
    }
  }
}
