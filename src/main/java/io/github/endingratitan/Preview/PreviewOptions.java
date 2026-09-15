/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Preview;

import java.io.File;

/**
 * 预览选项（公共 API）。
 *
 * 定位（0.3.3 最小版）：**只做"本地静态服务 + 一次构建"**——绑 `127.0.0.1`、默认端口 **23143**（`.env` 的
 * `preview-port`）、`--port 0` 由内核分配（测试用）。watch / SSE / dev-panel / 孤儿清理 / `--open` 全部留给 v4，
 * 但缝已就位（{@link SourceWatcher} / {@link ReloadNotifier}）。
 *
 * @param setsDir   站点源目录（标准布局 = `sets/`）
 * @param outputDir 产物目录（被静态服务；构建写它、请求读它 → 靠读写锁隔离）
 * @param assetsDir 预设资产目录（标准布局 = `src/assets`）
 * @param port      监听端口；**0 = 由内核分配**（测试用，实际端口见 {@link PreviewServer#port()}）
 * @param threads   有界线程池大小（HttpServer 默认执行器是"调用线程"= 请求串行，必须显式设池）
 * @param queue     线程池队列上限（满 → CallerRunsPolicy 背压，不丢请求也不无限占内存）
 * @param detect    每轮构建是否采集变更清单（watch 会用它；手动 `rebuild()` 时默认关）
 * @param detector  检测器偏好 auto|git|stat；null = 沿用 `.env`
 * @param rebuild   每轮都忽略 `.ssvul/deps-*.json`（排错用）
 * @param inject    是否给 HTML 响应注入预览客户端脚本（**只改响应、不动产物**；关掉可看"真实产物"）
 * @param maxClients 同时在线的 SSE 客户端上限（独立线程池；超限 503 + Retry-After）
 */
public record PreviewOptions(File setsDir, File outputDir, File assetsDir,
                            int port, int threads, int queue,
                            boolean detect, String detector, boolean rebuild,
                            boolean inject, int maxClients, WatchOptions watch) {

    public static final int DEF_PORT = 23143;
    public static final int DEF_THREADS = 4;
    public static final int DEF_QUEUE = 64;
    public static final int DEF_MAX_CLIENTS = 16;

    /** 常用构造：端口由调用方给（CLI 传 `.env` 的 preview-port 或 `--port`；测试传固定质数） */
    public static PreviewOptions of(File setsDir, File outputDir, File assetsDir, int port) {
        return new PreviewOptions(setsDir, outputDir, assetsDir, port, DEF_THREADS, DEF_QUEUE,
                false, null, false, true, DEF_MAX_CLIENTS, WatchOptions.DEFAULT);
    }

    /** 便捷：只改 watch 选项（测试里最常用） */
    public PreviewOptions withWatch(WatchOptions w) {
        return new PreviewOptions(setsDir, outputDir, assetsDir, port, threads, queue,
                detect, detector, rebuild, inject, maxClients, w);
    }

    /** 便捷：只改注入与客户端上限 */
    public PreviewOptions withSse(boolean injectHtml, int maxClients) {
        return new PreviewOptions(setsDir, outputDir, assetsDir, port, threads, queue,
                detect, detector, rebuild, injectHtml, maxClients, watch);
    }
}
