/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Preview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import io.github.endingratitan.Integra.BuildReport;
import io.github.endingratitan.Integra.ChangeSet;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * SSE 广播中枢（包内私有）：把"重建开始 / 结果"推给浏览器，页面据此自动刷新。
 *
 * 三条硬约束（都是踩过或算过的坑）：
 * ① **独立线程池**：`HttpServer` 没有异步 API，SSE handler 会阻塞到流结束；若和静态请求共用一个池，
 *    两个标签页就能把池占满、连 css 都拉不下来。这里给 SSE 单独开 `maxClients` 条 daemon 线程，
 *    静态请求池保持独立；超过上限直接 503（调用方回 `Retry-After`）。
 * ② **每客户端有界队列（32）**：慢客户端**断开**而不是拖住广播线程 —— 广播发生在构建结束后，
 *    绝不能因为某个卡住的浏览器把构建流程堵死（并行渲染 A 之后更甚）。
 * ③ **只在编排线程调用**：`building/built/build-error` 一律由 {@link PreviewServer#rebuild} 在构建结束后发出，
 *    绝不在渲染 worker 里发（否则并行化后会重复/乱序）。
 *
 * 协议：命名事件 + 自增 `id`（支持断线重连的 `Last-Event-ID` 回放，保留最近 16 条）+ 15s `: ping` 心跳。
 */
final class SseHub implements AutoCloseable {

    static final String CLIENT_PATH = "/__ssvul/events";
    private static final int CLIENT_QUEUE = 32;      // 每客户端待发帧上限
    private static final int REPLAY_MAX = 16;        // 断线回放缓冲
    private static final long HEARTBEAT_MS = 15_000; // 心跳间隔（防代理/空闲超时）
    private static final ObjectMapper JSON = new ObjectMapper();

    private record Ev(long id, String frame) {}

    private final int maxClients;
    private final ExecutorService pool;
    private final List<Client> clients = new CopyOnWriteArrayList<>();
    private final Deque<Ev> replay = new ArrayDeque<>();             // 只在 broadcast（编排线程）里动
    private final AtomicLong seq = new AtomicLong();
    private final AtomicInteger connected = new AtomicInteger();
    private final Supplier<String> readyJson;                       // 由服务提供（含端口与最近一次构建摘要）
    private volatile boolean closed;

    SseHub(int maxClients, Supplier<String> readyJson) {
        this.maxClients = Math.max(1, maxClients);
        this.readyJson = readyJson;
        this.pool = Executors.newFixedThreadPool(this.maxClients, r -> {
            Thread t = new Thread(r, "ssvul-sse");
            t.setDaemon(true);
            return t;
        });
    }

    int clientCount() { return connected.get(); }

    int maxClients() { return maxClients; }

    /** 接入一个客户端；超过上限/已关闭 → false（调用方回 503 + Retry-After） */
    boolean add(HttpExchange ex, String lastEventId) throws IOException {
        if (closed || connected.get() >= maxClients) return false;
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Accel-Buffering", "no");      // 万一套了反代：别缓冲
        ex.sendResponseHeaders(200, 0);                              // 0 = chunked（长连接不能给 Content-Length）
        Client c = new Client(ex, ex.getResponseBody());
        clients.add(c);
        connected.incrementAndGet();
        pool.execute(() -> pump(c, lastEventId));
        return true;
    }

    /** 广播（**只在编排线程调用**）；无客户端时直接返回（零开销） */
    void broadcast(String event, Map<String, Object> data) {
        if (closed || clients.isEmpty()) return;
        long id = seq.incrementAndGet();
        String frame = frame(id, event, json(data));
        replay.addLast(new Ev(id, frame));
        while (replay.size() > REPLAY_MAX) replay.pollFirst();
        for (Client c : clients) {
            if (!c.queue.offer(frame)) remove(c, "队列满（慢客户端）");
        }
    }

    /** 事件负载（`v:1` 版本位：将来加字段/加事件不破坏老客户端） */
    static Map<String, Object> payload(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("v", 1);
        if (key != null) m.put(key, value);
        return m;
    }

    /** 把 `BuildReport` 折成客户端要的摘要（**不放错误堆栈**，错误单独走 build-error 事件） */
    static Map<String, Object> summary(BuildReport r) {
        Map<String, Object> m = payload("ok", r.ok());
        m.put("ms", r.millis());
        m.put("written", r.stats().filesWritten);
        m.put("skipped", r.stats().filesSkipped);
        m.put("changed", List.copyOf(r.changedFiles()));
        return m;
    }

    /** 单个客户端：一次 retry 提示 → ready → 回放 → 队列/心跳循环 */
    private void pump(Client c, String lastEventId) {
        try {
            c.write("retry: 1000\n\n");
            c.write(frame(seq.incrementAndGet(), "ready", readyJson.get()));
            long since = parseId(lastEventId);
            if (since > 0) {
                for (Ev e : List.copyOf(replay)) {
                    if (e.id() > since) c.write(e.frame());
                }
            }
            while (!closed && c.open) {
                String f = c.queue.poll(HEARTBEAT_MS, TimeUnit.MILLISECONDS);
                c.write(f != null ? f : ": ping\n\n");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // 客户端断开/网络抖动：正常路径
        } finally {
            remove(c, "断开");
        }
    }

    private void remove(Client c, String why) {
        if (!c.open) return;
        c.open = false;
        if (clients.remove(c)) connected.decrementAndGet();
        try {
            c.ex.close();
        } catch (Exception ignored) {
            // 连接可能已经没了
        }
    }

    private static String frame(long id, String event, String data) {
        return "id: " + id + "\nevent: " + event + "\ndata: " + data + "\n\n";
    }

    private static long parseId(String v) {
        if (v == null || v.isEmpty()) return 0;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String json(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    @Override
    public void close() {
        closed = true;
        for (Client c : clients) remove(c, "关服");
        clients.clear();
        connected.set(0);
        pool.shutdownNow();
    }

    /** 一个浏览器连接：**单写者**（只有它的 pump 线程写），队列有界 */
    private static final class Client {
        final HttpExchange ex;
        final OutputStream out;
        final BlockingQueue<String> queue = new ArrayBlockingQueue<>(CLIENT_QUEUE);
        volatile boolean open = true;

        Client(HttpExchange ex, OutputStream out) {
            this.ex = ex;
            this.out = out;
        }

        void write(String s) throws IOException {
            out.write(s.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }
}
