/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Preview;

/**
 * 轮询"闸门"（公共 API）：监听器每轮先问它两个问题，**由服务提供**（只有服务知道客户端与构建状态）。
 *
 * 为什么需要它：
 * ① `viewersPresent()` —— `watch=auto` 的省资源关键：**没有浏览器连着就完全不轮询**（本机 9p 上一次
 *    `git status` 要 ~340ms/300 文件，没人看的时候一秒都不该花）；
 * ② `buildInFlight()` —— 构建期间不轮询（别和构建抢 I/O 与 CPU）；构建中发生的改动会在下一轮被检出
 *    （记录是构建期写的，改动晚于读取就一定会体现为差异）。
 */
public interface WatchGate {

    /** 是否有人在看（有 SSE 客户端） */
    boolean viewersPresent();

    /** 是否正在构建（single-flight：构建期间不轮询） */
    boolean buildInFlight();

    /** 常开闸门（`watch=1` 或测试手动驱动）：永远允许轮询、永远不认为在构建 */
    static WatchGate open() {
        return new WatchGate() {
            @Override
            public boolean viewersPresent() {
                return true;
            }

            @Override
            public boolean buildInFlight() {
                return false;
            }
        };
    }
}
