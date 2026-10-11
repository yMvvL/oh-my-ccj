package com.ccj.agent.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.ui.QrCode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 启动横幅里那个码的**决定**：什么时候画、什么时候什么都不画。
 *
 * <p>编码本身由 {@code QrCodeTest} 交给另一个实现去验证（zbar 解码、与 qrencode 逐模块比对），这里只管
 * 「该不该画」——而这一条也有一个明确的验收：用户点名 `--host 127.0.0.1` 时**不**打印二维码，因为那个地址
 * 在手机上根本走不通，打出来是骗人。
 */
class PhoneCodeTest {

  @Test
  void aLoopbackOnlyAddressListPrintsNothing() {
    // 这就是 --host 127.0.0.1：清单里没有任何带 token 的地址。
    assertEquals("", Cli.phoneCode(List.of("http://127.0.0.1:6767/")));
    assertEquals("", Cli.phoneCode(List.of()));
  }

  @Test
  void aTokenisedAddressBecomesACode() {
    String url = "http://100.64.0.1:6767/?token=0123456789abcdef0123456789abcdef";
    String code = Cli.phoneCode(List.of("http://127.0.0.1:6767/", url));

    assertFalse(code.isEmpty(), "有一个带 token 的地址时该画一个码");
    assertTrue(code.contains("\u2588") || code.contains("\u2580") || code.contains("\u2584"), code);
    // 码的行数由那个地址的版本决定；这里只确认它是一条形状正确的半块画。
    long lines = code.lines().count();
    assertEquals(
        (QrCode.matrix(url).length + 4 + 1) / 2,
        lines,
        "白边 2 格、一行两格模块：" + lines);
  }

  @Test
  void anAddressTooLongForTheEncoderFallsBackInsteadOfFailing() {
    // 超过 10 版（级别 M 下约 213 字节）时返回空串，调用方于是打印「拷到手机上」那句——而不是抛异常。
    String huge = "http://100.64.0.1:6767/?token=" + "a".repeat(300);
    assertFalse(QrCode.fits(huge));
    assertEquals("", Cli.phoneCode(List.of(huge)));
  }

  @Test
  void theFirstTokenisedAddressWins() {
    // 有几条带 token 的地址时（比如同时监听了 tailnet 与一个具名地址），画第一条，且它是完整的那一条。
    String first = "http://100.64.0.1:6767/?token=first";
    String second = "http://192.168.1.5:6767/?token=second";
    String code = Cli.phoneCode(List.of("http://127.0.0.1:6767/", first, second));

    assertEquals(QrCode.forTerminal(first, 2), code);
  }
}
