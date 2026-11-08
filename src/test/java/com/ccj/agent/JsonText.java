package com.ccj.agent;

import java.nio.file.Path;

/**
 * 测试用：把一段文本放进**手写的** JSON 字符串字面量里。
 *
 * <p>为什么需要它：这些用例用手拼字符串造夹具，写作
 * {@code "{\"path\": \"" + path + "\"}"}。在 Linux 上这永远是对的，因为路径里没有反斜杠；在 Windows 上
 * 路径是 {@code C:\\Users\\...}，把那个反斜杠原样嵌进去，{@code \\U} 不是一个合法转义，读回时报
 * {@code Unrecognized character escape 'U'}。
 *
 * <p>这个 bug 的形状值得记住：**它只在半边 CI 上暴露**。第一次真正跑 windows 作业时，
 * {@code ApprovalRulesTest} 13 个用例里 1 个失败、11 个错误，而报出来的句子离真相很远。所以除了修，
 * 还在 Linux 上钉了一条喂假 Windows 路径的用例——把「只有一半 CI 才看得见」变成「每次都能看见」。
 *
 * <p>生产代码从不需要它：写文件走的是 Jackson（{@code Json.writePretty}），转义由它负责。这里是给
 * 手拼夹具的测试用的。
 */
public final class JsonText {

  private JsonText() {}

  /**
   * {@code value} 在 JSON 字符串里该有的样子（不含两侧引号）。
   *
   * <p>只转义反斜杠与双引号：这两样是路径里唯一会破坏 JSON 的字符。控制字符不考虑——一个真实路径里
   * 不会有，而假装它会，只会让这段代码看起来比它实际处理的多。
   */
  public static String of(String value) {
    return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  /** 一个路径在 JSON 字符串里该有的样子。 */
  public static String of(Path path) {
    return path == null ? "" : of(path.toString());
  }
}
