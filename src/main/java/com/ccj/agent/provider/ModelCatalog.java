package com.ccj.agent.provider;

import java.util.List;

/**
 * 前端在这里问「我能选哪些提供方和模型，这个答案又是从哪儿来的？」。
 *
 * <p>当前只有一个实现（{@link ConfigModelCatalog}：内置项加上用户自己的定义）。这个接口的价值在于下一
 * 个实现——一个了解自身目录的 API 路由器可以自己从网关回答这个问题，连模型、健康状态和价格一起，而不需要
 * 代理、web 层或设置表单学会任何新东西。每个条目上的 {@code source} 让 UI 能说出「这来自你的配置」还是
 * 「这来自路由器」。
 */
public interface ModelCatalog {

  /**
   * @param provider 模型所属的提供方名字
   * @param model 在线路上发送的标识符
   * @param source 这个条目来自哪里，例如 {@code config} 或 {@code router}
   */
  record Model(String provider, String model, String source) {}

  /**
   * @param kind 线路协议：{@code openai} 或 {@code anthropic}
   * @param builtIn 编译进代理的提供方为 true，用户定义为 false
   * @param apiKeyEnv 读取这个提供方密钥的环境变量；只有设置表单能给出密钥时为 null——一个给出密钥字段
   *     的表单，必须说得出这把密钥本来会从哪儿来
   */
  record ProviderInfo(
      String name,
      String kind,
      String baseUrl,
      boolean builtIn,
      List<String> models,
      String apiKeyEnv) {}

  List<ProviderInfo> providers();

  /** 所有提供方的所有模型，摊平——模型选择器显示的就是它。 */
  List<Model> models();
}
