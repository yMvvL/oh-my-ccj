package com.ccj.agent.core;

/** 循环无法继续时抛出，例如提供方调用失败。 */
public class AgentException extends RuntimeException {

  public AgentException(String message) {
    super(message);
  }

  public AgentException(String message, Throwable cause) {
    super(message, cause);
  }
}
