package com.ccj.agent.core;

import java.util.Map;

/**
 * 描述图片的那个模型的端点、凭据和模型名，以及它可写的回复上限。
 *
 * <p>刻意不用主提供方的。这是两个彼此独立的选择：读一张截图适合便宜快的模型，写代码才用贵的，而拍私人物品
 * 的照片适合本地模型。复用主提供方会把它们硬拧成一个决定。
 *
 * <p>每个字段都是可选的，而一个什么都没填的配置块不是配置，而是没有配置：{@link Config} 把它存为 null，
 * 于是「关闭」只有一种表示，不会出现两条代码路径对「它是否开着」意见不一。
 *
 * @param maxTokens 一次描述的补全预算，null 表示使用 {@link VisionClient#DEFAULT_MAX_TOKENS}。这是上限
 *     而不是开销——描述花多少取决于模型写了什么，与给了多大空间无关——所以给它命名，永远只是为了给推理
 *     模型留够想完再开口的余地。在一张信息密集的手机截图上实测：1500 token 全花在了推理上，描述压根没开始
 *     写；4096 完成了任务，用了 2882。
 */
public record VisionConfig(
    String baseUrl, String apiKey, String apiKeyEnv, String model, Integer maxTokens) {

  public VisionConfig {
    baseUrl = blankToNull(baseUrl);
    apiKey = blankToNull(apiKey);
    apiKeyEnv = blankToNull(apiKeyEnv);
    model = blankToNull(model);
  }

  /** 原配置块的三个字段，预算留默认值。 */
  public VisionConfig(String baseUrl, String apiKey, String apiKeyEnv, String model) {
    this(baseUrl, apiKey, apiKeyEnv, model, null);
  }

  /** 当什么都没填时为 true。 */
  public boolean isEmpty() {
    return baseUrl == null && apiKey == null && apiKeyEnv == null && model == null && maxTokens == null;
  }

  /**
   * 当这里点明了发起一次描述请求不可或缺的两个字段时为 true。
   *
   * <p>密钥不在其列：它可以来自环境变量，而环境变量在这里读不到。
   */
  public boolean isConfigured() {
    return baseUrl != null && model != null;
  }

  /**
   * 同一个配置块，但忘掉密钥明文，保留端点、模型和预算。
   *
   * <p>用于误填的密钥，或正被环境变量替换掉的密钥：配置块的其余部分是一份可用的配置，丢掉它就会丢失用户
   * 查来的端点。
   */
  public VisionConfig withoutApiKey() {
    return new VisionConfig(baseUrl, null, apiKeyEnv, model, maxTokens);
  }

  /** 要发送的密钥；本记录和环境变量都没有提供时为 null。 */
  public String resolvedApiKey(Map<String, String> env) {
    if (apiKey != null) {
      return apiKey;
    }
    if (apiKeyEnv == null || env == null) {
      return null;
    }
    String fromEnv = env.get(apiKeyEnv);
    return fromEnv == null || fromEnv.isBlank() ? null : fromEnv.strip();
  }

  private static String blankToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.strip();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
