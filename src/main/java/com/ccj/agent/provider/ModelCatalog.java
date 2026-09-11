package com.ccj.agent.provider;

import java.util.List;

/**
 * Where a front end asks "which providers and models can I choose, and where did that come from?".
 *
 * <p>One implementation ships today ({@link ConfigModelCatalog}: built-ins plus the user's own
 * definitions). The point of the interface is the next one — an API router that knows its own
 * catalogue can answer this from the gateway itself, including models, health and pricing, without
 * the agent, the web layer or the settings form learning anything new. {@code source} on every
 * entry is what lets a UI say "this came from your config" versus "this came from the router".
 */
public interface ModelCatalog {

  /**
   * @param provider the provider name a model belongs to
   * @param model the identifier to send on the wire
   * @param source where the entry came from, for example {@code config} or {@code router}
   */
  record Model(String provider, String model, String source) {}

  /**
   * @param kind the wire protocol: {@code openai} or {@code anthropic}
   * @param builtIn true for the providers compiled into the agent, false for user definitions
   */
  record ProviderInfo(
      String name, String kind, String baseUrl, boolean builtIn, List<String> models) {}

  List<ProviderInfo> providers();

  /** Every model from every provider, flattened — what a model picker shows. */
  List<Model> models();
}
