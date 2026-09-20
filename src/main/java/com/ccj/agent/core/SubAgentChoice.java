package com.ccj.agent.core;

/**
 * 一个子代理角色被钉到的东西：它自己的模型，和它自己的思考档位。
 *
 * <p>两项都可以缺席，而缺席的意思是「跟主对话一样」——不是「这就不用模型」。这正是这块配置存在的理由：它是
 * 一次收窄，不是一次分叉。{@code explore} 用一个便宜模型去读文件，而 {@code build} 仍然拿主对话的模型写
 * 代码；两者用的是同一把钥匙和同一个端点，因为一次委派不是另一个提供方。
 *
 * <p>两项都点空的条目不是一条配置，所以在读进来的时候就被丢掉（见 {@link Config} 的规范构造器）：一个
 * 看起来设置了什么、实际什么都没说的条目，比它不在那里更糟。
 */
public record SubAgentChoice(String model, String reasoning) {

  public boolean isEmpty() {
    return (model == null || model.isBlank()) && (reasoning == null || reasoning.isBlank());
  }
}
