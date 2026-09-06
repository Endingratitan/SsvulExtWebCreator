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
 * 代码渲染引擎接口（构建期上色通道）。
 *
 * 三层架构中的"引擎层"：MarkdownRenderer 只负责产出 {@code <pre class="md-pre"><code class="md-code language-x">}
 * 外壳，内部内容交由本接口渲染。token 一律使用 canonical 类 tk-&lt;id&gt;，与 CSS 变量层（--md-code-*）无缝对接。
 *
 * 换装体系：{@link EngineRegistry} 按名注册；页面 engine 键可写有序链，按 {@link #accepts(String)}
 * 依次问询，全员拒接时链返回 null（调用方纯文本兜底 + 警告，构建永不因高亮失败）。
 * 客户端引擎（如 hljs）经 {@link #autoAssets()} 声明资源，页面按需注入（无代码块/无带语言块不注入，性能优先）。
 *
 * 第三方接入（v3 计划）：实现本接口 + 自带"自家类名 → tk-id"映射（等价于客户端 token-map.js 的 Java 侧版本）；
 * 用户引擎（U2/U3）与语法级（L2）引擎同样只实现本接口，内部实现不限复杂度。
 */
public interface CodeEngine {

    /**
     * 渲染一段代码为可安全嵌入 {@code <code>} 内的 HTML 片段。
     *
     * @param code     原始源码（未转义、不含围栏行）
     * @param language 围栏信息里的语言名（可能为空字符串）
     * @return 已 HTML 转义的片段，token 建议使用 canonical 类 tk-&lt;id&gt;；
     *         仅引擎链（EngineChain）可用 null 表示"全员拒接"（调用方按纯文本兜底），单引擎实现禁止返回 null
     */
    String renderCode(String code, String language);

    /** 引擎注册名（EngineRegistry 键）；默认取类名 */
    default String name() { return getClass().getSimpleName(); }

    /** 是否接受该语言；false = 链式回退时交给下一个引擎 */
    default boolean accepts(String language) { return true; }

    /** 引擎需要的客户端资源（pre-assets/ 路径，按执行顺序）；纯构建期引擎返回空 */
    default List<String> autoAssets() { return List.of(); }
}
