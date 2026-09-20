package com.ccj.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.ccj.agent.session.AttachmentStore;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * 缩略图：大图变小，其余的全都原样回去。
 *
 * <p>比别的都重要的两条是：缩出来的东西仍然是一张 PNG、比例没变，以及「画不出小图」不是一次拒绝——
 * 那段字节原封不动地交回去，页面至少还能显示它。
 */
class ThumbnailsTest {

  @Test
  void aLargePictureBecomesAPngWithItsLongestEdgeAtTheLimitAndTheSameShape() throws IOException {
    byte[] thumbnail = Thumbnails.of(png(300, 150));

    // 用嗅探器问，而不是对齐魔数：那正是调用方拿到这些字节之后会问的问题。
    assertEquals("image/png", AttachmentStore.mediaTypeOf(thumbnail));
    BufferedImage drawn = decode(thumbnail);
    assertEquals(Thumbnails.MAX_EDGE, drawn.getWidth());
    assertEquals(Thumbnails.MAX_EDGE / 2, drawn.getHeight(), "300x150 是 2:1，缩完还得是 2:1");
  }

  @Test
  void aTallPictureIsLimitedByItsHeightNotItsWidth() throws IOException {
    BufferedImage drawn = decode(Thumbnails.of(png(150, 300)));

    assertEquals(Thumbnails.MAX_EDGE / 2, drawn.getWidth());
    assertEquals(Thumbnails.MAX_EDGE, drawn.getHeight());
  }

  @Test
  void aPictureTheJdkCannotDrawComesBackUntouchedRatherThanRefused() {
    // WebP：这是服务端收得下的一种图，而 JDK 里没有它的解码器。「画不出小图」正是这一条要钉住的路。
    byte[] webp = webp();

    assertEquals("image/webp", AttachmentStore.mediaTypeOf(webp));
    assertSame(webp, Thumbnails.of(webp));
  }

  @Test
  void aPictureThatSniffsRightButWillNotDecodeComesBackUntouched() {
    // 魔数完整、内容却断了的 PNG（一台传到一半的下载就是这样）：嗅探说它是图片，解码器说画不出来，
    // 而后者也不该让整张图变成一次拒绝。
    byte[] truncated = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

    assertEquals("image/png", AttachmentStore.mediaTypeOf(truncated));
    assertSame(truncated, Thumbnails.of(truncated));
  }

  @Test
  void bytesThatAreNotAnImageComeBackUntouched() {
    byte[] notAnImage = "这不是一张图片，只是一些字节".getBytes(StandardCharsets.UTF_8);
    byte[] nothing = new byte[0];

    assertSame(notAnImage, Thumbnails.of(notAnImage));
    assertSame(nothing, Thumbnails.of(nothing), "空的东西也没有小图可画，而且这不该是一次异常");
  }

  @Test
  void aPictureAlreadyInsideTheLimitIsNotBlownUp() throws IOException {
    byte[] small = png(40, 20);

    byte[] answer = Thumbnails.of(small);

    assertSame(small, answer, "已经在限制之内：放大不是缩略图，重编一遍只会让它变差");
    BufferedImage drawn = decode(answer);
    assertEquals(40, drawn.getWidth());
    assertEquals(20, drawn.getHeight());
  }

  /** 一张这个 JDK 现造出来的真 PNG：手打的文件头只能证明嗅探器认识那几个标记。 */
  private static byte[] png(int width, int height) throws IOException {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    // 棋盘格而不是一片纯色：纯色的大图缩完仍然是一张纯色的小图，看不出缩放有没有真的发生。
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        image.setRGB(x, y, (x / 8 + y / 8) % 2 == 0 ? 0x3366CC : 0xFFFFFF);
      }
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    if (!ImageIO.write(image, "png", out)) {
      throw new IllegalStateException("这个 JDK 没有 PNG 写出器，因此没法造出测试用的图");
    }
    return out.toByteArray();
  }

  /** 一个 WebP 的文件头，够让嗅探器认出它的类型，也够让解码器承认自己画不出它。 */
  private static byte[] webp() {
    byte[] bytes = new byte[16];
    System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, bytes, 0, 4);
    System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, bytes, 8, 4);
    return bytes;
  }

  private static BufferedImage decode(byte[] bytes) throws IOException {
    BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
    assertNotNull(image, "这些字节必须是一张能被解码的图");
    return image;
  }
}
