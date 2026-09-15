/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Preview;

import io.github.endingratitan.Integra.BuildReport;
import io.github.endingratitan.Integra.ChangeSet;
import io.github.endingratitan.Integra.SiteBuilder;

import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 本地预览服务（公共 API）。
 *
 * 做什么：**构建一次 → 静态服务产物目录**；`rebuild()` 可随时重建；watch（M9b）改动即重建，
 * 重建结果经 **SSE**（`/__ssvul/events`）推给浏览器，注入的客户端脚本自动刷新页面。
 * 不做什么（明确划界）：dev-panel / 孤儿清理 / `--open`。
 *
 * 四条硬性设计（tech.md §4 定稿 + 0.3.4 追加）：
 * ① 只绑 **`127.0.0.1`**（不暴露到局域网）+ **Host 校验**（防 DNS rebinding）；默认端口来自 `.env` 的 `preview-port`；
 * ② **读写锁** `ReentrantReadWriteLock`：请求持读锁、重建持写锁 → 不会读到写了一半的产物；
 * ③ **必须有界线程池**：`HttpServer` 默认执行器是"调用线程"，等于请求串行；这里用
 *    `ThreadPoolExecutor` + 有上限队列 + `CallerRunsPolicy`（满了就背压，不丢请求、不无限占内存）；
 * ④ **SSE 独立线程池**（`SseHub`）：长连接不能占静态请求池，否则两个标签页就能把池占满。
 */
public final class PreviewServer implements AutoCloseable {

    private final PreviewOptions opt;
    private final HttpServer server;
    private final ThreadPoolExecutor pool;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final AtomicReference<BuildReport> lastBuild = new AtomicReference<>();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final SourceWatcher watcher;
    private final ReloadNotifier notifier;
    private final SseHub hub;
    private final java.util.concurrent.atomic.AtomicBoolean building = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean closed;

    /** 启动（默认：无监听 = 不自动重建，无额外通知） */
    public static PreviewServer start(PreviewOptions opt) throws IOException {
        return start(opt, SourceWatcher.noop(), ReloadNotifier.noop());
    }

    /**
     * 启动：**先构建、后开服**——首建失败也照样起服务（页面会显示错误原因，而不是让你对着终端猜）。
     *
     * @param watcher  源监听（CLI 传 {@link PollingWatcher}；不自动重建时传 {@link SourceWatcher#noop()}）
     * @param notifier 额外通知（默认无；SSE 推送由内置 `SseHub` 负责，与它无关）
     */
    public static PreviewServer start(PreviewOptions opt, SourceWatcher watcher, ReloadNotifier notifier) throws IOException {
        PreviewServer s = new PreviewServer(opt, watcher, notifier);
        BuildReport first = s.rebuild();
        if (first.ok()) {
            IO.println("[预览] 首次构建完成：" + first.written().size() + " 个产物，"
                    + first.stats().tTotal + " ms（写盘 " + first.stats().filesWritten + " / 跳过 " + first.stats().filesSkipped + "）");
        } else {
            IO.println("[预览] 首次构建失败（服务照常启动，页面会显示错误）：");
            for (String e : first.errors()) IO.println("    " + e);
        }
        s.server.start();
        if (opt.watch().enabled()) {
            s.watcher.start(s::rebuild, s.gate());   // watch=auto 时由闸门决定"没人看就不轮询"
        }
        IO.println("[预览] http://127.0.0.1:" + s.port() + "/   产物 " + opt.outputDir().getPath()
                + "   （Ctrl+C 停止；watch=" + opt.watch().mode() + " poll=" + opt.watch().pollMs()
                + "ms SSE 上限 " + opt.maxClients() + "）");
        return s;
    }

    /** 闸门：`watch=1` 常开；`auto` 只在有 SSE 客户端时轮询；构建期间一律不轮询（single-flight） */
    private WatchGate gate() {
        return new WatchGate() {
            @Override
            public boolean viewersPresent() {
                return opt.watch().alwaysOn() || hub.clientCount() > 0;
            }

            @Override
            public boolean buildInFlight() {
                return building.get();
            }
        };
    }

    private PreviewServer(PreviewOptions opt, SourceWatcher watcher, ReloadNotifier notifier) throws IOException {
        this.opt = opt;
        this.watcher = watcher;
        this.notifier = notifier;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", opt.port()), 16);
        this.hub = new SseHub(opt.maxClients(), this::readyJson);
        this.server.createContext("/__ssvul/", new ControlHandler(hub));      // 保留命名空间（最长前缀 → 优先）
        this.server.createContext("/", new StaticHandler(opt.outputDir(), lock, lastBuild::get, opt.inject()));
        this.pool = new ThreadPoolExecutor(opt.threads(), opt.threads(), 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(opt.queue()),
                r -> {
                    Thread t = new Thread(r, "ssvul-preview");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());   // 队列满 → 调用线程执行（背压，不丢请求）
        this.server.setExecutor(pool);
    }

    /** SSE 建连时的首帧负载：端口 + 最近一次构建摘要（后连的标签页也能立刻知道状态） */
    private String readyJson() {
        Map<String, Object> m = SseHub.payload("port", port());
        BuildReport r = lastBuild.get();
        if (r != null) m.put("last", SseHub.summary(r));
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(m);
        } catch (Exception e) {
            return "{\"v\":1}";
        }
    }

    /**
     * 重建（写锁内）：请求期间不会看到半个产物；返回本次报告并通知监听者与 SSE 客户端。
     *
     * 事件时序（**只在编排线程**，构建结束后才发结果 —— 并行渲染 A 之后也不会重复/乱序）：
     * `building`（进锁前）→ 构建 → `built` / `build-error`（**出锁后**，客户端刷新时拿到的必然是新产物）。
     */
    public BuildReport rebuild() {
        return rebuild(ChangeSet.NONE);
    }

    /**
     * 重建（写锁内）：请求期间不会看到半个产物；返回本次报告并通知监听者与 SSE 客户端。
     *
     * 事件时序（**只在编排线程**，构建结束后才发结果 —— 并行渲染 A 之后也不会重复/乱序）：
     * `building`（进锁前）→ 构建 → `built` / `build-error`（**出锁后**，客户端刷新时拿到的必然是新产物）。
     * `building` 标记同时是 watch 闸门的 `buildInFlight`（构建期间不轮询、不重复触发）。
     */
    public BuildReport rebuild(ChangeSet reason) {
        building.set(true);
        try {
            notifier.building(reason);
            Map<String, Object> b = SseHub.payload("changed", List.copyOf(reason.files()));
            b.put("detector", reason.detector());
            hub.broadcast("building", b);
            BuildReport r;
            lock.writeLock().lock();
            try {
                r = SiteBuilder.buildReport(opt.setsDir(), opt.outputDir(), opt.assetsDir(),
                        new SiteBuilder.BuildOptions(opt.rebuild(), opt.detect(), opt.detector(), null));
                lastBuild.set(r);
            } finally {
                lock.writeLock().unlock();
            }
            notifier.changed(r);
            if (r.ok()) {
                hub.broadcast("built", SseHub.summary(r));
            } else {
                Map<String, Object> e = SseHub.payload("ok", false);
                e.put("errors", List.copyOf(r.errors()));
                e.put("ms", r.millis());
                hub.broadcast("build-error", e);
            }
            return r;
        } finally {
            building.set(false);
        }
    }

    /** 当前连着的 SSE 客户端数（M9b 的 auto 模式据此决定要不要轮询；测试也用它） */
    public int clientCount() {
        return hub.clientCount();
    }

    /** 实际监听端口（`--port 0` 时是内核分配的） */
    public int port() {
        return server.getAddress().getPort();
    }

    /** 最近一次构建报告（首建失败时非 ok） */
    public BuildReport lastBuild() {
        return lastBuild.get();
    }

    /** 阻塞直到 {@link #close()}（CLI 用它把前台让给服务；Ctrl+C → shutdown hook → close） */
    public void awaitTermination() throws InterruptedException {
        stopped.await();
    }

    /** 优雅停机：停监听 → 关线程池 → 放行 awaitTermination（可重复调用） */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        hub.close();                 // 先停推送（关掉所有 SSE 连接）
        try {
            watcher.close();
        } catch (Exception ignored) {
            // 缝实现的异常不该影响停机
        }
        server.stop(0);
        pool.shutdownNow();
        stopped.countDown();
    }
}
