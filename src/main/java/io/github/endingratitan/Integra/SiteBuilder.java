/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * 项目来源: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Integra;

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

    SiteScan scan;   // divOf 供页面组装使用

    static class Queued {
        final File target;
        final String content;
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
        pagesBuilder.buildGlobals();
        for (SiteScan.Page p : pages) {
            if (p.bareMd) pagesBuilder.buildBareMd(p); else pagesBuilder.buildJsonPage(p);
        }
        for (String w : warnings) IO.println("[构建警告] " + w);
        if (!errors.isEmpty()) throw new RuntimeException(joinErrors());
        writer.writeAll();
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
        categories = "1".equals(lastOf("categories"));
        readmeOn = "1".equals(lastOf("readme"));
        localFavicon = "1".equals(lastOf("local-favicon"));
        offline = "1".equals(lastOf("offline"));
        envLoaded = true;
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

    String joinErrors() {
        StringBuilder sb = new StringBuilder("站点构建错误（" + errors.size() + " 条）：\n");
        for (String e : errors) sb.append("  ").append(e).append('\n');
        return sb.toString();
    }
}
