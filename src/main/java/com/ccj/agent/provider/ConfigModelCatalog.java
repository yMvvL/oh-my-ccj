package com.ccj.agent.provider;

import com.ccj.agent.core.Config;
import com.ccj.agent.core.ProviderDefinition;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The catalogue as configuration knows it: the built-in providers with their default models, plus
 * whatever the user defined.
 *
 * <p>It is deliberately synchronous and dependency-free — no network — because a settings form must
 * render instantly. A router that wants to answer from the network implements {@link ModelCatalog}
 * itself and gets cached wherever it needs to be.
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
              List.of(defaultModelFor(kind))));
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
                  definition.models()));
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

  private static String defaultModelFor(String kind) {
    return ProviderDefinition.ANTHROPIC.equals(kind)
        ? Config.DEFAULT_ANTHROPIC_MODEL
        : Config.DEFAULT_OPENAI_MODEL;
  }
}
