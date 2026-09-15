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
        final List<File> js = new ArrayList<>();      // 有效链（根→叶）：divOf 解析后含父的
        final List<File> css = new ArrayList<>();
        File template;
        String extendsType;                            // .extends 父类型（本目录原始声明）
        String contractMode = "extend";                // extend|override（.contract 首非空行）
        List<String> requiredHooks = List.of();        // .contract required 钩子
        List<String> chainTypes = List.of();           // 继承链类型名（根→叶；契约检查按家族注册名匹配）
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
        readDivFiles(info, dir, true);
        if (info.global && info.adds) sb.errors.add("div " + type + " 不能同时有 .global 与 .adds");
        sb.divs.put(type, info);
    }

    /** 读取 div 目录文件（标记/模板/js/css/extends/contract）；strict=true 时未知文件报错 */
    private void readDivFiles(DivInfo info, File dir, boolean strict) {
        for (File f : SiteBuilder.sortedFiles(dir)) {
            String n = f.getName();
            if (n.equals(".global")) { info.global = true; continue; }
            if (n.equals(".adds")) { info.adds = true; continue; }
            if (n.equals(".extends")) {
                info.extendsType = sb.readFile(f).lines().map(String::trim)
                        .filter(s -> !s.isEmpty()).findFirst().orElse("");
                continue;
            }
            if (n.equals(".contract")) { readContract(f, info); continue; }
            if (n.equals("template.html")) { info.template = f; continue; }
            if (n.startsWith(".")) continue;
            if (n.endsWith(".js")) info.js.add(f);
            else if (n.endsWith(".css")) info.css.add(f);
            else if (strict) sb.errors.add("div " + info.type + " 含未知文件: " + n);
        }
    }

    /** .contract：跳过空行后首个非空行 = extend|override 模式（非关键字则视为钩子，模式缺省 extend）；其余行 = required 钩子 */
    private void readContract(File f, DivInfo info) {
        List<String> hooks = new ArrayList<>();
        boolean first = true;
        for (String l : sb.readFile(f).split("\n")) {
            String s = l.trim();
            if (s.isEmpty()) continue;
            if (first) {
                first = false;
                switch (s) {
                    case "_extend", "extend", "-extend" -> info.contractMode = "extend";
                    case "_override", "override", "-override" -> info.contractMode = "override";
                    default -> hooks.add(s);   // 非关键字 → 视为钩子，模式缺省 extend
                }
            } else hooks.add(s);
        }
        info.requiredHooks = hooks;
    }

    /** 预设 div 按需加载（sets/divs 未命中时回落 src/assets/divs） */
    private DivInfo loadPresetDiv(String type) {
        File dir = new File(sb.presetDir, "divs/" + type);
        if (!dir.isDirectory()) return null;
        DivInfo info = new DivInfo(type, dir);
        readDivFiles(info, dir, false);
        if (info.global && info.adds) sb.errors.add("预设 div " + type + " 不能同时有 .global 与 .adds");
        sb.divs.put(type, info);
        return info;
    }

    // ==================== 继承解析（有效 DivInfo，memo） ====================

    private final Map<String, DivInfo> effectiveDivs = new HashMap<>();

    DivInfo divOf(String type) {
        DivInfo e = effectiveDivs.get(type);
        if (e != null) return e;
        e = resolve(type, new LinkedHashSet<>(), 0);
        effectiveDivs.put(type, e);   // null（父缺失）也缓存，避免重复报错
        return e;
    }

    private DivInfo resolve(String type, Set<String> seen, int depth) {
        if (!seen.add(type)) { sb.errors.add("div 继承环: " + type); return null; }
        if (depth >= 8) { sb.errors.add("div 继承链过长（上限 8）: " + type); return null; }
        DivInfo raw = sb.divs.get(type);
        if (raw == null) raw = loadPresetDiv(type);
        if (raw == null) return null;
        DivInfo out = new DivInfo(type, raw.dir);
        if (raw.extendsType == null || raw.extendsType.isEmpty()) {
            copyOf(out, raw);
            out.chainTypes = List.of(type);
            return out;
        }
        DivInfo parent = resolve(raw.extendsType, seen, depth + 1);
        if (parent == null) {
            sb.errors.add("div " + type + " 的父类型不存在: " + raw.extendsType);
            copyOf(out, raw);   // 降级：叶子 raw（构建最终因错误失败）
            out.chainTypes = List.of(type);
            return out;
        }
        List<String> chain = new ArrayList<>(parent.chainTypes);
        chain.add(type);
        out.chainTypes = chain;
        // 根→叶合并：模板后者覆盖；js/css 串联父前子后
        out.js.addAll(parent.js);
        out.css.addAll(parent.css);
        out.template = parent.template;
        if (raw.template != null) out.template = raw.template;
        out.js.addAll(raw.js);
        out.css.addAll(raw.css);
        // 标记：并集沿链；冲突时子 .contract 写 override 才可覆盖父标记，否则报错
        String parentMark = parent.adds ? "adds" : parent.global ? "global" : null;
        String ownMark = raw.adds ? "adds" : raw.global ? "global" : null;
        if (ownMark == null) {
            out.global = parent.global;
            out.adds = parent.adds;
        } else if (parentMark == null || parentMark.equals(ownMark)) {
            applyMark(out, ownMark);
        } else if ("override".equals(raw.contractMode)) {
            applyMark(out, ownMark);
        } else {
            sb.errors.add("div 继承链上 .adds/.global 互斥: " + type
                    + "（父为 " + parentMark + "，子为 " + ownMark + "；.contract 写 override 可覆盖）");
            applyMark(out, ownMark);   // 降级按子处理（构建最终失败）
        }
        out.contractMode = raw.contractMode;
        out.requiredHooks = raw.requiredHooks;
        return out;
    }

    private static void copyOf(DivInfo dst, DivInfo src) {
        dst.template = src.template;
        dst.js.addAll(src.js);
        dst.css.addAll(src.css);
        dst.global = src.global;
        dst.adds = src.adds;
        dst.contractMode = src.contractMode;
        dst.requiredHooks = src.requiredHooks;
    }

    private static void applyMark(DivInfo info, String mark) {
        info.adds = "adds".equals(mark);
        info.global = "global".equals(mark);
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
        // list 为生成器保留目录（ssvul:shared 分片输出 assets/data/list/），避免与用户数据碰撞
        if (new File(root, "list").isDirectory())
            sb.errors.add("sets/data/list 为生成器保留目录（list 的 ssvul:shared 分片输出），请改名");
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
            } else if (d.getName().equals("callout")) {
                // callout 视觉/语言逃生舱：CALLOUT.css 与 CALLOUT.<lang>.css（lang 为占位符；存在即注入，hasCallout 门控）
                for (File f : SiteBuilder.sortedFiles(d)) {
                    String n = f.getName();
                    if (n.equals("CALLOUT.css") || n.matches("CALLOUT\\.[A-Za-z0-9-]+\\.css")) {
                        sb.calloutFiles.put(n.toLowerCase(Locale.ROOT), f);   // 键小写：lang 命中时不区分大小写
                    } else {
                        sb.errors.add("sets/global/callout 仅允许 CALLOUT.css / CALLOUT.<lang>.css: " + n);
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
        // callout 覆写文件同样复制进 assets/global/callout/（文本：内部 pre-assets/@data 引用可被替换趟处理）
        for (Map.Entry<String, File> e : sb.calloutFiles.entrySet()) {
            sb.queue.add(new SiteBuilder.Queued(
                    new File(sb.outputDir, "assets/global/callout/" + e.getValue().getName()), sb.readFile(e.getValue()), 3));
        }
    }


    // ==================== 页面索引（search/list 数据源；渲染前预收集） ====================

    void collectPageIndex() {
        for (Page p : sb.pages) {
            String link, type, title, excerpt, text = "", tags = "";
            if (p.bareMd) {
                String md = sb.readFile(p.file);
                link = "pages/" + p.rel + p.name + "/";
                type = "md";
                title = cleanInline(firstHeading(md, p.name));
                excerpt = cleanInline(firstParagraph(md));
                text = md;
            } else {
                try {
                    com.fasterxml.jackson.databind.JsonNode root = AssetsConfigReader.jsonMapper().readTree(sb.readFile(p.file));
                    String name = root.path("name").asText("");
                    if (name.isEmpty()) name = p.name;
                    // link 必须用 json 的 name（输出路径由它决定）：曾用文件名拼 → name≠文件名 时列表/搜索链到 404
                    link = p.index ? "" : "pages/" + p.rel + name + "/";
                    type = "json";
                    title = cleanInline(root.path("title").asText(name));
                    excerpt = cleanInline(root.path("description").asText(""));
                    StringBuilder tg = new StringBuilder();
                    com.fasterxml.jackson.databind.JsonNode t = root.path("tags");
                    if (t.isArray()) for (com.fasterxml.jackson.databind.JsonNode x : t) { if (tg.length() > 0) tg.append(','); tg.append(x.asText()); }
                    tags = tg.toString();
                    StringBuilder full = new StringBuilder();
                    collectMd(root.path("page"), full);
                    text = full.toString();
                    if (excerpt.isEmpty() && !text.isEmpty()) excerpt = cleanInline(excerptOf(text));
                } catch (Exception ex) {
                    continue;   // 解析失败留给 buildJsonPage 的严格校验报错；索引跳过该页
                }
            }
            Map<String, String> e = new LinkedHashMap<>();
            e.put("link", link);
            e.put("date", ymd(p.file.lastModified()));
            e.put("type", type);
            e.put("title", title);
            e.put("excerpt", excerpt);
            e.put("text", text);
            e.put("tags", tags);
            sb.pageIndex.add(e);
        }
    }

    /** 条目文本净化：去图片、链接留文字、去行内标记（列表与搜索摘要共用；构建期条目即客户端条目） */
    static String cleanInline(String s) {
        if (s == null || s.isEmpty()) return "";
        String t = s.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "");   // 图片整段去掉
        t = t.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1");      // 链接留文字
        t = t.replaceAll("\\*{1,3}|_{1,2}|~~|`", "");              // 粗/斜/删除/行内码标记
        return t.trim();
    }

    private void collectMd(com.fasterxml.jackson.databind.JsonNode node, StringBuilder out) {
        if (node == null) return;
        if (node.isObject()) {
            com.fasterxml.jackson.databind.JsonNode m = node.get("markdown");
            if (m != null && m.isTextual()) appendMdText(m.asText(), out);
            java.util.Iterator<java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> it = node.fields();
            while (it.hasNext()) collectMd(it.next().getValue(), out);
        } else if (node.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode c : node) collectMd(c, out);
        }
    }

    private void appendMdText(String field, StringBuilder out) {
        if (field.startsWith("@")) {
            String path = field.substring(1);
            if (path.startsWith("pages/") || path.startsWith("data/")) {
                File f = new File(sb.setsDir, path);
                if (f.isFile()) { if (out.length() > 0) out.append('\n'); out.append(sb.readFile(f)); }
            }
            return;
        }
        if (out.length() > 0) out.append('\n');
        out.append(field);
    }

    static String firstHeading(String md, String fallback) {
        for (String l : md.split("\n")) {
            String t = l.trim();
            if (t.startsWith("# ")) return t.substring(2).trim();
        }
        return fallback;
    }

    static String firstParagraph(String md) {
        StringBuilder sb2 = new StringBuilder();
        boolean started = false;
        for (String l : md.split("\n")) {
            String t = l.trim();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("```") || t.startsWith("|")
                    || t.startsWith(">") || t.startsWith("-") || t.startsWith("[")) continue;
            if (started && sb2.length() > 0) sb2.append(' ');
            sb2.append(t);
            started = true;
            if (sb2.length() > 80) break;
        }
        return sb2.length() > 80 ? sb2.substring(0, 80) + "…" : sb2.toString();
    }

    static String excerptOf(String text) {
        String[] lines = text.split("\n");
        StringBuilder sb2 = new StringBuilder();
        for (String l : lines) {
            String t = l.trim();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("```") || t.startsWith("|")
                    || t.startsWith(">") || t.startsWith("-") || t.startsWith("[") || t.startsWith("[^")) continue;
            if (sb2.length() > 0) sb2.append(' ');
            sb2.append(t);
            if (sb2.length() > 80) break;
        }
        return sb2.length() > 80 ? sb2.substring(0, 80) + "…" : sb2.toString();
    }

    static String ymd(long millis) {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
                .format(java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()));
    }
}
