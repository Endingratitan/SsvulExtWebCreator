/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Integra;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * **一页的私有状态**（包内私有，0.4.0 并行渲染的地基）。
 *
 * 为什么需要它：渲染相原先把"当前页"的状态直接挂在 {@link SiteBuilder} 上（`sb.hasCode`、`sb.mdOptions`、
 * `sb.currentPageLink`…），一个构建只有一份 → **天然不能并行**。把这类字段搬进来之后，
 * 每个渲染线程持有自己的 `PageScope`，`SiteBuilder` 上只剩两类东西：
 * <ul>
 *   <li><b>共享只读</b>：配置、`divs`/`pageIndex`、预设表……（渲染期不再有人写）；</li>
 *   <li><b>跨页累加</b>：`queue`/`pageOutputs`/`presetCopies`/`listDirs`/`errors`/`warnings`/计数——
 *       全部换成线程安全容器，且它们的**顺序不影响产物字节**（见 history.md 的复杂度与合并分析）。</li>
 * </ul>
 *
 * 判定标准（搬什么进来）：**只在"本页渲染"的调用链里被写、又被后续同页步骤读** → 必须页内私有。
 * 反例：`searchNeeded` 虽然也在页内被写，但它只在渲染全部结束后被读（`emitSearchIndex`）→ 留在共享侧做 OR。
 */
final class PageScope {

    final SiteBuilder sb;              // 共享侧（配置 / 缓存 / 只读数据）
    final SiteScan.Page page;          // 源页（json / 裸 md / raw 目录）

    // ---- 页内标量 ----
    Map<String, String> mdOptions = new LinkedHashMap<>();   // 本页 md-options（div 级级联后的有效值）
    EngineChain engineChain;                                  // 本页引擎链
    List<String> engineAssets = List.of();                    // 本页要注入的引擎自动资源（门控后）
    boolean hasCode;
    boolean hasCallout;
    boolean copyJsNeeded;
    boolean injectCodeuiCss;
    boolean injectCodeuiJs;
    boolean injectCalloutCss;                                 // callout 覆写是否注入
    String injectCalloutVariant;                              // 命中的语言变体文件名（null → CALLOUT.css）
    boolean mdCsrNeeded;
    boolean themePickerNeeded;
    boolean offlineListWarned;                                // 列目录预设的 offline 警告每页只发一次（R16）
    int depth;                                                // 当前页深度（data-depth）

    // ---- 页内集合 ----
    final Set<String> pageGlobalRefs = new LinkedHashSet<>(); // 本页 global: 引用（父子双引用警告）
    final List<String> listAssets = new ArrayList<>();        // 本页要注入的 list 预设 js（pre-assets 相对路径）
    final List<String> mdCsrAssets = new ArrayList<>();       // 本页要注入的 md-csr 库 + hljs（路径稳定，可长缓存）
    final List<String> mdCsrCss = new ArrayList<>();          // 本页要注入的 css（math=on → KaTeX，连带 fonts/）
    String pageLink = "";                                     // 本页输出链接（list 渲染时排除自己）
    final Set<String> depKeys = new LinkedHashSet<>();        // 本页**实际读过**的源键（E：依赖图的唯一可信来源）

    PageScope(SiteBuilder sb, SiteScan.Page page) {
        this.sb = sb;
        this.page = page;
    }

    // ---- 页内助手（把原先挂在 SiteBuilder 上的"当前页"逻辑搬过来）----

    /** 按页面 lang 选 callout 覆写文件：语言变体（完整 lang → 主语言）优先，未命中回落 CALLOUT.css。
     *  变体是**自包含**的：命中变体就不再注入 CALLOUT.css（想叠加就在变体里 @import url("CALLOUT.css")）。 */
    void resolveCalloutFiles(String pageLang) {
        injectCalloutCss = false;
        injectCalloutVariant = null;
        if (!hasCallout || sb.calloutFiles.isEmpty()) return;   // 门控：本页没有 callout 就一个字节都不注入
        String lang = pageLang == null ? "" : pageLang.trim().toLowerCase(Locale.ROOT);
        String primary = lang.contains("-") ? lang.substring(0, lang.indexOf('-')) : lang;
        for (String cand : new String[]{lang, primary}) {
            if (cand.isEmpty()) continue;
            File hit = sb.calloutFiles.get("callout." + cand + ".css");
            if (hit != null) { injectCalloutVariant = hit.getName(); injectCalloutCss = true; return; }
        }
        if (sb.calloutFiles.containsKey("callout.css")) injectCalloutCss = true;
    }

    /** 引用预设资产（按需复制进产物）：写共享集合——它是 Set，顺序不影响产物字节 */
    void refPreset(String suffix) { sb.refPreset(suffix); }

    /** 注册本页输出路径（冲突检测在**合并/串行阶段**按页序做，见 `SiteBuilder.mergePage`） */
    void registerOutput(String out) { sb.pageOutputs.add(out); }
}
