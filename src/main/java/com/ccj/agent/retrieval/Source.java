package com.ccj.agent.retrieval;

/** 一份可以进入索引的语料：一个路径，以及它的全部文字。 */
public record Source(String path, String text) {

  public Source {
    path = path == null ? "" : path;
    text = text == null ? "" : text;
  }
}
