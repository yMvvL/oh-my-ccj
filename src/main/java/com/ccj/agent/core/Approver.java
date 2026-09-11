package com.ccj.agent.core;

/**
 * Gate consulted before a tool performs a side effect.
 *
 * <p>Read-only tools never call it; anything that writes, deletes or executes asks first unless the
 * session was started in auto-approve mode.
 */
@FunctionalInterface
public interface Approver {

  Approver ALWAYS = (title, detail) -> true;

  Approver NEVER = (title, detail) -> false;

  boolean approve(String title, String detail);
}
