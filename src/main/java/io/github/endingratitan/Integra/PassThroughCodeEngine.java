/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * 项目来源: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Integra;

import java.util.List;

/**
 * hljs 引擎条目（客户端上色链路）：构建期不做上色，仅 HTML 转义；
 * 浏览器端由 hljs.min.js 上色、token-map.js 映射 canonical 类 tk-*。
 * 资源经 autoAssets() 声明，页面含带语言的代码块时才注入（性能优先）。
 */
public class PassThroughCodeEngine implements CodeEngine {

    private static final List<String> HLJS_ASSETS = List.of(
            "pre-assets/lib/hljs/hljs.min.js",
            "pre-assets/md/js/token-map.js",
            "pre-assets/md/js/md-highlight.js");

    @Override
    public String renderCode(String code, String language) {
        return MarkdownRenderer.escapeHtml(code);
    }

    @Override
    public String name() { return "hljs"; }

    @Override
    public List<String> autoAssets() { return HLJS_ASSETS; }
}
