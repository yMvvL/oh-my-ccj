package com.ccj.agent.obs;

import com.ccj.agent.core.Json;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次运行里发生过的一件事，带起点、终点、几个键值，以及它里面发生的事。
 *
 * <p>一个回合的形状是树，不是一串数：一次运行里有几个模型回合，每个回合里又跑了几次工具，而「这一次工具
 * 为什么慢」只有在那棵树上才看得出来——平均到整个回合的直方图里，它和一次快的模型调用长得一模一样。所以
 * 指标回答「现在怎么样」，而这棵树回答「那一次到底发生了什么」。
 *
 * <p>时间戳是毫秒级的墙钟，另外每个 span 自带一个由回调给出的耗时（工具的那一个来自循环自己的
 * {@code nanoTime} 测量）。两者都留着，因为它们的用途不同：墙钟让一棵树能被摆在别的东西旁边看，而
 * 耗时那个数字是直的、能被相减的。
 */
public final class Span {

  private final String name;
  private final long startMillis;
  private final Map<String, String> attributes = new LinkedHashMap<>();
  private final List<Span> children = new ArrayList<>();
  private long endMillis;

  Span(String name) {
    this(name, System.currentTimeMillis());
  }

  Span(String name, long startMillis) {
    this.name = name;
    this.startMillis = startMillis;
    this.endMillis = startMillis;
  }

  /** 记下一个键值；返回自身，好让一棵树能一行一行地搭出来。 */
  public Span attribute(String name, String value) {
    attributes.put(name, value);
    return this;
  }

  /** 结束这个 span，带上它自己的耗时（毫秒）。 */
  public Span end(long elapsedMillis) {
    this.endMillis = startMillis + Math.max(0, elapsedMillis);
    return this;
  }

  /** 一个新孩子，已经挂在这棵树上了。 */
  public Span child(String name) {
    Span span = new Span(name);
    children.add(span);
    return span;
  }

  public String name() {
    return name;
  }

  public long startMillis() {
    return startMillis;
  }

  public long endMillis() {
    return endMillis;
  }

  public List<Span> children() {
    return List.copyOf(children);
  }

  /** 某个属性的值，没有时为 null。 */
  public String attribute(String name) {
    return attributes.get(name);
  }

  /** 这个 span 及其孩子的线上形状。 */
  public ObjectNode toJson() {
    ObjectNode node = Json.object();
    node.put("name", name);
    node.put("start", startMillis);
    node.put("end", endMillis);
    ObjectNode attributesNode = node.putObject("attributes");
    attributes.forEach(attributesNode::put);
    ArrayNode childrenNode = node.putArray("children");
    for (Span child : children) {
      childrenNode.add(child.toJson());
    }
    return node;
  }
}
