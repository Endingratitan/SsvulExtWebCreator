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

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SSE / 客户端注入 / 保留路径 / Host 校验的门禁（真 `HttpServer` + JDK `HttpClient`，零依赖）。
 *
 * SSE 读取用"后台线程灌队列 + 测试按需 poll"的写法 —— 直接顺序读流会死等，测试就没法给超时。
 */
public class SseTest {

    private static final int PORT_A = 20051, PORT_B = 20053, PORT_C = 20059, PORT_D = 20061, PORT_E = 20063;

    @TempDir
    Path tmp;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private void write(File f, String s) throws Exception {
        Files.createDirectories(f.getParentFile().toPath());
        Files.writeString(f.toPath(), s);
    }

    private File[] site() throws Exception {
        File sets = tmp.resolve("sets").toFile(), out = tmp.resolve("output").toFile();
        write(new File(sets, "Environment.config"), "cname=example.com\n");
        write(new File(sets, "divs/t/template.html"), "<div class=\"t\">{{content}}</div>");
        write(new File(sets, "divs/t/t.css"), ".t { color: red }\n");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"# 首页\\n\"}}}");
        return new File[]{sets, out};
    }

    private String url(PreviewServer s, String path) {
        return "http://127.0.0.1:" + s.port() + path;
    }

    private HttpResponse<String> get(PreviewServer s, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url(s, path))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** SSE 读取器：后台线程把行灌进队列，测试按需 poll（绝不无超时死等） */
    private final class SseReader implements AutoCloseable {
        private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        private final HttpResponse<Stream<String>> resp;
        private final Thread pump;

        SseReader(PreviewServer s) throws Exception {
            resp = http.send(HttpRequest.newBuilder(URI.create(url(s, SseHub.CLIENT_PATH)))
                            .header("Accept", "text/event-stream").GET().build(),
                    HttpResponse.BodyHandlers.ofLines());
            pump = new Thread(() -> resp.body().forEach(lines::add), "test-sse-reader");
            pump.setDaemon(true);
            pump.start();
        }

        int status() { return resp.statusCode(); }

        /** 等到指定事件的 data 行（`ms` 超时） */
        String awaitData(String event, long ms) throws Exception {
            long end = System.nanoTime() + ms * 1_000_000L;
            boolean seen = false;
            while (System.nanoTime() < end) {
                String l = lines.poll(100, TimeUnit.MILLISECONDS);
                if (l == null) continue;
                if (l.equals("event: " + event)) { seen = true; continue; }
                if (seen && l.startsWith("data: ")) return l.substring(6);
                if (!l.isEmpty() && !l.startsWith("data: ") && !l.startsWith("id: ")) seen = false;
            }
            return null;
        }

        String awaitLine(String prefix, long ms) throws Exception {
            long end = System.nanoTime() + ms * 1_000_000L;
            List<String> all = new ArrayList<>();
            while (System.nanoTime() < end) {
                String l = lines.poll(100, TimeUnit.MILLISECONDS);
                if (l != null) {
                    all.add(l);
                    if (l.startsWith(prefix)) return l;
                }
            }
            return null;
        }

        @Override
        public void close() {
            try {
                resp.body().close();
            } catch (Exception ignored) {
                // 流已结束
            }
        }
    }

    @Test
    void ssePushesBuildingAndBuilt() throws Exception {
        File[] p = site();
        try (PreviewServer s = PreviewServer.start(PreviewOptions.of(p[0], p[1], new File("src/assets"), PORT_A));
             SseReader r = new SseReader(s)) {
            assertEquals(200, r.status());
            assertNotNull(r.awaitLine("retry: ", 2000), "首帧应有 retry 提示");
            String ready = r.awaitData("ready", 3000);
            assertNotNull(ready, "连上应立刻收到 ready");
            assertTrue(ready.contains("\"v\":1") && ready.contains("\"port\":" + PORT_A), ready);

            // 手动触发一次重建 → 应收到 building + built
            var report = s.rebuild();
            assertTrue(report.ok(), String.join("\n", report.errors()));
            String building = r.awaitData("building", 3000);
            assertNotNull(building, "重建开始应有 building 事件");
            String built = r.awaitData("built", 5000);
            assertNotNull(built, "重建结束应有 built 事件");
            assertTrue(built.contains("\"ok\":true"), built);
            assertTrue(built.contains("\"written\":"), built);
        }
    }

    @Test
    void sseReportsBuildErrorWithoutReload() throws Exception {
        File[] p = site();
        write(new File(p[0], "pages/broken.json"), "{ 这不是 json }");
        try (PreviewServer s = PreviewServer.start(PreviewOptions.of(p[0], p[1], new File("src/assets"), PORT_B));
             SseReader r = new SseReader(s)) {
            assertNotNull(r.awaitData("ready", 3000));
            assertFalse(s.rebuild().ok(), "坏源必须构建失败");
            String err = r.awaitData("build-error", 5000);
            assertNotNull(err, "失败应发 build-error（客户端据此显示覆盖层且不刷新）");
            assertTrue(err.contains("\"ok\":false") && err.contains("errors"), err);
        }
    }

    @Test
    void injectionOnlyOnHtmlAndOnlyInResponse() throws Exception {
        File[] p = site();
        try (PreviewServer s = PreviewServer.start(PreviewOptions.of(p[0], p[1], new File("src/assets"), PORT_C))) {
            String html = get(s, "/").body();
            assertEquals(1, countOccurrences(html, "/__ssvul/client.js"), "HTML 响应应恰好注入一次");
            String css = get(s, "/pages/INDEX/index.css").body();
            assertFalse(css.contains("__ssvul"), "非 HTML 不注入");
            // 客户端脚本本身可服务
            HttpResponse<String> js = get(s, "/__ssvul/client.js");
            assertEquals(200, js.statusCode());
            assertTrue(js.headers().firstValue("Content-Type").orElse("").startsWith("text/javascript"));
            assertTrue(js.body().contains("EventSource"), js.body());
            // 保留路径的其它东西一律 403
            assertEquals(403, get(s, "/__ssvul/secret.txt").statusCode());
            // **产物本身零字节变化**（注入只发生在响应里）
            String disk = Files.readString(new File(p[1], "index.html").toPath());
            assertFalse(disk.contains("__ssvul"), "注入绝不能落进产物");
        }
    }

    @Test
    void injectCanBeDisabled() throws Exception {
        File[] p = site();
        PreviewOptions opt = new PreviewOptions(p[0], p[1], new File("src/assets"), PORT_D,
                PreviewOptions.DEF_THREADS, PreviewOptions.DEF_QUEUE, false, null, false, false, 2,
                WatchOptions.DEFAULT);
        try (PreviewServer s = PreviewServer.start(opt)) {
            assertFalse(get(s, "/").body().contains("__ssvul"), "--inject 0 时不应注入");
        }
    }

    @Test
    void rejectsForeignHostAndCapsClients() throws Exception {
        File[] p = site();
        PreviewOptions opt = new PreviewOptions(p[0], p[1], new File("src/assets"), PORT_E,
                PreviewOptions.DEF_THREADS, PreviewOptions.DEF_QUEUE, false, null, false, true, 1,
                WatchOptions.DEFAULT);
        try (PreviewServer s = PreviewServer.start(opt)) {
            // ① DNS rebinding 防护：裸 socket 发一个 Host: evil.com 的请求（HttpClient 不许改 Host）
            assertTrue(rawStatus(s.port(), "evil.com").startsWith("HTTP/1.1 403"), rawStatus(s.port(), "evil.com"));
            assertTrue(rawStatus(s.port(), "127.0.0.1:" + PORT_E).startsWith("HTTP/1.1 200"),
                    rawStatus(s.port(), "127.0.0.1:" + PORT_E));
            // ② SSE 上限：第 1 个连上，第 2 个被 503 拒绝
            try (SseReader first = new SseReader(s)) {
                assertNotNull(first.awaitData("ready", 3000));
                assertEquals(1, s.clientCount());
                HttpResponse<String> second = http.send(
                        HttpRequest.newBuilder(URI.create(url(s, SseHub.CLIENT_PATH))).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(503, second.statusCode(), "超过 SSE 上限应 503（客户端会按 Retry-After 重试）");
            }
        }
    }

    private static String rawStatus(int port, String host) throws Exception {
        try (Socket sock = new Socket("127.0.0.1", port)) {
            sock.setSoTimeout(5000);
            OutputStream os = sock.getOutputStream();
            os.write(("GET / HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
            return in.readLine();
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }
}
