/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
/**
 * 本地预览子系统（0.3.3 最小版）：`HttpServer` 绑 `127.0.0.1` + 静态服务产物目录 + 读写锁 + 有界线程池。
 *
 * 分层：本包**只消费** {@code Integra} 的公共门面（{@code SiteBuilder.buildReport} / {@code BuildReport}），
 * 不碰管线内部；CLI（`SsvulExtWebCreator`）再消费本包。v4 的 watch/SSE 从这里两条缝接入：
 * {@link io.github.endingratitan.Preview.SourceWatcher}（源监听）与
 * {@link io.github.endingratitan.Preview.ReloadNotifier}（重建通知）。
 *
 * 为什么最小版不做监听：本机 `/mnt/d`（9p）上 inotify 实测**完全无事件**，可靠的变更检测要靠
 * git / stat+哈希（见 tech.md §2），那是 v4 的工程。
 */
package io.github.endingratitan.Preview;
