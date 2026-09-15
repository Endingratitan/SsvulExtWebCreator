/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Preview;

import io.github.endingratitan.Integra.ChangeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 轮询监听门禁：**只跑短命、小样本、可关闭的真轮询**（3 个文件的临时站 + 100ms 间隔），
 * 断言完立刻 `close()` —— 绝不给 WSL 留下常驻轮询进程（这是本机资源纪律，见 tech.md）。
 */
public class WatcherTest {

    private static final int PORT_A = 20071;

    @TempDir
    Path tmp;

    private void write(File f, String s) throws Exception {
        Files.createDirectories(f.getParentFile().toPath());
        Files.writeString(f.toPath(), s);
    }

    /** 3 文件小站（够真检测，又便宜到 100ms 轮询也不心疼） */
    private File[] site() throws Exception {
        File sets = tmp.resolve("sets").toFile(), out = tmp.resolve("output").toFile();
        write(new File(sets, "Environment.config"), "cname=example.com\n");
        write(new File(sets, "divs/t/template.html"), "<div class=\"t\">{{content}}</div>");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"# 首页\\n\"}}}");
        return new File[]{sets, out};
    }

    private PollingWatcher watcher(File[] p, BlockingQueue<ChangeSet> got, WatchGate gate) {
        PollingWatcher w = new PollingWatcher(p[0], p[1], new File("src/assets"), "auto", 100, 120, 800, s -> { });
        w.start(got::add, gate);
        return w;
    }

    @Test
    void pollingDetectsChangeAndStopsAfterClose() throws Exception {
        File[] p = site();
        var r = io.github.endingratitan.Integra.SiteBuilder.buildReport(p[0], p[1], new File("src/assets"));
        assertTrue(r.ok(), String.join("\n", r.errors()));       // 先用一次构建建立"源记录"基准

        BlockingQueue<ChangeSet> got = new LinkedBlockingQueue<>();
        PollingWatcher w = watcher(p, got, WatchGate.open());
        try {
            assertNull(got.poll(500, TimeUnit.MILLISECONDS), "没有改动就不该回调（去抖 + 空集不回调）");
            write(new File(p[0], "pages/INDEX.json"),
                    "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"# 改过了\\n\"}}}");
            ChangeSet cs = got.poll(5, TimeUnit.SECONDS);
            assertNotNull(cs, "改动应在几秒内被检出");
            assertTrue(cs.files().contains("pages/INDEX.json"), cs.files().toString());
            assertTrue(w.pollCount() > 0, "应真的轮询过");
            assertTrue(w.pollCostMs() >= 0);
        } finally {
            w.close();
        }
        int after = w.pollCount();
        Thread.sleep(400);
        assertEquals(after, w.pollCount(), "close() 之后必须停止轮询");
    }

    @Test
    void autoModeStaysSilentWithoutViewers() throws Exception {
        File[] p = site();
        assertTrue(io.github.endingratitan.Integra.SiteBuilder.buildReport(p[0], p[1], new File("src/assets")).ok());
        BlockingQueue<ChangeSet> got = new LinkedBlockingQueue<>();
        WatchGate nobody = new WatchGate() {
            @Override
            public boolean viewersPresent() {
                return false;                                    // 没有浏览器连着（watch=auto 的真实场景）
            }

            @Override
            public boolean buildInFlight() {
                return false;
            }
        };
        PollingWatcher w = watcher(p, got, nobody);
        try {
            write(new File(p[0], "pages/INDEX.json"),
                    "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"# 没人看\\n\"}}}");
            assertNull(got.poll(800, TimeUnit.MILLISECONDS), "没人看 → 不轮询、不回调（省 WSL 资源）");
            assertEquals(0, w.pollCount(), "一次检测都不该跑");
        } finally {
            w.close();
        }
    }

    @Test
    void buildInFlightSkipsPolling() throws Exception {
        File[] p = site();
        assertTrue(io.github.endingratitan.Integra.SiteBuilder.buildReport(p[0], p[1], new File("src/assets")).ok());
        BlockingQueue<ChangeSet> got = new LinkedBlockingQueue<>();
        WatchGate busy = new WatchGate() {
            @Override
            public boolean viewersPresent() {
                return true;
            }

            @Override
            public boolean buildInFlight() {
                return true;                                     // 模拟"构建正在跑"
            }
        };
        PollingWatcher w = watcher(p, got, busy);
        try {
            Thread.sleep(600);
            assertEquals(0, w.pollCount(), "构建期间不轮询（不抢 I/O 与 CPU）");
            assertTrue(got.isEmpty());
        } finally {
            w.close();
        }
    }

    /** 全链路：编辑源文件 → watch 检出 → 重建 → SSE 推 `built` → 页面内容已更新 */
    @Test
    void editTriggersRebuildAndSsePush() throws Exception {
        File[] p = site();
        BlockingQueue<ChangeSet> got = new LinkedBlockingQueue<>();
        PollingWatcher w = new PollingWatcher(p[0], p[1], new File("src/assets"), "auto", 100, 120, 800, s -> { });
        PreviewOptions opt = PreviewOptions.of(p[0], p[1], new File("src/assets"), PORT_A)
                .withWatch(new WatchOptions("1", 100));
        try (PreviewServer s = PreviewServer.start(opt, w, ReloadNotifier.noop())) {
            assertEquals(PORT_A, s.port());
            HttpResponse<Stream<String>> resp = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + PORT_A + SseHub.CLIENT_PATH)).build(),
                    HttpResponse.BodyHandlers.ofLines());
            BlockingQueue<String> lines = new LinkedBlockingQueue<>();
            Thread pump = new Thread(() -> resp.body().forEach(lines::add), "test-sse");
            pump.setDaemon(true);
            pump.start();
            awaitEvent(lines, "ready", 3000);

            write(new File(p[0], "pages/INDEX.json"),
                    "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"# 自动重建\\n\"}}}");
            assertNotNull(awaitEvent(lines, "built", 8000), "编辑源文件应触发重建并推 built");

            HttpResponse<String> page = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + PORT_A + "/")).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertTrue(page.body().contains("自动重建"), "页面应已更新");
        } finally {
            w.close();
        }
    }

    private static String awaitEvent(BlockingQueue<String> lines, String event, long ms) throws Exception {
        long end = System.nanoTime() + ms * 1_000_000L;
        boolean seen = false;
        while (System.nanoTime() < end) {
            String l = lines.poll(100, TimeUnit.MILLISECONDS);
            if (l == null) continue;
            if (l.equals("event: " + event)) { seen = true; continue; }
            if (seen && l.startsWith("data: ")) return l.substring(6);
        }
        return null;
    }
}
