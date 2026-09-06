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
import java.util.*;

/**
 * 站点输入扫描（包内私有）：div 索引（三态）→ 页面注册（json/裸md/raw 文件夹/INDEX 特判）→
 * data（禁 js/css、readme 平铺）→ favicon。owner 为 SiteBuilder（共享收集容器）。
 */
class SiteScan {

    static class DivInfo {
        final String type;
        final File dir;
        boolean global, adds;
        final List<File> js = new ArrayList<>();
        final List<File> css = new ArrayList<>();
        File template;
        DivInfo(String type, File dir) { this.type = type; this.dir = dir; }
    }

    static class Page {
        final boolean index;
        final File file;
        final String rel;       // pages 下相对目录（"" 或 "a/b/"）
        final String name;      // 文件名或 INDEX
        final boolean bareMd;
        Page(boolean index, File file, String rel, String name, boolean bareMd) {
            this.index = index; this.file = file; this.rel = rel; this.name = name; this.bareMd = bareMd;
        }
    }

    private final SiteBuilder sb;

    SiteScan(SiteBuilder sb) { this.sb = sb; }

    // ==================== div 索引 ====================

    void scanDivs() {
        File root = new File(sb.setsDir, "divs");
        if (!root.isDirectory()) return;
        if (sb.categories) {
            for (File cat : SiteBuilder.sortedDirs(root)) {
                for (File d : SiteBuilder.sortedDirs(cat)) registerDiv(cat.getName() + "/" + d.getName(), d);
            }
        } else {
            for (File d : SiteBuilder.sortedDirs(root)) registerDiv(d.getName(), d);
        }
    }

    private void registerDiv(String type, File dir) {
        if (!type.matches("^[a-z0-9]+(-[a-z0-9]+)*(/[a-z0-9]+(-[a-z0-9]+)*)?$")) {
            sb.errors.add("div 类型不符合 kebab-case: " + type);
            return;
        }
        if (sb.divs.containsKey(type)) { sb.errors.add("div 类型重复: " + type); return; }
        DivInfo info = new DivInfo(type, dir);
        for (File f : SiteBuilder.sortedFiles(dir)) {
            String n = f.getName();
            if (n.equals(".global")) { info.global = true; continue; }
            if (n.equals(".adds")) { info.adds = true; continue; }
            if (n.equals("template.html")) { info.template = f; continue; }
            if (n.startsWith(".")) continue;
            if (n.endsWith(".js")) info.js.add(f);
            else if (n.endsWith(".css")) info.css.add(f);
            else sb.errors.add("div " + type + " 含未知文件: " + n);
        }
        if (info.global && info.adds) sb.errors.add("div " + type + " 不能同时有 .global 与 .adds");
        sb.divs.put(type, info);
    }

    /** 预设 div 按需加载（sets/divs 未命中时回落 src/assets/divs） */
    private DivInfo loadPresetDiv(String type) {
        File dir = new File(sb.presetDir, "divs/" + type);
        if (!dir.isDirectory()) return null;
        DivInfo info = new DivInfo(type, dir);
        for (File f : SiteBuilder.sortedFiles(dir)) {
            String n = f.getName();
            if (n.equals(".global")) { info.global = true; continue; }
            if (n.equals(".adds")) { info.adds = true; continue; }
            if (n.equals("template.html")) { info.template = f; continue; }
            if (n.startsWith(".")) continue;
            if (n.endsWith(".js")) info.js.add(f);
            else if (n.endsWith(".css")) info.css.add(f);
        }
        if (info.global && info.adds) sb.errors.add("预设 div " + type + " 不能同时有 .global 与 .adds");
        sb.divs.put(type, info);
        return info;
    }

    DivInfo divOf(String type) {
        DivInfo info = sb.divs.get(type);
        if (info == null) info = loadPresetDiv(type);
        return info;
    }

    // ==================== 页面扫描 ====================

    void scanPages() {
        File root = new File(sb.setsDir, "pages");
        if (!root.isDirectory()) return;
        scanPagesDir(root, "");
    }

    private void scanPagesDir(File dir, String rel) {
        Set<String> mdNames = new HashSet<>();
        Set<String> jsonNames = new HashSet<>();
        for (File f : SiteBuilder.sortedFiles(dir)) {
            String n = f.getName();
            if (f.isDirectory()) {
                if (isRawFolder(f)) {
                    registerOutput("pages/" + rel + n);
                    queueRaw(f, "pages/" + rel + n);
                } else {
                    scanPagesDir(f, rel + n + "/");
                }
            } else if (n.endsWith(".md")) {
                mdNames.add(n.substring(0, n.length() - 3));
            } else if (n.endsWith(".json")) {
                jsonNames.add(n.substring(0, n.length() - 5));
            } else if (!n.startsWith(".")) {
                sb.errors.add("pages 下未知文件类型: " + rel + n);
            }
        }
        for (String base : mdNames) {
            if (jsonNames.contains(base)) {
                sb.errors.add("同名 md 与 json 冲突（两套配置抢一个输出路径）: " + rel + base);
                continue;
            }
            if (!base.matches("^[a-z0-9]+(-[a-z0-9]+)*$")) {
                sb.errors.add("md 文件名不符合 kebab-case: " + rel + base + ".md");
                continue;
            }
            registerOutput("pages/" + rel + base);
            sb.pages.add(new Page(false, new File(dir, base + ".md"), rel, base, true));
        }
        for (String base : jsonNames) {
            if (base.equals("INDEX")) {
                if (!rel.isEmpty()) { sb.errors.add("INDEX.json 仅允许放在 pages/ 根目录"); continue; }
                sb.pages.add(new Page(true, new File(dir, base + ".json"), rel, "INDEX", false));
                continue;
            }
            if (!base.matches("^[a-z0-9]+(-[a-z0-9]+)*$")) {
                sb.errors.add("json 文件名不符合 kebab-case: " + rel + base + ".json");
                continue;
            }
            sb.pages.add(new Page(false, new File(dir, base + ".json"), rel, base, false));
        }
    }

    private void registerOutput(String out) {
        if (!sb.pageOutputs.add(out)) sb.errors.add("页面输出路径冲突: " + out);
    }

    /** 目录内直接含 html/css/js 文件 → raw 页面；否则视为中间结构目录 */
    private boolean isRawFolder(File dir) {
        File[] fs = dir.listFiles();
        if (fs == null) return false;
        for (File f : fs) {
            if (!f.isFile()) continue;
            String n = f.getName();
            if (n.endsWith(".html") || n.endsWith(".css") || n.endsWith(".js")) return true;
        }
        return false;
    }

    private void queueRaw(File dir, String outRel) {
        for (File f : SiteBuilder.sortedFiles(dir)) {
            if (f.isDirectory()) { queueRaw(f, outRel + "/" + f.getName()); continue; }
            File target = new File(sb.outputDir, outRel + "/" + f.getName());
            if (SiteBuilder.isText(f.getName())) sb.queue.add(new SiteBuilder.Queued(target, sb.readFile(f), SiteBuilder.depthOf(outRel)));
            else sb.queue.add(new SiteBuilder.Queued(target, f));
        }
    }

    // ==================== data / favicon ====================

    void scanData() {
        File root = new File(sb.setsDir, "data");
        if (!root.isDirectory()) return;
        scanDataDir(root, "");
    }

    private void scanDataDir(File dir, String rel) {
        for (File f : SiteBuilder.sortedFiles(dir)) {
            String n = f.getName();
            if (f.isDirectory()) { scanDataDir(f, rel + n + "/"); continue; }
            if (n.startsWith(".")) continue;
            WebType t = WebType.checkName(n);
            if (t == WebType.JS || t == WebType.CSS) {
                sb.errors.add("data 目录禁止 js/css: data/" + rel + n);
                continue;
            }
            if (rel.startsWith("readme/")) {
                if (!n.endsWith(".md")) { sb.errors.add("data/readme/ 仅允许 .md 文件: " + n); continue; }
                if (!sb.readmeOn) continue;
                if (!sb.readmeNames.add(n)) { sb.errors.add("readme md 文件名重复（复制到 output 根会冲突）: " + n); continue; }
                sb.queue.add(new SiteBuilder.Queued(new File(sb.outputDir, n), sb.readFile(f), 0));
                continue;
            }
            sb.queue.add(new SiteBuilder.Queued(new File(sb.outputDir, "assets/data/" + rel + n), f));
        }
    }

    void scanFavicon() {
        if (sb.localFavicon) return;   // 云端模式不复制本地 favicon
        File root = new File(sb.setsDir, "favicon");
        if (!root.isDirectory()) return;
        for (File f : SiteBuilder.sortedFiles(root)) {
            if (f.isFile()) sb.queue.add(new SiteBuilder.Queued(new File(sb.outputDir, "favicon/" + f.getName()), f));
        }
    }

    // ==================== outer（bucket 本地镜像） ====================

    void scanOuter() {
        File root = new File(sb.setsDir, "outer");
        if (!root.isDirectory()) return;
        sb.outerDirExists = true;
        for (File bucketDir : SiteBuilder.sortedDirs(root)) {
            collectOuter(bucketDir, bucketDir.getName() + "/");
        }
    }

    private void collectOuter(File dir, String rel) {
        for (File f : SiteBuilder.sortedFiles(dir)) {
            if (f.isDirectory()) { collectOuter(f, rel + f.getName() + "/"); continue; }
            sb.outerFiles.put(rel + f.getName(), f);   // 键 = "调用名/相对路径"
        }
    }

    // ==================== global（全局域，如 codeui） ====================

    void scanGlobal() {
        File root = new File(sb.setsDir, "global");
        if (!root.isDirectory()) return;
        for (File d : SiteBuilder.sortedDirs(root)) {
            if (d.getName().equals("codeui")) {
                for (File f : SiteBuilder.sortedFiles(d)) {
                    String n = f.getName();
                    if (n.equals("CODEUI.css") || n.equals("CODEUI.js")) {
                        sb.codeuiFiles.put(n, f);
                    } else {
                        sb.errors.add("sets/global/codeui 仅允许 CODEUI.css/CODEUI.js: " + n);
                    }
                }
            } else {
                sb.warn("sets/global/ 未知域（预留，已忽略）: " + d.getName());
            }
        }
        sb.codeuiCssExists = sb.codeuiFiles.containsKey("CODEUI.css");
        sb.codeuiJsExists = sb.codeuiFiles.containsKey("CODEUI.js");
        // 站点级文件复制进 output/assets/global/codeui/（文本：内部 pre-assets/@data 引用可被替换趟处理）
        for (Map.Entry<String, File> e : sb.codeuiFiles.entrySet()) {
            sb.queue.add(new SiteBuilder.Queued(
                    new File(sb.outputDir, "assets/global/codeui/" + e.getKey()), sb.readFile(e.getValue()), 3));
        }
    }
}
