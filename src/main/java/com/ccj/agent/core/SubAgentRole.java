package com.ccj.agent.core;

import java.util.List;

/**
 * 子代理是做什么的，而这决定了它能碰什么。
 *
 * <p>由角色来挑选工具，而不是让调用方同时挑两者，因为这两件事是同一个决定：一个能顺手修好所发现问题的
 * 校验者就不是在校验，而一个能写入的探索者就是一个偷偷改动用户文件的隐藏代理。说清了角色，也就说清了边界。
 */
public enum SubAgentRole {

  /**
   * 查清事情：读、搜、顺着线索在代码库里一路追下去。
   *
   * <p>只读，所以它做的任何事都改变不了什么，也不需要征求同意。它是默认角色，因为它的价值不依赖信任——
   * 无论有没有人盯着，它省下的上下文都一样省下。
   */
  EXPLORE(
      "explore",
      "You investigate and report. You cannot change anything, so do not offer to: find the answer,"
          + " name the files and line numbers it lives at, and stop.",
      false),

  /**
   * 检查已经存在的工作：标准解法对暴力解法，实现对规格，断言对代码。
   *
   * <p>刻意与 {@link #EXPLORE} 用同一套工具——两者的区别在于提示要求它们做什么，而不在于它们能动什么。
   * 按能力把它们拆开，会凭空造出一条工作本身并不存在的约束。这个角色的要点在于它<em>报告</em>：一个悄悄
   * 修好所发现问题的校验者，等于用自己的工作替换了证据，也就没人知道那东西曾经错过。
   */
  VERIFY(
      "verify",
      "You check work that already exists and report what you find. You cannot change anything, and"
          + " you must not propose edits as if you had. When something is wrong, give the exact"
          + " input, the expected result and the actual one.",
      false),

  /**
   * 产出制品：代码、测试、数据、文档。
   *
   * <p>唯一会写入的角色。它的改动走与主代理相同的审批，因为一个不弹提示就能改文件的隐藏代理，正是这个
   * 功能绝不能变成的东西。
   */
  BUILD(
      "build",
      "You produce the artefact you were asked for, at final quality: complete, compiling, and"
          + " ready to be used as it stands. Write it into the working directory you were given, and"
          + " say in your report which files are finished work and which are intermediates you kept"
          + " only to work with.",
      true);

  private final String wireName;
  private final String instruction;
  private final boolean writes;

  SubAgentRole(String wireName, String instruction, boolean writes) {
    this.wireName = wireName;
    this.instruction = instruction;
    this.writes = writes;
  }

  /** 模型传给 {@code task} 工具的名字。 */
  public String wireName() {
    return wireName;
  }

  /** 告诉这个角色它自己是什么。 */
  public String instruction() {
    return instruction;
  }

  /** 唯一可以写入的角色为 true。 */
  public boolean writes() {
    return writes;
  }

  /** 该角色可用的工具名，按它们被提供的顺序。 */
  public List<String> toolNames() {
    return writes
        ? List.of("read", "glob", "grep", "write", "edit")
        : List.of("read", "glob", "grep");
  }

  /** 名字对应的角色；模型要了一个不存在的名字时为空。 */
  public static java.util.Optional<SubAgentRole> of(String name) {
    if (name == null) {
      return java.util.Optional.empty();
    }
    String wanted = name.strip().toLowerCase(java.util.Locale.ROOT);
    for (SubAgentRole role : values()) {
      if (role.wireName.equals(wanted)) {
        return java.util.Optional.of(role);
      }
    }
    return java.util.Optional.empty();
  }

  /** 提供给「要了别的东西」的模型的名字列表。 */
  public static String names() {
    StringBuilder out = new StringBuilder();
    for (SubAgentRole role : values()) {
      if (!out.isEmpty()) {
        out.append(", ");
      }
      out.append(role.wireName);
    }
    return out.toString();
  }
}
