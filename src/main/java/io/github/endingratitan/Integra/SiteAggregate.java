/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Integra;

import io.github.endingratitan.Integra.SiteBuilder;
import io.github.endingratitan.Integra.SiteScan;
import io.github.endingratitan.WebMinify.css.CssDeduper;
import io.github.endingratitan.WebMinify.css.CssMinifier;
import io.github.endingratitan.WebMinify.js.JsDeclScan;
import io.github.endingratitan.WebMinify.js.JsDeduper;
import io.github.endingratitan.WebMinify.js.JsMinifier;
import io.github.endingratitan.WebMinify.js.JsMinifierRegistry;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * **聚合与压缩**（包内私有协作件，owner 引用模式）：把若干 div 的 js/css 聚合成"每页一份"的产物。
 *
 * 关系：被 **`SitePages`（每页聚合）** 与 **`SiteWrite`（落盘前压缩）** 共用的助手，本身不属于任何阶段；
 * 它读 `SiteBuilder` 的压缩档位（`minifyLevel`/`minifierName`）与内容缓存，产出交给写盘相。
 * 管线位置：`SiteSetup → SiteDetect → SiteScan → SitePages（用本类聚合）→ SiteWrite`。
 *
 * 手法：origin 集去重（共享祖先只出一份）→ 文件级内容去重 → `JsDeduper`/`CssDeduper` →
 * runtime 按需尾接 → 压缩（结果按"档位|引擎|内容哈希"缓存，同源多页只压一次）。
 */
final class SiteAggregate {

    private final SiteBuilder sb;

    SiteAggregate(SiteBuilder sb) { this.sb = sb; }

    private final Map<String, String> minifyCache = new ConcurrentHashMap<>(); // 键 = 档位|引擎|内容哈希 → 压缩结果
    private final Map<String, String> cssCache = new ConcurrentHashMap<>();    // 键 = 档位|css|内容哈希 → 去重+压缩结果

    // ==================== 聚合助手（origin 去重 + A 层 + 项级去重 + runtime） ====================

    boolean dedupOn() { return sb.minifyLevel != 0; }
    boolean compressOn() { return sb.minifyLevel == 2; }
    boolean keepDedupComments() { return sb.minifyLevel == -1; }

    /** 聚合 div js（链文件根→叶；origin 保证共享祖先只出一份；A 层冲突扫描；项级去重；runtime 尾接） */
    String aggregateDivJs(List<SiteScan.DivInfo> divs) {
        long t0 = System.nanoTime();
        try {
            return aggregateDivJs0(divs);
        } finally {
            sb.stats.nsAgg.add(System.nanoTime() - t0);
            sb.stats.aggCalls.incrementAndGet();
        }
    }

    private String aggregateDivJs0(List<SiteScan.DivInfo> divs) {
        List<File> files = new ArrayList<>();
        Set<String> origins = new HashSet<>();
        for (SiteScan.DivInfo d : divs)
            for (File f : d.js)
                if (origins.add(f.getPath())) files.add(f);
        List<File> uniq = new ArrayList<>();
        Set<String> sigs = new HashSet<>();
        for (File f : files) {
            String content = sb.readFile(f);
            if (sigs.add(JsDeduper.signatureOf(content))) uniq.add(f);   // 文件级内容去重
        }
        if (dedupOn()) {
            Map<String, String> decl = new LinkedHashMap<>();
            for (File f : uniq) {
                for (String name : JsDeclScan.topLevelBlockScoped(sb.readFile(f))) {
                    String prev = decl.put(name, f.getName());
                    if (prev != null && !prev.equals(f.getName()))
                        sb.errors.add("js 顶层 let/const/class 重名（串联后 SyntaxError）: " + name + "（" + prev + " 与 " + f.getName() + "）");
                }
            }
        }
        StringBuilder concat = new StringBuilder();
        for (File f : uniq) concat.append(sb.readFile(f)).append('\n');
        String js = concat.toString();
        if (dedupOn()) {
            JsDeduper.Result r = JsDeduper.dedupItems(js, keepDedupComments());
            js = r.code();
            for (String note : r.notes()) sb.warn(note);
        }
        // runtime 前置（div js 顶层即调 SsvulDiv.register，运行时必须先定义）；仅当聚合内容使用 SsvulDiv
        if (js.contains("SsvulDiv")) {
            String runtime = sb.readPreset("runtime/ssvul-div.js");
            sb.refPreset("runtime/ssvul-div.js");
            js = runtime + js;
        }
        if (compressOn()) js = minifyJs(js);
        return js;
    }

    /** 聚合 div css（origin 去重 + 同选择器去重 + 压缩） */
    String aggregateDivCss(List<SiteScan.DivInfo> divs) {
        long t0 = System.nanoTime();
        try {
            return aggregateDivCss0(divs);
        } finally {
            sb.stats.nsAgg.add(System.nanoTime() - t0);
            sb.stats.aggCalls.incrementAndGet();
        }
    }

    private String aggregateDivCss0(List<SiteScan.DivInfo> divs) {
        List<File> files = new ArrayList<>();
        Set<String> origins = new HashSet<>();
        for (SiteScan.DivInfo d : divs)
            for (File f : d.css)
                if (origins.add(f.getPath())) files.add(f);
        StringBuilder concat = new StringBuilder();
        for (File f : files) concat.append(sb.readFile(f)).append('\n');
        String css = concat.toString();
        if (!dedupOn()) return css;
        // 结果缓存（键 = 档位|内容哈希）：多页共享同一 div 集合时只去重/压缩一次（P4）
        String key = sb.minifyLevel + "|css|" + DepsManifest.sha256(css.getBytes(StandardCharsets.UTF_8));
        String hit = cssCache.get(key);
        if (hit != null) { sb.stats.cssMinifyHits.incrementAndGet(); return hit; }
        String out = CssDeduper.dedup(css, keepDedupComments());
        if (compressOn()) out = CssMinifier.minify(out);
        sb.stats.cssMinifyCalls.incrementAndGet();
        cssCache.put(key, out);
        return out;
    }

    /** 生成物 js 后缀：去重/压缩档（≥1）→ .min.js；0/-1 → .js */
    String jsSuffix() { return sb.minifyLevel >= 1 ? ".min.js" : ".js"; }

    /** 经注册表压缩（Closure 预留缝）；**结果缓存**（键 = 档位|引擎|内容哈希）：同源多页共享同一聚合时只压一次；
     *  降级说明并入构建警告；异常兜底原文 */
    String minifyJs(String js) {
        String key = sb.minifyLevel + "|" + sb.minifierName + "|" + DepsManifest.sha256(js.getBytes(StandardCharsets.UTF_8));
        String hit = minifyCache.get(key);
        if (hit != null) { sb.stats.minifyHits.incrementAndGet(); return hit; }
        JsMinifier m = JsMinifierRegistry.get(sb.minifierName);
        JsMinifier.Result r = m.minify(js);
        sb.stats.minifyCalls.incrementAndGet();
        sb.stats.minifyBytes.add(js.length());
        for (String note : r.notes()) sb.warn(note);
        minifyCache.put(key, r.code());
        return r.code();
    }
}
