package com.ccj.agent.core;

import java.nio.file.Path;
import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * 一个目录，加上属于它的会话。
 *
 * <p>会话只有在它所谈论的文件旁边才有意义，所以两者一起走：切换工作区会改变工具解析相对路径的基准，也改变
 * 前端展示哪段历史。
 *
 * <p>名字同时充当其会话所在的目录名，因此会按单个路径段能安全容纳的字符来校验：桌面选择器交回来的是一个
 * 文件夹，而文件夹叫什么由用户决定——{@code 数学}、{@code my project}——所以拒绝这些名字只会让最需要它的
 * 人用不上选择器。分隔符、{@code .} 和 {@code ..}、控制字符以及开头的连字符除外：这些字符会改变路径的
 * 含义，或者让参数看起来像个 flag。
 */
public record Workspace(String name, Path path, Path sessionsDir) {

  /** 一个路径段的名字，最多 40 个字符。 */
  private static final Pattern NAME =
      Pattern.compile("[^/\\\\\\p{Cntrl}\\p{Zl}\\p{Zp}]{1,40}");

  public Workspace {
    name = name == null ? "" : name.strip();
    path = path == null ? null : path.toAbsolutePath().normalize();
    sessionsDir = sessionsDir == null ? null : sessionsDir.toAbsolutePath().normalize();
  }

  public static boolean validName(String name) {
    if (name == null) {
      return false;
    }
    // 用合成形式，好让在 macOS 上输入的名字（NFD）与在 Linux 上输入的同一个名字（NFC）是同一个工作区，
    // 而不是两条看起来一模一样的记录。
    String clean = Normalizer.normalize(name.strip(), Normalizer.Form.NFC);
    if (clean.isEmpty() || clean.equals(".") || clean.equals("..")) {
      return false;
    }
    // 名字会用作目录名，也会出现在 `--workspace` 里；开头的连字符会被当成 flag。
    return clean.charAt(0) != '-' && NAME.matcher(clean).matches();
  }
  public static String requireValidName(String name) {
    if (!validName(name)) {
      throw new IllegalArgumentException(RULE);
    }
    return Normalizer.normalize(name.strip(), Normalizer.Form.NFC);
  }

  /** 规则本身，压成一句话，好让字段下方的拒绝提示读起来和被违反的那条规则是同一条。 */
  public static final String RULE =
      "工作区名称必须为 1-40 个字符，不能包含路径分隔符，不能以连字符开头，也不能是 '.' 或 '..'";
}
