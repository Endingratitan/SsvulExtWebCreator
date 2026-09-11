/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Integra;

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
    Map<String, String> mdOptions = Collections.emptyMap();   // 页面 md-options（默认空 = 全部默认值）

    // ---- 收集 ----
    final List<String> errors = new ArrayList<>();
    final List<String> warnings = new ArrayList<>();               // 构建警告（控制台输出，不阻断）
    final List<Queued> queue = new ArrayList<>();
    final Set<String> presetCopies = new LinkedHashSet<>();   // 被引用的 pre-assets 文件
    final Set<String> presetDirCopies = new LinkedHashSet<>(); // 连带复制的目录（如 katex fonts）
    final Map<String, SiteScan.DivInfo> divs = new LinkedHashMap<>();  // type -> div
    final Set<String> pageOutputs = new LinkedHashSet<>();    // "pages/..." 形式
    final List<SiteScan.Page> pages = new ArrayList<>();
    final Set<String> readmeNames = new HashSet<>();          // readme md 文件名去重
    final Map<String, File> outerFiles = new LinkedHashMap<>();  // "调用名/路径" → outer 镜像文件
    boolean webGlobalJs, webGlobalCss;
    boolean offline;                                           // offline=1：bucket 引用本地替换
    boolean outerDirExists;                                    // sets/outer 目录存在（对照警告用）
    EngineChain engineChain;                                   // 当前页引擎链（写盘前查询资源注入）
    List<String> engineAssets = List.of();                     // 本页要注入的引擎自动资源（性能门控后）
    final Map<String, File> codeuiFiles = new LinkedHashMap<>();  // sets/global/codeui 的站点级文件
    boolean codeuiCssExists, codeuiJsExists;                   // CODEUI.css/js 存在性（注入门控之一）
    final Map<String, File> calloutFiles = new LinkedHashMap<>();    // sets/global/callout 覆写文件（文件名 → 源）
    boolean hasCallout, injectCalloutCss;                            // 每页：callout 门控 + 是否注入覆写
    String injectCalloutVariant;                                     // 每页：命中的语言变体文件名（null → 用 CALLOUT.css）
    boolean hasCode, copyJsNeeded, injectCodeuiCss, injectCodeuiJs;   // 每页：hasCode 门控 + 注入标记
    final Set<String> pageGlobalRefs = new LinkedHashSet<>();        // 每页 global: 引用（父子双引用警告）
    final List<Map<String, String>> pageIndex = new ArrayList<>();  // 页面索引（search/list 数据源）：link/title/date/excerpt/text/tags
    boolean searchNeeded;                                           // 任页用到 search div → 输出 search-index.json
    final Map<String, Boolean> listDirs = new LinkedHashMap<>();    // list 的 ssvul:shared 声明的 dir 集合（按目录分片发射）
    boolean offlineListWarned;                                      // 每页：列目录预设的 offline 警告只发一次（R16）
    String currentPageLink;                                         // 当前页输出链接（list 构建期渲染时排除自己）
    final List<String> listAssets = new ArrayList<>();              // 本页要注入的 list 预设 js（pre-assets 相对路径）
    final List<String> mdCsrAssets = new ArrayList<>();             // 本页要注入的 md-csr 库 + hljs（低频预设路径，可长期缓存）
    final List<String> mdCsrCss = new ArrayList<>();                // md-csr 要注入的 css（math=on → KaTeX；连带 fonts/）
    boolean themePickerNeeded;                                        // 页含 palette-picker → 链接全部内置主题 css
    boolean mdCsrNeeded;                                              // 页含 md-csr → 注入 md.css / md-callout.css（内容构建期不可知）
    int currentPageDepth;                                           // 当前页深度（data-depth 属性注入）
    int minifyLevel = 2;                                       // -1 去重+注释保留 | 0 全关 | 1 去重不压缩 | 2 全开（默认）
    String minifierName = "simple";                            // minifier 键（v3 预留 closure）

    SiteScan scan;   // divOf 供页面组装使用

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

    public static void build(File setsDir, File outputDir, File presetDir) {
        new SiteBuilder(setsDir, outputDir, presetDir).run();
    }

    private SiteBuilder(File setsDir, File outputDir, File presetDir) {
        this.setsDir = setsDir; this.outputDir = outputDir; this.presetDir = presetDir;
    }

    private void run() {
        scan = new SiteScan(this);
        SiteTags tags = new SiteTags(this);
        SitePages pagesBuilder = new SitePages(this, tags);
        SiteWrite writer = new SiteWrite(this);

        loadEnv();
        queueCname();
        scan.scanDivs();
        scan.scanPages();
        scan.scanData();
        scan.scanFavicon();
        scan.scanOuter();
        scan.scanGlobal();
        scan.collectPageIndex();   // 预收集页面元数据（search/list 数据源；渲染前可用）
        // 重排 B：先渲染页面（divOf 惰性解析继承链），后生成全局聚合，再回填占位符
        for (SiteScan.Page p : pages) {
            if (p.bareMd) pagesBuilder.buildBareMd(p); else pagesBuilder.buildJsonPage(p);
        }
        pagesBuilder.buildGlobals();
        pagesBuilder.emitSearchIndex();
        pagesBuilder.emitListShards();
        backfillGlobals();
        for (String w : warnings) IO.println("[构建警告] " + w);
        if (!errors.isEmpty()) throw new RuntimeException(joinErrors());
        writer.writeAll();
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
        List<File> files = new ArrayList<>();
        Set<String> origins = new HashSet<>();
        for (SiteScan.DivInfo d : divs)
            for (File f : d.css)
                if (origins.add(f.getPath())) files.add(f);
        StringBuilder concat = new StringBuilder();
        for (File f : files) concat.append(readFile(f)).append('\n');
        String css = concat.toString();
        if (dedupOn()) css = CssDeduper.dedup(css, keepDedupComments());
        if (compressOn()) css = CssMinifier.minify(css);
        return css;
    }

    // ==================== 环境 ====================

    private void loadEnv() {
        File f = new File(setsDir, "Environment.config");
        if (!f.isFile()) {
            errors.add("缺少 sets/Environment.config（cname 为必填项）；首次使用可将 example-sets/ 的内容复制为 sets/ 快速开始");
            return;
        }
        AssetsConfigReader acr = new AssetsConfigReader(f);
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

    void copyDir(File src, File dst) throws IOException {
        File[] fs = src.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) copyDir(f, new File(dst, f.getName()));
            else {
                Files.createDirectories(dst.toPath());
                Files.copy(f.toPath(), new File(dst, f.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    String readPreset(String rel) {
        File f = new File(presetDir, rel);
        if (!f.isFile()) { errors.add("预设文件缺失: src/assets/" + rel); return ""; }
        return readFile(f);
    }

    String readFile(File f) {
        try {
            return Files.readString(f.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            errors.add("读取失败: " + f + " - " + e.getMessage());
            return "";
        }
    }

    /** 构建警告（控制台输出，不阻断） */
    void warn(String msg) { warnings.add(msg); }

    /** 按页面 lang 选 callout 覆写文件：语言变体（完整 lang → 主语言）优先，未命中回落 CALLOUT.css（存在即注入）。
     *  变体是**自包含**的：命中变体就不再注入 CALLOUT.css（想叠加就在变体里 @import url("CALLOUT.css")）。 */
    void resolveCalloutFiles(String pageLang) {
        injectCalloutCss = false;
        injectCalloutVariant = null;
        if (!hasCallout || calloutFiles.isEmpty()) return;   // 门控：本页没有 callout 就一个字节都不注入
        String lang = pageLang == null ? "" : pageLang.trim().toLowerCase(Locale.ROOT);
        String primary = lang.contains("-") ? lang.substring(0, lang.indexOf('-')) : lang;
        for (String cand : new String[]{lang, primary}) {
            if (cand.isEmpty()) continue;
            File hit = calloutFiles.get("callout." + cand + ".css");
            if (hit != null) { injectCalloutVariant = hit.getName(); injectCalloutCss = true; return; }
        }
        if (calloutFiles.containsKey("callout.css")) injectCalloutCss = true;
    }

    /** 生成物 js 后缀：去重/压缩档（≥1）→ .min.js；0/-1 → .js */
    String jsSuffix() { return minifyLevel >= 1 ? ".min.js" : ".js"; }

    /** 经注册表压缩（Closure 预留缝）；降级说明并入构建警告；异常兜底原文 */
    String minifyJs(String js) {
        JsMinifier m = JsMinifierRegistry.get(minifierName);
        JsMinifier.Result r = m.minify(js);
        for (String note : r.notes()) warn(note);
        return r.code();
    }

    // ==================== .contract 契约检查（词法可判定才警，漏报用通用警告覆盖） ====================

    private final Set<String> contractChecked = new HashSet<>();

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

    String joinErrors() {
        StringBuilder sb = new StringBuilder("站点构建错误（" + errors.size() + " 条）：\n");
        for (String e : errors) sb.append("  ").append(e).append('\n');
        return sb.toString();
    }
}
