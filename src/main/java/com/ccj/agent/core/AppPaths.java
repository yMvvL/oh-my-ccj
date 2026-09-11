package com.ccj.agent.core;

import java.nio.file.Path;
import java.util.Map;

/**
 * Filesystem layout, overridable through {@code CCJ_HOME}.
 *
 * <p>Kept in one place so a test can point the whole application at a temporary directory without
 * touching the user's real configuration.
 */
public final class AppPaths {

  public static final String HOME_ENV = "CCJ_HOME";

  private final Path home;

  public AppPaths(Path home) {
    this.home = home.toAbsolutePath().normalize();
  }

  /** Resolves the home directory from the environment, falling back to {@code ~/.oh-my-ccj}. */
  public static AppPaths fromEnv(Map<String, String> env) {
    String override = env.get(HOME_ENV);
    if (override != null && !override.isBlank()) {
      return new AppPaths(Path.of(override));
    }
    String userHome = env.getOrDefault("HOME", System.getProperty("user.home", "."));
    return new AppPaths(Path.of(userHome).resolve(".oh-my-ccj"));
  }

  public static AppPaths system() {
    return fromEnv(System.getenv());
  }

  public Path home() {
    return home;
  }

  public Path configFile() {
    return home.resolve("config.json");
  }

  public Path sessionsDir() {
    return home.resolve("sessions");
  }
}
