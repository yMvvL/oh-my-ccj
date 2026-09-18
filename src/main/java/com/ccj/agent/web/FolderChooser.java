package com.ccj.agent.web;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 桌面自己的文件夹选择器。
 *
 * <p>它存在是因为浏览器给不出绝对路径：Web 的 File System Access API 有意只交回一个带名字的目录句柄，
 * 别的什么也没有。而服务器跑在用户正坐着的这台机器上，所以它可以直接问桌面——这正是本地工具该做的事。
 *
 * <p>做成可注入的，测试就永远不会弹出窗口，无头部署也能明确地拒绝。
 */
public interface FolderChooser {

  /**
   * 弹出模态选择器并返回选中的目录。
   *
   * @return 选中的目录；用户取消或选择器超时则为空
   * @throws IOException 完全无法弹出选择器时抛出，消息值得展示给用户
   */
  Optional<Path> choose(String title) throws IOException;
}
