/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Integra;

/**
 * 警告通道（R12：⑧ 的预留缝，当前无实现无调用）。
 * 计划：`Console`（默认，现有 `[构建警告]` 行为）与 `Page`（写进页面内的 warn-panel）双实现，
 * 由 ⑧ 的 `warn-display` 键选择；在它落地前，警告一律走 {@code SiteBuilder.warn(msg)}。
 */
public interface WarnTun {
    /**
    * @param warn   warning提示信息
    * @param way    warning提示方式
     **/

    String WarnReport(String warn, String way);

}
