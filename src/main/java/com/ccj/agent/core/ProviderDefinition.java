package com.ccj.agent.core;

import java.util.List;

/**
 * 用户自己定义的提供方：一个他起的名字、一种协议、一个端点，以及它提供的模型。
 *
 * <p>正因如此，中继、网关、自建 vLLM 或个人 API 路由器无需等待发版就能用上：它永远只是一个名字、一个
 * URL 和一种协议。
 *
 * @param kind 说哪种线路协议；{@code openai} 涵盖所有讲 {@code /chat/completions} 的，
 *     {@code anthropic} 指 messages API，{@code openai-responses} 指 OpenAI 的 Responses API，
 *     {@code gemini} 指 Google 的 {@code :streamGenerateContent}
 * @param models 设置表单里可选的模型；空列表就是「自己把模型名打进去」
 */
public record ProviderDefinition(
    String name, String kind, String baseUrl, String apiKeyEnv, List<String> models) {

  public static final String OPENAI = "openai";
  public static final String ANTHROPIC = "anthropic";
  public static final String OPENAI_RESPONSES = "openai-responses";
  public static final String GEMINI = "gemini";

  /** 提供方自己会追加的端点路径，base URL 里不能重复带上。 */
  private static final List<String> ENDPOINT_SUFFIXES =
      List.of("/chat/completions", "/responses", "/v1/messages", "/messages");

  /**
   * 提供方名称是标识符，仅此而已——它从来不是目录——所以保留严格字符集，不像工作区名称那样取文件夹自己
   * 的名字。
   */
  private static final java.util.regex.Pattern NAME =
      java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,39}");

  public ProviderDefinition {
    name = name == null ? "" : name.strip();
    kind = kind == null || kind.isBlank() ? OPENAI : kind.strip().toLowerCase();
    baseUrl = normaliseBaseUrl(baseUrl == null ? "" : baseUrl.strip());
    apiKeyEnv = apiKeyEnv == null || apiKeyEnv.isBlank() ? null : apiKeyEnv.strip();
    models = models == null ? List.of() : List.copyOf(models);
  }

  /**
   * 去掉提供方自己会追加的那段路径——凡进入系统的 base URL，无论是填在提供方定义里还是设置表单里，都遵循
   * 这一条规则。{@code https://host/v1} 加上 {@code /chat/completions} 才是请求；一个已经以
   * {@code /v1/chat/completions} 结尾的 base URL 会把这段路径发两次然后 404，用这种方式学到规则很让人
   * 困惑——所以要保留的是前缀。
   */
  public static String normaliseBaseUrl(String baseUrl) {
    String value = baseUrl == null ? "" : baseUrl.strip();
    boolean changed = true;
    while (changed) {
      changed = false;
      // 先处理结尾的斜杠：「.../chat/completions/」并不以「/chat/completions」结尾，所以先剥后缀会把
      // 端点路径留下，提供方再往后面追加自己的路径，就重复了两次。
      while (value.endsWith("/")) {
        value = value.substring(0, value.length() - 1);
        changed = true;
      }
      for (String suffix : ENDPOINT_SUFFIXES) {
        if (value.toLowerCase().endsWith(suffix)) {
          value = value.substring(0, value.length() - suffix.length());
          changed = true;
        }
      }
    }
    return value;
  }

  /** 只有两种协议，所以其他任何值都是值得尽早报出的笔误。 */
  /** 已知的协议，按设置面板里希望出现的顺序。 */
  public static final List<String> KINDS = List.of(OPENAI, ANTHROPIC, OPENAI_RESPONSES, GEMINI);

  public static boolean validKind(String kind) {
    return kind != null && KINDS.contains(kind.strip().toLowerCase());
  }

  public ProviderDefinition requireValid() {
    if (name.isBlank()) {
      throw new IllegalArgumentException("提供方定义需要名字");
    }
    if (!NAME.matcher(name.strip()).matches()) {
      throw new IllegalArgumentException(
          "提供方名称必须为 1-40 个字符，只能包含字母、数字、点、连字符或下划线");
    }
    if (!validKind(kind)) {
      throw new IllegalArgumentException(
          "未知的提供方类型 '" + kind + "'；请使用 " + String.join("、", KINDS));
    }
    if (baseUrl.isBlank()) {
      throw new IllegalArgumentException("提供方 '" + name + "' 需要 base URL");
    }
    return this;
  }
}
