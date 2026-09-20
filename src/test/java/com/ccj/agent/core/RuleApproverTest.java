package com.ccj.agent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
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
    assertTrue(
        ApprovalAnswer.DENY_BY_RULE.refusal().contains("approvals.json"),
        "被规则挡下的理由要指出规则在哪个文件里：" + ApprovalAnswer.DENY_BY_RULE.refusal());
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

  @Test
  void theFifthAnswerRefusesTheNextSameCallByRuleWithoutAskingAgain() throws IOException {
    // 「以后都拒绝」得和「永远允许」一样熬过这个进程，否则被一个反复出现的提示烦到的人只剩下把整个会话的
    // 审批关掉这一条路——而那正是这道关卡要防的事。
    ApprovalRules rules = rules("{\"projects\": {\"%s\": {}}}");
    Person askedOnce = new Person(ApprovalAnswer.DENY_ALWAYS);
    List<String> announced = new ArrayList<>();
    RuleApprover chain = new RuleApprover(rules, askedOnce, announced::add);

    assertEquals(ApprovalAnswer.DENY_ALWAYS, chain.approve(cmd("make check")));
    assertEquals(1, askedOnce.asked.size(), "这一次是问人问出来的");
    assertEquals(
        List.of("今后一直拒绝，规则写入 " + file + " — bash: make check"),
        announced,
        "写下规则这件事要留痕，痕迹里要有文件的位置");

    // 同一个文件上的另一条链：这就是「下一个进程」。
    Person askedAgain = new Person(ApprovalAnswer.ALLOW_ONCE);
    List<String> second = new ArrayList<>();
    RuleApprover reopened =
        new RuleApprover(ApprovalRules.open(file, project), askedAgain, second::add);

    assertEquals(ApprovalAnswer.DENY_BY_RULE, reopened.approve(cmd("make check")));
    assertTrue(askedAgain.asked.isEmpty(), "规则作答了，所以这一次没有问人");
    assertEquals(1, second.size());
    assertTrue(
        second.get(0).contains(file.toString()), "挡下它的理由要说清规则在哪个文件里：" + second);
    assertTrue(
        ApprovalAnswer.DENY_ALWAYS.refusal().contains("approvals.json"),
        "这一次的拒绝理由要指出规则被写进了哪里：" + ApprovalAnswer.DENY_ALWAYS.refusal());

    // 而那条规则只说这一条命令、这一个工具：别的命令与别的工具照样得问人。
    assertEquals(ApprovalAnswer.ALLOW_ONCE, reopened.approve(cmd("make check --verbose")));
    assertEquals(
        ApprovalAnswer.ALLOW_ONCE,
        reopened.approve(ApprovalRequest.file("write", project.resolve("notes.txt"), "写文件")));
    assertEquals(2, askedAgain.asked.size(), "两件不相干的事都不受那条规则影响");
  }

  @Test
  void denyAlwaysWritesTheSameSubjectAllowAlwaysWouldHaveWritten() throws IOException {
    // 两个答复回答的是同一个问题，所以文件里记下的主语必须一模一样。一条比「永远允许」写得更宽的拒绝规则，
    // 挡下的就不止用户眼前这件事。
    rules("{\"projects\": {\"%s\": {}}}"); // 先有项目目录，才写得出指向它的请求
    List<ApprovalRequest> requests =
        List.of(
            cmd("mvn -q -o test"),
            ApprovalRequest.file("write", project.resolve("src/App.java"), "改文件"));
    for (ApprovalRequest request : requests) {
      ApprovalRules forAllow = rules("{\"projects\": {\"%s\": {}}}");
      new RuleApprover(forAllow, new Person(ApprovalAnswer.ALLOW_ALWAYS), text -> {})
          .approve(request);
      JsonNode allowed = writtenRule(file, "allow");

      ApprovalRules forDeny = rules("{\"projects\": {\"%s\": {}}}");
      new RuleApprover(forDeny, new Person(ApprovalAnswer.DENY_ALWAYS), text -> {})
          .approve(request);
      JsonNode denied = writtenRule(file, "deny");

      assertEquals(allowed, denied, "两个答复写下的主语必须是同一条：" + request.summary());
      assertFalse(
          Files.readString(file).contains("\"allow\""),
          "拒绝只写拒绝列表，没有顺手放行什么：" + Files.readString(file));
    }
  }

  /** {@code file} 里那个项目唯一的一条 {@code kind} 规则。 */
  private static JsonNode writtenRule(Path file, String kind) throws IOException {
    String written = Files.readString(file);
    JsonNode rules = Json.parse(written).get("projects").fields().next().getValue().path(kind);
    assertEquals(1, rules.size(), "文件里没有一条 " + kind + " 规则：" + written);
    return rules.get(0);
  }
}
