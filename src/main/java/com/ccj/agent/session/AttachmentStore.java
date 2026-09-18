package com.ccj.agent.session;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HexFormat;

/**
 * 一段会话拥有的图片，就在它旁边的磁盘上。
 *
 * <p>附件目录是 {@code <sessionsDir>/<session-id>.attachments}，与 {@code <session-id>.jsonl} 平级，
 * 并且是从会话文件自己的路径推导出来的，而不是来自关于「会话住在哪里」的第二套说法。正是这个位置让一个
 * 会话能被连同它的图片一起删掉——把它们留下会长出一个没人列出、也没人清理的目录——也让它们不进用户的
 * 项目，这是这个安排的另外一半用意：一张收据的照片不是项目内容，一个因为有人拍了张照就出现在
 * {@code git status} 里的文件，是没人要的意外。
 *
 * <p>存什么由上传的头几个字节决定，绝不由它的名字或它到达时带的 {@code Content-Type} 决定。名字是一句
 * 声称，头也是一句声称；magic number 才是文件本身，不属于四种被接受图像之一的上传会被拒绝，并附带
 * 实际读到的东西。
 */
public final class AttachmentStore {

  /**
   * 这个类愿意持有的最大上传，也是它愿意读的最大请求体。
   *
   * <p>八兆是手机拍的一张照片还带富余：当初拿来量的是 2.1 MB 的 PNG，其 base64 请求体是 2.9 MB。
   * 它刻意不是服务器通用的请求体上限，后者保持在一兆，因为一兆对 JSON 命令来说正合适——抬高那个会把
   * 每个端点都放宽，只为迁就单独一种上传。
   */
  public static final long MAX_BYTES = 8L * 1024 * 1024;

  /** 与会话文件并排的那个目录的后缀。 */
  public static final String DIRECTORY_SUFFIX = ".attachments";

  /** 每次读的块大小；大到足够便宜，小到不会给一份很短的上传垫上多余的空间。 */
  private static final int CHUNK_BYTES = 8 * 1024;

  /** 一个名字洗净之后没有可用字符剩下时的默认值。 */
  private static final String DEFAULT_STEM = "image";

  /**
   * 存储时最长的基本名，不含 {@code -n} 后缀和媒体类型自带的扩展名。
   *
   * <p>名字从上传里带过来，好让它在转录里还能认得出来，但它是一个标签而不是一条路径：浏览器过来的
   * 文件名有的长达几百个百分号解码出的垃圾字符，留出这么长的余量也是为了让加上后缀的名字仍然短。
   */
  private static final int MAX_STEM_LENGTH = 64;

  /** 在一个名字上放弃之前要试多少个 {@code -n} 后缀。 */
  private static final int MAX_NAME_ATTEMPTS = 1000;

  private static final String PNG = "image/png";
  private static final String JPEG = "image/jpeg";
  private static final String GIF = "image/gif";
  private static final String WEBP = "image/webp";

  private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
  private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
  private static final byte[] GIF87A = {'G', 'I', 'F', '8', '7', 'a'};
  private static final byte[] GIF89A = {'G', 'I', 'F', '8', '9', 'a'};
  private static final byte[] RIFF_MAGIC = {'R', 'I', 'F', 'F'};
  private static final byte[] WEBP_MAGIC = {'W', 'E', 'B', 'P'};

  private static final HexFormat HEX = HexFormat.ofDelimiter(" ").withUpperCase();

  private final Path directory;

  private AttachmentStore(Path directory) {
    this.directory = directory;
  }

  /**
   * 某个会话的存储，以它所属的会话文件来寻址。
   *
   * @throws IllegalArgumentException 当该文件不是会话文件时，好让一个会把目录放到别处的名字——满是点
   *     的词干，或者根本不是会话 id 的名字——在这里就被拒绝，而不是变成一个目录路径
   */
  public static AttachmentStore forSession(Path sessionFile) {
    Path file = sessionFile.toAbsolutePath().normalize();
    String id = SessionStore.idOf(file.getFileName().toString());
    if (id.isEmpty()) {
      throw new IllegalArgumentException(
          "不是会话文件，因此没有附件目录：" + sessionFile);
    }
    return new AttachmentStore(file.resolveSibling(id + DIRECTORY_SUFFIX));
  }

  /**
   * 图片去哪里。由第一次 {@link #save} 创建，于是从未收到过图片的会话不会有一个空目录需要解释。
   */
  public Path directory() {
    return directory;
  }

  /**
   * 磁盘上的一张图片。
   *
   * @param path 它实际写到了哪里；其中的名字不是上传时的名字
   * @param mediaType 这些字节是什么，嗅探得出而不是听人说的
   */
  public record Saved(Path path, String mediaType) {}

  /**
   * 把一次上传存到会话的目录下，并报告它落到了哪里。
   *
   * <p>名字予以保留，因为一份写着 {@code IMG_0001.jpg} 的转录读得懂，写着 {@code a3f9c1} 的读不懂，
   * 但它永远只是一个名字：其中任何路径都被丢掉，{@code ..} 活不下来，其余字符被收敛为字母、数字、点、短横
   * 和下划线。扩展名会被换成嗅探出的媒体类型所对应的那个，于是存下来的文件永远不会和它自己的内容对不上。
   *
   * <p>已经在用的名字不会被覆盖——第二张 {@code IMG_0001.jpg} 是第二张照片——所以写入用的是
   * {@code CREATE_NEW}，撞上的那次尝试带后缀重试。这是文件系统对名字的一次占用，而不是先检查再写入，
   * 后者在两次上传同时到达时会双双通过。
   *
   * @throws IllegalArgumentException 当没有字节可供识别，或字节不是 PNG、JPEG、WebP、GIF 时
   * @throws UncheckedIOException 当字节写不出去时
   */
  public Saved save(String name, byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      throw new IllegalArgumentException(
          "无法存储 '" + name + "'：它没有字节，因此没有可识别的东西");
    }
    String mediaType = sniff(bytes);
    if (mediaType == null) {
      throw new IllegalArgumentException(
          "无法存储 '"
              + name
              + "'：上传以 "
              + HEX.formatHex(bytes, 0, Math.min(bytes.length, 8))
              + " 开头，这不是 PNG、JPEG、WebP 或 GIF 图像的 magic number（类型是从字节读出来的，"
              + "不是从名字或 Content-Type）");
    }
    String stem = sanitizedStem(name);
    String extension = extensionFor(mediaType);
    try {
      Files.createDirectories(directory);
      for (int attempt = 1; attempt <= MAX_NAME_ATTEMPTS; attempt++) {
        Path target = directory.resolve(fileName(stem, extension, attempt));
        try {
          Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
          return new Saved(target, mediaType);
        } catch (FileAlreadyExistsException taken) {
          // 别人占了这个名字；下一次尝试用带后缀的。
        } catch (IOException failed) {
          // 写了一半的图片以后和完整的区分不开，所以把它删掉。
          deleteQuietly(target);
          throw failed;
        }
      }
      throw new IOException(
          "从 '" + stem + extension + "' 推导出的全部 " + MAX_NAME_ATTEMPTS + " 个名字都已被占用");
    } catch (IOException e) {
      throw new UncheckedIOException("无法把 '" + name + "' 存储到 " + directory, e);
    }
  }

  /**
   * 读一个请求体，凡是超过 {@link #MAX_BYTES} 的都拒绝。
   *
   * <p>上限只有施加在读取上才成其为上限。声明的长度可能没有，有也是客户端对自家请求体的声称——所以先
   * 检查长度，因为自称 9 MB 的请求体无需再费心，然后把读取封顶，好让一个说谎的、或干脆不带长度流式传来
   * 的请求体<em>在读取的过程中</em>就停在上限处。整个缓冲下来再事后度量，恰好会持有上限存在的目的就是拒绝
   * 的那些字节，而且任意大小的上传都会吃掉一个与上传同样大的堆。
   *
   * @param declaredLength 调用方知道时的 {@code Content-Length}，请求体分块到达时为负数
   * @throws IOException 当上传超过上限时，无论它自己说没说
   */
  public static byte[] readBounded(InputStream body, long declaredLength) throws IOException {
    if (declaredLength > MAX_BYTES) {
      throw new IOException(
          "上传声明了 "
              + declaredLength
              + " 字节，超过 "
              + limitDescription()
              + " 的附件上限");
    }
    ByteArrayOutputStream out =
        new ByteArrayOutputStream(
            declaredLength > 0 ? (int) Math.min(declaredLength, MAX_BYTES) : CHUNK_BYTES);
    byte[] chunk = new byte[CHUNK_BYTES];
    long total = 0;
    while (true) {
      // 超过上限一个字节就足以知道上传超了，而且绝不会多持有。
      int room = (int) Math.min(chunk.length, MAX_BYTES + 1 - total);
      int read = body.read(chunk, 0, room);
      if (read < 0) {
        return out.toByteArray();
      }
      total += read;
      if (total > MAX_BYTES) {
        throw new IOException(
            "上传读到这里已经 " + total + " 字节，仍在继续到达，超过了 " + limitDescription()
                + " 的附件上限");
      }
      out.write(chunk, 0, read);
    }
  }

  /** {@code bytes} 的媒体类型，不是这个类存储的类型时为 null。 */
  public static String mediaTypeOf(byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      throw new IllegalArgumentException(
          "无法识别一次没有字节的上传，因此没有东西可存");
    }
    String mediaType = sniff(bytes);
    if (mediaType == null) {
      throw new IllegalArgumentException(
          "上传以 "
              + HEX.formatHex(bytes, 0, Math.min(bytes.length, 8))
              + " 开头，这不是 PNG、JPEG、WebP 或 GIF 图像的 magic number（类型是从字节读出来的，"
              + "不是从名字或 Content-Type）");
    }
    return mediaType;
  }

  /** {@link #mediaTypeOf} 背后读 magic number 的那一步：那些字节对应的类型，或 null。 */
  private static String sniff(byte[] bytes) {
    if (matches(bytes, 0, PNG_MAGIC)) {
      return PNG;
    }
    if (matches(bytes, 0, JPEG_MAGIC)) {
      return JPEG;
    }
    if (matches(bytes, 0, GIF87A) || matches(bytes, 0, GIF89A)) {
      return GIF;
    }
    // WebP 是一种 RIFF 容器，而 RIFF 也装 WAV 和 AVI——偏移 8 处的四个字节才说明这是哪个，所以光有
    // RIFF 不能算数。
    if (matches(bytes, 0, RIFF_MAGIC) && matches(bytes, 8, WEBP_MAGIC)) {
      return WEBP;
    }
    return null;
  }

  private static boolean matches(byte[] bytes, int offset, byte[] magic) {
    if (bytes.length < offset + magic.length) {
      return false;
    }
    for (int i = 0; i < magic.length; i++) {
      if (bytes[offset + i] != magic[i]) {
        return false;
      }
    }
    return true;
  }

  private static String extensionFor(String mediaType) {
    return switch (mediaType) {
      case PNG -> ".png";
      case JPEG -> ".jpg";
      case GIF -> ".gif";
      case WEBP -> ".webp";
      default -> throw new IllegalStateException("没有对应 " + mediaType + " 的扩展名");
    };
  }

  /**
   * 把上传的名字收敛成写下来安全的东西：一个路径元素、一个不被采信的扩展名，以及一组对文件系统没有意义
   * 的字符。
   */
  private static String sanitizedStem(String name) {
    String stem = name == null ? "" : name;
    // 直到最后一个分隔符之前的内容全部去掉，正是这一点让 "../../evil.png" 和 "/etc/evil.png" 变成普通
    // 名字而不是路径。
    int separator = Math.max(stem.lastIndexOf('/'), stem.lastIndexOf('\\'));
    if (separator >= 0) {
      stem = stem.substring(separator + 1);
    }
    int extension = stem.lastIndexOf('.');
    if (extension > 0) {
      stem = stem.substring(0, extension);
    }
    // 路径穿越需要一个分隔符，而这里已经没有——但存下来的名字会被人和模型读回去，所以看起来仍像路径的序列
    // 不留。
    stem = stem.replaceAll("\\.{2,}", ".").replaceAll("[^A-Za-z0-9._-]+", "_");
    stem = stem.replaceAll("^\\.+|\\.+$", "");
    if (stem.isEmpty()) {
      return DEFAULT_STEM;
    }
    return stem.length() <= MAX_STEM_LENGTH ? stem : stem.substring(0, MAX_STEM_LENGTH);
  }

  /** 第一次尝试用不带后缀的名字，之后是 {@code stem-1}、{@code stem-2}，依此类推。 */
  private static String fileName(String stem, String extension, int attempt) {
    return attempt == 1 ? stem + extension : stem + "-" + (attempt - 1) + extension;
  }

  /** 尽力而为的删除；这里的失败不能顶替引起它的那个错误。 */
  private static void deleteQuietly(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException ignored) {
      // 写入已经失败、即将被报告；剩下一个文件是更小的问题。
    }
  }

  private static String limitDescription() {
    return (MAX_BYTES / (1024 * 1024)) + " MB";
  }

  /**
   * 删掉一段会话的图片以及装它们的那个目录。
   *
   * <p>这是「把图片存在会话旁边」的另一半：被删掉的会话若把照片留在磁盘上，就会长出一个没人列出、没人
   * 读取、也没人清理的目录，而那正是当初选择这个位置时要避开的堆积。
   *
   * @return 有东西可删时为 true
   */
  public static boolean deleteFor(Path sessionFile) {
    return deleteDirectory(forSession(sessionFile).directory());
  }

  /**
   * 删掉一个由这个类拥有的目录，内容一并删掉。
   *
   * <p>深度优先，对遍历中的错误尽力而为：清不空的目录以「未删除」上报而不是抛出去，因为这是调用方的清理
   * 路径，那里的失败不能成为拦住一个会话被删掉的原因。
   *
   * @return 目录本来在、现在已经不在时为 true
   */
  public static boolean deleteDirectory(Path directory) {
    if (directory == null || !Files.isDirectory(directory)) {
      return false;
    }
    boolean removed = true;
    try (var entries = Files.list(directory)) {
      for (Path entry : entries.toList()) {
        if (Files.isDirectory(entry)) {
          removed &= deleteDirectory(entry);
        } else {
          try {
            Files.deleteIfExists(entry);
          } catch (IOException stuck) {
            removed = false;
          }
        }
      }
    } catch (IOException unreadable) {
      return false;
    }
    try {
      Files.deleteIfExists(directory);
      return removed;
    } catch (IOException stuck) {
      return false;
    }
  }
}
