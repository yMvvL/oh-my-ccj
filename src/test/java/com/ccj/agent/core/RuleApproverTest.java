package com.ccj.agent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 这条链：规则优先，其次是人，而每个答案都会被记录到它该在的地方。
 *
 * <p>用桩委托驱动，而不是浏览器，因为被测的是两者之间的顺序与簿记 —— 有趣的那些错误正在那里。
 * 曾经漏掉的那个：为一条含重定向的命令写的规则永远匹配不上，于是「本次会话内允许」恰恰对那些
 * 人们最不想再被问到的命令毫无作用，而抓到它的测试是一个链式测试，不是匹配测试。
 */
class RuleApproverTest {

  @TempDir Path tmp;

  private Path project;
  private Path file;

  /** 一个按脚本作答、并记录自己被问了什么的委托。 */
  private static final class Person implements Approver {
    private final List<ApprovalRequest> asked = new ArrayList<>();
    private final ApprovalAnswer answer;

    Person(ApprovalAnswer answer) {
      this.answer = answer;
    }

    @Override
    public ApprovalAnswer approve(ApprovalRequest request) {
      asked.add(request);
      return answer;
    }
  }

  private ApprovalRules rules(String json) throws IOException {
    project = Files.createDirectories(tmp.resolve("project"));
    file = tmp.resolve("approvals.json");
    Files.writeString(file, json.formatted(project));
    return ApprovalRules.open(file, project);
  }

  private static ApprovalRequest cmd(String command) {
    return ApprovalRequest.command(command, "the detail the prompt shows");
  }

  @Test
  void aRuleAnswersWithoutAskingThePersonAndSaysSo() throws IOException {
    List<String> announced = new ArrayList<>();
    Person person = new Person(ApprovalAnswer.DENY);
    RuleApprover chain =
        new RuleApprover(
            rules("{\"projects\": {\"%s\": {\"allow\": [{\"tool\": \"bash\", \"command\": \"mvn -q -o test\"}]}}}"),
            person,
            announced::add);

    assertEquals(ApprovalAnswer.ALLOW_ONCE, chain.approve(cmd("mvn -q -o test")));
    assertTrue(person.asked.isEmpty(), "一条能作答的规则意味着没有去问任何人");
    assertEquals(List.of("规则批准 — bash: mvn -q -o test"), announced);
  }

  @Test
  void aDenyRuleRefusesWithoutAskingAndIsNotAPersonSayingNo() throws IOException {
    Person person = new Person(ApprovalAnswer.ALLOW_ONCE);
    RuleApprover chain =
        new RuleApprover(
            rules("{\"projects\": {\"%s\": {\"deny\": [{\"tool\": \"bash\", \"command\": \"rm -rf /\"}]}}}"),
            person,
            text -> {});

    assertEquals(ApprovalAnswer.DENY_BY_RULE, chain.approve(cmd("rm -rf /")));
    assertTrue(person.asked.isEmpty());
    assertEquals("被审批文件中的某条规则拒绝（可运行 ccj --help、查看 SECURITY.md 了解如何改规则）",
        ApprovalAnswer.DENY_BY_RULE.refusal());
    assertEquals("被用户拒绝", ApprovalAnswer.DENY.refusal());
  }

  @Test
  void withNoRuleThePersonIsAskedAndTheAnswerIsRecordedWhereItBelongs() throws IOException {
    // 「本次会话内」是记忆，「始终」是一次文件写入，而两者都不是「就这一次，可以」—— 这正是它所
    // 替换掉的那个布尔值无法做出的三向区分。
    ApprovalRules rules = rules("{\"projects\": {\"%s\": {}}}");
    Person once = new Person(ApprovalAnswer.ALLOW_ONCE);
    List<String> announced = new ArrayList<>();
    RuleApprover chain = new RuleApprover(rules, once, announced::add);

    assertEquals(ApprovalAnswer.ALLOW_ONCE, chain.approve(cmd("printf a > one.txt")));
    assertEquals(1, once.asked.size());
    assertEquals(ApprovalAnswer.ALLOW_ONCE, chain.approve(cmd("printf a > one.txt")), "又被问了一次");
    assertEquals(2, once.asked.size());
    assertTrue(announced.isEmpty(), "由人来作答，就不是一个需要报告的自动决定");
    assertFalse(Files.readString(file).contains("printf"), "而且什么都没写下来");

    // 同一条命令，回答「本次会话」：被记住，且不写进文件。
    Person session = new Person(ApprovalAnswer.ALLOW_SESSION);
    RuleApprover remembering = new RuleApprover(rules, session, announced::add);
    assertEquals(ApprovalAnswer.ALLOW_SESSION, remembering.approve(cmd("printf a > one.txt")));
    assertEquals(ApprovalAnswer.ALLOW_ONCE, remembering.approve(cmd("printf a > one.txt")));
    assertEquals(1, session.asked.size(), "第二次由记住的答案来回应");
    assertFalse(Files.readString(file).contains("printf"), "会话内的允许留在内存里");

    // 而「始终」是唯一能活过这个进程的那个。
    Person always = new Person(ApprovalAnswer.ALLOW_ALWAYS);
    RuleApprover writing = new RuleApprover(rules, always, announced::add);
    assertEquals(ApprovalAnswer.ALLOW_ALWAYS, writing.approve(cmd("make check")));

    assertTrue(Files.readString(file).contains("make check"), Files.readString(file));
    assertEquals(
        ApprovalAnswer.ALLOW_ONCE,
        new RuleApprover(rules, new Person(ApprovalAnswer.DENY), text -> {})
            .approve(cmd("make check")),
        "一条基于同一文件的新链会从它那里得到答案");
    assertTrue(announced.stream().anyMatch(text -> text.startsWith("本会话内已批准")));
    assertTrue(announced.stream().anyMatch(text -> text.contains("今后一直批准，规则写入 ")));
  }

  @Test
  void aRulesFileThatCannotBeReadAsksRatherThanAllowing() throws IOException {
    // 退回到「没有规则」，会把一个错别字变成一扇敞开的门：用户以为自己写了规则，而工具的表现却
    // 像他一条都没写。
    rules("{\"projects\": {\"%s\": {\"allow\": \"not an array\"}}}");
    Person person = new Person(ApprovalAnswer.DENY);
    List<String> announced = new ArrayList<>();
    RuleApprover chain = new RuleApprover(ApprovalRules.open(file, project), person, announced::add);

    assertEquals(ApprovalAnswer.DENY, chain.approve(cmd("ls")));
    assertEquals(1, person.asked.size(), "做决定的仍然是那个人");
    assertTrue(announced.get(0).contains("审批文件无法读取"), announced.toString());
  }
}
