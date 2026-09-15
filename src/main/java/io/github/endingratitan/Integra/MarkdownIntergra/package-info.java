/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */

/**
 * Markdown 渲染子系统（md 的全部实现都在这里；自 {@code Integra} 拆出为独立子包）。
 *
 * <p>对外只有**一个门面**：{@link io.github.endingratitan.Integra.MarkdownIntergra.MarkdownRenderer}
 * ——{@code render / renderParts / joinResult / escapeHtml / MdResult} 是公开 API
 * （引擎链沿 {@code renderParts} 的参数传入，不再有可变的静态引擎字段），
 * 其余类均为**包内私有**协作件，包外只需认识门面。</p>
 *
 * <p>结构（按"派发 → 判定 → 各块类型"分层）：</p>
 * <ul>
 *   <li>{@link io.github.endingratitan.Integra.MarkdownIntergra.MarkdownRenderer}
 *       ——门面 + 渲染状态（模式/错误警告/锚点/TOC/脚注开关/code-ui）+ 共享工具（转义、缩进、行首空格）</li>
 *   <li>{@link io.github.endingratitan.Integra.MarkdownIntergra.MdBlocks}——块级派发、段落合并、兜底一行</li>
 *   <li>{@link io.github.endingratitan.Integra.MarkdownIntergra.MdBlockScan}——"这一行是什么"的判定（不产出 HTML）</li>
 *   <li>{@link io.github.endingratitan.Integra.MarkdownIntergra.MdCodeBlocks}——围栏代码 + code-ui 外壳 + 块数学</li>
 *   <li>{@link io.github.endingratitan.Integra.MarkdownIntergra.MdQuotes}——引用块 + callout 块</li>
 *   <li>{@link io.github.endingratitan.Integra.MarkdownIntergra.MdLists}——列表（任务列表、续行子视图、围栏/表格逃逸）</li>
 *   <li>{@link io.github.endingratitan.Integra.MarkdownIntergra.MdTables}——表格（对齐、单元格切分）</li>
 *   <li>{@link io.github.endingratitan.Integra.MarkdownIntergra.MdInline}——行内解析（强调/链接/图/行内码/数学/按键…）</li>
 *   <li>{@link io.github.endingratitan.Integra.MarkdownIntergra.MdFootnotes}——脚注子系统（定义掩码、编号、脚注区）</li>
 *   <li>{@link io.github.endingratitan.Integra.MarkdownIntergra.Callouts}——callout 类型表（内置类型、扩展名规则、默认标签）</li>
 * </ul>
 *
 * <p>协作约定：所有协作类共享同一个 {@code MarkdownRenderer} 实例（"owner 引用模式"），
 * 通过它的包内字段读取模式、写错误与警告；块级/引用/列表之间由 {@code MdBlocks} 回调实现子视图递归。
 * 因此本包内**不要**再出现第二份渲染状态。</p>
 */
package io.github.endingratitan.Integra.MarkdownIntergra;
