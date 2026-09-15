/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
/**
 * 本机能力层（`Settings`）：**只管"这台机器怎么跑生成器"，一个字节都不进产物**。
 *
 * 分层判据（2026-09 定，从 `Integra` 拆出）：
 * <ul>
 *   <li><b>产物相关 → `Integra`</b>：`sets/Environment.config` 是网站契约（cname/bucket/minify… 决定产物字节），
 *       所以它留在 Integra 的配置链里；</li>
 *   <li><b>机器相关 → 本包</b>：{@link io.github.endingratitan.Settings.DotEnv}（`.env` 六键）与
 *       {@link io.github.endingratitan.Settings.GitProbe}（git 能力/变更清单/仓库整理）。</li>
 * </ul>
 *
 * 依赖方向：`Integra`、`Preview`、`SsvulExtWebCreator`(CLI) 都**单向依赖本包**；本包不依赖它们
 * （`GitProbe` 因此只接值、不认识构建状态类）。拆包的直接动因：预览要读端口、v4 的 watch 要直接用变更清单，
 * 若继续留在 Integra 内部，就得不断往 `SiteBuilder` 门面上挂纯桥接方法——那是分层不适的信号。
 */
package io.github.endingratitan.Settings;
