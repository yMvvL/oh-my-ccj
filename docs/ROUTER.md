# 把一个路由器插进 ccj

给将要坐在模型提供方前面的那个 API 路由器的笔记。短版本：**大部分集成已经存在，不需要代码**，而唯一确实需要代码的那一块是一个单一接口，它已经有一个能照抄的可用实现。

## 1. 路由器就是一个提供方

任何会说 OpenAI chat-completions API 的东西，今天就已经可以直接用，不需要任何改动：

```bash
# 从设置面板，或者：
curl -X POST localhost:6767/api/providers -H 'content-type: application/json' -d '{
  "name": "router",
  "kind": "openai",
  "baseUrl": "http://localhost:9090/v1",
  "apiKeyEnv": "ROUTER_KEY",
  "models": ["deepseek-v4-flash", "gpt-5.5"]
}'
```

之后这个提供方就可以在设置面板里选择，`ccj --provider router --model …` 在终端里能用，模型列表也会在模型字段里提供。定义住在 `~/.oh-my-ccj/providers.json`；`kind: "anthropic"` 可供偏好 messages API 的路由器使用。密钥从具名的环境变量读取，所以没有任何机密被写下来。

**对一个只做代理转发的路由器来说，这就是全部集成。** 没有代理代码，没有 UI 代码。

## 2. 动态目录需要一个接口

`ModelCatalog` 回答「存在哪些提供方和模型，以及这个答案来自哪里？」：

```java
public interface ModelCatalog {
  List<ProviderInfo> providers();   // 名字、kind、baseUrl、builtIn、models
  List<Model> models();             // provider、model、source（今天是 "config"，以后是 "router"）
}
```

`ConfigModelCatalog` 是随包发布的实现（内置加上 `providers.json`）。一个由路由器支撑的实现是对同样两个方法的第二份实现，例如：

```java
final class RouterCatalog implements ModelCatalog {
  private final String baseUrl;                 // http://localhost:9090
  @Override public List<Model> models() {
    // GET {baseUrl}/v1/models，或者路由器自己的端点——看它暴露哪一个。
    // 映射进 Model(provider, model, "router")；缓存几秒。
  }
}
```

有两件事让它成为一条真正的接缝，而不只是一个计划：

- web 层从不知道目录来自哪里。`GET /api/models` 序列化目录返回的任何东西，包括 `source`，所以一个由路由器提供的条目会出现在模型字段里，前端不用改。页面甚至已经给来源打标签了。
- 代理里没有别的东西读这个目录。实际使用的提供方仍然由 `config.provider` + `config.model` 选定，也就是说，可以在目录并不认同的情况下选中这个路由器——而且如果目录慢或者挂了，设置表单仍然从配置渲染（这就是为什么随包发布的实现是同步的、不做任何 I/O）。

接线就是一行，就在 CLI 构建 hub 的地方：

```java
new AgentHub.Settings(…, new RouterCatalog(baseUrl), providerStore, …);
```

## 3. 路由器应该暴露什么

目录只需要一个端点，而无聊的选择最好：

| 端点 | 用于 | 备注 |
|---|---|---|
| `GET /v1/models` | 模型字段 | OpenAI 形状的 `{"data":[{"id":…}]}` 让适配器保持平凡 |
| `POST /v1/chat/completions` | 代理的回合 | 已经必需，已经能用 |
| `GET /health`（可选） | 未来的提供方状态面板 | 今天不需要它做任何事 |

## 4. 刻意不属于代理的东西

代理只与一个端点说话，也不试图当路由器：没有跨提供方故障转移、没有成本记账、没有模型选择启发式。那些是路由器的活儿，重复它们会把两套互相矛盾的政策放到同一条路径上。代理*确实*报告的是它能看见的记账——token、缓存命中率、工具调用，按会话（`/api/status.usage`）——这正是路由器面板为了展示节省量、而不是猜测节省量所需要的东西。

## 5. 如果以后想要一个路由器面板

模式就是工作区面板：一个 `GET /api/router/*` 端点代理路由器自己的 API（本地路由器没有什么值得中继的 CORS 故事），再加上页面里的一个窗格。已经存在、会被复用的零件：只有回环、带 token 闸门的服务器、SSE 事件流、通知通道，以及设置表单的提供方列表。`AgentHub` 之上没有任何东西需要改；hub 会获得一个针对路由器元数据的瘦客户端，就像它获得了文件夹选择器一样。
