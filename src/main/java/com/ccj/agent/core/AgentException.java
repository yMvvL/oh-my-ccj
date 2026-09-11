package com.ccj.agent.core;

/** Raised when the loop cannot continue, e.g. the provider call failed. */
public class AgentException extends RuntimeException {

  public AgentException(String message) {
    super(message);
  }

  public AgentException(String message, Throwable cause) {
    super(message, cause);
  }
}
