/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Integra;

import io.github.endingratitan.Settings.DotEnv;
import io.github.endingratitan.Settings.GitProbe;
import io.github.endingratitan.WebMinify.css.CssDeduper;
import io.github.endingratitan.WebMinify.css.CssMinifier;
import io.github.endingratitan.WebMinify.js.JsDeduper;
import io.github.endingratitan.WebMinify.js.JsDeclScan;
import io.github.endingratitan.WebMinify.js.JsMinifier;
import io.github.endingratitan.WebMinify.js.JsMinifierRegistry;
import io.github.endingratitan.WebMinify.js.Lex;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;

/**
 * 站点构建管线（公共门面）。
 *
 * 结构：本类负责配置、收集容器与编排；输入扫描见 {@link SiteScan}，页面组装见 {@link SitePages}，
 * 标签生成见 {@link SiteTags}，输出替换与写盘见 {@link SiteWrite}。
 *
 * 流程：Environment.config → div 索引（三态）→ 页面注册（json/裸md/raw 文件夹、INDEX 特判）→
 * data/favicon 扫描复制 → web_global 与 .global 文件生成 → 页面组装（BASE.html + 模板注入 + 标签）→
 * 输出替换趟（pre-assets/@data/@favicon/@page/bucket，按深度）→ UTF-8 写盘。
 *
 * 收集式报错：错误汇总后一次性抛出，抛出前不写任何文件（输出目录保持干净）。
 */
public class SiteBuilder {

    // ---- 配置 ----
    final File setsDir, outputDir, presetDir;
    Map<String, List<String>> env = new LinkedHashMap<>();
    Map<String, String> buckets = new LinkedHashMap<>();
    final Map<String, Map<String, String>> bucketAttrs = new LinkedHashMap<>();  // 调用名 -> {endpoint,prefix,ref}
    int sessionTtl = 86400;                                    // session-ttl 键：会话缓存默认时长（秒），0=默认不缓存
    boolean categories, readmeOn, localFavicon;
    boolean envLoaded;

    // ---- 收集 ----
    final List<String> errors = java.util.Collections.synchronizedList(new ArrayList<>());
    final List<String> warnings = java.util.Collections.synchronizedList(new ArrayList<>());               // 构建警告（控制台输出，不阻断）
    final List<Queued> queue = java.util.Collections.synchronizedList(new ArrayList<>());
    final BuildStats stats = new BuildStats();        // 构建统计（计数优先；末尾打印一行）
    final Set<String> written = new LinkedHashSet<>(); // 本次构建产出的相对路径（**含被跳过写入的**：孤儿清理/依赖记录的前提）
    DepsManifest manifest = new DepsManifest();        // 上次构建的依赖记录（缺失/损坏 = 空记录）

    // ---- 本机设置（.env）与变更检测（M6）----
    DotEnv dotenv = new DotEnv();                      // 项目根的 .env（缺失即用内置默认，首轮创建）
    Set<String> changedFiles = new LinkedHashSet<>();  // 变更清单（相对 sets/；仅采集过才有意义）
    String detectorUsed = "off";                       // off=未采集 | git | stat
    boolean detectWanted;                              // 本次是否采集变更清单（预览/--detect；普通构建零开销）
    String detectorPref = "auto";                      // auto | git | stat（CLI --git/--no-git/--detector 覆盖 .env）
    boolean rebuildAll;                                // --rebuild：忽略 manifest（全量重写，排错用）
    Boolean gitInitForced;                             // --git-init 0/1：覆盖"仅标准布局自动建库"（null = 未指定）
    boolean gitRepo;                                   // 本次是否可用 git 检测（init/护栏/gc 之后的结果）
    int threadsPref = -1;                              // 写盘相并发度：-1=auto（默认）｜0=串行｜N=指定

    // ---- P3/P4 缓存（**实例级 = 每构建一份**，run() 结束显式清空；绝不做进程级，否则长驻预览会吃旧内容）----
    // 0.4.0：三张缓存跨**渲染线程**共享 → 并发容器 + 原子记账（P3 命中率不许因并行下降）
    private final Map<String, String> textCache = new ConcurrentHashMap<>();   // 路径 → 内容（首次读即存，上限保护兜底）
    private final java.util.concurrent.atomic.LongAdder cacheBytes = new java.util.concurrent.atomic.LongAdder();
    private final Map<String, String> minifyCache = new ConcurrentHashMap<>(); // 键 = 档位|引擎|内容哈希 → 压缩结果
    private final Map<String, String> cssCache = new ConcurrentHashMap<>();    // 键 = 档位|css|内容哈希 → 去重+压缩结果
    private static final int CACHE_FILE_MAX = 512 * 1024;            // 单文件 ≤512KiB 才进缓存
    int cacheTotalMax = DotEnv.DEF_CACHE_LIMIT << 20;                // 总量上限（`.env` 的 cache-limit，MiB；0=关）
    final Set<String> presetCopies = java.util.Collections.synchronizedSet(new LinkedHashSet<>());   // 被引用的 pre-assets 文件
    final Set<String> presetDirCopies = java.util.Collections.synchronizedSet(new LinkedHashSet<>()); // 连带复制的目录（如 katex fonts）
    final Map<String, SiteScan.DivInfo> divs = new LinkedHashMap<>();  // type -> div
    final Set<String> pageOutputs = java.util.Collections.synchronizedSet(new LinkedHashSet<>());    // "pages/..." 形式
    final List<SiteScan.Page> pages = new ArrayList<>();
    final Set<String> readmeNames = new HashSet<>();          // readme md 文件名去重
    final Map<String, File> outerFiles = new LinkedHashMap<>();  // "调用名/路径" → outer 镜像文件
    boolean webGlobalJs, webGlobalCss;
    boolean offline;                                           // offline=1：bucket 引用本地替换
    boolean outerDirExists;                                    // sets/outer 目录存在（对照警告用）
    final Map<String, File> codeuiFiles = new LinkedHashMap<>();  // sets/global/codeui 的站点级文件
    boolean codeuiCssExists, codeuiJsExists;                   // CODEUI.css/js 存在性（注入门控之一）
    final Map<String, File> calloutFiles = new LinkedHashMap<>();    // sets/global/callout 覆写文件（文件名 → 源）
    final List<Map<String, String>> pageIndex = new ArrayList<>();  // 页面索引（search/list 数据源）：link/title/date/excerpt/text/tags
    volatile boolean searchNeeded;                                           // 任页用到 search div → 输出 search-index.json
    final Map<String, Boolean> listDirs = java.util.Collections.synchronizedMap(new LinkedHashMap<>());    // list 的 ssvul:shared 声明的 dir 集合（按目录分片发射）
    int minifyLevel = 2;                                       // -1 去重+注释保留 | 0 全关 | 1 去重不压缩 | 2 全开（默认）
    String minifierName = "simple";                            // minifier 键（v3 预留 closure）

    SiteScan scan;   // divOf 供页面组装使用
    /** E 埋点：当前渲染线程正在渲染的那一页（只用于统计"每页依赖多少源键"；并行渲染天然一页一线程） */
    static final ThreadLocal<PageScope> CURRENT_SCOPE = new ThreadLocal<>();
    final Set<String> depGroups = new LinkedHashSet<>();       // 去重后的"依赖键集合"指纹（内联组）
    int depKeysMin = Integer.MAX_VALUE, depKeysMax, depPages;

    static class Queued {
        final File target;
        String content;               // 回填占位符会改写
        final int depth;
        final File source;      // binary 用
        final boolean binary;
        Queued(File target, String content, int depth) { this.target = target; this.content = content; this.depth = depth; this.source = null; this.binary = false; }
        Queued(File target, File source) { this.target = target; this.content = null; this.depth = 0; this.source = source; this.binary = true; }
    }

    // ==================== 入口 ====================

    /**
     * 构建选项（公共 API）：预览/CI 用得上，普通命令行走默认值。
     *
     * @param rebuild  忽略 `.ssvul/deps.json`（全量重写；排错与"怀疑快路径"时用）
     * @param detect   采集变更清单（git 或 stat；普通构建不采集 = 零新增开销）
     * @param detector 检测器偏好 `auto|git|stat`；null = 沿用 `.env`
     * @param gitInit  是否允许在 sets/ 自动建立 git 基线；null/false = 不动调用方的目录
     *                 （**CLI 在标准布局下传 true**；库 API 默认不动，测试与嵌入调用可保持目录干净）
     */
    public record BuildOptions(boolean rebuild, boolean detect, String detector, Boolean gitInit, Integer threads) {
        public static final BuildOptions DEFAULTS = new BuildOptions(false, false, null, null, null);

        /** 便捷构造：不管并发度（沿用 `.env` 的 auto/显式值） */
        public BuildOptions(boolean rebuild, boolean detect, String detector, Boolean gitInit) {
            this(rebuild, detect, detector, gitInit, null);
        }
    }

    /**
     * **只做变更检测**（公共 API，不构建、不写盘）：预览 watch 每轮调它；v5 增量将来也用它。
     *
     * 为什么放在这里而不是 Preview：检测的**基准**是 `deps-*.json` 里的源记录（size+mtime+哈希前缀），
     * 那属于构建状态；放在门面上还能让 watch 与 v5 共用**同一套判据**（git 两段解析 + 快筛 + 哈希终判）。
     * 每轮重读一次记录（20–70KB JSON，几毫秒）换的是"无常驻状态"，watch 可随时重启。
     *
     * @param detectorPref `auto` / `git` / `stat`
     * @return 变更集（空集 = 没变）；**不抛异常**，失败时返回空集并把原因放进 warnings
     */
    public static ChangeSet detectChanges(File setsDir, File outputDir, File presetDir,
                                         String detectorPref, List<String> warnings) {
        SiteBuilder b = new SiteBuilder(setsDir, outputDir, presetDir);
        try {
            b.manifest = DepsManifest.load(b.manifestFile(), b.absSets(), b.absOutput(), b.warnings);
            b.detectorPref = detectorPref == null ? "auto" : detectorPref;
            b.gitRepo = b.resolveRepo(false);      // **allowInit=false**：轮询绝不建仓库/整理仓库
            b.detectNow();
            warnings.addAll(b.warnings);
            return new ChangeSet(b.detectorUsed, b.changedFiles, System.currentTimeMillis());
        } catch (RuntimeException e) {
            warnings.add("变更检测失败（按「无变更」处理）: " + e.getMessage());
            return ChangeSet.NONE;
        }
    }

    /** 构建；失败抛异常（错误信息格式与历来一致）——CLI `build` 走这条 */
    public static void build(File setsDir, File outputDir, File presetDir) {
        build(setsDir, outputDir, presetDir, BuildOptions.DEFAULTS);
    }

    public static void build(File setsDir, File outputDir, File presetDir, BuildOptions opts) {
        BuildReport r = buildReport(setsDir, outputDir, presetDir, opts);
        if (!r.ok()) throw new RuntimeException(joinErrors(r.errors()));
    }

    /**
     * 构建（**不抛异常**）：把错误/警告/写集/变更清单/统计打包返回。
     * 预览（要显示错误而不是崩掉）、CI 与"结构计数门禁"测试都用这条。
     */
    public static BuildReport buildReport(File setsDir, File outputDir, File presetDir) {
        return buildReport(setsDir, outputDir, presetDir, BuildOptions.DEFAULTS);
    }

    public static BuildReport buildReport(File setsDir, File outputDir, File presetDir, BuildOptions opts) {
        SiteBuilder b = new SiteBuilder(setsDir, outputDir, presetDir);
        b.rebuildAll = opts.rebuild();
        b.detectWanted = opts.detect();
        if (opts.detector() != null) b.detectorPref = opts.detector();
        b.gitInitForced = opts.gitInit();
        if (opts.threads() != null) b.threadsPref = opts.threads();
        long t0 = System.currentTimeMillis();
        try {
            b.run();
            return new BuildReport(true, List.copyOf(b.errors), List.copyOf(b.warnings),
                    Set.copyOf(b.written), Set.copyOf(b.changedFiles), b.detectorUsed,
                    System.currentTimeMillis() - t0, b.stats);
        } catch (RuntimeException e) {
            List<String> errs = new ArrayList<>(b.errors);
            if (errs.isEmpty()) errs.add(String.valueOf(e.getMessage()));   // 非收集式异常（IO 等）也要可见
            return new BuildReport(false, List.copyOf(errs), List.copyOf(b.warnings),
                    Set.copyOf(b.written), Set.copyOf(b.changedFiles), b.detectorUsed,
                    System.currentTimeMillis() - t0, b.stats);
        }
    }

    private SiteBuilder(File setsDir, File outputDir, File presetDir) {
        this.setsDir = setsDir; this.outputDir = outputDir; this.presetDir = presetDir;
    }

    private void run() {
        scan = new SiteScan(this);
        SitePages pagesBuilder = new SitePages(this);   // 全局相实例（页面渲染每页另建）
        SiteWrite writer = new SiteWrite(this);

        try {
        long t0 = System.nanoTime();
        long e0 = System.nanoTime();
        manifest = rebuildAll ? new DepsManifest()
                : DepsManifest.load(manifestFile(), absSets(), absOutput(), warnings);   // 缺失/损坏/异站 = 空记录（走慢路径，绝不影响正确性）
        manifest.siteSets = absSets();          // 本次构建的站点身份（写回记录，便于人眼核对）
        manifest.siteOutput = absOutput();
        loadDotEnv();              // ① 本机设置（.env）：必须最先——cache-limit/detector/git-gc 都从它来
        t0 = System.nanoTime();
        loadGitState();            // ② git：首次基线 / 护栏 / 仓库整理 / 变更清单（**必须在任何源读取之前**，否则清单被本次扫描污染）
        stats.tDetect = ms(t0);
        loadEnv();                 // ③ 网站契约（sets/Environment.config）
        queueCname();
        stats.tEnv = ms(e0);       // .env + Environment.config + CNAME 入队（C：扫描相缺口归因）
        long s0 = System.nanoTime();
        scan.scanDivs();
        stats.tDivs = ms(s0);
        s0 = System.nanoTime();
        scan.scanPages();
        stats.tScanPages = ms(s0);
        s0 = System.nanoTime();
        scan.scanData();
        stats.tScanData = ms(s0);
        s0 = System.nanoTime();
        scan.scanFavicon();
        stats.tFavicon = ms(s0);
        s0 = System.nanoTime();
        scan.scanOuter();
        stats.tOuter = ms(s0);
        s0 = System.nanoTime();
        scan.scanGlobal();
        stats.tGlobalScan = ms(s0);
        stats.tScanMisc = stats.tFavicon + stats.tOuter + stats.tGlobalScan;
        s0 = System.nanoTime();
        scan.collectPageIndex();   // 预收集页面元数据（search/list 数据源；渲染前可用）
        stats.tIndex = ms(s0);
        stats.tScan = ms(t0) - stats.tDetect;
        // 重排 B：先渲染页面（divOf 惰性解析继承链），后生成全局聚合，再回填占位符
        t0 = System.nanoTime();
        renderPages();
        stats.depPages = depPages;                                   // E 埋点汇总（依赖图规模）
        stats.depKeysMin = depKeysMin == Integer.MAX_VALUE ? 0 : depKeysMin;
        stats.depKeysMax = depKeysMax;
        stats.depGroups = depGroups.size();
        stats.tPages = ms(t0);
        IO.println("[依赖埋点] 页数=" + depPages + " 每页源键=" + (depKeysMin == Integer.MAX_VALUE ? 0 : depKeysMin)
                + ".." + depKeysMax + " 去重后依赖组=" + depGroups.size());
        t0 = System.nanoTime();
        pagesBuilder.buildGlobals();
        pagesBuilder.emitSearchIndex();
        pagesBuilder.emitListShards();
        backfillGlobals();
        stats.tGlobals = ms(t0);
        for (String w : warnings) IO.println("[构建警告] " + w);
        if (detectWanted) IO.println("[变更检测] " + detectorUsed + " → "
                + (changedFiles.isEmpty() ? "无变更" : changedFiles.size() + " 个文件"));
        if (!errors.isEmpty()) throw new RuntimeException(joinErrors());
        t0 = System.nanoTime();
        Set<String> prevOutputs = new LinkedHashSet<>(manifest.outputs.keySet());   // 写盘前快照（merge 会覆盖记录）
        writer.writeAll();
        stats.tWrite = ms(t0);
        reportOrphans(prevOutputs);
        stats.tTotal = stats.tScan + stats.tDetect + stats.tPages + stats.tGlobals + stats.tWrite;
        IO.println(stats.report());   // 计数为主、耗时参考（供 Bench/CI 抓取）
        manifest.save(manifestFile(), written.size());   // 原子写；内容未变则跳过；失败只影响下次加速
        } finally {
            clearCaches();            // 构建结束立刻释放（长驻预览进程里尤其重要）
        }
    }

    private static long ms(long t0) { return (System.nanoTime() - t0) / 1_000_000; }

    /**
     * 上次写过、这次不再产生的产物（源被删除/改名）→ **警告**。
     * 清理本身留待后续版本（`outputs` 键集差集已具备前提）；但预览场景必须让人看见 ——
     * 否则旧产物会一直被服务，看起来像"改了没生效"。
     */
    private void reportOrphans(Set<String> prevOutputs) {
        if (prevOutputs.isEmpty()) return;
        List<String> orphans = new ArrayList<>();
        for (String rel : prevOutputs) {
            if (!written.contains(rel)) orphans.add(rel);
        }
        if (orphans.isEmpty()) return;
        Collections.sort(orphans);
        String sample = String.join("、", orphans.subList(0, Math.min(3, orphans.size())));
        warn("有 " + orphans.size() + " 个产物已不再生成（源被删除或改名）: " + sample + (orphans.size() > 3 ? " …" : "")
                + "（旧文件仍在产物目录里，自动清理待后续版本；预览会继续服务它们）");
    }

    /**
     * 渲染全部页面（0.4.0 **并行**）：**串行预热 → 并行渲染 → 收尾**。
     *
     * 为什么可以乱序完成：每页只写"自己的产物路径"，跨页累加器（queue/pageOutputs/presetCopies/listDirs/
     * errors/warnings/计数）都是线程安全容器，且**它们的顺序不影响产物字节**（见 history.md 的复杂度分析）。
     * 预热那一步把 `scan.effectiveDivs` 填满 → 渲染期它只读，省掉惰性解析的竞争。
     */
    private void renderPages() {
        for (String t : new ArrayList<>(divs.keySet())) scan.divOf(t);   // ① div 链预热（含 null 缓存）
        int threads = ioThreads(pages.size());
        if (threads <= 1) {                                              // 小站自动串行：不建池、零开销
            for (SiteScan.Page p : pages) renderOne(p);
            return;
        }
        ExecutorService pool = ioPool(threads);
        try {
            List<Callable<Void>> tasks = new ArrayList<>(pages.size());
            for (SiteScan.Page p : pages) tasks.add(() -> {
                renderOne(p);
                return null;
            });
            for (Future<Void> f : pool.invokeAll(tasks)) {
                try {
                    f.get();
                } catch (Exception e) {
                    errors.add("页面渲染线程异常: " + e.getMessage());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }
    }

    /** 渲染一页：每页一个 `PageScope` + 一个 `SitePages`/`SiteTags` 实例（页内状态互不共享） */
    private void renderOne(SiteScan.Page p) {
        PageScope scope = new PageScope(this, p);
        CURRENT_SCOPE.set(scope);
        try {
            new SitePages(this, new SiteTags(this, scope), scope).render(p);
        } catch (RuntimeException e) {
            errors.add("页面渲染失败（" + (p.file == null ? p.name : p.file.getName()) + "）: " + e.getMessage());
        } finally {
            CURRENT_SCOPE.remove();
            synchronized (depGroups) {                     // E 埋点统计：页数 / 依赖键数分布 / 内联组数
                depPages++;
                depKeysMin = Math.min(depKeysMin, scope.depKeys.size());
                depKeysMax = Math.max(depKeysMax, scope.depKeys.size());
                depGroups.add(String.join("|", new java.util.TreeSet<>(scope.depKeys)));
            }
        }
    }

    /** 回填 web_global 占位符（页面组装时无法预知全局聚合是否产出） */
    private void backfillGlobals() {
        for (Queued q : queue) {
            if (q.content == null) continue;
            q.content = replacePh(q.content, "{{WEBGLOBAL_JS:",
                    webGlobalJs ? "  <script src=\"@P@assets/js/web_global" + jsSuffix() + "\"></script>\n" : "");
            q.content = replacePh(q.content, "{{WEBGLOBAL_CSS:",
                    webGlobalCss ? "  <link rel=\"stylesheet\" href=\"@P@assets/css/web_global.css\">\n" : "");
        }
    }

    private static String replacePh(String c, String mark, String tag) {
        String out = c;
        int idx;
        while ((idx = out.indexOf(mark)) >= 0) {
            int close = out.indexOf("}}", idx + mark.length());
            if (close < 0) break;
            int depth = Integer.parseInt(out.substring(idx + mark.length(), close));
            out = out.substring(0, idx) + tag.replace("@P@", depthPrefix(depth)) + out.substring(close + 2);
        }
        return out;
    }

    // ==================== 聚合助手（origin 去重 + A 层 + 项级去重 + runtime） ====================

    boolean dedupOn() { return minifyLevel != 0; }
    boolean compressOn() { return minifyLevel == 2; }
    boolean keepDedupComments() { return minifyLevel == -1; }

    /** 聚合 div js（链文件根→叶；origin 保证共享祖先只出一份；A 层冲突扫描；项级去重；runtime 尾接） */
    String aggregateDivJs(List<SiteScan.DivInfo> divs) {
        long t0 = System.nanoTime();
        try {
            return aggregateDivJs0(divs);
        } finally {
            stats.nsAgg.add(System.nanoTime() - t0);
            stats.aggCalls.incrementAndGet();
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
            String content = readFile(f);
            if (sigs.add(JsDeduper.signatureOf(content))) uniq.add(f);   // 文件级内容去重
        }
        if (dedupOn()) {
            Map<String, String> decl = new LinkedHashMap<>();
            for (File f : uniq) {
                for (String name : JsDeclScan.topLevelBlockScoped(readFile(f))) {
                    String prev = decl.put(name, f.getName());
                    if (prev != null && !prev.equals(f.getName()))
                        errors.add("js 顶层 let/const/class 重名（串联后 SyntaxError）: " + name + "（" + prev + " 与 " + f.getName() + "）");
                }
            }
        }
        StringBuilder concat = new StringBuilder();
        for (File f : uniq) concat.append(readFile(f)).append('\n');
        String js = concat.toString();
        if (dedupOn()) {
            JsDeduper.Result r = JsDeduper.dedupItems(js, keepDedupComments());
            js = r.code();
            for (String note : r.notes()) warn(note);
        }
        // runtime 前置（div js 顶层即调 SsvulDiv.register，运行时必须先定义）；仅当聚合内容使用 SsvulDiv
        if (js.contains("SsvulDiv")) {
            String runtime = readPreset("runtime/ssvul-div.js");
            refPreset("runtime/ssvul-div.js");
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
            stats.nsAgg.add(System.nanoTime() - t0);
            stats.aggCalls.incrementAndGet();
        }
    }

    private String aggregateDivCss0(List<SiteScan.DivInfo> divs) {
        List<File> files = new ArrayList<>();
        Set<String> origins = new HashSet<>();
        for (SiteScan.DivInfo d : divs)
            for (File f : d.css)
                if (origins.add(f.getPath())) files.add(f);
        StringBuilder concat = new StringBuilder();
        for (File f : files) concat.append(readFile(f)).append('\n');
        String css = concat.toString();
        if (!dedupOn()) return css;
        // 结果缓存（键 = 档位|内容哈希）：多页共享同一 div 集合时只去重/压缩一次（P4）
        String key = minifyLevel + "|css|" + DepsManifest.sha256(css.getBytes(StandardCharsets.UTF_8));
        String hit = cssCache.get(key);
        if (hit != null) { stats.cssMinifyHits.incrementAndGet(); return hit; }
        String out = CssDeduper.dedup(css, keepDedupComments());
        if (compressOn()) out = CssMinifier.minify(out);
        stats.cssMinifyCalls.incrementAndGet();
        cssCache.put(key, out);
        return out;
    }

    // ==================== 环境 ====================

    private void loadEnv() {
        File f = new File(setsDir, "Environment.config");
        if (!f.isFile()) {
            errors.add("缺少 sets/Environment.config（cname 为必填项）；首次使用可将 example-sets/ 的内容复制为 sets/ 快速开始");
            return;
        }
        AssetsConfigReader acr = new AssetsConfigReader(f, stats, readFile(f));
        acr.Read();
        env = acr.getConfig();
        buckets = acr.getBuckets();
        bucketAttrs.clear();
        bucketAttrs.putAll(acr.getBucketAttrs());
        categories = "1".equals(lastOf("categories"));
        readmeOn = "1".equals(lastOf("readme"));
        localFavicon = "1".equals(lastOf("local-favicon"));
        offline = "1".equals(lastOf("offline"));
        String mv = lastOf("minify");
        minifyLevel = switch (mv) {
            case "", "2" -> 2;                 // 默认全开
            case "-1" -> -1;
            case "0" -> 0;
            case "1" -> 1;
            default -> {
                errors.add("minify 值须为 -1/0/1/2: " + mv);
                yield 2;
            }
        };
        String mn = lastOf("minifier");
        minifierName = mn.isEmpty() ? "simple" : mn;
        String st = lastOf("session-ttl");
        if (!st.isEmpty()) {
            try {
                int v = Integer.parseInt(st);
                if (v < 0) throw new NumberFormatException();
                sessionTtl = v;
            } catch (NumberFormatException e) {
                errors.add("session-ttl 值须为非负整数（秒；0=默认不缓存）: " + st);
            }
        }
        envLoaded = true;
        // U1 词表：每次构建都重建 simple 实例（含/不含用户词表），避免注册表跨构建残留旧词
        Map<String, Map<String, String>> userWords = new LinkedHashMap<>();
        List<String> ew = env.get("engine-words");
        if (ew != null) for (String raw : ew) loadEngineWords(raw, userWords);
        EngineRegistry.register("simple", new LexerCodeEngine(userWords));
    }

    /** 解析 engine-words=(语言,data/词表路径)；词表行：#注释 | 词 | 词:tokenid（默认 kw） */
    private void loadEngineWords(String raw, Map<String, Map<String, String>> userWords) {
        String v = raw.trim();
        if (v.startsWith("(") && v.endsWith(")")) v = v.substring(1, v.length() - 1).trim();
        int comma = v.indexOf(',');
        if (comma <= 0 || comma == v.length() - 1) {
            errors.add("engine-words 值需为 (语言,data/路径) 格式: " + raw);
            return;
        }
        String lang = v.substring(0, comma).trim();
        String path = v.substring(comma + 1).trim();
        if (!lang.matches("[a-z0-9]+(-[a-z0-9]+)*")) { errors.add("engine-words 语言名不合法（kebab-case）: " + lang); return; }
        if (!path.startsWith("data/")) { errors.add("engine-words 词表文件须位于 sets/data/ 下: " + path); return; }
        File f = new File(setsDir, path);
        if (!f.isFile()) { errors.add("engine-words 词表文件不存在: sets/" + path); return; }
        Map<String, String> m = userWords.computeIfAbsent(lang, k -> new LinkedHashMap<>());
        String[] lines = readFile(f).split("\n");
        for (int ln = 0; ln < lines.length; ln++) {
            String s = lines[ln].trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            int colon = s.indexOf(':');
            String word = colon < 0 ? s : s.substring(0, colon).trim();
            String tok = colon < 0 ? "kw" : s.substring(colon + 1).trim();
            if (word.isEmpty() || word.contains(" ")) { errors.add("engine-words 词表非法词（第 " + (ln + 1) + " 行）: " + s); continue; }
            if (!LexerCodeEngine.TOKEN_IDS.contains(tok)) {
                errors.add("engine-words token 未知（第 " + (ln + 1) + " 行，可用 tk id 见 token-map.js）: " + s);
                continue;
            }
            m.put(word, tok);
        }
    }

    private String lastOf(String key) {
        List<String> v = env.get(key);
        return (v == null || v.isEmpty()) ? "" : v.get(v.size() - 1);
    }

    /** cname 为唯一必填键；值为 0 时不创建 CNAME 文件（视为端口部署） */
    private void queueCname() {
        if (!envLoaded) return;   // 配置文件缺失时 loadEnv 已报错，避免重复
        String cname = lastOf("cname");
        if (cname.isEmpty()) {
            errors.add("Environment.config 缺少必填键 cname（填 0 表示不创建 CNAME 文件）");
            return;
        }
        if (cname.equals("0")) return;
        queue.add(new Queued(new File(outputDir, "CNAME"), cname + "\n", 0));
    }

    // ==================== 工具（包内可见） ====================

    /** 相对路径的目录层数（"pages/a/b" → 3 段 → 深度 3） */
    static int depthOf(String rel) {
        if (rel.isEmpty()) return 0;
        int d = 1;
        for (int k = 0; k < rel.length(); k++) if (rel.charAt(k) == '/') d++;
        return d;
    }

    static String depthPrefix(int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("../");
        return sb.toString();
    }

    static boolean isText(String name) {
        return name.endsWith(".html") || name.endsWith(".htm") || name.endsWith(".css")
                || name.endsWith(".js") || name.endsWith(".md") || name.endsWith(".json")
                || name.endsWith(".txt") || name.endsWith(".svg") || name.endsWith(".xml");
    }

    static List<File> sortedDirs(File dir) {
        File[] fs = dir.listFiles(File::isDirectory);
        if (fs == null) return Collections.emptyList();
        List<File> l = new ArrayList<>(Arrays.asList(fs));
        l.sort(Comparator.comparing(File::getName));
        return l;
    }

    static List<File> sortedFiles(File dir) {
        File[] fs = dir.listFiles();
        if (fs == null) return Collections.emptyList();
        List<File> l = new ArrayList<>(Arrays.asList(fs));
        l.sort(Comparator.comparing(File::getName));
        return l;
    }

    /** 程序侧登记预设文件复制（作者内容里的 pre-assets/ 字面量由替换趟另行处理） */
    void refPreset(String suffix) {
        if (suffix.isEmpty() || suffix.contains("..")) {
            errors.add("pre-assets/ 引用非法: \"" + suffix + "\"");
            return;
        }
        if (!new File(presetDir, suffix).isFile()) {
            errors.add("预设文件不存在: src/assets/" + suffix);
            return;
        }
        presetCopies.add(suffix);
        // css 同目录的 fonts/ 连带复制（KaTeX 字体等）
        if (suffix.endsWith(".css")) {
            File parent = new File(presetDir, suffix).getParentFile();
            File fonts = new File(parent, "fonts");
            if (fonts.isDirectory()) {
                presetDirCopies.add(suffix.substring(0, suffix.lastIndexOf('/') + 1) + "fonts");
            }
        }
    }

    String readPreset(String rel) {
        File f = new File(presetDir, rel);
        if (!f.isFile()) { errors.add("预设文件缺失: src/assets/" + rel); return ""; }
        return readFile(f);
    }

    /** 输出路径 → 相对 outputDir 的路径（统一 '/'；用于写集与 deps.json） */
    String relOf(File target) {
        String base = outputDir.getAbsolutePath();
        String p = target.getAbsolutePath();
        String rel = p.startsWith(base) ? p.substring(base.length()) : p;
        rel = rel.replace('\\', '/');
        return rel.startsWith("/") ? rel.substring(1) : rel;
    }

    /** 单次 stat（拿不到就返回 null；调用方按"需要检查"处理） */
    static java.nio.file.attribute.BasicFileAttributes attrs(File f) {
        try {
            return Files.readAttributes(f.toPath(), java.nio.file.attribute.BasicFileAttributes.class);
        } catch (IOException e) {
            return null;
        }
    }

    /** 文本产物的记录（写盘阶段在并行 job 里构造，合并阶段才进 manifest → 无共享写） */
    DepsManifest.Rec textRec(File target, long size, String sha) {
        DepsManifest.Rec r = new DepsManifest.Rec();
        r.size = size;
        r.mtime = target.lastModified();
        r.sha = sha;
        return r;
    }

    /** 二进制副本的记录（另记来源的 size/mtime；不读文件算哈希，字体等大文件代价太高） */
    DepsManifest.Rec binaryRec(File dst, long size, long srcSize, long srcMtime) {
        DepsManifest.Rec r = new DepsManifest.Rec();
        r.size = size;
        r.mtime = dst.lastModified();
        r.srcSize = srcSize;
        r.srcMtime = srcMtime;
        return r;
    }

    // ==================== 写盘相并发度（0.3.3；实测 9p 上并发近线性） ====================

    /** 默认上限 8：实测收益拐点（302 文件 stat：1→462ms / 2→93 / 4→44 / 8→32），再往上边际只有 ~1.4× */
    static final int IO_THREADS_MAX = 8;

    /**
     * 写盘相的 I/O 并发度：**CLI/.env 覆盖 > auto**；auto = `min(8, 核数-1)`（**留一个核**给宿主/其他服务），
     * 再按工作量收敛（每 16 个产物才值得多开一个线程）→ 小站自动退回串行，不为 12 个文件建池。
     * `pref = 0`（.env/CLI）= 强制串行；`pref < 0` = auto。核心是纯函数，便于门禁断言。
     *
     * 注：2 核机器 auto → 1（串行）；要用满请显式 `threads=2` / `--threads 2`。
     */
    static int ioThreads(int pref, int items, int cores) {
        if (pref == 0) return 1;                              // 明确要求串行
        int cap = pref > 0 ? pref : Math.min(IO_THREADS_MAX, Math.max(1, cores - 1));
        int byWork = Math.max(1, (items + 15) / 16);
        return Math.max(1, Math.min(cap, byWork));
    }

    int ioThreads(int items) {
        return ioThreads(threadsPref, items, Runtime.getRuntime().availableProcessors());
    }

    /** 并发度 ≤1 时返回 null（走原地串行，**同一套代码路径**，不做两套实现） */
    ExecutorService ioPool(int threads) {
        if (threads <= 1) return null;
        return Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "ssvul-io");
            t.setDaemon(true);
            return t;
        });
    }

    /** 源文件记录（v5 增量重建的输入）：只记 sets/ 与 assets/ 下的文件，键带前缀、值为相对路径。
     *  记 size+mtime+**内容 sha**（sha 供变更复核与 v5 增量；算它是顺手的，不额外读盘）。 */
    private void recSource(File f, String content, String sha) {
        recSource(sourceKey(f), f.length(), f.lastModified(), sha);
    }

    /** 源记录（按键；预取/基线可复用统计结果，省掉 readFile 里的两次 stat） */
    void recSource(String key, long size, long mtime) {
        recSource(key, size, mtime, null);
    }

    void recSource(String key, long size, long mtime, String sha) {
        if (key == null) return;
        DepsManifest.Rec old = manifest.sources.get(key);
        if (old != null && (sha == null || old.sha != null)) return;   // 已有记录（且不补齐 sha 时）不重复 stat
        DepsManifest.Rec r = new DepsManifest.Rec();
        r.size = size;
        r.mtime = mtime;
        r.sha = sha != null ? sha : (old == null ? null : old.sha);
        manifest.putSource(key, r);
    }

    private String sourceKey(File f) {
        String p = f.getAbsolutePath();
        String sets = setsDir.getAbsolutePath(), pre = presetDir.getAbsolutePath();
        if (p.startsWith(sets + File.separator)) return "sets:" + p.substring(sets.length() + 1).replace('\\', '/');
        if (p.startsWith(pre + File.separator)) return "assets:" + p.substring(pre.length() + 1).replace('\\', '/');
        return null;   // 输出目录里的文件等：不记
    }

    // ==================== 本机设置（.env）与 git（M6） ====================

    /** ① `.env`：读取（缺失则探测 git 能力后**创建一次**）；此后只读，失败只警告 */
    private void loadDotEnv() {
        File root = projectRoot();
        dotenv = DotEnv.load(root, warnings);
        int cap = GitProbe.available() ? 1 : 0;
        if (!dotenv.exists) {
            DotEnv.create(root, cap, warnings);
            dotenv.exists = DotEnv.fileOf(root).isFile();   // 只读目录里创建失败 → 下次再试
            dotenv.git = cap;
        } else {
            DotEnv.backfill(root, cap, warnings);           // 升级后老 .env 缺新键 → 末尾补齐（只动我们生成的、只追加）
        }
        cacheTotalMax = dotenv.cacheLimitBytes();
        if (threadsPref == -1) threadsPref = dotenv.threads;   // CLI 已给则优先
    }

    /** ③ git：首次基线（init + add -A）→ 忽略护栏 → 仓库整理 → 变更清单（**按需**：普通构建零开销） */
    private void loadGitState() {
        gitRepo = resolveRepo(true);
        if (!detectWanted) return;                       // 普通构建不采集变更清单
        detectNow();
    }

    /** `.env` 的 git 能力位：文件里写 `git=0` = 明确不用 git；文件不存在则按探测结果 */
    boolean gitEnabled() {
        return dotenv.exists ? dotenv.git == 1 : GitProbe.available();
    }

    /**
     * 判定"能不能用 git 检测"（含首次基线 / 忽略护栏 / 仓库整理）。
     * @param allowInit 是否允许建基线（**只给构建用**：watch 每轮轮询绝不能建库/整理仓库）
     */
    private boolean resolveRepo(boolean allowInit) {
        boolean repo = gitEnabled() && GitProbe.isRepo(setsDir);
        if (allowInit && gitEnabled() && gitInitForced != null && gitInitForced && !repo && setsDir.isDirectory()) {
            if (GitProbe.init(setsDir, warnings)) {
                repo = true;
                recordBaseline();   // 基线刚建立 = "当前的源就是基线"，别让 init 自己（含 .gitignore）被报成变更
                IO.println("已在 " + setsDir.getName() + "/ 建立独立 git 仓库（基线 git add -A，未提交；生成器仓库忽略该目录）");
            }
        }
        if (repo && GitProbe.anyIgnored(setsDir, List.of("Environment.config", "pages", "divs"))) {
            warn("sets/ 的源被 .gitignore 忽略（git 看不见这些改动）→ 变更检测回退 stat+哈希");
            repo = false;
        }
        if (allowInit && repo) {
            long[] gc = GitProbe.maybeGc(setsDir, dotenv.gitGc, manifest.gcAt, warnings);
            if (gc != null) {                       // 触发了整理 → 记下时刻与体积（机器状态，写在依赖记录里）
                manifest.gcAt = gc[0];
                manifest.gcBytes = gc[1];
            }
        }
        return repo;
    }

    /** 跑一次检测（写 `changedFiles` / `detectorUsed`）；构建与 watch 共用同一条路径 */
    void detectNow() {
        if (gitRepo && !detectorPref.equals("stat")) detectByGit();
        else detectByStat();
    }

    /** 站点身份（规范化绝对路径）：manifest 文件名与 `site` 块都用它 */
    String absSets() { return setsDir.getAbsoluteFile().getPath(); }

    String absOutput() { return outputDir.getAbsoluteFile().getPath(); }

    private void detectByGit() {
        GitProbe.Changes ch = GitProbe.status(setsDir);
        if (ch == null) {
            warn("git status 不可用（超时或报错）→ 变更检测回退 stat+哈希");
            detectByStat();
            return;
        }
        Set<String> out = new LinkedHashSet<>(ch.worktree());
        // 只进暂存区的条目（含 init 后那批 "A "）不可直接信：与上次构建的源记录复核，相同才排除（保守不误报）
        for (String rel : ch.stagedOnly()) {
            if (sourceChanged(rel)) out.add(rel);
        }
        changedFiles = out;
        detectorUsed = "git";
        stats.detectCalls++;
    }

    /**
     * 快筛 + **内容哈希终判**（tech.md §2 的判据分层）：size/mtime 不符 = 候选；候选若大小相同且记录里有 sha，
     * 就读一次内容按哈希复核 —— `touch`（只改 mtime）不再引发无谓重建，"同长度改写 + mtime 复原"也不再漏判。
     * 源文件的 sha 是 `readFile` 顺手算的（实测只占读盘成本的 2–7%，见 `build/ShaProbe.java`）。
     */
    private boolean sourceChanged(String rel) {
        String key = "sets:" + rel;
        DepsManifest.Rec r = manifest.sources.get(key);
        File f = new File(setsDir, rel);
        java.nio.file.attribute.BasicFileAttributes a = attrs(f);
        if (r == null || a == null || a.size() != r.size || a.lastModifiedTime().toMillis() != r.mtime) {
            if (r != null && r.sha != null && a != null && a.size() == r.size) {
                String sha = sha256File(f);
                if (sha != null && sha.equals(r.sha)) {
                    stats.hashVerifiedSkips++;
                    return false;                       // 内容没变（典型：纯 touch）→ 不算变更
                }
            }
            return true;
        }
        return false;
    }

    /** 候选文件的哈希复核（只对候选读一次；失败按"变了"处理，保守） */
    private static String sha256File(File f) {
        try {
            return DepsManifest.recSha(Files.readAllBytes(f.toPath()));
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * stat 快筛：与上次构建的**源记录**（size+mtime）比对，不符即候选（含首次构建：无记录 = 全是候选）。
     *
     * 边界（诚实说明）：快筛**绝不单独用于跳过写盘**（写盘与否由产物记录的内容哈希把关），它只产出
     * "哪些源可能变了"的清单；"同长度改写 + mtime 被复原"这种刻意构造会漏判 —— 要绝对可靠请用
     * `detector=git`（git 有 racy-timestamp 保护）或 `--rebuild`。
     */
    private void detectByStat() {
        List<File> all = new ArrayList<>();
        collectFiles(setsDir, all);
        Set<String> ch = new LinkedHashSet<>();
        for (File f : all) {
            String key = sourceKey(f);
            if (key == null) continue;
            if (sourceChanged(key.substring(key.indexOf(':') + 1))) ch.add(key.substring(key.indexOf(':') + 1));
        }
        changedFiles = ch;
        detectorUsed = "stat";
        stats.detectCalls++;
    }

    /** init 后把**当前全部源**记入基线（size+mtime）：初始化本身不该被下一句检测报成"全变了" */
    private void recordBaseline() {
        List<File> all = new ArrayList<>();
        collectFiles(setsDir, all);
        for (File f : all) {
            String key = sourceKey(f);
            if (key == null || manifest.sources.containsKey(key)) continue;
            DepsManifest.Rec r = new DepsManifest.Rec();
            r.size = f.length();
            r.mtime = f.lastModified();
            manifest.putSource(key, r);
        }
    }

    /** sets/ 下全部常规文件（**排序**：遍历顺序不能随文件系统变；跳过 .git/.ssvul） */
    private static void collectFiles(File dir, List<File> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        Arrays.sort(fs, Comparator.comparing(File::getName));
        for (File f : fs) {
            if (f.isDirectory()) {
                String n = f.getName();
                if (n.equals(".git") || n.equals(".ssvul")) continue;
                collectFiles(f, out);
            } else if (f.isFile()) {
                out.add(f);
            }
        }
    }

    /** 项目根 = **输出目录的父级**（标准布局 = 仓库根）：`.env` 与 `.ssvul/` 都放这里，绝不进 output/ */
    File projectRoot() {
        File parent = outputDir.getAbsoluteFile().getParentFile();
        return parent == null ? outputDir.getAbsoluteFile() : parent;
    }

    /** 依赖记录位置：`<项目根>/.ssvul/deps-<站点号>.json`（**每站点一份**，同一父目录多站点不会互相顶掉） */
    File manifestFile() {
        return new File(projectRoot(), ".ssvul/deps-" + DepsManifest.siteId(setsDir, outputDir) + ".json");
    }

    String readFile(File f) {
        String key = f.getAbsolutePath();
        PageScope sc0 = CURRENT_SCOPE.get();               // E 埋点：**每次调用**都归属（命中也要记！）
        if (sc0 != null) {
            String k0 = sourceKey(f);
            if (k0 != null) sc0.depKeys.add(k0);
        }
        String hit = textCache.get(key);
        if (hit != null) { stats.cacheHits.incrementAndGet(); return hit; }        // P3：二次命中走内存
        stats.diskReads.incrementAndGet();
        stats.cacheMisses.incrementAndGet();
        try {
            byte[] raw = Files.readAllBytes(f.toPath());
            String s = new String(raw, StandardCharsets.UTF_8);
            recSource(f, s, DepsManifest.recSha(raw));   // 记录 64 位哈希前缀：供变更复核与 v5 增量（实测哈希只占读盘成本 2–7%）
            // 首次读即缓存：实测"二次命中才存"会让第二次读照样落盘（白付一次 9p stat+read）
            // 并行下先看额度再 putIfAbsent（多线程最多轻微超限，缓存只是加速）；放不进去就算了
            if (s.length() <= CACHE_FILE_MAX && cacheBytes.sum() + s.length() <= cacheTotalMax
                    && textCache.putIfAbsent(key, s) == null) {
                cacheBytes.add(s.length());
                stats.cacheStores.incrementAndGet();
            }
            return s;
        } catch (IOException e) {
            errors.add("读取失败: " + f + " - " + e.getMessage());
            return "";
        }
    }

    /** 构建结束即释放内容缓存（长驻预览进程里尤其重要；实例本来就是每构建一份，这里是显式兜底） */
    private void clearCaches() {
        textCache.clear();
        cacheBytes.reset();
    }

    /** 构建警告（控制台输出，不阻断） */
    void warn(String msg) { warnings.add(msg); }


    /** 生成物 js 后缀：去重/压缩档（≥1）→ .min.js；0/-1 → .js */
    String jsSuffix() { return minifyLevel >= 1 ? ".min.js" : ".js"; }

    /** 经注册表压缩（Closure 预留缝）；**结果缓存**（键 = 档位|引擎|内容哈希）：同源多页共享同一聚合时只压一次；
     *  降级说明并入构建警告；异常兜底原文 */
    String minifyJs(String js) {
        String key = minifyLevel + "|" + minifierName + "|" + DepsManifest.sha256(js.getBytes(StandardCharsets.UTF_8));
        String hit = minifyCache.get(key);
        if (hit != null) { stats.minifyHits.incrementAndGet(); return hit; }
        JsMinifier m = JsMinifierRegistry.get(minifierName);
        JsMinifier.Result r = m.minify(js);
        stats.minifyCalls.incrementAndGet();
        stats.minifyBytes.add(js.length());
        for (String note : r.notes()) warn(note);
        minifyCache.put(key, r.code());
        return r.code();
    }

    // ==================== .contract 契约检查（词法可判定才警，漏报用通用警告覆盖） ====================

    private final Set<String> contractChecked = java.util.Collections.synchronizedSet(new HashSet<>());   // 渲染线程会并发 add

    void checkContract(SiteScan.DivInfo effective) {
        if (effective == null || effective.requiredHooks.isEmpty()) return;
        if (!contractChecked.add(effective.type)) return;
        SiteScan.DivInfo raw = divs.get(effective.type);
        if (raw == null || raw.js.isEmpty()) return;
        StringBuilder own = new StringBuilder();
        for (File f : raw.js) own.append(readFile(f)).append('\n');
        List<Lex.Tok> toks = Lex.tokens(own.toString());
        boolean anyRegister = false;
        for (int k = 0; k < toks.size(); k++) {
            Lex.Tok t = toks.get(k);
            if (t.type() == Lex.IDENT && t.text().equals("register")) anyRegister = true;
            if (t.type() != Lex.IDENT || !t.text().equals("register")) continue;
            int p = k + 1;
            while (p < toks.size() && (toks.get(p).type() == Lex.NL || toks.get(p).type() == Lex.COMMENT)) p++;
            if (p >= toks.size() || toks.get(p).type() != Lex.PUNCT || !toks.get(p).text().equals("(")) continue;
            int q = p + 1;
            while (q < toks.size() && (toks.get(q).type() == Lex.NL || toks.get(q).type() == Lex.COMMENT)) q++;
            if (q >= toks.size() || toks.get(q).type() != Lex.STRING) continue;
            String name = toks.get(q).text();
            name = name.length() >= 2 ? name.substring(1, name.length() - 1) : name;
            if (!effective.chainTypes.contains(name)) continue;   // 家族注册名（基类名）也在链上
            int r = q + 1;
            while (r < toks.size() && (toks.get(r).type() == Lex.NL || toks.get(r).type() == Lex.COMMENT
                    || (toks.get(r).type() == Lex.PUNCT && toks.get(r).text().equals(",")))) r++;
            if (r >= toks.size() || toks.get(r).type() != Lex.PUNCT || !toks.get(r).text().equals("{")) break;
            int depth = 0;
            Map<String, Boolean> keys = new LinkedHashMap<>();
            for (int m = r; m < toks.size(); m++) {
                Lex.Tok u = toks.get(m);
                if (u.type() == Lex.PUNCT) {
                    if (u.text().equals("{")) depth++;
                    else if (u.text().equals("}") && --depth == 0) break;
                    continue;
                }
                if (u.type() == Lex.IDENT && m + 1 < toks.size()
                        && toks.get(m + 1).type() == Lex.PUNCT && toks.get(m + 1).text().equals(":")) {
                    boolean nulled = m + 2 < toks.size() && toks.get(m + 2).type() == Lex.KEYWORD
                            && toks.get(m + 2).text().equals("null");
                    keys.put(u.text(), nulled);
                }
            }
            for (String h : effective.requiredHooks) {
                Boolean nulled = keys.get(h);
                if (nulled == null) warn("div " + effective.type + " 缺 required 钩子 " + h + "（父产物可能丢失）");
                else if (nulled) warn("div " + effective.type + " required 钩子 " + h + " 被置 null");
            }
            return;
        }
        if (anyRegister) warn("div " + effective.type + " 契约无法静态核验（动态注册），请自查 required 钩子: " + effective.requiredHooks);
        else warn("div " + effective.type + " 声明了 required 钩子但 js 无 register 调用: " + effective.requiredHooks);
    }

    String joinErrors() { return joinErrors(errors); }

    static String joinErrors(List<String> errs) {
        StringBuilder sb = new StringBuilder("站点构建错误（" + errs.size() + " 条）：\n");
        for (String e : errs) sb.append("  ").append(e).append('\n');
        return sb.toString();
    }
}
