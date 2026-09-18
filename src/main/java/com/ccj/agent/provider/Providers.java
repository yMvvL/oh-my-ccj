package com.ccj.agent.provider;

import com.ccj.agent.core.Config;
import com.ccj.agent.core.Provider;
import com.ccj.agent.core.ProviderDefinition;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 为一个已解析的 {@link Config} 挑选提供方。
 *
 * <p>请求里凡是可能出错的地方都在这里被拒掉，在任何一个套接字打开之前，这样 CLI 就能把一处配置错误变成
 * 一句可照做的话，而不是一页厂家的报错。
 */
public final class Providers {

  private static final List<String> OPENAI_NAMES =
      List.of("openai", "openai-compatible", "deepseek", "groq", "ollama", "custom");
  private static final List<String> ANTHROPIC_NAMES = List.of("anthropic");
  private static final List<String> SUPPORTED =
      Stream.concat(OPENAI_NAMES.stream(), ANTHROPIC_NAMES.stream()).toList();

  private Providers() {}

  /** {@link #create} 接受的每一个内置提供方名字，规范名在前。 */
  public static List<String> supported() {
    return SUPPORTED;
  }

  /** 内置项加上用户自己定义的那些，也就是选择器应当给出的东西。 */
  public static List<String> supported(ProviderStore store) {
    List<String> names = new java.util.ArrayList<>(SUPPORTED);
    if (store != null) {
      for (ProviderDefinition definition : store.list()) {
        if (!names.contains(definition.name())) {
          names.add(definition.name());
        }
      }
    }
    return List.copyOf(names);
  }

  /**
   * 配置里存的端点与凭据，是否就是 {@code name} 真正会用的那一对。
   *
   * <p>自定义提供方从它自己的定义里取，除非那对值是专门为它填的——只有配置这么说时，存下的那一对才是这个
   * 提供方的。内置提供方没有定义可以回退，所以存下的是什么就是它的。这正是设置表单在把它们显示出来之前必须
   * 问的问题：显示一个不会被用到的值，就是在提供把它保存下来，而错误的端点就是这样又一次被写进去的。
   */
  public static boolean usesStoredSettings(Config resolved, ProviderStore store, String name) {
    if (resolved == null || name == null || name.isBlank()) {
      return false;
    }
    ProviderDefinition definition = store == null ? null : store.find(name).orElse(null);
    return definition == null || resolved.settingsBelongTo(name);
  }

  /**
   * {@code name} 真正会被调用的端点，它并不总是配置里存的那个：自定义提供方由它的定义来服务，除非那个存下
   * 的 URL 是专门为它填的；内置提供方则听配置怎么说。
   *
   * <p>只由一处回答这个问题，状态报告的和请求实际做的才不会各走各的——「这到底发往哪里」有两个答案，正是
   * 一个会话报告着一个端点、却在调另一个端点的由来。
   */
  public static String effectiveBaseUrl(Config resolved, ProviderStore store, String name) {
    if (resolved == null) {
      return null;
    }
    ProviderDefinition definition = store == null ? null : store.find(name).orElse(null);
    if (definition == null) {
      return resolved.baseUrl();
    }
    // 「存过」的意思是这份配置真的点名了一个端点：resolved() 会给它不认识的任何名字填上 OpenAI 的默认
    // 值，而那个填充值是占位符，不是选择——采用它会把自定义提供方指向别人的地址。
    boolean stored =
        resolved.baseUrl() != null
            && !resolved.baseUrl().isBlank()
            && !resolved.baseUrl().equals(Config.defaultBaseUrl(name));
    return usesStoredSettings(resolved, store, name) && stored
        ? resolved.baseUrl()
        : definition.baseUrl();
  }

  /**
   * @param resolved {@link Config#resolved()} 之后的配置
   * @param env 读取 API 密钥的环境；可以为 null
   */
  public static Provider create(Config resolved, Map<String, String> env) {
    return create(resolved, env, null);
  }

  /**
   * @param store 自定义提供方定义；没有时可以为 null
   */
  public static Provider create(Config resolved, Map<String, String> env, ProviderStore store) {
    if (resolved == null) {
      throw new IllegalArgumentException("需要一份已解析的配置");
    }
    Map<String, String> environment = env == null ? Map.of() : env;
    String name =
        resolved.provider() == null || resolved.provider().isBlank()
            ? Config.DEFAULT_PROVIDER
            : resolved.provider().strip().toLowerCase();

    // 自定义定义优先于内置别名表：用户自己那个 "custom" 条目必须就是他写的意思，而不是通用的
    // OpenAI 兼容默认值。
    ProviderDefinition custom = store == null ? null : store.find(name).orElse(null);
    requireModel(resolved, custom);
    // 这份配置里的端点与密钥，是不是为*这个*提供方填的。它们同住一个扁平文件，否则一分钟前用过的提供方留下
    // 的值，和真正为这个提供方准备的值就分不出来——而用了它，就会把这个提供方的流量发去那个地址、带着那个提供
    // 方的凭据，既是泄漏也是一笔账单。
    boolean ownsSettings = resolved.settingsBelongTo(name);
    String apiKey = requireApiKey(resolved, environment, custom, name, ownsSettings);

    if (custom != null) {
      // 自定义提供方的端点来自它的定义：只有定义知道哪个地址服务于哪个提供方。存下的 base URL 只有在专门
      // 为这个提供方填过时才会被采纳（--base-url 参数、CCJ_BASE_URL，或就是这张表单）。
      String baseUrl = effectiveBaseUrl(resolved, store, name);
      return ProviderDefinition.ANTHROPIC.equals(custom.kind())
          ? new AnthropicProvider(baseUrl, apiKey)
          : new OpenAiProvider(baseUrl, apiKey);
    }

    if (!OPENAI_NAMES.contains(name) && !ANTHROPIC_NAMES.contains(name)) {
      throw new IllegalArgumentException(
          "未知的提供方 '"
              + name
              + "'；内置："
              + String.join(", ", SUPPORTED)
              + (store == null || store.list().isEmpty()
                  ? "（可在设置面板里自定义一个）"
                  : "；已定义："
                      + String.join(
                          ", ",
                          store.list().stream().map(ProviderDefinition::name).toList())));
    }
    String baseUrl = resolveBaseUrl(resolved, name);
    return name.equals(AnthropicProvider.NAME)
        ? new AnthropicProvider(baseUrl, apiKey)
        : new OpenAiProvider(baseUrl, apiKey);
  }

  /** 自定义提供方自带密钥变量；内置的有自己的。 */
  private static String requireApiKey(
      Config resolved,
      Map<String, String> env,
      ProviderDefinition custom,
      String name,
      boolean ownsSettings) {
    if (custom == null) {
      return requireApiKey(resolved, env, name);
    }
    // 密钥住在定义点名的那个变量里。否则由 kind 决定，而配置里的 `apiKeyEnv` 只有在它是有意为之的选择
    // 时才算数：`resolved()` 会用这个提供方*名字*本会产生的默认值填上那个字段，于是 Anthropic 类型的自
    // 定义提供方就会被要求提供 OPENAI_API_KEY——并把全局导出的 OpenAI 密钥发给第三方中继。
    String configVariable = resolved.apiKeyEnv();
    String variable;
    if (custom.apiKeyEnv() != null && !custom.apiKeyEnv().isBlank()) {
      variable = custom.apiKeyEnv();
    } else if (configVariable != null
        && !configVariable.isBlank()
        && !configVariable.equals(Config.defaultKeyEnv(resolved.provider()))) {
      variable = configVariable;
    } else {
      variable = Config.defaultKeyEnv(custom.kind());
    }
    // 字面量密钥只有在配置这么说时才属于这个提供方；不属于它的那把是为别人填的，把它交给这个端点正是这条
    // 规则要防的泄漏。
    if (ownsSettings) {
      String literal = resolved.apiKey();
      if (literal != null && !literal.isBlank()) {
        return literal.strip();
      }
    }
    String fromEnv = env.get(variable);
    if (fromEnv != null && !fromEnv.isBlank()) {
      return fromEnv.strip();
    }
    if (looksLikeAKey(variable)) {
      // 一个极容易犯的错，而光秃秃的「没有 API 密钥」说不清它。放在任何会点名变量的分支之前检查，因为这个
      // 字段里放的是密钥而不是变量名，把它原样回显就等于把凭据写进日志。
      throw new IllegalArgumentException(
          "提供方 '"
              + name
              + "' 没有 API 密钥：apiKeyEnv 设置里放的看起来就是 API 密钥本身（"
              + redact(variable)
              + "）——请把密钥填进 API key 字段，这里填环境变量的*名字*，"
              + "例如 MY_RELAY_KEY");
    }
    if (resolved.apiKey() != null && !resolved.apiKey().isBlank()) {
      String owner =
          resolved.settingsFor() == null || resolved.settingsFor().isBlank()
              ? "另一个提供方"
              : "提供方 '" + resolved.settingsFor() + "'";
      throw new IllegalArgumentException(
          "提供方 '"
              + name
              + "' 没有 API 密钥：配置文件里存的那把是为"
              + owner
              + "填的，不会发给 '"
              + name
              + "'——请在选中 '"
              + name
              + "' 后重新粘贴，或在环境里设置 "
              + variable);
    }
    throw new IllegalArgumentException(
        "提供方 '"
            + name
            + "' 没有 API 密钥：请在环境里设置 "
            + variable
            + "，或在配置文件里填 \"apiKey\"");
  }

  private static void requireModel(Config resolved) {
    requireModel(resolved, null);
  }

  private static void requireModel(Config resolved, ProviderDefinition custom) {
    if (resolved.model() != null && !resolved.model().isBlank()) {
      return;
    }
    String provider = resolved.provider();
    String fallback = custom == null ? Config.defaultModel(provider) : null;
    String why =
        custom != null
            ? custom.models().isEmpty()
                ? "提供方 '" + provider + "' 是自定义提供方，但没有任何模型列表"
                : "提供方 '" + provider + "' 提供：" + String.join(", ", custom.models())
            : fallback == null
                ? "提供方 '" + provider + "' 没有默认模型"
                : "默认值（" + fallback + "）只适用于提供方自己的端点，而 '"
                    + resolved.baseUrl()
                    + "' 是自定义端点";
    throw new IllegalArgumentException(
        "没有配置模型：传 --model <name>、设置 CCJ_MODEL，或在配置文件里加 \"model\""
            + " —— "
            + why);
  }

  private static boolean looksLikeAKey(String value) {
    if (value == null) {
      return false;
    }
    String trimmed = value.strip();
    return trimmed.startsWith("sk-") || (!trimmed.equals(trimmed.toUpperCase()) && trimmed.length() > 32);
  }

  private static String redact(String value) {
    String trimmed = value == null ? "" : value.strip();
    return trimmed.length() <= 6 ? "***" : trimmed.substring(0, 3) + "***" + trimmed.substring(trimmed.length() - 3);
  }

  private static String requireApiKey(Config resolved, Map<String, String> env, String provider) {
    String apiKey = resolved.resolvedApiKey(env);
    if (apiKey != null) {
      return apiKey;
    }
    String envVar =
        resolved.apiKeyEnv() == null || resolved.apiKeyEnv().isBlank()
            ? Config.defaultKeyEnv(provider)
            : resolved.apiKeyEnv();
    throw new IllegalArgumentException(
        "提供方 '"
            + provider
            + "' 没有 API 密钥：请在环境里设置 "
            + envVar
            + "，或在配置文件里填 `apiKey`");
  }

  private static String resolveBaseUrl(Config resolved, String provider) {
    String baseUrl = resolved.baseUrl();
    return baseUrl == null || baseUrl.isBlank() ? Config.defaultBaseUrl(provider) : baseUrl;
  }
}
