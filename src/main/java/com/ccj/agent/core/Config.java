package com.ccj.agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把文件、环境和命令行叠加之后得到的生效配置。
 *
 * <p>每个字段都可以为 null，意思是「这里没有指定」。{@link #merge} 按从低到高的优先级叠加各来源，
 * {@link #resolved} 再补上依赖提供方的默认值，于是代码的其他部分只会看到一份完整的配置。
 *
 * <p>优先级，从低到高：内置默认值、{@code ~/.oh-my-ccj/config.json}、{@code CCJ_*} 环境变量、命令行
 * flag。
 *
 * <p>{@code baseUrl}、{@code apiKey} 和 {@code apiKeyEnv} 是<em>属于提供方的</em>：只有挨着它们被填写时
 * 所针对的那个提供方，它们才有意义，而 {@code settingsFor} 记录的正是这件事。见
 * {@link #settingsBelongTo}。
 *
 * <p>{@code maxTotalTokens} 是这条会话累计花掉多少 token 的上限，null 表示不设上限——它与
 * {@code maxContextTokens} 是两件事：后者管一次请求能带多少内容，前者管这条会话一共能花多少，越过之后
 * 就不再开始新回合。判定在 {@link SpendLimit} 里，因为这个类只负责「值是什么」，不负责「值意味着什么」。
 */
public record Config(
    String provider,
    String model,
    String baseUrl,
    String apiKey,
    String apiKeyEnv,
    Double temperature,
    Integer maxTokens,
    Boolean autoApprove,
    Integer outputLimitBytes,
    String systemPrompt,
    String reasoning,
    Integer maxContextTokens,
    String settingsFor,
    Map<String, ProviderSettings> remembered,
    VisionConfig vision,
    Integer maxTotalTokens) {

  /**
   * 上下文预算出现之前的字段形态，这样不关心它的调用方就不必点它的名。
   */
  public Config(
      String provider,
      String model,
      String baseUrl,
      String apiKey,
      String apiKeyEnv,
      Double temperature,
      Integer maxTokens,
      Boolean autoApprove,
      Integer outputLimitBytes,
      String systemPrompt,
      String reasoning) {
    this(
        provider,
        model,
        baseUrl,
        apiKey,
        apiKeyEnv,
        temperature,
        maxTokens,
        autoApprove,
        outputLimitBytes,
        systemPrompt,
        reasoning,
        null,
        null,
        Map.of(),
        null,
        null);
  }

  /**
   * 凭据被限定到提供方之前字段的形态，这样不关心这个归属的调用方就不必点它的名。
   */
  public Config(
      String provider,
      String model,
      String baseUrl,
      String apiKey,
      String apiKeyEnv,
      Double temperature,
      Integer maxTokens,
      Boolean autoApprove,
      Integer outputLimitBytes,
      String systemPrompt,
      String reasoning,
      Integer maxContextTokens) {
    this(
        provider,
        model,
        baseUrl,
        apiKey,
        apiKeyEnv,
        temperature,
        maxTokens,
        autoApprove,
        outputLimitBytes,
        systemPrompt,
        reasoning,
        maxContextTokens,
        null,
        Map.of(),
        null,
        null);
  }

  /**
   * 花费上限出现之前的字段形态，这样不关心它的调用方就不必点它的名：不设上限，正是这个字段缺席时的意思。
   *
   * <p>网页表单就走这条路——它按位置点名每一个它管理的字段，为了让一个 String 别落进别的槽位里；它不管理的
   * 字段显式给 null，包括这个。
   */
  public Config(
      String provider,
      String model,
      String baseUrl,
      String apiKey,
      String apiKeyEnv,
      Double temperature,
      Integer maxTokens,
      Boolean autoApprove,
      Integer outputLimitBytes,
      String systemPrompt,
      String reasoning,
      Integer maxContextTokens,
      String settingsFor,
      Map<String, ProviderSettings> remembered,
      VisionConfig vision) {
    this(
        provider,
        model,
        baseUrl,
        apiKey,
        apiKeyEnv,
        temperature,
        maxTokens,
        autoApprove,
        outputLimitBytes,
        systemPrompt,
        reasoning,
        maxContextTokens,
        settingsFor,
        remembered,
        vision,
        null);
  }

  /**
   * 已记住配对的 map 在这里被规范化：键统一小写，因为提供方名字在别处都是不区分大小写匹配的；而一个什么都没
   * 有的条目会被丢掉，而不是当作一句空头承诺留着。vision 块也按同样方式规范化：什么都没点明的块就是没有配
   * 置，而「关闭」有两种表示已经多了一种。
   */
  public Config {
    Map<String, ProviderSettings> clean = new LinkedHashMap<>();
    if (remembered != null) {
      remembered.forEach(
          (name, settings) -> {
            if (name != null && !name.isBlank() && settings != null && !settings.isEmpty()) {
              clean.put(name.strip().toLowerCase(), settings);
            }
          });
    }
    remembered = Map.copyOf(clean);
    if (vision != null && vision.isEmpty()) {
      vision = null;
    }
  }

  public static final String DEFAULT_PROVIDER = "openai";
  public static final String OPENAI_BASE_URL = "https://api.openai.com/v1";
  public static final String ANTHROPIC_BASE_URL = "https://api.anthropic.com";
  public static final String OPENAI_KEY_ENV = "OPENAI_API_KEY";
  public static final String ANTHROPIC_KEY_ENV = "ANTHROPIC_API_KEY";
  public static final String DEFAULT_OPENAI_MODEL = "gpt-4o-mini";
  public static final String DEFAULT_ANTHROPIC_MODEL = "claude-sonnet-4-5";
  public static final int DEFAULT_OUTPUT_LIMIT_BYTES = 32 * 1024;

  /** 推理努力档位，升序排列；null 表示「什么都不说，让模型自己决定」。 */
  public static final List<String> REASONING_LEVELS = List.of("low", "high", "max");

  public static Config empty() {
    return new Config(null, null, null, null, null, null, null, null, null, null, null, null);
  }

  /** 返回这份配置，其中 {@code higher} 指定了的每个字段都接管过来。 */
  public Config merge(Config higher) {
    if (higher == null) {
      return this;
    }
    return new Config(
        pick(provider, higher.provider),
        pick(model, higher.model),
        pick(baseUrl, higher.baseUrl),
        pick(apiKey, higher.apiKey),
        pick(apiKeyEnv, higher.apiKeyEnv),
        pick(temperature, higher.temperature),
        pick(maxTokens, higher.maxTokens),
        pick(autoApprove, higher.autoApprove),
        pick(outputLimitBytes, higher.outputLimitBytes),
        pick(systemPrompt, higher.systemPrompt),
        pick(reasoning, higher.reasoning),
        pick(maxContextTokens, higher.maxContextTokens),
        pick(settingsFor, higher.settingsFor),
        mergeRemembered(remembered, higher.remembered),
        mergeVision(vision, higher.vision),
        pick(maxTotalTokens, higher.maxTotalTokens));
  }

  /**
   * 两层的 vision 块，逐字段合并。
   *
   * <p>逐字段而不是整块，理由与其他每个字段都单独挑选相同：只点明模型的层——{@code CCJ_VISION_MODEL}、
   * 一个 {@code --vision-model} flag——绝不能抹掉它下面的端点。整块替换会把「改用这个模型」变成「把端点和
   * 密钥都忘掉」。
   */
  private static VisionConfig mergeVision(VisionConfig lower, VisionConfig higher) {
    if (higher == null || higher.isEmpty()) {
      return lower;
    }
    if (lower == null || lower.isEmpty()) {
      return higher;
    }
    return new VisionConfig(
        pick(lower.baseUrl(), higher.baseUrl()),
        pick(lower.apiKey(), higher.apiKey()),
        pick(lower.apiKeyEnv(), higher.apiKeyEnv()),
        pick(lower.model(), higher.model()),
        pick(lower.maxTokens(), higher.maxTokens()));
  }

  /**
   * 两层的已记住配对，每个提供方由更高的一层胜出。
   *
   * <p>和其他每个字段一样分层，但 map 没法「挑选」：一层若对某个提供方只字未提，绝不能抹掉更低层关于它
   * 知道的东西。这正是这个 map 的全部意义——为「你没在用的那个提供方」填的密钥，必须在「你在用另一个提供
   * 方」期间的每次保存中活下来。
   */
  private static Map<String, ProviderSettings> mergeRemembered(
      Map<String, ProviderSettings> lower, Map<String, ProviderSettings> higher) {
    if (higher == null || higher.isEmpty()) {
      return lower;
    }
    if (lower == null || lower.isEmpty()) {
      return higher;
    }
    Map<String, ProviderSettings> merged = new LinkedHashMap<>(lower);
    merged.putAll(higher);
    return merged;
  }

  /**
   * 当这份配置里的端点和凭据字段是为 {@code provider} 填的时为 true。
   *
   * <p>那三个字段属于提供方，却住在一个扁平文件里，所以没有这个标记时，一分钟前那个提供方留下的 base URL
   * 和密钥，看起来与为当前提供方准备的一模一样。把「不知道」读成「是的，这个密钥是给那个端点的」，就是这样
   * 让一家厂商的流量——以及它的凭据——走到另一家厂商的地址上。所以标记为 null 意味着<em>不是这个提供方的
   * </em>，而从不是「就当是吧」。
   */
  public boolean settingsBelongTo(String provider) {
    if (settingsFor == null || settingsFor.isBlank() || provider == null || provider.isBlank()) {
      return false;
    }
    return settingsFor.strip().equalsIgnoreCase(provider.strip());
  }

  /** 这份配置设置了任何一个属于提供方的字段时为 true。 */
  public boolean setsProviderSettings() {
    return baseUrl != null || apiKey != null || apiKeyEnv != null;
  }

  /**
   * 当这个扁平配对带着一个标记、点名了 {@code provider} 之外的提供方时为 true。
   *
   * <p>{@link #settingsBelongTo} 回答的是「这个配对可以在这里用吗」，而无论是别人的配对还是无人认领的
   * 配对，答案都是否。这个方法回答的是切换时必须问的那个更窄的问题：这个配对刻意是别人的，所以要丢掉，而不是
   * 继承下来。
   */
  private boolean markedForAnotherProvider(String provider) {
    return settingsFor != null
        && !settingsFor.isBlank()
        && provider != null
        && !provider.isBlank()
        && !settingsFor.strip().equalsIgnoreCase(provider.strip());
  }

  /**
   * 这份配置在其扁平字段里持有的配对；什么都没持有时为 null。
   *
   * <p>只是该提供方默认值的端点或密钥变量会被略去：文件记录的是被选择的东西，而把一个
   * {@link #resolved()} 反正会重新推导出来的默认值重复写一遍，就是让一个没有意义的地址被写下来、日后被误
   * 当成刻意填的。
   */
  public ProviderSettings settings() {
    String name = provider == null ? DEFAULT_PROVIDER : provider.strip().toLowerCase();
    String keptBaseUrl = defaultBaseUrl(name).equals(baseUrl) ? null : baseUrl;
    String keptKeyEnv = defaultKeyEnv(name).equals(apiKeyEnv) ? null : apiKeyEnv;
    ProviderSettings settings = new ProviderSettings(keptBaseUrl, apiKey, keptKeyEnv);
    return settings.isEmpty() ? null : settings;
  }

  /**
   * 这份配置去掉属于提供方的字段；切换提供方时正应该从这里开始：新提供方既不继承旧端点，也不继承旧凭据。
   */
  public Config forgetProviderSettings() {
    return new Config(
        provider, model, null, null, null, temperature, maxTokens, autoApprove, outputLimitBytes,
        systemPrompt, reasoning, maxContextTokens, null, remembered, vision, maxTotalTokens);
  }

  /**
   * 同一份配置，但忘掉 vision 块的密钥明文，端点、模型和预算留在原处。
   *
   * <p>它与 {@link #withoutVision} 分开，因为两者是不同意图：一个被粘错框的密钥不是一份该丢掉的配置，而
   * {@code merge} 两者都表达不了——它没提到的字段意思就是「别动」，而这恰恰是清空密钥必须覆盖掉的东西。
   * 所以设置表单会说清它要哪一种。
   */
  public Config withoutVisionKey() {
    if (vision == null || vision.apiKey() == null) {
      return this;
    }
    return new Config(
        provider, model, baseUrl, apiKey, apiKeyEnv, temperature, maxTokens, autoApprove,
        outputLimitBytes, systemPrompt, reasoning, maxContextTokens, settingsFor,
        remembered, vision.withoutApiKey(), maxTotalTokens);
  }

  /**
   * 同一份配置，但完全没有 vision 块，这就是该功能的关闭方式：在别处「不存在」与「空」意思相同，所以这是
   * 「关闭」的唯一表示。
   */
  public Config withoutVision() {
    if (vision == null) {
      return this;
    }
    return new Config(
        provider, model, baseUrl, apiKey, apiKeyEnv, temperature, maxTokens, autoApprove,
        outputLimitBytes, systemPrompt, reasoning, maxContextTokens, settingsFor,
        remembered, null, maxTotalTokens);
  }

  /** 同一份配置，但把它的端点和凭据字段标记为属于 {@code provider}。 */
  public Config scopedTo(String provider) {
    return new Config(
        this.provider,
        model,
        baseUrl,
        apiKey,
        apiKeyEnv,
        temperature,
        maxTokens,
        autoApprove,
        outputLimitBytes,
        systemPrompt,
        reasoning,
        maxContextTokens,
        provider,
        remembered,
        vision,
        maxTotalTokens);
  }

  /** 为 {@code provider} 记住的配对，如果有的话。 */
  public ProviderSettings rememberedFor(String provider) {
    return provider == null ? null : remembered.get(provider.strip().toLowerCase());
  }

  /** 端点与密钥被记住在这里的提供方名字。 */
  public java.util.Set<String> rememberedNames() {
    return remembered.keySet();
  }

  /**
   * 把这份配置持有的配对存在 {@code provider} 名下，这样切回来时还能恢复。
   *
   * <p>只有属于该提供方的那对值得留着：别的东西是在别处填的，把它归到这个名下正是这个 map 要防止的错误。
   */
  public Config remembering(String provider, ProviderSettings settings) {
    if (provider == null || provider.isBlank() || settings == null || settings.isEmpty()) {
      return this;
    }
    Map<String, ProviderSettings> next = new LinkedHashMap<>(remembered);
    next.put(provider.strip().toLowerCase(), settings);
    return withRemembered(next);
  }

  /** 同一份配置，在它自己没点名提供方时，采用 {@code other} 的提供方名。 */
  public Config namedBy(Config other) {
    if (provider != null || other == null || other.provider() == null) {
      return this;
    }
    return new Config(
        other.provider(),
        model,
        baseUrl,
        apiKey,
        apiKeyEnv,
        temperature,
        maxTokens,
        autoApprove,
        outputLimitBytes,
        systemPrompt,
        reasoning,
        maxContextTokens,
        settingsFor,
        remembered,
        vision,
        maxTotalTokens);
  }

  /** 忘掉为 {@code provider} 记住的东西。 */
  public Config forgetting(String provider) {
    if (provider == null || !remembered.containsKey(provider.strip().toLowerCase())) {
      return this;
    }
    Map<String, ProviderSettings> next = new LinkedHashMap<>(remembered);
    next.remove(provider.strip().toLowerCase());
    return withRemembered(next);
  }

  /**
   * 当扁平字段里那对不属于当前提供方时，把这个提供方记住的配对搬进扁平字段。
   *
   * <p>map 里放着「你没在用的那些提供方」带来的东西；扁平字段放着当前生效的配对。把条目从 map 里挪出来，可以让
   * 它只有一份：生效的配对住在扁平字段里，别人的住在 map 里。
   *
   * <p>当要切换到的提供方从未填过任何东西、而扁平字段里的配对又标记着属于别的提供方时，这个配对会被丢掉而不是
   * 继承：那是一分钟前用的那个提供方的地址和凭据，而一次刚刚点名了另一个提供方的运行，绝不能把一家厂商的密钥发到
   * 另一家的端点上。完全没有标记的文件是手写的，那里配对就原样留着——「没有标记」不是「别人的」，只是「没人记录
   * 过是谁填的」。
   */
  public Config recalling(String provider) {
    if (provider == null || provider.isBlank() || settingsBelongTo(provider)) {
      return this;
    }
    ProviderSettings settings = rememberedFor(provider);
    if (settings == null) {
      return markedForAnotherProvider(provider) ? forgetProviderSettings().resolved() : this;
    }
    Map<String, ProviderSettings> next = new LinkedHashMap<>(remembered);
    next.remove(provider.strip().toLowerCase());
    // 之后再 resolved，这样只点名了密钥的配对仍然能拿到这个提供方自己的端点和密钥变量：被替换掉的是另一个
    // 提供方的东西，而把它的端点一并带过来，正是那个标记要防止的错误。
    return new Config(
            this.provider,
            model,
            settings.baseUrl(),
            settings.apiKey(),
            settings.apiKeyEnv(),
            temperature,
            maxTokens,
            autoApprove,
            outputLimitBytes,
            systemPrompt,
            reasoning,
            maxContextTokens,
            provider,
            next,
            vision,
            maxTotalTokens)
        .resolved();
  }

  /**
   * 一次设置变更之后的配置：要离开的会被记住，要切换到的会被取回，而一次点名了端点或密钥的变更会把它写成当前
   * 提供方的。
   *
   * <p>就是这条规则让「存下来的配对」与「正在用它的提供方」保持同步。丢掉任何一半，会话就会用一家厂商的地址去
   * 调用、却带着另一家——或者谁也不是的——凭据，而这正是这个 map 和 {@code settingsFor} 存在的理由。
   */
  public Config changedBy(Config changes) {
    if (changes == null) {
      return this;
    }
    String target = changes.provider() != null ? changes.provider() : provider;
    boolean switching =
        target != null && (provider == null || !target.strip().equalsIgnoreCase(provider.strip()));
    Config base = this;
    if (switching && settingsBelongTo(provider)) {
      // 要离开了：生效的配对属于正在离开的提供方，所以它按自己的名字留下来，而不是因为挡路被扔掉。这次请求
      // 携带的任何东西都是给要切换*到*的那个提供方的——点明一个提供方就是这个意思。
      base = base.remembering(provider, base.settings());
    }
    boolean replaces = changes.setsProviderSettings();
    if (switching || (replaces && !base.settingsBelongTo(target))) {
      base = base.forgetProviderSettings();
    }
    // 在 `resolved()` 填上依赖提供方的默认值之前做标记：一个被填成默认值的端点不是用户为这个提供方填的，标记它
    // 会告诉 `recalling` 说生效的配对已经是这个提供方的了——一个被记住的密钥就是这样不再被使用的。
    Config merged = base.merge(changes);
    if ((switching || replaces) && merged.setsProviderSettings()) {
      merged = merged.scopedTo(merged.provider());
    }
    merged = merged.resolved();
    if (changes.apiKey() != null && changes.apiKey().isBlank()) {
      // 清空密钥就是清空：扁平字段清掉，否则一份被记住的副本会在下次切换时回来。用户要求忘掉的密钥，不是该
      // 留着的密钥。
      merged = merged.withApiKey(null).forgetting(merged.provider());
    }
    return merged.recalling(merged.provider());
  }

  private Config withApiKey(String key) {
    return new Config(
        provider, model, baseUrl, key, apiKeyEnv, temperature, maxTokens, autoApprove,
        outputLimitBytes, systemPrompt, reasoning, maxContextTokens, settingsFor,
        remembered, vision, maxTotalTokens);
  }

  private Config withRemembered(Map<String, ProviderSettings> next) {
    return new Config(
        provider, model, baseUrl, apiKey, apiKeyEnv, temperature, maxTokens, autoApprove,
        outputLimitBytes, systemPrompt, reasoning, maxContextTokens, settingsFor, next,
        vision, maxTotalTokens);
  }

  /** 补上取决于所选提供方的默认值。 */
  public Config resolved() {
    String resolvedProvider = provider == null || provider.isBlank() ? DEFAULT_PROVIDER : provider.strip().toLowerCase();
    String resolvedBaseUrl =
        baseUrl != null && !baseUrl.isBlank()
            ? ProviderDefinition.normaliseBaseUrl(stripTrailingSlash(baseUrl))
            : defaultBaseUrl(resolvedProvider);
    String resolvedKeyEnv =
        apiKeyEnv != null && !apiKeyEnv.isBlank() ? apiKeyEnv : defaultKeyEnv(resolvedProvider);
    String resolvedModel = model != null && !model.isBlank() ? model : defaultModelFor(resolvedProvider, resolvedBaseUrl);
    return new Config(
        resolvedProvider,
        resolvedModel,
        resolvedBaseUrl,
        apiKey,
        resolvedKeyEnv,
        temperature,
        maxTokens,
        autoApprove != null && autoApprove,
        outputLimitBytes == null ? DEFAULT_OUTPUT_LIMIT_BYTES : outputLimitBytes,
        systemPrompt,
        normaliseReasoning(reasoning),
        maxContextTokens,
        settingsFor,
        remembered,
        vision,
        maxTotalTokens);
  }

  /**
   * 努力档位，转小写并校验。未知的值是值得报出来的笔误，而不是可以默默忽略的设置：它改变模型把 token 花在
   * 什么上。
   */
  public static String normaliseReasoning(String reasoning) {
    if (reasoning == null || reasoning.isBlank()) {
      return null;
    }
    String value = reasoning.strip().toLowerCase();
    if (!REASONING_LEVELS.contains(value)) {
      throw new IllegalArgumentException(
          "未知的推理档位 '" + reasoning + "'；请使用 " + String.join(", ", REASONING_LEVELS));
    }
    return value;
  }

  public static String defaultBaseUrl(String provider) {
    return "anthropic".equals(provider) ? ANTHROPIC_BASE_URL : OPENAI_BASE_URL;
  }

  public static String defaultKeyEnv(String provider) {
    return "anthropic".equals(provider) ? ANTHROPIC_KEY_ENV : OPENAI_KEY_ENV;
  }

  /** 没有配置模型时该用的模型；该提供方没有显然的默认值时返回 null。 */
  public static String defaultModel(String provider) {
    if (provider == null) {
      return null;
    }
    return switch (provider.strip().toLowerCase()) {
      case "openai" -> DEFAULT_OPENAI_MODEL;
      case "anthropic" -> DEFAULT_ANTHROPIC_MODEL;
      default -> null;
    };
  }

  /**
   * 默认模型只有在提供方自己的端点上才说得通。中继给模型起的名字很随意，在那里猜会把一个清楚的配置错误变成
   * 一个晦涩的 404。
   */
  private static String defaultModelFor(String provider, String baseUrl) {
    return defaultBaseUrl(provider).equals(baseUrl) ? defaultModel(provider) : null;
  }

  /** 要发送的 API 密钥；配置与环境变量都没提供时为 null。 */
  public String resolvedApiKey(Map<String, String> env) {
    if (apiKey != null && !apiKey.isBlank()) {
      return apiKey.strip();
    }
    if (apiKeyEnv == null || apiKeyEnv.isBlank()) {
      return null;
    }
    String fromEnv = env.get(apiKeyEnv);
    return fromEnv == null || fromEnv.isBlank() ? null : fromEnv.strip();
  }

  /** 读取 {@code config.json}；文件不存在则得到 {@link #empty()}。 */
  public static Config fromFile(Path file) {
    if (file == null || !Files.isRegularFile(file)) {
      return empty();
    }
    JsonNode root;
    try {
      root = Json.parse(Files.readString(file));
    } catch (IOException e) {
      throw new UncheckedIOException("无法读取 " + file, e);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("配置文件无效 " + file + "：" + e.getMessage(), e);
    }
    if (!root.isObject()) {
      throw new IllegalArgumentException("配置文件无效 " + file + "：应当是一个 JSON 对象");
    }
    return new Config(
        text(root, "provider"),
        text(root, "model"),
        text(root, "baseUrl"),
        text(root, "apiKey"),
        text(root, "apiKeyEnv"),
        number(root, "temperature"),
        integer(root, "maxTokens"),
        bool(root, "autoApprove"),
        integer(root, "outputLimitBytes"),
        text(root, "systemPrompt"),
        text(root, "reasoning"),
        integer(root, "maxContextTokens"),
        text(root, "settingsFor"),
        readRemembered(root),
        readVision(root),
        integer(root, "maxTotalTokens"));
  }

  /**
   * {@code vision} 块：描述图片的那个模型的端点、凭据、模型名与回复上限。不存在、为 null 或为空都表示该功能
   * 关闭；而不是对象的块是值得报出来的笔误，不是可以默默忽略的设置。
   */
  private static VisionConfig readVision(JsonNode root) {
    JsonNode node = root.get("vision");
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isObject()) {
      throw new IllegalArgumentException("配置字段 'vision' 必须是一个对象");
    }
    VisionConfig vision =
        new VisionConfig(
            text(node, "baseUrl"),
            text(node, "apiKey"),
            text(node, "apiKeyEnv"),
            text(node, "model"),
            integer(node, "maxTokens"));
    return vision.isEmpty() ? null : vision;
  }

  /** {@code remembered} map：提供方名到为它填写的端点与凭据。 */
  private static Map<String, ProviderSettings> readRemembered(JsonNode root) {
    JsonNode entries = root.get("remembered");
    if (entries == null || !entries.isObject()) {
      return Map.of();
    }
    Map<String, ProviderSettings> out = new LinkedHashMap<>();
    entries
        .fields()
        .forEachRemaining(
            entry -> {
              JsonNode body = entry.getValue();
              if (body == null || !body.isObject()) {
                return;
              }
              ProviderSettings settings =
                  new ProviderSettings(
                      text(body, "baseUrl"), text(body, "apiKey"), text(body, "apiKeyEnv"));
              if (!settings.isEmpty()) {
                out.put(entry.getKey().strip().toLowerCase(), settings);
              }
            });
    return out;
  }

  /** 读取 {@code CCJ_*} 变量。 */
  public static Config fromEnv(Map<String, String> env) {
    return new Config(
        env.get("CCJ_PROVIDER"),
        env.get("CCJ_MODEL"),
        env.get("CCJ_BASE_URL"),
        env.get("CCJ_API_KEY"),
        env.get("CCJ_API_KEY_ENV"),
        parseDouble(env.get("CCJ_TEMPERATURE")),
        parseInteger(env.get("CCJ_MAX_TOKENS")),
        parseBoolean(env.get("CCJ_AUTO_APPROVE")),
        parseInteger(env.get("CCJ_OUTPUT_LIMIT_BYTES")),
        env.get("CCJ_SYSTEM_PROMPT"),
        env.get("CCJ_REASONING"),
        parseInteger(env.get("CCJ_MAX_CONTEXT_TOKENS")),
        null,
        Map.of(),
        readEnvVision(env),
        parseInteger(env.get("CCJ_MAX_TOTAL_TOKENS")));
  }

  /**
   * {@code CCJ_VISION_*} 变量，作为一块或 null。
   *
   * <p>按它们填充的那个块命名，而不是按主提供方的变量命名，因为它们配置的是另一个模型：
   * {@code CCJ_API_KEY} 绝不能兼作图片描述器的密钥，而这正是 vision 要单独配置的全部理由。
   */
  private static VisionConfig readEnvVision(Map<String, String> env) {
    VisionConfig vision =
        new VisionConfig(
            env.get("CCJ_VISION_BASE_URL"),
            env.get("CCJ_VISION_API_KEY"),
            env.get("CCJ_VISION_API_KEY_ENV"),
            env.get("CCJ_VISION_MODEL"),
            parseInteger(env.get("CCJ_VISION_MAX_TOKENS")));
    return vision.isEmpty() ? null : vision;
  }

  /**
   * 与 {@link #merge} 相同，顺序是文件、环境、调用方的覆盖值。
   *
   * <p>点名端点或密钥的 flag 或 {@code CCJ_*} 变量，是对「这次运行最终用的提供方」的一次明确表态，所以它会被
   * 标记为该提供方的，并直接胜出。从文件读到的值不是：它可能是为一次运行并不使用的提供方写的，所以当它不属于当前
   * 提供方时，改用为该提供方记住的配对。
   */
  public static Config layered(Path configFile, Map<String, String> env, Config overrides) {
    Config fromEnvironment = fromEnv(env);
    Config merged =
        empty().merge(fromFile(configFile)).merge(fromEnvironment).merge(overrides).resolved();
    boolean namedHere =
        (overrides != null && overrides.setsProviderSettings())
            || fromEnvironment.setsProviderSettings();
    if (namedHere) {
      return merged.scopedTo(merged.provider());
    }
    return merged.recalling(merged.provider());
  }

  /**
   * 把 UI 管理的设置写进 {@code file}，保留那里已有的其他每个键——手写的系统提示词或输出上限必须在一次设置
   * 表单的访问之后活下来。
   *
   * <p>为 null 的字段会被移除，而空白的 {@code apiKey} 也会被移除，而不是存成空字符串。文件以仅限属主的权限
   * 创建，因为它可能装着密钥。
   *
   * <p>{@code settingsFor} 与它们同行：正是它说明这些 {@code baseUrl} 和 {@code apiKey} 是谁的，所以移除
   * 这些字段也就移除了这个标记。{@code remembered} map 为「你填过的其他每个提供方」保存着同一对东西，这正
   * 让一个提供方可以切回来而无需再粘一遍它的密钥。
   */
  public static void writeInto(Path file, Config managed) {
    ObjectNode root;
    if (Files.isRegularFile(file)) {
      JsonNode existing = readTree(file);
      if (!existing.isObject()) {
        throw new IllegalArgumentException("配置文件 " + file + " 必须包含一个 JSON 对象");
      }
      root = (ObjectNode) existing;
    } else {
      root = Json.object();
    }

    putText(root, "provider", managed.provider());
    putText(root, "model", managed.model());
    // 与 Config.settings() 同一条规则：只是提供方默认值的值不写进去，这样文件永远不会积攒没人选过的端点和
    // 密钥变量。
    putText(root, "baseUrl", chosenBaseUrl(managed));
    putText(root, "apiKey", managed.apiKey() == null || managed.apiKey().isBlank() ? null : managed.apiKey());
    putText(root, "apiKeyEnv", chosenKeyEnv(managed.provider(), managed.apiKeyEnv()));
    // 这个标记意思是「这些字段是为那个提供方填的」：没有字段时它就没有可指向的东西，而一个过期的名字只会让
    // 下一个读者把它们归错档。
    putText(root, "settingsFor", managed.setsProviderSettings() ? managed.settingsFor() : null);
    writeRemembered(root, managed.remembered());
    writeVision(root, managed.vision());
    // 步数上限已经没了，而一个 ccj 不再读取的键会宣传一个毫无作用的设置，所以旧文件在重写时顺手清理掉。
    root.remove("maxSteps");
    // 思考语言也是：它现在由 CCJ.md 或 --system 里的提示词决定，而一个留在文件里的名字，谁也不会再读。
    root.remove("language");
    putNumber(root, "temperature", managed.temperature());
    putNumber(root, "maxTokens", managed.maxTokens());
    putText(root, "reasoning", managed.reasoning());
    putNumber(root, "maxContextTokens", managed.maxContextTokens());
    putNumber(root, "maxTotalTokens", managed.maxTotalTokens());

    try {
      Path parent = file.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(
          file,
          Json.writePretty(root) + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING);
      restrictToOwner(file);
    } catch (IOException e) {
      throw new UncheckedIOException("无法写入 " + file, e);
    }
  }

  /** 写出已记住的 map，不丢掉现有文件为其他提供方保存的任何东西。 */
  private static void writeRemembered(ObjectNode root, Map<String, ProviderSettings> remembered) {
    if (remembered == null || remembered.isEmpty()) {
      root.remove("remembered");
      return;
    }
    ObjectNode entries = root.putObject("remembered");
    remembered.forEach(
        (name, settings) -> {
          ObjectNode body = entries.putObject(name);
          putText(body, "baseUrl", chosenBaseUrl(name, settings.baseUrl()));
          putText(body, "apiKey", settings.apiKey());
          putText(body, "apiKeyEnv", chosenKeyEnv(name, settings.apiKeyEnv()));
        });
  }

  /**
   * 按现状写出 vision 块，没有则移除它。
   *
   * <p>与提供方字段不同，这里没有默认值可以比较：端点就是用户点名的那个，所以每个被设置的字段都会被记录，
   * 不会因为看起来普通而被略去。字段是逐个写的，因为一个只点名了模型的块是一份真实的配置——配置的是它从文件
   * 继承来的那个端点。
   */
  private static void writeVision(ObjectNode root, VisionConfig vision) {
    if (vision == null || vision.isEmpty()) {
      root.remove("vision");
      return;
    }
    ObjectNode block = root.putObject("vision");
    putText(block, "baseUrl", vision.baseUrl());
    putText(block, "apiKey", vision.apiKey());
    putText(block, "apiKeyEnv", vision.apiKeyEnv());
    putText(block, "model", vision.model());
    putNumber(block, "maxTokens", vision.maxTokens());
  }

  private static String chosenBaseUrl(Config config) {
    return chosenBaseUrl(config.provider(), config.baseUrl());
  }

  private static String chosenBaseUrl(String provider, String baseUrl) {
    if (baseUrl == null || baseUrl.isBlank()) {
      return null;
    }
    String name = provider == null ? DEFAULT_PROVIDER : provider.strip().toLowerCase();
    return defaultBaseUrl(name).equals(baseUrl) ? null : baseUrl;
  }

  private static String chosenKeyEnv(String provider, String apiKeyEnv) {
    if (apiKeyEnv == null || apiKeyEnv.isBlank()) {
      return null;
    }
    String name = provider == null ? DEFAULT_PROVIDER : provider.strip().toLowerCase();
    return defaultKeyEnv(name).equals(apiKeyEnv) ? null : apiKeyEnv;
  }

  private static JsonNode readTree(Path file) {    try {
      return Json.parse(Files.readString(file));
    } catch (IOException e) {
      throw new UncheckedIOException("无法读取 " + file, e);
    }
  }

  /** 尽力而为：非 POSIX 文件系统保留自己的默认权限，而不是让这次保存失败。 */
  private static void restrictToOwner(Path file) {
    try {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    } catch (UnsupportedOperationException | IOException ignored) {
      // 无事可做；保存本身已经成功了。
    }
  }

  private static void putText(ObjectNode root, String field, String value) {
    if (value == null || value.isBlank()) {
      root.remove(field);
    } else {
      root.put(field, value);
    }
  }

  private static void putNumber(ObjectNode root, String field, Number value) {
    if (value == null) {
      root.remove(field);
    } else if (value instanceof Integer integer) {
      root.put(field, integer.intValue());
    } else {
      root.put(field, value.doubleValue());
    }
  }

  /** 脱敏后的视图，可以安全打印。 */
  public Map<String, String> describe(Map<String, String> env) {
    Map<String, String> out = new LinkedHashMap<>();
    out.put("provider", provider);
    out.put("model", model == null ? "（未设置）" : model);
    out.put("baseUrl", baseUrl);
    out.put("apiKey", resolvedApiKey(env) == null ? "（未设置）" : "***" + tail(resolvedApiKey(env)));
    // 上面那个端点和密钥属于哪个提供方；「（未知）」是手写的或旧版本写的文件，而自定义提供方不会使用一个
    // 不属于任何人的配对。
    out.put(
        "settingsFor",
        settingsFor == null || settingsFor.isBlank() ? "（未知）" : settingsFor);
    out.put("autoApprove", String.valueOf(autoApprove));
    out.put("outputLimitBytes", String.valueOf(outputLimitBytes));
    out.put("reasoning", reasoning == null ? "（提供方默认）" : reasoning);
    out.put(
        "maxContextTokens",
        maxContextTokens == null ? "（无预算）" : String.valueOf(maxContextTokens));
    out.put(
        "maxTotalTokens", maxTotalTokens == null ? "（无上限）" : String.valueOf(maxTotalTokens));
    out.put("vision", describeVision(env));
    return out;
  }

  /**
   * vision 模型写成一行：关闭，或者它在哪里、是哪个模型、有没有找到密钥——永远不是密钥本身，这是这个类报告
   * 每一个密钥时都遵守的规则。
   */
  private String describeVision(Map<String, String> env) {
    if (vision == null || !vision.isConfigured()) {
      return "（关闭）";
    }
    String key = vision.resolvedApiKey(env);
    return vision.baseUrl()
        + " · "
        + vision.model()
        + " · "
        + (key == null ? "（无密钥）" : "***" + tail(key));
  }

  private static String tail(String key) {
    return key.length() <= 4 ? "" : key.substring(key.length() - 4);
  }

  private static String stripTrailingSlash(String url) {
    String stripped = url.strip();
    return stripped.endsWith("/") ? stripped.substring(0, stripped.length() - 1) : stripped;
  }

  private static String text(JsonNode root, String field) {
    JsonNode node = root.get(field);
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isTextual()) {
      throw new IllegalArgumentException("配置字段 '" + field + "' 必须是字符串");
    }
    return node.asText();
  }

  private static Integer integer(JsonNode root, String field) {
    JsonNode node = root.get(field);
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isInt()) {
      throw new IllegalArgumentException("配置字段 '" + field + "' 必须是整数");
    }
    return node.asInt();
  }

  private static Double number(JsonNode root, String field) {
    JsonNode node = root.get(field);
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isNumber()) {
      throw new IllegalArgumentException("配置字段 '" + field + "' 必须是数字");
    }
    return node.asDouble();
  }

  private static Boolean bool(JsonNode root, String field) {
    JsonNode node = root.get(field);
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isBoolean()) {
      throw new IllegalArgumentException("配置字段 '" + field + "' 必须是布尔值");
    }
    return node.asBoolean();
  }

  private static Integer parseInteger(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Integer.valueOf(raw.strip());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("不是整数：" + raw, e);
    }
  }

  private static Double parseDouble(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Double.valueOf(raw.strip());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("不是数字：" + raw, e);
    }
  }

  private static Boolean parseBoolean(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String value = raw.strip().toLowerCase();
    return switch (value) {
      case "1", "true", "yes", "on" -> true;
      case "0", "false", "no", "off" -> false;
      default -> throw new IllegalArgumentException("不是布尔值：" + raw);
    };
  }

  private static <T> T pick(T lower, T higher) {
    return higher != null ? higher : lower;
  }
}
