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

import java.util.function.Consumer;

/**
 * **源变更监听缝**：由 git 探测 / stat 轮询 / `WatchService` 实现；本批（M9a）只有 {@link #noop()}，
 * M9b 落 `PollingWatcher`（git/stat + 哈希复核 + 自适应退避 + 按需唤醒）。
 *
 * 为什么参数是 `Consumer<ChangeSet>` 而不是 `Runnable`：监听器必须能说出**改了什么** ——
 * ① 预览据此决定"要不要重建"（空集不重建）；② SSE 要把变更清单发给客户端；
 * ③ v5 增量要靠它决定重算范围。回调**只在确实有变更时**发生（去抖与过滤在实现里做）。
 *
 * 为什么必须轮询：本机 `/mnt/d` 上 inotify 实测**完全无事件**（注册 300 子目录还要 1565ms），
 * 可靠的变更检测只能走 "git status（~1.1ms/文件）或 stat 快筛 + 内容哈希"。
 */
public interface SourceWatcher extends AutoCloseable {

    /**
     * 开始监听；检测到变更时回调（空集不回调）。
     *
     * @param gate 闸门：实现每轮先问"有人在看吗 / 正在构建吗"——`watch=auto` 靠它做到**没人看就不轮询**，
     *             以及构建期间不抢 I/O（详见 {@link WatchGate}）
     */
    void start(Consumer<ChangeSet> onChange, WatchGate gate);

    @Override
    void close();

    /** 什么都不做（不自动重建）：测试与"只看不重建"的场景 */
    static SourceWatcher noop() {
        return new SourceWatcher() {
            @Override
            public void start(Consumer<ChangeSet> onChange, WatchGate gate) {
                // 不监听
            }

            @Override
            public void close() {
                // 无资源
            }
        };
    }
}
