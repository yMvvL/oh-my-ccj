package com.ccj.agent.web;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 一条带图片的消息怎么显示，是浏览器代码，住在 {@code web/app.js} 里，那是一个没有导出的
 * 经典脚本，所以它的用例是一个 node 脚本（{@code src/test/js/picture-message.test.mjs}），把那一节从
 * 发布出去的文件里抠出来，对着一个小小的 node 桩运行，就像 {@link WebWorkspaceAddTest} 那样。
 *
 * <p>它钉住的是**入口只有一条**：三个手势最后都调用 {@code sendPhoto}，而那是唯一把图片交给服务器的
 * 地方。这一点值得钉，是因为它上一次坏掉的方式——上传曾经自己开启一个回合，而描述本该等着和用户的下一
 * 句话一起发——而多一条图片路径就是多一个会那么干的地方。
 */
class WebPictureMessageTest {

  private static final Path SCRIPT = Path.of("src", "test", "js", "picture-message.test.mjs");

  @Test
  void thePictureMessageScriptPasses() throws IOException, InterruptedException {
    WebSessionRowTest.runNodeCases(SCRIPT, "图片消息的渲染");
  }

  @Test
  void theRendererOnlyLaysTheTextOutAndNeverRewritesIt() {
    String script = WebSessionRowTest.appSourceOrSkip();

    // 会话文件、重放与压缩靠的都是那段文本，所以渲染层只允许**读**它：`[picture ` 在这里是一个
    // 被查找的模式，而不是被拼接出去的字符串。把这条区别钉住，是因为「渲染时补上标记」正是那段
    // 文本会开始漂移的地方——而它漂了，会话文件、重放和压缩就各拿到不一样的东西。
    int parser = script.indexOf("function pictureParts(");
    assertTrue(parser > 0, "解析器在");
    int next = script.indexOf("function appendUser(", parser);
    String slice = script.substring(parser, next > parser ? next : script.length());
    assertTrue(slice.contains("text.indexOf('[picture ')"), "标记是被查找的");
    assertTrue(slice.contains("indexOf(PICTURE_TRAILER)"), "按那句文件说明切开");
    assertTrue(
        !slice.contains("'[picture ' +") && !slice.contains("+ '[picture '"),
        "而它从不被拼接出去");
  }

}
