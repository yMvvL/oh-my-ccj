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
 * The chain: rules first, the person second, and each answer recorded where it belongs.
 *
 * <p>Driven with a stub delegate rather than a browser, because what is under test is the order and
 * the bookkeeping between the two — which is where the interesting mistakes are. The one that got
 * away once: a rule written for a command containing a redirect could never match, so "allow for
 * this session" silently did nothing for exactly the commands people most want to stop being asked
 * about, and the test that caught it was a chain test rather than a matching test.
 */
class RuleApproverTest {

  @TempDir Path tmp;

  private Path project;
  private Path file;

  /** A delegate that answers from a script and records what it was asked. */
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
    assertTrue(person.asked.isEmpty(), "a rule that answers means nobody is asked");
    assertEquals(List.of("allowed by rule — bash: mvn -q -o test"), announced);
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
    assertEquals("denied by a rule in the approvals file (see ccj --help and SECURITY.md)",
        ApprovalAnswer.DENY_BY_RULE.refusal());
    assertEquals("rejected by user", ApprovalAnswer.DENY.refusal());
  }

  @Test
  void withNoRuleThePersonIsAskedAndTheAnswerIsRecordedWhereItBelongs() throws IOException {
    // "For this session" is memory, "always" is a file write, and neither is "yes, once" — which is
    // the three-way distinction the boolean this replaced could not make.
    ApprovalRules rules = rules("{\"projects\": {\"%s\": {}}}");
    Person once = new Person(ApprovalAnswer.ALLOW_ONCE);
    List<String> announced = new ArrayList<>();
    RuleApprover chain = new RuleApprover(rules, once, announced::add);

    assertEquals(ApprovalAnswer.ALLOW_ONCE, chain.approve(cmd("printf a > one.txt")));
    assertEquals(1, once.asked.size());
    assertEquals(ApprovalAnswer.ALLOW_ONCE, chain.approve(cmd("printf a > one.txt")), "asked again");
    assertEquals(2, once.asked.size());
    assertTrue(announced.isEmpty(), "a person answering is not an automatic decision to report");
    assertFalse(Files.readString(file).contains("printf"), "and nothing was written down");

    // The same command, answered "session": remembered, and not written to the file.
    Person session = new Person(ApprovalAnswer.ALLOW_SESSION);
    RuleApprover remembering = new RuleApprover(rules, session, announced::add);
    assertEquals(ApprovalAnswer.ALLOW_SESSION, remembering.approve(cmd("printf a > one.txt")));
    assertEquals(ApprovalAnswer.ALLOW_ONCE, remembering.approve(cmd("printf a > one.txt")));
    assertEquals(1, session.asked.size(), "the second time is answered by what was remembered");
    assertFalse(Files.readString(file).contains("printf"), "a session allow stays in memory");

    // And "always" is the one that outlives the process.
    Person always = new Person(ApprovalAnswer.ALLOW_ALWAYS);
    RuleApprover writing = new RuleApprover(rules, always, announced::add);
    assertEquals(ApprovalAnswer.ALLOW_ALWAYS, writing.approve(cmd("make check")));

    assertTrue(Files.readString(file).contains("make check"), Files.readString(file));
    assertEquals(
        ApprovalAnswer.ALLOW_ONCE,
        new RuleApprover(rules, new Person(ApprovalAnswer.DENY), text -> {})
            .approve(cmd("make check")),
        "a fresh chain over the same file answers from it");
    assertTrue(announced.stream().anyMatch(text -> text.startsWith("allowed for this session")));
    assertTrue(announced.stream().anyMatch(text -> text.contains("allowed from now on")));
  }

  @Test
  void aRulesFileThatCannotBeReadAsksRatherThanAllowing() throws IOException {
    // Falling back to "no rules" would turn a typo into an open gate: the user believes they wrote
    // rules, and the tool behaves as though they had written none.
    rules("{\"projects\": {\"%s\": {\"allow\": \"not an array\"}}}");
    Person person = new Person(ApprovalAnswer.DENY);
    List<String> announced = new ArrayList<>();
    RuleApprover chain = new RuleApprover(ApprovalRules.open(file, project), person, announced::add);

    assertEquals(ApprovalAnswer.DENY, chain.approve(cmd("ls")));
    assertEquals(1, person.asked.size(), "the person is still the one who decides");
    assertTrue(announced.get(0).contains("could not be read"), announced.toString());
  }
}
