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
 * 目录是未来某个路由器要实现的那道接缝，所以它的形状被钉住了：提供方带着协议和端点，模型带着它们来自哪里。
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
  void anEmptiedListOffersNothingInsteadOfEveryBuiltIn() {
    // 那个 bug：`shown()` 对「从未收窄」和「我删掉了最后一个提供方」都返回空列表，于是删掉最后一个被读成
    // 「没有意见」，所有内置项又回来了。
    ProviderStore store = ProviderStore.open(tmp);
    store.setShown(List.of("openai"));
    store.setShown(List.of());
    ConfigModelCatalog catalog = new ConfigModelCatalog(store);

    assertTrue(store.shown().isEmpty());
    assertTrue(store.narrowed(), "被清空的列表仍是一个答案");
    assertTrue(catalog.providers().isEmpty(), "而目录照办：" + catalog.providers());
    assertTrue(catalog.models().isEmpty());
  }

  @Test
  void anEmptyStoreStillOffersTheBuiltIns() {
    ConfigModelCatalog catalog = new ConfigModelCatalog(null);

    assertTrue(catalog.providers().stream().anyMatch(ModelCatalog.ProviderInfo::builtIn));
    assertFalse(catalog.models().isEmpty());
  }
}
