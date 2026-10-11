package com.ccj.agent.web;

import com.ccj.agent.core.Config;
import com.ccj.agent.core.Json;
import com.ccj.agent.core.Message;
import com.ccj.agent.core.Provider;
import com.ccj.agent.core.ProviderDefinition;
import com.ccj.agent.core.VisionConfig;
import com.ccj.agent.core.Workspace;
import com.ccj.agent.provider.ModelCatalog;
import com.ccj.agent.provider.ProviderStore;
import com.ccj.agent.provider.Providers;
import com.ccj.agent.provider.VisionClient;
import com.ccj.agent.session.SessionStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模型、提供方、工作区与配置这几张表单的往返：JSON 进来，写文件或改状态，再把新的 JSON 交回去。
 *
 * <p>4.8 搬出来的第二块（第一块是 {@link Conversation} 与 {@link WebListener}）。它和前一块不一样的地方在于
 * **它需要写的权限**：它改配置、换提供方、切工作区，也顺带在事件流上说一句「刚刚做了什么」。所以它拿的不是一堆
 * 字段，而是 {@link HubState} 一个——**一个参数**，而不是十个（那十个是这个名字第一次尝试时编译器报出来的数）。
 *
 * <p>搬出去的意义：从前它散在一个两千多行的类里，读者只能靠读完整个类来确认「哪里会改服务器的状态」。
 */
final class HubForms {

  private final HubState state;

  HubForms(HubState state) {
    this.state = state;
  }

  /**
   * 设置表单可以提供的选择：提供方（内置的和用户定义的）以及它们的模型。
   *
   * <p>由一个 {@link ModelCatalog} 提供，所以以后换成路由器支撑的目录时页面不必改：它渲染的形状是
   * 目录的形状，不是配置的形状。
   */
  public ObjectNode modelsJson() {
    ObjectNode root = Json.object();
    ModelCatalog catalog = state.settings().modelCatalog();
    ArrayNode providers = root.putArray("providers");
    ArrayNode models = root.putArray("models");
    if (catalog == null) {
      return root;
    }
    for (ModelCatalog.ProviderInfo provider : catalog.providers()) {
      ObjectNode entry = providers.addObject();
      entry.put("name", provider.name());
      entry.put("kind", provider.kind());
      entry.put("baseUrl", provider.baseUrl());
      entry.put("builtIn", provider.builtIn());
      // 密钥将来自哪个变量，好让设置表单能点名它，而不是去猜协议的默认值——一个中转提供方就是这样被
      // 告知去读别人家密钥变量的。
      if (provider.apiKeyEnv() == null) {
        entry.putNull("apiKeyEnv");
      } else {
        entry.put("apiKeyEnv", provider.apiKeyEnv());
      }
      ArrayNode list = entry.putArray("models");
      provider.models().forEach(list::add);
    }
    for (ModelCatalog.Model model : catalog.models()) {
      ObjectNode entry = models.addObject();
      entry.put("provider", model.provider());
      entry.put("model", model.model());
      entry.put("source", model.source());
    }
    // 此刻不在列表里的内置项：「添加一个内置提供方」控件可以给出的东西。措辞是「可选」，不是「被隐藏」
    // ——没有什么需要从死里复活。
    ArrayNode available = root.putArray("builtIns");
    Set<String> offered = new LinkedHashSet<>();
    providers.forEach(entry -> offered.add(entry.path("name").asText().toLowerCase()));
    for (String name : com.ccj.agent.provider.Providers.supported()) {
      if (!offered.contains(name.toLowerCase())) {
        available.add(name);
      }
    }
    return root;
  }

  // ------------------------------------------------------------------ providers

  /**
   * 定义一个用户自己拥有的提供方。存起来之前先校验，所以一个不可能工作的定义永远不会变成可选项。
   */
  public ObjectNode addProvider(JsonNode body) {
    ProviderStore store = requireProviderStore();
    List<String> models = new ArrayList<>();
    JsonNode list = body.path("models");
    if (list.isArray()) {
      list.forEach(model -> models.add(model.asText()));
    } else if (list.isTextual()) {
      // 逗号分隔的字符串正是表单字段自然会发来的东西。
      for (String model : list.asText().split(",")) {
        if (!model.isBlank()) {
          models.add(model.strip());
        }
      }
    }
    ProviderDefinition definition =
        new ProviderDefinition(
                body.path("name").asText(""),
                body.path("kind").asText(ProviderDefinition.OPENAI),
                body.path("baseUrl").asText(""),
                body.path("apiKeyEnv").asText(""),
                models)
            .requireValid();
    store.save(definition);
    // 保存一个定义同时也等于说「我要这个提供方」：如果列表是显式的，新名字就必须加入它，否则目录永远
    // 不会显示刚刚保存的东西。
    ensureListed(definition.name());
    state.publish(
        "notice",
        Json.object().put("text", "已定义提供方 '" + definition.name() + "'"));
    state.publishStatus();
    return modelsJson();
  }

  /**
   * 从列表中移除一个提供方。
   *
   * <p>用户自己做的定义会被删除。内置的编译进了 agent 里，所以没有什么可删：它离开列表，而列表变成
   * 显式的，这就是「已删除」需要表达的全部意思。没有任何东西被记成「已隐藏」，也没有任何东西被当作「可
   * 恢复项」提供——加回来就是一次普通的添加。
   */
  public ObjectNode removeProvider(String name) {
    ProviderStore store = requireProviderStore();
    String clean = knownProvider(name);
    boolean inUse = state.config().provider() != null && state.config().provider().equalsIgnoreCase(clean);
    String tail =
        inUse
            ? " —— 它正是正在使用的那个；本会话继续运行，但请在下次重启之前选另一个提供方"
            : "";

    if (store.find(clean).isEmpty()) {
      List<String> remaining = new ArrayList<>(currentProviderNames());
      remaining.removeIf(known -> known.equalsIgnoreCase(clean));
      store.setShown(remaining);
    } else {
      store.remove(clean);
      List<String> stillShown = new ArrayList<>(store.shown());
      if (stillShown.removeIf(known -> known.equalsIgnoreCase(clean))) {
        store.setShown(stillShown);
      }
    }
    state.publish("notice", Json.object().put("text", "已删除提供方 '" + clean + "'" + tail));
    state.publishStatus();
    return modelsJson();
  }

  /** 把一个内置提供方加回列表——一次普通的添加，不是复活。 */
  public ObjectNode addBuiltInProvider(String name) {
    ProviderStore store = requireProviderStore();
    String clean = name == null ? "" : name.strip();
    boolean builtIn =
        Providers.supported().stream().anyMatch(known -> known.equalsIgnoreCase(clean));
    if (!builtIn) {
      throw new IllegalArgumentException(
          "'" + clean + "' 不是内置提供方；请改为自定义一个");
    }
    if (currentProviderNames().stream().anyMatch(known -> known.equalsIgnoreCase(clean))) {
      throw new IllegalArgumentException("'" + clean + "' 已经在列表里");
    }
    List<String> next = new ArrayList<>(currentProviderNames());
    next.add(clean);
    store.setShown(next);
    state.publish("notice", Json.object().put("text", "已添加提供方 '" + clean + "'"));
    state.publishStatus();
    return modelsJson();
  }

  /** 目录当前提供的每一个提供方名字。 */
  private List<String> currentProviderNames() {
    List<String> names = new java.util.ArrayList<>();
    ModelCatalog catalog = state.settings().modelCatalog();
    if (catalog != null) {
      catalog.providers().forEach(entry -> names.add(entry.name()));
    }
    return names;
  }

  /**
   * 为一个提供方记住某个模型，好让手输的名字活过下一次渲染——选择器给出的列表，和当前正在用的模型不是
   * 一回事。
   */
  public ObjectNode addModel(String provider, String model) {
    ProviderStore store = requireProviderStore();
    String name = knownProvider(provider);
    String value = model == null ? "" : model.strip();
    if (value.isEmpty()) {
      throw new IllegalArgumentException("需要一个模型名");
    }
    List<String> models = new java.util.ArrayList<>(offeredModels(name));
    if (!models.contains(value)) {
      models.add(value);
      store.setModels(name, models);
      state.publish("notice", Json.object().put("text", "已把模型 '" + value + "' 添加到 " + name));
      state.publishStatus();
    }
    return modelsJson();
  }

  /**
   * 忘掉一个提供方的某个模型。
   *
   * <p>可选项列表和当前的选择是两回事：从列表里移除一个模型绝不能改动配置，而拒绝移除正在使用的模型，
   * 在它是唯一可选项时会把用户卡死。活动的模型保持活动、保持可见——它只是不再被推荐了。
   */
  public ObjectNode removeModel(String provider, String model) {
    ProviderStore store = requireProviderStore();
    String name = knownProvider(provider);
    String value = model == null ? "" : model.strip();
    List<String> models = new java.util.ArrayList<>(offeredModels(name));
    if (!models.remove(value)) {
      throw new IllegalArgumentException("'" + value + "' 不是 " + name + " 的模型");
    }
    store.setModels(name, models);
    state.publish("notice", Json.object().put("text", "已从 " + name + " 移除模型 '" + value + "'"));
    state.publishStatus();
    return modelsJson();
  }

  /** 目录当前为一个提供方提供的东西，也就是编辑的起点。 */
  private List<String> offeredModels(String provider) {
    ModelCatalog catalog = state.settings().modelCatalog();
    if (catalog == null) {
      return List.of();
    }
    return catalog.providers().stream()
        .filter(entry -> entry.name().equalsIgnoreCase(provider))
        .findFirst()
        .map(ModelCatalog.ProviderInfo::models)
        .orElse(List.of());
  }

  private String knownProvider(String provider) {
    String name = provider == null ? "" : provider.strip();
    if (name.isEmpty()) {
      throw new IllegalArgumentException("需要一个提供方名");
    }
    ModelCatalog catalog = state.settings().modelCatalog();
    boolean known =
        catalog != null
            && catalog.providers().stream().anyMatch(entry -> entry.name().equalsIgnoreCase(name));
    if (!known) {
      throw new IllegalArgumentException(
          "没有名为 '"
              + name
              + "' 的提供方；请先添加一个，或使用其中之一："
              + (catalog == null
                  ? ""
                  : String.join(
                      ", ",
                      catalog.providers().stream().map(ModelCatalog.ProviderInfo::name).toList())));
    }
    return name;
  }

  /** 把一个名字加入显式列表；列表还是隐式的时候什么都不做。 */
  private void ensureListed(String name) {
    ProviderStore store = requireProviderStore();
    List<String> shown = new ArrayList<>(store.shown());
    if (!store.narrowed() || shown.stream().anyMatch(known -> known.equalsIgnoreCase(name))) {
      return;
    }
    shown.add(name);
    store.setShown(shown);
  }

  private ProviderStore requireProviderStore() {
    if (state.settings().providerStore() == null) {
      throw new IllegalStateException("这个服务器启动时没有提供方注册表");
    }
    return state.settings().providerStore();
  }

  // ------------------------------------------------------------------ workspaces

  /** 切换器需要的注册表形态：名字、路径、那里积了多少历史、哪一个是活动的。 */
  public ObjectNode workspacesJson() {
    ObjectNode root = Json.object();
    Workspace active = state.settings().workspaces().active();
    root.put("active", active == null ? "" : active.name());
    ArrayNode list = root.putArray("workspaces");
    for (Workspace workspace : state.settings().workspaces().list()) {
      ObjectNode entry = list.addObject();
      entry.put("name", workspace.name());
      entry.put("path", workspace.path().toString());
      entry.put("sessions", SessionStore.count(workspace.sessionsDir()));
      entry.put("active", active != null && active.name().equals(workspace.name()));
    }
    return root;
  }

  public ObjectNode addWorkspace(String name, String path) {
    // 只是一条注册表记录，仅此而已：没有正在跑的回合会读这个列表，所以添加一条不会打扰已经在进行的工作。
    // 过去要求这里必须空闲，正是第一个工作区忙着时第二个工作区无法被添加的原因。
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("工作区需要一个目录");
    }
    state.settings().workspaces().add(name, Path.of(path.strip()));
    state.publish("notice", Json.object().put("text", "已添加工作区 '" + name.strip() + "'"));
    return workspacesJson();
  }

  /**
   * 添加一个从桌面自己的选择器里挑出来的目录。选择器就是整个手势，所以名字取目录的名字——名字被占用时
   * 如何解决，见 {@link WorkspaceStore#add(Path)}。
   */
  public ObjectNode addWorkspace(Path path) {
    if (path == null) {
      throw new IllegalArgumentException("工作区需要一个目录");
    }
    requireDistinctPath(path);
    Workspace added = state.settings().workspaces().add(path);
    state.publish(
        "notice",
        Json.object().put("text", "工作区 '" + added.name() + "' → " + added.path()));
    return workspacesJson();
  }

  /* 注册表按名字键存放，所以两个名字可以指向同一个目录——这本来没问题，但同一个目录出现两次就不行：两份
   * 会话历史会争抢同一个工作目录，而屏幕上没有任何东西说明哪份列表属于哪一个。 */
  private void requireDistinctPath(Path path) {
    Path wanted = path.toAbsolutePath().normalize();
    for (Workspace known : state.settings().workspaces().list()) {
      if (known.path().equals(wanted)) {
        throw new IllegalArgumentException(
            "那个目录已经是工作区 '"
                + known.name()
                + "'；请直接用树里已有的条目，不要再添加一次");
      }
    }
  }

  public ObjectNode removeWorkspace(String name) {
    // 忘掉一条记录不删任何文件，也不停任何回合；正在跑的回合继续持有它已经拿到的会话文件。只有活动工作区
    // 受保护，而那是注册表自己的规则。
    state.settings().workspaces().remove(name);
    state.publish("notice", Json.object().put("text", "已忘记工作区 '" + name.strip() + "'"));
    return workspacesJson();
  }

  /**
   * 同时切换工作目录和会话存储，并在目标工作区里开一个全新的会话——跨一次目录变更去续上别人的对话，比
   * 一份空转录更糟。
   */
  public ObjectNode switchWorkspace(String name) {
    // 回合并行时也允许：每一个都持有自己启动时的工作目录，所以在这里切换命名空间没法把已经在进行的工作
    // 改道。
    Workspace workspace = state.settings().workspaces().activate(name);
    state.useSession(SessionStore.create(workspace.sessionsDir()));
    state.publish(
        "notice",
        Json.object().put("text", "workspace '" + workspace.name() + "' → " + workspace.path()));
    return state.status();
  }

  // ------------------------------------------------------------------ settings

  /** 设置表单需要的东西。API 密钥本身从不离开服务器。 */
  public ObjectNode configJson() {
    Config active = state.config();
    Config fileConfig = Config.fromFile(state.settings().configFile());
    // 属于这个提供方的一对：要么是文件里的扁平字段，要么是它从上次访问起为这个提供方记住的那一对。无论
    // 哪种，都是这个提供方的密钥，而密钥本身从不离开服务器。
    boolean storedForThisProvider =
        fileConfig.apiKey() != null
            && !fileConfig.apiKey().isBlank()
            && (fileConfig.settingsBelongTo(active.provider())
                || fileConfig.rememberedFor(active.provider()) != null);
    String apiKeySource =
        storedForThisProvider
            ? "config"
            : active.resolvedApiKey(state.settings().env()) != null ? "env" : "none";
    ObjectNode node = Json.object();
    node.put("configured", state.provider().get() != null);
    node.put("provider", active.provider());
    node.put("model", active.model());
    node.put("baseUrl", Providers.effectiveBaseUrl(active, state.settings().providerStore(), active.provider()));
    node.put("apiKeyEnv", active.apiKeyEnv());
    // 上面两个是不是这个提供方实际会用的。false 表示它们是为别的提供方输入的（自定义提供方不用它们），
    // 那么表单必须给出这个提供方自己的端点，而不是那个过期的。
    node.put(
        "usesStoredSettings",
        Providers.usesStoredSettings(active, state.settings().providerStore(), active.provider()));
    // 哪些提供方已存有密钥，按名字：表单在任何东西被粘贴之前就逐个提供方说「已保存」或「未保存」，而在
    // 它们之间切换正是这个 map 存在的全部理由。
    ArrayNode remembered = node.putArray("rememberedProviders");
    Set<String> withKey = new LinkedHashSet<>();
    fileConfig.rememberedNames().forEach(withKey::add);
    if (storedForThisProvider && active.provider() != null) {
      withKey.add(active.provider().strip().toLowerCase());
    }
    withKey.forEach(remembered::add);
    node.put("apiKeySource", apiKeySource);
    if (active.reasoning() == null) {
      node.putNull("reasoning");
    } else {
      node.put("reasoning", active.reasoning());
    }
    ArrayNode levels = node.putArray("reasoningLevels");
    Config.REASONING_LEVELS.forEach(levels::add);
    if (active.temperature() == null) {
      node.putNull("temperature");
    } else {
      node.put("temperature", active.temperature());
    }
    if (active.maxTokens() == null) {
      node.putNull("maxTokens");
    } else {
      node.put("maxTokens", active.maxTokens());
    }
    node.put("configFile", state.settings().configFile().toString());
    ArrayNode providers = node.putArray("providers");
    Set<String> names = new LinkedHashSet<>();
    if (state.settings().modelCatalog() != null) {
      state.settings().modelCatalog().providers().forEach(provider -> names.add(provider.name()));
    }
    names.forEach(providers::add);
    node.set("vision", visionJson(active));
    return node;
  }

  /**
   * 设置表单需要知道的、关于那个描述图片的模型的东西。
   *
   * <p>密钥遵循这里其他每一个密钥都遵循的规则：说明有没有、从哪来，绝不说它是什么。{@code apiKeySource}
   * 用的是提供方字段用的同一套词汇，所以表单不必再定一条规则就能说「已存在配置文件里」或「来自环境变量」。
   *
   * <p>{@code on} 是这个功能实际会做的事，这和「那个块存在」不是一回事：一个点了端点却没点模型的块什么
   * 也描述不了，而把这种情况称作 on 的表单，等于承诺了图片按钮随后会拒绝的东西。
   */
  private ObjectNode visionJson(Config active) {
    VisionConfig vision = active.vision();
    ObjectNode node = Json.object();
    node.put("configured", vision != null && vision.isConfigured());
    node.put("on", vision != null && vision.isConfigured() && vision.resolvedApiKey(state.settings().env()) != null);
    node.put("defaultMaxTokens", VisionClient.DEFAULT_MAX_TOKENS);
    node.put("baseUrl", (String) (vision == null ? null : vision.baseUrl()));
    node.put("model", (String) (vision == null ? null : vision.model()));
    node.put("apiKeyEnv", (String) (vision == null ? null : vision.apiKeyEnv()));
    if (vision == null || vision.maxTokens() == null) {
      node.putNull("maxTokens");
    } else {
      node.put("maxTokens", vision.maxTokens());
    }
    node.put(
        "apiKeySource",
        vision == null || vision.resolvedApiKey(state.settings().env()) == null
            ? "none"
            : vision.apiKey() != null ? "config" : "env");
    return node;
  }

  /**
   * 校验提交上来的设置，把它们持久化，并把正在跑的会话切到它们上面。
   *
   * <p>先构建提供方：错误的密钥或不可达的 base URL 必须在文件被动到之前就失败，否则一个笔误就会留下
   * 一份起不来的配置。
   *
   * <p>切换提供方是从一份没有端点、没有凭据的配置开始的：它们属于此前活动的那个提供方，继承它们，正是
   * 一个自称 "CommandCode" 的会话把流量发到上一个提供方的地址、并带上上一个提供方密钥的方式。这次请求
   * 携带的东西仍然生效，而结果会标记为新提供方的，这样存下来的那一对在下一次被读取时仍然诚实。
   */
  public ObjectNode applyConfig(JsonNode posted) {
    state.requireEverythingIdle("修改模型");
    Config changes = changesFrom(posted);
    // 两边走同一套过渡：被离开的端点和密钥会记在它们所属的提供方名下，要切过去的那个会被回忆起来，而
    // 点名了某项设置的改动会把它写成活动提供方的。
    Config candidate = withVisionEdits(state.config().changedBy(changes), posted);
    Provider built = state.settings().providerFactory().create(candidate, state.settings().env());
    try {
      // 文件是被写入的那一侧，所以它带着实际在用的提供方：表单可以只提交一个密钥，而一次保存绝不能把
      // 文件留成没有它所属提供方的样子。
      Config stored = Config.fromFile(state.settings().configFile()).namedBy(state.config()).changedBy(changes);
      Config.writeInto(state.settings().configFile(), withVisionEdits(stored, posted));
    } catch (RuntimeException e) {
      built.close();
      throw e;
    }
    Provider previous = state.provider().getAndSet(built);
    if (previous != null) {
      previous.close();
    }
    state.setConfig(candidate);
    // 用配置里的名字，而不是实现的名字：对一个用户称作 "myrelay" 的提供方说「正在使用 openai」，
    // 会让人觉得设置被忽略了。
    state.publish(
        "notice",
        Json.object()
            .put("text", "using " + candidate.provider() + " · " + candidate.model()));
    state.publishStatus();
    return state.status();
  }

  /**
   * 用提交上来的设置发一个最小的请求，什么都不保存。这是本服务器唯一花用户 token 的地方，而且只在他们
   * 按下按钮时花。
   */
  public ObjectNode testConfiguration(JsonNode posted) {
    state.requireShownIdle("测试这些设置");
    // 测试还没保存的设置，就必须测提交上来的那套东西：与表单的「保存」相同的过渡，只是不写盘，这样一个
    // 不属于本提供方的值会被替换掉，而不是留在那儿。
    Config candidate = state.config().changedBy(changesFrom(posted));
    try (Provider probe = state.settings().providerFactory().create(candidate, state.settings().env())) {
      long started = System.nanoTime();
      Message.Assistant reply =
          probe.complete(
              new Provider.Request(
                  candidate.model(),
                  "Reply with the single word: ok",
                  List.of(new Message.User("ping")),
                  List.of(),
                  0.0,
                  16, null),
              event -> {});
      long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
      String text = reply.text().isBlank() ? "（空回复）" : reply.text().strip();
      return Json.object().put("ok", true).put("reply", text).put("elapsedMs", elapsedMillis);
    } catch (Exception e) {
      throw new IllegalArgumentException(FormFields.message(e));
    }
  }

  /** 只包含表单实际发来的字段；空文本意味着「保持原样」。 */
  private Config changesFrom(JsonNode posted) {
    if (posted == null || !posted.isObject()) {
      throw new IllegalArgumentException("需要一个 JSON 对象");
    }
    String apiKey = FormFields.text(posted, "apiKey");
    if (posted.path("clearApiKey").asBoolean(false)) {
      apiKey = "";
    }
    String reasoning = FormFields.text(posted, "reasoning");
    if (reasoning != null) {
      // "default" 是选择器表达「让提供方自己决定」的方式，也就是清掉这个档位。
      reasoning = "default".equalsIgnoreCase(reasoning) ? "" : Config.normaliseReasoning(reasoning);
    }
    Double temperature = FormFields.number(posted, "temperature");
    if (temperature != null && (temperature < 0 || temperature > 2)) {
      throw new IllegalArgumentException("temperature 必须在 0 与 2 之间");
    }
    // vision 块逐字段处理：表单提交它所持有的东西，而它留空的字段意味着「保持原样」——和上面那个密钥
    // 字段遵循同一条规则。清掉密钥和关掉这个功能没法用空字符串表达（空字段是「不变」），所以它们是显式的
    // 旗标，由 `applyConfig` 在合并之后应用。
    Integer visionMaxTokens = FormFields.integer(posted, "visionMaxTokens");
    if (visionMaxTokens != null && visionMaxTokens < 1) {
      throw new IllegalArgumentException("视觉补全预算至少要有 1 个 token");
    }
    String visionApiKey = FormFields.text(posted, "visionApiKey");
    VisionConfig vision =
        new VisionConfig(
            FormFields.text(posted, "visionBaseUrl"),
            visionApiKey,
            FormFields.text(posted, "visionApiKeyEnv"),
            FormFields.text(posted, "visionModel"),
            visionMaxTokens);
    String provider = FormFields.text(posted, "provider");
    // 表单总是会提交它的密钥变量字段，并预填该提供方的默认值，而那不算用户在指认一份凭据。这是从一个手写
    // 配置文件报上来的 bug：提交默认值被算成「这次改动写了自己的端点和密钥」，`changedBy` 随后丢掉了文件里
    // 那对未标记的值，而构建提供方就因「没有 API key」失败——结果一份有人手敲出来的配置文件完全无法从表单
    // 保存。只等于默认值的值在这里不算一个值，这与 `writeInto` 在决定写什么时已经遵循的规则是同一条。
    String apiKeyEnv = FormFields.text(posted, "apiKeyEnv");
    if (apiKeyEnv != null
        && apiKeyEnv.equals(Config.defaultKeyEnv(provider == null ? state.config().provider() : provider))) {
      apiKeyEnv = null;
    }
    // 完整的形状，按位置点名：这个表单不管理的每一个字段都显式给 null，因为更短的构造函数会把一个 String
    // 放进错误的槽位里而不吭声。
    return new Config(
        provider,
        FormFields.text(posted, "model"),
        FormFields.text(posted, "baseUrl"),
        apiKey,
        apiKeyEnv,
        temperature,
        FormFields.integer(posted, "maxTokens"),
        null, // autoApprove
        null, // outputLimitBytes
        null, // systemPrompt
        reasoning,
        null, // maxContextTokens
        null, // settingsFor
        java.util.Map.of(),
        vision);
  }

  /**
   * 把提交上来的视觉指令应用到一份配置上。
   *
   * <p>在合并之后、而不是合并之中：「忘掉密钥」和「关掉图片」不是值，而是移除某个值——而一个对某个字段
   * 一无所知的合并会保留那里的东西，那与这两者中任何一个的意思都相反。
   */
  private static Config withVisionEdits(Config config, JsonNode posted) {
    if (posted == null || !posted.isObject()) {
      return config;
    }
    Config edited =
        posted.path("clearVisionApiKey").asBoolean(false) ? config.withoutVisionKey() : config;
    return posted.path("clearVision").asBoolean(false) ? edited.withoutVision() : edited;
  }
}
