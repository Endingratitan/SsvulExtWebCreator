/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Preview;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 预览最小版门禁：**真 `HttpServer`（绑 `127.0.0.1:0`）+ JDK `HttpClient`**，不用桩。
 * 覆盖：端口分配 / MIME / 目录回落 / 404 / 路径穿越 403 / 405 / HEAD 无体 / 重建后新内容 /
 * 构建失败给错误页 / 并发请求。
 */
public class PreviewTest {

    /**
     * 测试端口 = **固定的 >20000 质数**（不用 0：内核分配会掩盖端口相关缺陷，且每次端口不同就没法断言接线正确）。
     * 每例一个不同质数，避免同一端口反复绑定时的复用抖动；默认端口 23143 同样是 >20000 的质数。
     * （注：TCP 端口是 16 位，上限 65535，不存在"大于 200000"的端口。）
     */
    private static final int PORT_A = 20011, PORT_B = 20021, PORT_C = 20023, PORT_D = 20029, PORT_E = 20047;

    @TempDir
    Path tmp;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private void write(File f, String s) throws Exception {
        Files.createDirectories(f.getParentFile().toPath());
        Files.writeString(f.toPath(), s);
    }

    /** 2 页小站：首页 + pages/p0（带自己的 js/css） */
    private File[] site() throws Exception {
        File sets = tmp.resolve("sets").toFile(), out = tmp.resolve("output").toFile();
        write(new File(sets, "Environment.config"), "cname=example.com\n");
        write(new File(sets, "divs/t/template.html"), "<div class=\"t\">{{content}}</div>");
        write(new File(sets, "divs/t/t.js"), "window.SsvulDiv && SsvulDiv.register('t', { init: function () {} });\n");
        write(new File(sets, "divs/t/t.css"), ".t { color: red }\n");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"# 首页\\n\"}}}");
        write(new File(sets, "pages/p0.json"),
                "{\"name\":\"p0\",\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"# 第一页\\n\"}}}");
        return new File[]{sets, out};
    }

    private String url(PreviewServer s, String path) {
        return "http://127.0.0.1:" + s.port() + path;
    }

    private HttpResponse<String> get(PreviewServer s, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url(s, path))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private PreviewServer start(File sets, File out, int port) throws Exception {
        return PreviewServer.start(PreviewOptions.of(sets, out, new File("src/assets"), port));
    }

    @Test
    void servesProductsAndGuardsPaths() throws Exception {
        File[] p = site();
        try (PreviewServer s = start(p[0], p[1], PORT_A)) {
            assertEquals(PORT_A, s.port(), "固定质数端口：接线必须真的照做");
            assertTrue(s.lastBuild().ok(), String.join("\n", s.lastBuild().errors()));

            HttpResponse<String> root = get(s, "/");
            assertEquals(200, root.statusCode());
            assertTrue(root.headers().firstValue("Content-Type").orElse("").startsWith("text/html"),
                    root.headers().firstValue("Content-Type").orElse(""));
            assertEquals("no-store", root.headers().firstValue("Cache-Control").orElse(""), "预览绝不缓存");
            assertTrue(root.body().contains("首页"), root.body());

            assertEquals(200, get(s, "/pages/p0/").statusCode(), "目录 → index.html");
            assertEquals(200, get(s, "/pages/p0/index.html").statusCode());
            String css = get(s, "/pages/p0/p0.css").headers().firstValue("Content-Type").orElse("");
            assertEquals("text/css; charset=utf-8", css, "按扩展名给 MIME");
            assertEquals(404, get(s, "/nope.html").statusCode());
            assertEquals(403, get(s, "/%2e%2e/Environment.config").statusCode(), "路径穿越必须 403");
            assertEquals(403, get(s, "/..%2fEnvironment.config").statusCode(), "编码穿越同样 403");

            HttpResponse<String> post = http.send(HttpRequest.newBuilder(URI.create(url(s, "/")))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(405, post.statusCode(), "只支持 GET/HEAD");

            HttpResponse<String> head = http.send(HttpRequest.newBuilder(URI.create(url(s, "/")))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, head.statusCode());
            assertTrue(head.body().isEmpty(), "HEAD 不返回体");
        }
    }

    @Test
    void rebuildPublishesNewContent() throws Exception {
        File[] p = site();
        try (PreviewServer s = start(p[0], p[1], PORT_B)) {
            assertEquals(200, get(s, "/").statusCode());
            write(new File(p[0], "pages/INDEX.json"),
                    "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"# 改过的首页\\n\"}}}");
            var r = s.rebuild();
            assertTrue(r.ok(), String.join("\n", r.errors()));
            assertTrue(r.stats().filesWritten > 0, "改动必须落到产物");
            assertTrue(get(s, "/").body().contains("改过的首页"), "重建后立刻可见新内容");
        }
    }

    @Test
    void failedBuildShowsErrorPageInsteadOfHidingIt() throws Exception {
        File[] p = site();
        // 首建就失败：坏 JSON（服务仍要起来，并把原因摆在页面上）
        write(new File(p[0], "pages/broken.json"), "{ 这不是 json }");
        try (PreviewServer s = start(p[0], p[1], PORT_C)) {
            assertFalse(s.lastBuild().ok(), "坏源 → 构建失败");
            HttpResponse<String> miss = get(s, "/missing.html");
            assertEquals(500, miss.statusCode(), "构建失败时不装作 404");
            assertTrue(miss.body().contains("构建失败"), miss.body());
            assertTrue(miss.body().contains("broken.json") || miss.body().contains("JSON"), miss.body());
        }
    }

    @Test
    void concurrentRequestsUseBoundedPool() throws Exception {
        File[] p = site();
        try (PreviewServer s = start(p[0], p[1], PORT_D)) {
            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                List<Callable<Integer>> tasks = new ArrayList<>();
                for (int i = 0; i < 32; i++) {
                    String path = (i % 2 == 0) ? "/" : "/pages/p0/";
                    tasks.add(() -> get(s, path).statusCode());
                }
                List<Future<Integer>> rs = pool.invokeAll(tasks);
                for (Future<Integer> f : rs) assertEquals(200, f.get(), "并发请求全部成功（有界池 + 读锁）");
            } finally {
                pool.shutdownNow();
            }
            // 重建与请求并发：也不该崩（写锁与读锁互斥）
            write(new File(p[0], "pages/p0.json"),
                    "{\"name\":\"p0\",\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"# 并发重建\\n\"}}}");
            assertTrue(s.rebuild().ok());
            assertEquals(200, get(s, "/pages/p0/").statusCode());
        }
    }

    @Test
    void closeStopsServerAndReleasesAwait() throws Exception {
        File[] p = site();
        PreviewServer s = start(p[0], p[1], PORT_E);
        int port = s.port();
        assertEquals(PORT_E, port);
        assertEquals(200, get(s, "/").statusCode());
        s.close();
        s.close();                                   // 幂等
        assertThrows(Exception.class, () -> get(s, "/"), "关闭后连接应失败");
    }
}
