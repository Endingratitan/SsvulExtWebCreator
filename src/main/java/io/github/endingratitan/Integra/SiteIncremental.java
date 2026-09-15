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
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * **增量跳过（E）**：判据、重放、记录（包内私有协作件，owner 引用模式 —— 与 `SiteScan`/`SitePages`/
 * `SiteTags`/`SiteWrite` 同构，`SiteBuilder` 留一个 `inc` 字段）。
 *
 * 为什么独立成类：这块逻辑横跨"构建前（检测/秒回）→ 渲染期（按页跳过与副作用重放）→ 构建末（写页记录/惰性记源）"，
 * 塞在 `SiteBuilder` 里会把它撑到难以回看（本项目已有 1300+ 行）；搬出来之后 `SiteBuilder` 只剩"过程编排 + 共享状态"。
 *
 * 判据（可证等价，不是启发式）：
 * <pre>
 * 秒回  ⇔ 变更集空 ∧ 预设侧无变更 ∧ config 指纹同 ∧ 有页记录 ∧ 产物目录非空 ∧ 每个记录产物 stat 通过
 * 跳过页 ⇔ 该页依赖键一个都没变 ∧ config 同 ∧ 该页产物全部 stat 通过
 * </pre>
 * 记录：`pages[页身份] = {deps, outs, search, listDirs}`；无页归属的读取进 `manifest.shared`（变则全量）。
 * 详见 `tech.md` 的"0.4.0-E 算法优化（决定版）"与 `docs/bugs/`。
 */
final class SiteIncremental {

    private final SiteBuilder sb;

    SiteIncremental(SiteBuilder sb) { this.sb = sb; }

    // ---- 状态 ----

    /** 配置指纹（生成器版本 + `Environment.config` 原文）：影响产物但不进任何页的依赖集 → 变则全量 */
    String configFp = "";
    /** 产物校验：0 = 不校验；1 = 逐产物 stat（默认；缺失或被改 → 该页重渲染 = **自愈**） */
    int verifyLevel = 1;
    /** **没有页归属**的读取（扫描相：`.extends`/`.contract`/data/global/Environment.config）→ 有变化就全量渲染 */
    final Set<String> sharedKeys = java.util.Collections.synchronizedSet(new LinkedHashSet<>());
    /** 预设侧（`assets:`）变更的源键——只 stat 页依赖里出现过的那些（实测 2–8ms） */
    final Set<String> changedAssets = java.util.Collections.synchronizedSet(new LinkedHashSet<>());
    /** 本次检测遍历到的源文件（rel → {size, mtime}）：① 删除检测 ② 构建末尾把"惰性文件"记入基线 */
    final Map<String, long[]> visited = new java.util.LinkedHashMap<>();
    /** 本轮**真正渲染过**的页（用于重写页记录）；跳过的页保留旧记录 */
    final List<PageScope> renderedScopes = java.util.Collections.synchronizedList(new ArrayList<>());
    /** 归属页 → 它的产物 rel（写盘相收集；跳过重放与产物校验都用它） */
    final Map<String, Set<String>> outsByOwner = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>());
    /** 预设后缀 → 引用它的页身份集合（`PageScope.refPreset` 登记；写盘相据此把副本 rel 记进各页 outs）。
     *  为什么不靠"扫正文里的 assets/pre/…"：运行库这类注入引用**不一定出现在正文字面量里**（实测已踩：漏了
     *  `assets/pre/runtime/ssvul-div.js` → 跳过页的写集不全 → 假孤儿 + 记录被丢）。 */
    private final Map<String, Set<String>> presetOwners = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>());
    /** 出现"全新的、非页面的"源文件（新 `.extends`/`.contract`/新 div/新 global）→ 无页归属，**全量** */
    boolean forceFull;
    /** 本轮是否真的跑过变更检测（秒回需要它） */
    boolean detected;
    /** `manifest.shared`（扫描相读取）里有变化 → 全量渲染（在 renderPages 开头算一次） */
    boolean sharedChanged;

    /** 生成器版本（资源 `ssvul-version.txt`；手工编译/测试环境可能没有 → 空串） */
    static String generatorVersion() {
        try (java.io.InputStream in = SiteBuilder.class.getResourceAsStream("/ssvul-version.txt")) {
            return in == null ? "" : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** 页身份（页记录键 = 源文件在 sets 下的键，如 `sets:pages/about.json`） */
    String identityOf(SiteScan.Page p) { return p == null || p.file == null ? null : sb.sourceKeyOf(p.file); }

    /** 把一个产物登记到它的归属页（写盘相调用；同一页可能引用多个共享副本） */
    void addOwnerOut(String owner, String rel) {
        if (owner == null) return;
        outsByOwner.computeIfAbsent(owner, k -> new LinkedHashSet<>()).add(rel);
    }

    /** 引用登记（`PageScope.refPreset` 调用）：预设后缀 → 引用它的页身份 */
    void refPresetOwner(String suffix, String owner) {
        if (owner == null || suffix == null) return;
        presetOwners.computeIfAbsent(suffix, k -> java.util.Collections.synchronizedSet(new LinkedHashSet<>())).add(owner);
    }

    /** 写盘相：预设副本 `assets/pre/<后缀>` 落到**每个引用它的页**的 outs 里（跳过重放/写集/校验都用它） */
    void addPresetOut(String suffix, String rel) {
        Set<String> owners = presetOwners.get(suffix);
        if (owners == null) return;
        for (String o : owners) addOwnerOut(o, rel);
    }

    // ---- 判据 ----

    /** 产物是否仍然完好（口径①：与产物记录比 size+mtime）——缺失或被手改 → false → 该页重渲染（自愈） */
    private boolean outputOk(String rel) {
        DepsManifest.Rec r = sb.manifest.output(rel);
        if (r == null) return false;
        java.nio.file.attribute.BasicFileAttributes a = SiteBuilder.attrs(new File(sb.outputDir, rel));
        return a != null && a.isRegularFile() && a.size() == r.size && a.lastModifiedTime().toMillis() == r.mtime;
    }

    /** 该源键是否在本次变更集里（`sets:` 走变更清单，`assets:` 走预设侧检测） */
    boolean keyChanged(String k) {
        if (k.startsWith("sets:")) return sb.detect.changedFiles.contains(k.substring(5));
        if (k.startsWith("assets:")) return changedAssets.contains(k);
        return false;
    }

    /** **零变更秒回**的全部先决条件（任何一条不满足 → 走一般路径，绝不冒险） */
    boolean fastPathOk() {
        if (sb.rebuildAll || forceFull || !detected) return false;
        if (!sb.detect.changedFiles.isEmpty()) return false;
        if (!changedAssets.isEmpty()) return false;                // 预设侧有变更 → 走一般路径（精确到页）
        if (!configFp.equals(sb.manifest.config)) return false;    // 配置/版本变了 → 全量
        if (sb.manifest.pages.isEmpty()) return false;             // 无页记录（首次/旧版本/坏文件）→ 老路径
        if (!sb.outputDir.isDirectory()) return false;             // 产物目录被删 → 必须重建
        String[] top = sb.outputDir.list();
        if (top == null || top.length == 0) return false;          // 空产物目录 → 必须重建
        if (verifyLevel > 0) {
            for (String rel : sb.manifest.outputs.keySet()) if (!outputOk(rel)) return false;
        }
        return true;
    }

    /** 这一页能不能跳过（依赖未变 + config 未变 + 本页产物都还在） */
    boolean pageSkippable(DepsManifest.PageRec rec, boolean sharedChanged) {
        if (sb.rebuildAll || forceFull || sharedChanged) return false;
        if (!configFp.equals(sb.manifest.config)) return false;
        for (String k : rec.deps) if (keyChanged(k)) return false;
        if (verifyLevel > 0) for (String o : rec.outs) if (!outputOk(o)) return false;
        return true;
    }

    /** 跳过一页时的**副作用重放**：写集继承、预设重放、`@page/` 目标注册、页级全局标志恢复 */
    void replayPage(DepsManifest.PageRec rec) {
        for (String o : rec.outs) {
            sb.written.add(o);                                     // 写集必须含被跳过的产物（否则假孤儿）
            if (o.startsWith("assets/pre/")) sb.refPreset(o.substring("assets/pre/".length()));
            int i = o.lastIndexOf('/');
            if (o.startsWith("pages/") && i > 0) sb.pageOutputs.add(o.substring(0, i));
        }
        if (rec.search) sb.searchNeeded = true;
        for (String d : rec.listDirs) sb.listDirs.put(d, true);
    }

    // ---- 预设侧检测（方案 (c)）----

    /**
     * 键集 = ① 页 deps 里已有的 `assets:`（页面**直接读过**的预设）∪ ② 由页 outs 反推的
     * `assets/pre/<后缀>` → `assets:<后缀>`（**只被按名引用、从未被读**的那批：`md.css`/`md-theme.js`/katex/list 库…；
     * 同时也给"改动前写下的旧记录"兜底）。判据与 `sets/` 侧一致：size/mtime 与源记录不符即算变更。
     * 实测成本：example-sets 41 键 = 8ms（3 线程）；300 页站 2 键 = 2ms。
     */
    void detectAssets() {
        if (sb.manifest.pages.isEmpty()) return;
        Set<String> keys = new LinkedHashSet<>();
        for (DepsManifest.PageRec r : sb.manifest.pages.values()) {
            for (String k : r.deps) if (k.startsWith("assets:")) keys.add(k);
            for (String o : r.outs) if (o.startsWith("assets/pre/")) keys.add("assets:" + o.substring("assets/pre/".length()));
        }
        if (keys.isEmpty()) return;
        List<String> list = new ArrayList<>(keys);
        List<Callable<Void>> tasks = new ArrayList<>(list.size());
        for (String k : list) tasks.add(() -> {
            if (assetChanged(k)) changedAssets.add(k);
            return null;
        });
        ExecutorService pool = sb.ioPool(sb.ioThreads(list.size()));
        try {
            if (pool == null) for (Callable<Void> c : tasks) c.call();
            else for (Future<Void> f : pool.invokeAll(tasks)) f.get();
        } catch (Exception ignored) {
            // 预设侧检测失败只影响本次加速：按"未变"处理（保守方向由 sets/ 侧与 --rebuild 兜底）
        } finally {
            if (pool != null) pool.shutdownNow();
        }
    }

    /** 预设源是否变了；**无记录则先记一次当前状态并判为未变**（旧记录的一次性播种，避免"永远判变更"） */
    private boolean assetChanged(String key) {
        java.nio.file.attribute.BasicFileAttributes a =
                SiteBuilder.attrs(new File(sb.presetDir, key.substring("assets:".length())));
        if (a == null) return false;                       // 文件不存在 → refPreset 会报错，不在这里重复报
        DepsManifest.Rec r = sb.manifest.sources.get(key);
        if (r == null) {                                   // 新引用/旧记录：播种一次，本轮不算变更
            sb.recSource(key, a.size(), a.lastModifiedTime().toMillis());
            return false;
        }
        return a.size() != r.size || a.lastModifiedTime().toMillis() != r.mtime;
    }

    // ---- 记录 ----

    /** 渲染结束后重写页记录（跳过的页保留旧记录；已消失的页删掉记录，否则它的产物会被当成"应存在"） */
    void assemblePageRecords() {
        for (PageScope s : renderedScopes) {
            String id = s.identity();
            if (id == null) continue;
            DepsManifest.PageRec rec = new DepsManifest.PageRec();
            rec.deps = new ArrayList<>(s.depKeys);
            Set<String> outs = outsByOwner.get(id);
            if (outs != null) rec.outs = new ArrayList<>(outs);
            rec.search = s.searchNeeded;
            rec.listDirs = new ArrayList<>(s.listDirs);
            sb.manifest.pages.put(id, rec);
        }
        Set<String> live = new java.util.HashSet<>();
        for (SiteScan.Page p : sb.pages) {
            String id = identityOf(p);
            if (id != null) live.add(id);
        }
        sb.manifest.pages.keySet().retainAll(live);
        // `shared` = "**没有页归属**的读取"，但必须**剔掉任何页面也读过的键**：
        // 索引相（collectPageIndex 读全部页面文件）与全局相（buildGlobals 聚合 div 资产）都在页作用域之外读，
        // 不过滤的话**任何一页的改动都会命中 shared → 全量渲染**（实测踩到：改一页 → 12 页全渲染）。
        // 过滤后 shared 只剩"真·站点级"依赖（`.extends`/`.contract`/`Environment.config`/global/data/README…）。
        Set<String> pageDeps = new java.util.HashSet<>();
        for (DepsManifest.PageRec r : sb.manifest.pages.values()) pageDeps.addAll(r.deps);
        List<String> sh = new ArrayList<>();
        for (String k : new java.util.TreeSet<>(sharedKeys)) if (!pageDeps.contains(k)) sh.add(k);
        sb.manifest.shared = sh;
        sb.manifest.config = configFp;
    }

    /**
     * 把"整轮构建都没读过、又不在 `pages/` 下"的文件记入基线 → 下一轮不再被永久误报成"已变更"
     * （否则任何带 favicon/未被引用数据的站点，watch 会**每轮**白重建一次）。
     * `pages/**` 必须排除：**新页面文件的存在本身就是"要构建它"的信号**。
     */
    void recordInertSources() {
        for (Map.Entry<String, long[]> e : visited.entrySet()) {
            String rel = e.getKey();
            if (rel.startsWith("pages/")) continue;
            String key = "sets:" + rel;
            if (sb.manifest.sources.containsKey(key)) continue;
            sb.recSource(key, e.getValue()[0], e.getValue()[1]);
        }
    }

    /** 未使用的占位（保留 `Files` 引用以便未来扩展：资产存在性检查等） */
    @SuppressWarnings("unused")
    private static boolean isRegular(File f) {
        return Files.isRegularFile(f.toPath());
    }
}
