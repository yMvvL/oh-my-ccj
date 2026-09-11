package com.ccj.agent.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Config;
import com.ccj.agent.core.ProviderDefinition;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The catalogue is the seam a future router implements, so its shape is pinned: providers carry the
 * protocol and the endpoint, models carry where they came from.
 */
class ConfigModelCatalogTest {

  @TempDir Path tmp;

  @Test
  void listsBuiltInsWithTheirDefaultModels() {
    ConfigModelCatalog catalog = new ConfigModelCatalog(ProviderStore.open(tmp));

    ModelCatalog.ProviderInfo openai =
        catalog.providers().stream().filter(p -> p.name().equals("openai")).findFirst().orElseThrow();
    assertTrue(openai.builtIn());
    assertEquals(ProviderDefinition.OPENAI, openai.kind());
    assertEquals(Config.OPENAI_BASE_URL, openai.baseUrl());
    assertEquals(List.of(Config.DEFAULT_OPENAI_MODEL), openai.models());

    assertTrue(catalog.providers().stream().anyMatch(p -> p.name().equals("anthropic") && p.builtIn()));
    assertTrue(catalog.models().stream().allMatch(m -> m.source().equals(ConfigModelCatalog.SOURCE)));
  }

  @Test
  void aUserDefinedProviderAppearsWithItsOwnModels() {
    ProviderStore store = ProviderStore.open(tmp);
    store.save(
        new ProviderDefinition(
            "myrelay", ProviderDefinition.OPENAI, "https://relay.example.com/v1", null,
            List.of("deepseek-v4-flash", "gpt-5.5")));
    ConfigModelCatalog catalog = new ConfigModelCatalog(store);

    ModelCatalog.ProviderInfo relay =
        catalog.providers().stream().filter(p -> p.name().equals("myrelay")).findFirst().orElseThrow();
    assertFalse(relay.builtIn());
    assertEquals("https://relay.example.com/v1", relay.baseUrl());
    assertEquals(List.of("deepseek-v4-flash", "gpt-5.5"), relay.models());

    List<String> models =
        catalog.models().stream()
            .filter(m -> m.provider().equals("myrelay"))
            .map(ModelCatalog.Model::model)
            .toList();
    assertEquals(List.of("deepseek-v4-flash", "gpt-5.5"), models);
  }

  @Test
  void anEmptyStoreStillOffersTheBuiltIns() {
    ConfigModelCatalog catalog = new ConfigModelCatalog(null);

    assertTrue(catalog.providers().stream().anyMatch(ModelCatalog.ProviderInfo::builtIn));
    assertFalse(catalog.models().isEmpty());
  }
}
