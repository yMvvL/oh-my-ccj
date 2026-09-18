package com.ccj.agent.core;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;

/**
 * 把项目相对路径与用户写下的模式做匹配。
 *
 * <p>两个让用户表达「这适用于那种文件」的功能——编辑后检查和审批规则——共用这份实现，因为「`**` 在这里是
 * 什么意思」有两份实现就多了一份，无法保持一致，而差异会表现为某个永远不触发的检查，或某条永远匹配不上的
 * 规则。
 *
 * <p>以 {@code **&#47;} 开头的模式会被尝试两次：按原样，以及去掉该前缀。第二次不是图方便。
 * {@link PathMatcher} 把 {@code **&#47;*.java} 读作「某个目录里的 java 文件」，匹配不到项目根部的
 * {@code Foo.java}；而用户见过的其他工具——gitignore、`.editorconfig`、ripgrep 的 {@code --glob}——
 * 都把 {@code **&#47;foo} 读作「任意深度（包括零层）的 foo」。一个静默匹配不到任何东西的模式，与一条没有
 * 任何东西可反对的规则无从区分，而这正是两个调用方都要避免的失败。
 */
public final class PathGlobs {

  private PathGlobs() {}

  /** 当 {@code glob} 匹配 {@code relative}（相对项目根的路径）时为 true。 */
  public static boolean matches(String glob, String relative) {
    if (matcher(glob).matches(Path.of(relative))) {
      return true;
    }
    return glob.startsWith("**/") && matcher(glob.substring(3)).matches(Path.of(relative));
  }

  /**
   * 路径的项目相对、正斜杠形式；位于项目之外时返回 null。
   *
   * <p>「在项目之外」不是「照样用绝对路径去匹配」：规则是关于本项目的陈述，别处的文件是另一个问题，有另一个
   * 答案。
   */
  public static String relative(Path file, Path project) {
    Path target = file.toAbsolutePath().normalize();
    Path root = project.toAbsolutePath().normalize();
    if (!target.startsWith(root)) {
      return null;
    }
    return root.relativize(target).toString().replace('\\', '/');
  }

  private static PathMatcher matcher(String glob) {
    return FileSystems.getDefault().getPathMatcher("glob:" + glob);
  }
}
