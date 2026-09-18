package com.ccj.agent.provider;

import com.ccj.agent.core.Json;
import com.ccj.agent.core.ProviderDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 用户自己定义的提供方，保存在 {@code <home>/providers.json}。
 *
 * <p>与 {@code config.json} 分开，因为它们回答的是不同的问题：配置说的是*哪一个*提供方在用、并存放密
 * 钥，这里说的是*有哪些*提供方。新增一个不能打扰你正在用的模型，而一个定义在切走之后依然存在。
 *
 * <p>内置提供方不存这里——它们是代码——所以删掉一个条目永远不会把 {@code openai} 或
 * {@code anthropic} 一起带走。
 */
public final class ProviderStore {

  private static final String FILE_NAME = "providers.json";

  private final Path home;
  private final Path file;
  private final Map<String, ProviderDefinition> definitions = new LinkedHashMap<>();

  /**
   * 用户编辑过的模型列表，按提供方名字存放——也包括没有自己的定义的内置提供方。一份列表一旦记在这里就有
   * 了决定权，所以删掉的模型会一直保持被删；在那之前，用的是目录自带的那份列表。
   */
  private final Map<String, List<String>> modelLists = new LinkedHashMap<>();

  /**
   * 用户收窄列表之后，他真正拥有的那些提供方。
   *
   * <p>{@code null} 表示「没有意见：代理自带的一切加上你的定义」；而一个列表——包括空列表——就是全部答
   * 案。只要有东西被删，列表就变成显式的，所以被删掉的提供方就是不在，而不是被记成「已隐藏」——UI 没有什么
   * 要解释、也没有什么要恢复，加回来就是一次普通的添加。{@code null} 与空列表的区别，正是
   * {@link #narrowed()} 存在的意义。
   */
  private java.util.List<String> shown;

  private ProviderStore(Path home) {
    this.home = home.toAbsolutePath().normalize();
    this.file = this.home.resolve(FILE_NAME);
  }

  public static ProviderStore open(Path home) {
    ProviderStore store = new ProviderStore(home);
    if (Files.isRegularFile(store.file)) {
      store.load();
      if (store.healList()) {
        store.write();
      }
    }
    return store;
  }

  public Path file() {
    return file;
  }

  /** 显式列表；用户从未删过提供方时为空。 */
  public synchronized List<String> shown() {
    return shown == null ? List.of() : List.copyOf(shown);
  }

  /**
   * 用户收窄过列表之后为 true，这一点 {@link #shown()} 说不出来：无论「没有意见」还是「我把最后一个删
   * 了」，它都返回空列表。决定要给出什么的调用方必须分得清这两者——否则删掉最后一个提供方会让所有内置项复活，
   * 而往一个已清空的列表里添加也永远记不下来。
   */
  public synchronized boolean narrowed() {
    return shown != null;
  }

  public synchronized boolean isShown(String provider) {
    if (shown == null || provider == null) {
      return true;
    }
    String key = provider.strip().toLowerCase();
    return shown.stream().anyMatch(name -> name.equalsIgnoreCase(key));
  }

  /**
   * 记录整份列表。调用时传的是「应当剩下的那些」，因为只有调用方知道那些本存储从未存过的内置项。
   */
  public synchronized void setShown(List<String> providers) {
    java.util.List<String> clean = new java.util.ArrayList<>();
    for (String provider : providers == null ? List.<String>of() : providers) {
      String value = provider == null ? "" : provider.strip();
      if (!value.isEmpty() && clean.stream().noneMatch(name -> name.equalsIgnoreCase(value))) {
        clean.add(value);
      }
    }
    shown = clean;
    write();
  }

  /**
   * 用户为某个提供方记下的列表。
   *
   * <p>空表示「从未记录过，用目录自带的列表」；存在但为空表示「记录为空的」——正是这个区别让用户删掉最后
   * 一个模型之后它保持被删，而不是提供方的默认列表又冒出来。
   */
  public synchronized java.util.Optional<List<String>> modelsFor(String provider) {
    if (provider == null) {
      return java.util.Optional.empty();
    }
    return java.util.Optional.ofNullable(modelLists.get(provider.strip().toLowerCase()));
  }

  /**
   * 记录某个提供方的模型列表。调用方传入的是它希望最终得到的那份列表，因为只有目录知道「去掉这个模型之后的
   * 列表」是什么意思。
   */
  public synchronized void setModels(String provider, List<String> models) {
    String key = provider == null ? "" : provider.strip().toLowerCase();
    if (key.isEmpty()) {
      throw new IllegalArgumentException("需要提供方名字");
    }
    List<String> clean = new java.util.ArrayList<>();
    for (String model : models == null ? List.<String>of() : models) {
      String value = model == null ? "" : model.strip();
      if (!value.isEmpty() && !clean.contains(value)) {
        clean.add(value);
      }
    }
    modelLists.put(key, List.copyOf(clean));
    write();
  }

  public synchronized List<ProviderDefinition> list() {
    return List.copyOf(definitions.values());
  }

  public synchronized Optional<ProviderDefinition> find(String name) {
    return name == null
        ? Optional.empty()
        : Optional.ofNullable(definitions.get(name.strip().toLowerCase()));
  }

  /** 新增或替换一个定义。 */
  public synchronized ProviderDefinition save(ProviderDefinition definition) {
    ProviderDefinition valid = definition.requireValid();
    definitions.put(valid.name().toLowerCase(), valid);
    write();
    return valid;
  }

  public synchronized void remove(String name) {
    String key = name == null ? "" : name.strip().toLowerCase();
    if (definitions.remove(key) == null) {
      throw new IllegalArgumentException(
          "没有名为 '"
              + key
              + "' 的自定义提供方；已定义："
              + (definitions.isEmpty() ? "（无）" : String.join(", ", definitions.keySet())));
    }
    write();
  }

  private void load() {
    JsonNode root;
    try {
      root = Json.parse(Files.readString(file));
    } catch (IOException e) {
      throw new UncheckedIOException("无法读取 " + file, e);
    }
    JsonNode shownNames = root.path("shown");
    if (shownNames.isArray()) {
      shown = new java.util.ArrayList<>();
      shownNames.forEach(name -> shown.add(name.asText()));
    }
    JsonNode models = root.path("models");
    if (models.isObject()) {
      models
          .fields()
          .forEachRemaining(
              entry -> {
                if (!entry.getValue().isArray()) {
                  return;
                }
                List<String> recorded = new ArrayList<>();
                entry.getValue().forEach(model -> recorded.add(model.asText()));
                modelLists.put(entry.getKey().toLowerCase(), List.copyOf(recorded));
              });
    }
    JsonNode entries = root.path("providers");
    if (!entries.isObject()) {
      return;
    }
    boolean healed = false;
    entries
        .fields()
        .forEachRemaining(
            entry -> {
              String name = entry.getKey();
              JsonNode body = entry.getValue();
              String kind = body.path("kind").asText(ProviderDefinition.OPENAI);
              String baseUrl = body.path("baseUrl").asText("");
              String apiKeyEnv = body.path("apiKeyEnv").asText("");
              List<String> modelNames = new ArrayList<>();
              JsonNode list = body.path("models");
              if (list.isArray()) {
                list.forEach(model -> modelNames.add(model.asText()));
              }
              try {
                ProviderDefinition definition =
                    new ProviderDefinition(name, kind, baseUrl, apiKeyEnv, modelNames).requireValid();
                definitions.put(definition.name().toLowerCase(), definition);
              } catch (IllegalArgumentException ignored) {
                // 用不了的条目会被跳过：一个坏定义不能把其余的藏起来。
              }
            });
  }

  /**
   * 列表没有提到的定义就是不可见的，没有用户想要这样：旧版本（或被 bug）写下的定义会被折回显式列表，而不是
   * 丢失。
   */
  private boolean healList() {
    if (shown == null) {
      return false;
    }
    boolean changed = false;
    // 用定义自己的拼写，而不是存储时用的那个小写键：这份列表是给用户看的，悄悄改掉他们提供方的名字会让人
    // 意外。
    for (ProviderDefinition definition : definitions.values()) {
      String name = definition.name();
      if (shown.stream().noneMatch(known -> known.equalsIgnoreCase(name))) {
        shown.add(name);
        changed = true;
      }
    }
    return changed;
  }

  private void write() {
    ObjectNode root = Json.object();
    if (shown != null) {
      ArrayNode shownNames = root.putArray("shown");
      shown.forEach(shownNames::add);
    }
    ObjectNode models = root.putObject("models");
    modelLists.forEach(
        (provider, list) -> {
          ArrayNode array = models.putArray(provider);
          list.forEach(array::add);
        });
    ObjectNode entries = root.putObject("providers");
    for (ProviderDefinition definition : definitions.values()) {
      ObjectNode body = entries.putObject(definition.name());
      body.put("kind", definition.kind());
      body.put("baseUrl", definition.baseUrl());
      if (definition.apiKeyEnv() != null) {
        body.put("apiKeyEnv", definition.apiKeyEnv());
      }
      if (!definition.models().isEmpty()) {
        ArrayNode declared = body.putArray("models");
        definition.models().forEach(declared::add);
      }
    }
    try {
      Files.createDirectories(home);
      Files.writeString(
          file,
          Json.writePretty(root) + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING);
    } catch (IOException e) {
      throw new UncheckedIOException("无法写入 " + file, e);
    }
  }
}
