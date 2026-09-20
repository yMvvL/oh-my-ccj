package com.ccj.agent.web;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 图片的三个入口——按钮、粘贴、拖放——是浏览器代码，住在 {@code web/app.js} 里，那是一个没有导出的
 * 经典脚本，所以它的用例是一个 node 脚本（{@code src/test/js/composer-picture.test.mjs}），把那一节从
 * 发布出去的文件里抠出来，对着一个小小的 node 桩运行，就像 {@link WebWorkspaceAddTest} 那样。
 *
 * <p>它钉住的是**入口只有一条**：三个手势最后都调用 {@code sendPhoto}，而那是唯一把图片交给服务器的
 * 地方。这一点值得钉，是因为它上一次坏掉的方式——上传曾经自己开启一个回合，而描述本该等着和用户的下一
 * 句话一起发——而多一条图片路径就是多一个会那么干的地方。
 */
class WebComposerPictureTest {

  private static final Path SCRIPT = Path.of("src", "test", "js", "composer-picture.test.mjs");

  @Test
  void theComposerPictureScriptPasses() throws IOException, InterruptedException {
    WebSessionRowTest.runNodeCases(SCRIPT, "图片的粘贴与拖放");
  }

  @Test
  void theFrontEndStillHasExactlyOnePlaceThatUploadsAPicture() {
    String script = WebSessionRowTest.appSourceOrSkip();

    // 一个上传函数，四个调用点：相机、相册、粘贴、拖放。发布出去的脚本里多出第二个上传函数，就是
    // 图片流程开始分叉的地方——而分叉的那一次，照片在用户还没写下想让它做什么之前就被送出去了。
    assertTrue(
        script.contains("async function sendPhoto(file)"), "上传只有一个函数");
    assertTrue(script.contains("dom.photoCamera.addEventListener"), "相机的入口接上了");
    assertTrue(script.contains("dom.photoAlbum.addEventListener"), "相册的入口接上了");
    assertTrue(script.contains("document.addEventListener('paste'"), "粘贴的入口接上了");
    assertTrue(script.contains("document.addEventListener('drop'"), "拖放的入口接上了");
    assertTrue(
        !script.contains("async function uploadPhoto(") && !script.contains("async function sendPicture("),
        "没有第二个上传函数");
  }

}
