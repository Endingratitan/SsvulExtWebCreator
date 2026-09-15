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

/**
 * **重建通知缝**：把重建的"开始 / 结果"推给外部观察者。M9a 起生产实现是 `SseHub`（浏览器自动刷新）；
 * {@link #noop()} 供测试与"只要构建不要通知"的场景。
 *
 * 约定（**并行渲染 A 的兼容性前提**）：这两个方法**只在编排线程、构建结束后**被调用，
 * 绝不在渲染 worker 里调 —— 否则并行化后会重复/乱序。实现本身必须**非阻塞**
 * （`SseHub` 用每客户端有界队列 + 溢出断开，绝不拖住建构建线程）。
 */
public interface ReloadNotifier {

    /** 即将开始一次重建（客户端据此显示"重建中…"）；`reason` = 触发它的变更集（空集 = 手动/首次触发） */
    default void building(ChangeSet reason) {
        // 默认不通知
    }

    /** 一次重建结束（成功失败都走这里；失败时 `report.ok() == false`） */
    void changed(BuildReport report);

    /** 什么都不做：测试与"静默预览"用 */
    static ReloadNotifier noop() {
        return report -> {
            // 无客户端
        };
    }
}
