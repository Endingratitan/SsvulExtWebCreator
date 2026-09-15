/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Integra;

import java.util.List;
import java.util.Set;

/**
 * 一次构建的结果报告（公共 API，供预览 / CI / 结构门禁测试使用）。
 *
 * @param ok           是否成功（false 时 output/ 一般仍是上一次成功的状态——管线在写盘前就抛错）
 * @param errors       收集到的错误（失败时非空）
 * @param warnings     收集到的警告（构建照常进行）
 * @param written      本次构建的**完整写集**（相对 output 的路径，**含被跳过写入的文件**）
 * @param changedFiles 变更清单（相对 sets/ 的路径；**仅当本次采集过**——普通构建为零开销不采集）
 * @param detector     本次用的检测器：`off`（未采集）/ `git` / `stat`
 * @param millis       端到端耗时（毫秒）
 * @param stats        行为计数与分相耗时（性能门禁断言这些计数，而不是墙钟）
 */
public record BuildReport(boolean ok, List<String> errors, List<String> warnings,
                          Set<String> written, Set<String> changedFiles, String detector,
                          long millis, BuildStats stats) {
}
