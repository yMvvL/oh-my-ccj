package com.ccj.agent.provider;

import com.ccj.agent.core.Config;
import com.ccj.agent.core.ProviderDefinition;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 配置所知道的模型目录：内置提供方及它们的默认模型，再加上用户自己定义的那些。
 *
 * <p>它刻意做成同步、不依赖任何东西——不碰网络——因为设置表单必须瞬间渲染出来。想从网络侧回答的路由器
 * 自己实现 {@link ModelCatalog}，再在需要的地方加缓存。
 */
public final class ConfigModelCatalog implements ModelCatalog {

  public static final String SOURCE = "config";

  private final ProviderStore store;

  public ConfigModelCatalog(ProviderStore store) {
    this.store = store;
  }

  @Override
  public List<ProviderInfo> providers() {
    List<ProviderInfo> all = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    List<String> explicit = store == null ? List.of() : store.shown();
    if (store != null && store.narrowed()) {
      // 用户收窄了列表：就是这个顺序、就是这些项。
      for (String name : explicit) {
        if (!seen.add(name)) {
          continue;
        }
        ProviderDefinition definition = store == null ? null : store.find(name).orElse(null);
        boolean builtIn = Providers.supported().stream().anyMatch(known -> known.equalsIgnoreCase(name));
        if (definition == null && !builtIn) {
          // 既没有定义、也不是内置的名字：某个已删除定义留下的残渣。跳过它才是诚实的答案——为它硬造一个
          // OpenAI 提供方就不是了。
          continue;
        }
        String kind = definition != null ? definition.kind() : kindOf(name);
        String baseUrl = definition != null ? definition.baseUrl() : Config.defaultBaseUrl(kind);
        List<String> models =
            definition != null
                ? effective(name, definition.models())
                : effective(name, List.of(defaultModelFor(kind)));
        String keyEnv = definition != null ? definition.apiKeyEnv() : Config.defaultKeyEnv(kind);
        all.add(new ProviderInfo(name, kind, baseUrl, definition == null, models, keyEnv));
      }
      return List.copyOf(all);
    }
    for (String name : Providers.supported()) {
      if (!seen.add(name)) {
        continue;
      }
      String kind =
          name.equals(ProviderDefinition.ANTHROPIC)
              ? ProviderDefinition.ANTHROPIC
              : ProviderDefinition.OPENAI;
      all.add(
          new ProviderInfo(
              name,
              kind,
              Config.defaultBaseUrl(kind),
              true,
              effective(name, List.of(defaultModelFor(kind))),
              Config.defaultKeyEnv(kind)));
    }
    if (store != null) {
      for (ProviderDefinition definition : store.list()) {
        if (seen.add(definition.name())) {
          all.add(
              new ProviderInfo(
                  definition.name(),
                  definition.kind(),
                  definition.baseUrl(),
                  false,
                  effective(definition.name(), definition.models()),
                  definition.apiKeyEnv()));
        }
      }
    }
    return List.copyOf(all);
  }

  @Override
  public List<Model> models() {
    List<Model> models = new ArrayList<>();
    for (ProviderInfo provider : providers()) {
      for (String model : provider.models()) {
        models.add(new Model(provider.name(), model, SOURCE));
      }
    }
    return List.copyOf(models);
  }

  /**
   * 某个提供方要给出的模型列表：用户最后记下的那份说了算，这样他删掉的模型保持被删、他添加的保持存在；
   * 否则就用提供方自带的那份列表。
   */
  /** 一个内置名字讲的协议。 */
  /**
   * 一个内置名字说的是哪种线路。
   *
   * <p>{@code openai-responses} 与 {@code gemini} 与它们的实现类同名，所以这里问的其实是「这个名字是不是
   * 一个协议名」——用户自定义的名字永远不会走到这里，那些走的是定义里的 {@code kind}。
   */
  private static String kindOf(String name) {
    for (String kind : ProviderDefinition.KINDS) {
      if (kind.equalsIgnoreCase(name)) {
        return kind;
      }
    }
    return ProviderDefinition.OPENAI;
  }

  private List<String> effective(String provider, List<String> fallback) {
    return store == null ? fallback : store.modelsFor(provider).orElse(fallback);
  }

  private static String defaultModelFor(String kind) {
    return ProviderDefinition.ANTHROPIC.equals(kind)
        ? Config.DEFAULT_ANTHROPIC_MODEL
        : Config.DEFAULT_OPENAI_MODEL;
  }
}
