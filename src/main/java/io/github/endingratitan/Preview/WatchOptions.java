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
 * watch 选项（公共 API）。
 *
 * @param mode   `auto`（默认：**有浏览器连着才轮询**，没人看零开销）/ `1`（常开）/ `0`（关，只手动重建）
 * @param pollMs 轮询间隔下限（毫秒）；实际间隔会按单次检测耗时自适应退避到最多 2s
 */
public record WatchOptions(String mode, int pollMs) {

    public static final String AUTO = "auto";
    public static final WatchOptions DEFAULT = new WatchOptions(AUTO, 700);

    public WatchOptions {
        if (mode == null || mode.isEmpty()) mode = AUTO;
        if (pollMs < 50) pollMs = 50;
    }

    /** 是否启用监听（`mode=0` 就完全不创建/不启动监听器） */
    public boolean enabled() { return !mode.equals("0"); }

    /** 是否常开（`mode=1`：即使没有浏览器连着也轮询） */
    public boolean alwaysOn() { return mode.equals("1"); }
}
