package com.ccj.agent.core;

import java.nio.file.Path;
import java.util.Map;

/**
 * 文件系统布局，可通过 {@code CCJ_HOME} 覆盖。
 *
 * <p>集中放在一处，这样测试就能把整个应用指向一个临时目录，而不碰用户真实的配置。
 */
public final class AppPaths {

  public static final String HOME_ENV = "CCJ_HOME";

  private final Path home;

  public AppPaths(Path home) {
    this.home = home.toAbsolutePath().normalize();
  }

  /** 从环境变量解析主目录，回退到 {@code ~/.oh-my-ccj}。 */
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
