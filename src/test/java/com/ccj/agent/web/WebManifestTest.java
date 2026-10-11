package com.ccj.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 「加到主屏」需要的那两样：一个清单、一个图标。
 *
 * <p>它们都是静态文件，所以这里断言的是**它们真的被服务出去了**，以及页面真的引用了它们——一个 404 的清单
 * 在浏览器里看起来和「这个站点不支持安装」一模一样，而那是唯一会发现的症状。
 */
class WebManifestTest extends WebHarness {

  @Test
  void theManifestIsServedAndSaysHowToInstallIt() throws Exception {
    HttpResponse<String> response = get("/manifest.webmanifest");

    assertEquals(200, response.statusCode(), response.body());
    assertTrue(
        response.headers().firstValue("content-type").orElse("").contains("manifest+json"),
        "清单要带 manifest+json 的内容类型：" + response.headers().firstValue("content-type"));
    JsonNode manifest = Json.parse(response.body());
    assertEquals("/", manifest.path("start_url").asText());
    assertEquals("standalone", manifest.path("display").asText());
    assertEquals("ccj", manifest.path("short_name").asText());
    assertTrue(manifest.path("name").asText().length() > 3, manifest.toString());
    assertEquals("/icon.svg", manifest.path("icons").get(0).path("src").asText());
  }

  @Test
  void theIconIsServedAsAnSvg() throws Exception {
    HttpResponse<String> response = get("/icon.svg");

    assertEquals(200, response.statusCode(), response.body());
    assertTrue(response.body().startsWith("<svg"), response.body().substring(0, 40));
    assertTrue(
        response.headers().firstValue("content-type").orElse("").contains("image/svg+xml"),
        "图标要带 svg 的内容类型：" + response.headers().firstValue("content-type"));
  }

  @Test
  void thePageLinksBothOfThemOrNobodyEverInstallsIt() throws Exception {
    String page = Files.readString(Path.of("src", "main", "resources", "web", "index.html"));

    assertTrue(page.contains("rel=\"manifest\" href=\"/manifest.webmanifest\""), "页面要引用清单");
    assertTrue(page.contains("rel=\"icon\" href=\"/icon.svg\""), "页面要引用图标");
    assertNotNull(hub.status(), "顺带确认这个用例的服务器真的起来了");
  }

  @Test
  void anUnknownStaticFileIsStillRefused() throws Exception {
    // 新加的两条路由不该把「按资源名取文件」变成一条任意读取：这个端点只认写死的那几个名字。
    assertEquals(404, get("/web/../../pom.xml").statusCode());
    assertEquals(404, get("/nope.js").statusCode());
  }
}
