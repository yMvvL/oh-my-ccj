package com.ccj.agent.core;

/**
 * 为某一个提供方填写的端点和凭据：当该提供方是当前激活的那个时，{@link Config} 就把它保存在扁平的
 * {@code baseUrl}/{@code apiKey}/{@code apiKeyEnv} 字段里；对其他的提供方，这就是它替它们记住的内容。
 *
 * <p>它天生属于某个提供方，所以作为一个整体传递，并与它所属的名字放在一起：只有密钥没有端点，或只有端点
 * 没有密钥，这种配对从来都无法配合工作。每个字段都可以为 null 或空白，意思是「这一项没填过」——此时改用
 * 该提供方的定义或内置默认值。
 */
public record ProviderSettings(String baseUrl, String apiKey, String apiKeyEnv) {

  public ProviderSettings {
    baseUrl = blankToNull(baseUrl);
    apiKey = blankToNull(apiKey);
    apiKeyEnv = blankToNull(apiKeyEnv);
  }

  /** 当什么都没填时为 true；这种配对不值得记住。 */
  public boolean isEmpty() {
    return baseUrl == null && apiKey == null && apiKeyEnv == null;
  }

  /** 当这个配对里存有密钥明文时为 true，那也是报告里唯一需要脱敏的字段。 */
  public boolean hasKey() {
    return apiKey != null;
  }

  private static String blankToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.strip();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
