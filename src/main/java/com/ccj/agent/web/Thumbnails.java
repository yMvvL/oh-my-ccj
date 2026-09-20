package com.ccj.agent.web;

import com.ccj.agent.session.AttachmentStore;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;

/**
 * 一张附件的缩略图：长边不超过 {@link #MAX_EDGE} 像素的 PNG，尽力而为。
 *
 * <p>它存在，是因为一条会话要能在另一台设备上读得懂：换一台手机、或者刷新一次页面之后，待发送的那张
 * 图片就只剩一个文件名和一句描述了。缩略图是页面自己画出一张图的最小代价，而它是每次请求现画出来的
 * ——磁盘上不留第二个副本，于是也没有一份会过期、需要清理、或者与原件对不上的缓存。
 *
 * <p><strong>画不出小图不是一次拒绝。</strong> 字节是不是一张图片由
 * {@link AttachmentStore#mediaTypeOf(byte[])} 说了算，解码交给 JDK 的 {@link ImageIO}；后者解不了的
 * 格式——WebP 是现成的一例——就把原字节交回去。那张图片本身是好的，只是画不出小图；一次缩略图失败
 * 不该让页面连图都看不到。调用方不必区分这两种回答：它要么拿到一张 PNG，要么拿到一张原图，两者都能
 * 显示，而返回字节自己的媒体类型可以随时用 {@link AttachmentStore#mediaTypeOf(byte[])} 问出来。
 */
public final class Thumbnails {

  /** 缩略图最长边的像素数：够在手机上一眼认出是哪张图，又小到值得为它多走一次请求。 */
  public static final int MAX_EDGE = 96;

  private Thumbnails() {}

  /**
   * {@code bytes} 的缩略图，或者 {@code bytes} 本身。
   *
   * <p>本来就落在 {@link #MAX_EDGE} 之内的图原样返回：放大不是缩略图，而重编一遍只会让它变差、变大。
   *
   * @return 一张长边不超过 {@link #MAX_EDGE} 的 PNG；不是这里收的四种图像之一、解码器画不出来、
   *     或者本来就不比 {@link #MAX_EDGE} 大时，返回的正是 {@code bytes} 本身
   */
  public static byte[] of(byte[] bytes) {
    // 没有字节的东西没有小图可画。在这里抛出去，只会让调用方多一条处理不了任何事情的错误分支。
    if (bytes == null || bytes.length == 0 || !isStoredImage(bytes)) {
      return bytes;
    }
    BufferedImage image = decode(bytes);
    if (image == null || Math.max(image.getWidth(), image.getHeight()) <= MAX_EDGE) {
      return bytes;
    }
    byte[] png = encodePng(scaled(image));
    // 写不出 PNG 是 JDK 少了写出器，不是这张图有问题：原图仍然是能显示的东西。
    return png == null ? bytes : png;
  }

  /**
   * 这些字节是不是服务器收得下的那四种图像之一。
   *
   * <p>判定只问 {@link AttachmentStore#mediaTypeOf(byte[])}，因为它就是仓库里那唯一一处「从字节读出
   * 图片类型」的地方——它认不出的字节在这里就是没有可画的东西。它用抛异常而不是返回空来表达这件事
   * （它平时长在拒绝一次上传的路径上，那里需要一句能读的拒绝理由），所以这里的捕获是「不是图片」这个
   * 回答，而不是一次错误。<strong>先问它的理由不止一致：</strong> {@link ImageIO} 比它认得宽（BMP、
   * TIFF 也能解），而多缩几种格式会让服务端对「图片」的定义多出第二种说法。真正的解码能力仍旧由
   * {@link ImageIO} 说，因为一个格式认得出来不等于画得出来。
   */
  private static boolean isStoredImage(byte[] bytes) {
    try {
      AttachmentStore.mediaTypeOf(bytes);
      return true;
    } catch (IllegalArgumentException notOneOfTheFour) {
      return false;
    }
  }

  /**
   * 解码，或者在 {@link ImageIO} 画不出来时为 {@code null}。
   *
   * <p>坏字节不从这里抛出去：一段声称是 PNG 却读不出来的字节，对页面来说和 WebP 是同一件事——没有小图
   * 可画——而两条路都在这里汇成同一个 {@code null}，比在上层再分一次岔要短。解码器抱怨坏字节的方式
   * 不止一种（{@link IOException}，以及格式不当时从读取器里冒出来的运行时异常），而它们全都只是这个
   * 意思。
   */
  private static BufferedImage decode(byte[] bytes) {
    try (InputStream in = new ByteArrayInputStream(bytes)) {
      return ImageIO.read(in);
    } catch (IOException | RuntimeException cannotBeDrawn) {
      return null;
    }
  }

  /** {@code source} 的等比缩小版：长边正好落在 {@link #MAX_EDGE} 上。 */
  private static BufferedImage scaled(BufferedImage source) {
    int width = source.getWidth();
    int height = source.getHeight();
    double factor = (double) MAX_EDGE / Math.max(width, height);
    // 极端比例的图会把短边舍入到零；零像素的画布画不出它，而一个像素画得出。
    int targetWidth = Math.max(1, (int) Math.round(width * factor));
    int targetHeight = Math.max(1, (int) Math.round(height * factor));
    // 带透明通道：原件有透明区时，缩略图也得有，否则那些地方会变成黑块。
    BufferedImage target =
        new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = target.createGraphics();
    try {
      // 双线性加高质量渲染：默认的最近邻在缩小几倍时会丢掉细线，那样的小图认不出是哪张图。
      graphics.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
    } finally {
      graphics.dispose();
    }
    return target;
  }

  /**
   * {@code image} 的 PNG 字节，或者没有写出器时的 {@code null}。
   *
   * <p>写进内存流在这里不可能因为 I/O 失败，所以那条路只留下一个 {@code null}——与解码失败汇到同一个
   * 回答上：原字节。
   */
  private static byte[] encodePng(BufferedImage image) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      return ImageIO.write(image, "png", out) ? out.toByteArray() : null;
    } catch (IOException e) {
      return null;
    }
  }
}
