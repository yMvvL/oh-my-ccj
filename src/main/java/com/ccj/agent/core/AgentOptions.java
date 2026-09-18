package com.ccj.agent.core;

/**
 * 代理循环本身在意的旋钮。
 *
 * <p>对每次用户输入所触发的模型回合数，刻意不设上限。回合结束于模型不再要求调用工具，或者有人中止它——
 * 没有别的结束方式。上限分辨不出一个陷在车辙里的模型和一个正在啃长任务的模型，而为了惩罚前者去砍掉后者的
 * 那个数字比没有数字更糟：它会让一场本可成功的运行失败，且失败点随机得与工作毫无关系。要停下来请用
 * {@link AgentLoop#abort()}。
 *
 * @param model 提供方特定的模型标识符
 * @param system 系统提示词，null 表示使用 {@link Prompts#DEFAULT_SYSTEM}
 */
public record AgentOptions(
    String model,
    String system,
    Double temperature,
    Integer maxTokens,
    String reasoning,
    Integer maxContextTokens) {

  /** 提示词预算出现之前的旋钮形态：没有预算。 */
  public AgentOptions(
      String model, String system, Double temperature, Integer maxTokens, String reasoning) {
    this(model, system, temperature, maxTokens, reasoning, null);
  }

  public static AgentOptions defaults() {
    return new AgentOptions(null, null, null, null, null, null);
  }

  /** 模型应投入的努力档位，null 表示交给提供方决定。 */
  public String reasoning() {
    return reasoning;
  }

  /**
   * 提示词预算，以估算 token 计；0 表示「把整段对话都发出去」。
   *
   * <p>默认不设置是刻意的：把模型的上下文窗口猜错比不猜更糟，而真正算数的数字是用户花钱买的那个。
   */
  public int contextBudget() {
    return maxContextTokens == null || maxContextTokens <= 0 ? 0 : maxContextTokens;
  }

  public String systemPrompt() {
    return system == null || system.isBlank() ? Prompts.DEFAULT_SYSTEM : system;
  }
}
