/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * 项目来源: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Integra;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * 站点构建管线（阶段 2）：
 * Environment.config → div 索引（三态）→ 页面注册（json/裸md/raw 文件夹、INDEX 特判）→
 * data/favicon 扫描复制 → web_global 与 .global 文件生成 → 页面组装（BASE.html + 模板注入 + 标签）→
 * 输出替换趟（pre-assets/@data/@favicon/@page/bucket，按深度）→ UTF-8 写盘。
 *
 * 收集式报错：错误汇总后一次性抛出，抛出前不写任何文件（输出目录保持干净）。
 */
public class SiteBuilder {

    // ---- 配置 ----
    private final File setsDir, outputDir, presetDir;
    private Map<String, List<String>> env = new LinkedHashMap<>();
    private Map<String, String> buckets = new LinkedHashMap<>();
    private boolean categories, readmeOn, localFavicon;

    // ---- 收集 ----
    private final List<String> errors = new ArrayList<>();
    private final List<Queued> queue = new ArrayList<>();
    private final Set<String> presetCopies = new LinkedHashSet<>();   // 被引用的 pre-assets 文件
    private final Set<String> presetDirCopies = new LinkedHashSet<>(); // 连带复制的目录（如 katex fonts）
    private final Map<String, DivInfo> divs = new LinkedHashMap<>();  // type -> div
    private final Set<String> pageOutputs = new LinkedHashSet<>();    // "pages/..." 形式
    private final List<Page> pages = new ArrayList<>();
    private boolean webGlobalJs, webGlobalCss;

    private final Set<String> readmeNames = new HashSet<>();     // readme md 文件名去重

    // ---- 数据结构 ----
    static class DivInfo {
        final String type;
        final File dir;
        boolean global, adds;
        final List<File> js = new ArrayList<>();
        final List<File> css = new ArrayList<>();
        File template;
        DivInfo(String type, File dir) { this.type = type; this.dir = dir; }
    }

    static class Queued {
        final File target;
        final String content;
        final int depth;
        final File source;      // binary 用
        final boolean binary;
        Queued(File target, String content, int depth) { this.target = target; this.content = content; this.depth = depth; this.source = null; this.binary = false; }
        Queued(File target, File source) { this.target = target; this.content = null; this.depth = 0; this.source = source; this.binary = true; }
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

    // ==================== 入口 ====================

    public static void build(File setsDir, File outputDir, File presetDir) {
        new SiteBuilder(setsDir, outputDir, presetDir).run();
    }

    private SiteBuilder(File setsDir, File outputDir, File presetDir) {
        this.setsDir = setsDir; this.outputDir = outputDir; this.presetDir = presetDir;
    }

    private void run() {
        loadEnv();
        queueCname();
        scanDivs();
        scanPages();
        scanData();
        scanFavicon();
        buildGlobals();
        for (Page p : pages) {
            if (p.bareMd) buildBareMd(p); else buildJsonPage(p);
        }
        if (!errors.isEmpty()) throw new RuntimeException(joinErrors());
        writeAll();
    }

    // ==================== 环境 ====================

    private boolean envLoaded;

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

    // ==================== div 索引 ====================

    private void scanDivs() {
        File root = new File(setsDir, "divs");
        if (!root.isDirectory()) return;
        if (categories) {
            for (File cat : sortedDirs(root)) {
                for (File d : sortedDirs(cat)) registerDiv(cat.getName() + "/" + d.getName(), d);
            }
        } else {
            for (File d : sortedDirs(root)) registerDiv(d.getName(), d);
        }
    }

    private void registerDiv(String type, File dir) {
        if (!type.matches("^[a-z0-9]+(-[a-z0-9]+)*(/[a-z0-9]+(-[a-z0-9]+)*)?$")) {
            errors.add("div 类型不符合 kebab-case: " + type);
            return;
        }
        if (divs.containsKey(type)) { errors.add("div 类型重复: " + type); return; }
        DivInfo info = new DivInfo(type, dir);
        for (File f : sortedFiles(dir)) {
            String n = f.getName();
            if (n.equals(".global")) { info.global = true; continue; }
            if (n.equals(".adds")) { info.adds = true; continue; }
            if (n.equals("template.html")) { info.template = f; continue; }
            if (n.startsWith(".")) continue;
            if (n.endsWith(".js")) info.js.add(f);
            else if (n.endsWith(".css")) info.css.add(f);
            else errors.add("div " + type + " 含未知文件: " + n);
        }
        if (info.global && info.adds) errors.add("div " + type + " 不能同时有 .global 与 .adds");
        divs.put(type, info);
    }

    /** 预设 div 按需加载（sets/divs 未命中时回落 src/assets/divs） */
    private DivInfo loadPresetDiv(String type) {
        File dir = new File(presetDir, "divs/" + type);
        if (!dir.isDirectory()) return null;
        DivInfo info = new DivInfo(type, dir);
        for (File f : sortedFiles(dir)) {
            String n = f.getName();
            if (n.equals(".global")) { info.global = true; continue; }
            if (n.equals(".adds")) { info.adds = true; continue; }
            if (n.equals("template.html")) { info.template = f; continue; }
            if (n.startsWith(".")) continue;
            if (n.endsWith(".js")) info.js.add(f);
            else if (n.endsWith(".css")) info.css.add(f);
        }
        if (info.global && info.adds) errors.add("预设 div " + type + " 不能同时有 .global 与 .adds");
        divs.put(type, info);
        return info;
    }

    private DivInfo divOf(String type) {
        DivInfo info = divs.get(type);
        if (info == null) info = loadPresetDiv(type);
        return info;
    }

    // ==================== 页面扫描 ====================

    private void scanPages() {
        File root = new File(setsDir, "pages");
        if (!root.isDirectory()) return;
        scanPagesDir(root, "");
    }

    private void scanPagesDir(File dir, String rel) {
        Set<String> mdNames = new HashSet<>();
        Set<String> jsonNames = new HashSet<>();
        for (File f : sortedFiles(dir)) {
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
                errors.add("pages 下未知文件类型: " + rel + n);
            }
        }
        for (String base : mdNames) {
            if (jsonNames.contains(base)) {
                errors.add("同名 md 与 json 冲突（两套配置抢一个输出路径）: " + rel + base);
                continue;
            }
            if (!base.matches("^[a-z0-9]+(-[a-z0-9]+)*$")) {
                errors.add("md 文件名不符合 kebab-case: " + rel + base + ".md");
                continue;
            }
            registerOutput("pages/" + rel + base);
            pages.add(new Page(false, new File(dir, base + ".md"), rel, base, true));
        }
        for (String base : jsonNames) {
            if (base.equals("INDEX")) {
                if (!rel.isEmpty()) { errors.add("INDEX.json 仅允许放在 pages/ 根目录"); continue; }
                pages.add(new Page(true, new File(dir, base + ".json"), rel, "INDEX", false));
                continue;
            }
            if (!base.matches("^[a-z0-9]+(-[a-z0-9]+)*$")) {
                errors.add("json 文件名不符合 kebab-case: " + rel + base + ".json");
                continue;
            }
            pages.add(new Page(false, new File(dir, base + ".json"), rel, base, false));
        }
    }

    private void registerOutput(String out) {
        if (!pageOutputs.add(out)) errors.add("页面输出路径冲突: " + out);
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
        for (File f : sortedFiles(dir)) {
            if (f.isDirectory()) { queueRaw(f, outRel + "/" + f.getName()); continue; }
            File target = new File(outputDir, outRel + "/" + f.getName());
            if (isText(f.getName())) queue.add(new Queued(target, readFile(f), depthOf(outRel)));
            else queue.add(new Queued(target, f));
        }
    }

    // ==================== data / favicon ====================

    private void scanData() {
        File root = new File(setsDir, "data");
        if (!root.isDirectory()) return;
        scanDataDir(root, "");
    }

    private void scanDataDir(File dir, String rel) {
        for (File f : sortedFiles(dir)) {
            String n = f.getName();
            if (f.isDirectory()) { scanDataDir(f, rel + n + "/"); continue; }
            if (n.startsWith(".")) continue;
            WebType t = WebType.checkName(n);
            if (t == WebType.JS || t == WebType.CSS) {
                errors.add("data 目录禁止 js/css: data/" + rel + n);
                continue;
            }
            if (rel.startsWith("readme/")) {
                if (!n.endsWith(".md")) { errors.add("data/readme/ 仅允许 .md 文件: " + n); continue; }
                if (!readmeOn) continue;
                if (!readmeNames.add(n)) { errors.add("readme md 文件名重复（复制到 output 根会冲突）: " + n); continue; }
                queue.add(new Queued(new File(outputDir, n), readFile(f), 0));
                continue;
            }
            queue.add(new Queued(new File(outputDir, "assets/data/" + rel + n), f));
        }
    }

    private void scanFavicon() {
        if (localFavicon) return;   // 云端模式不复制本地 favicon
        File root = new File(setsDir, "favicon");
        if (!root.isDirectory()) return;
        for (File f : sortedFiles(root)) {
            if (f.isFile()) queue.add(new Queued(new File(outputDir, "favicon/" + f.getName()), f));
        }
    }

    // ==================== 全局文件 ====================

    private void buildGlobals() {
        List<String> types = new ArrayList<>(divs.keySet());
        Collections.sort(types);
        StringBuilder js = new StringBuilder(), css = new StringBuilder();
        for (String type : types) {
            DivInfo info = divs.get(type);
            if (!info.adds) continue;
            for (File f : info.js) js.append(readFile(f)).append('\n');
            for (File f : info.css) css.append(readFile(f)).append('\n');
        }
        if (js.length() > 0) {
            queue.add(new Queued(new File(outputDir, "assets/js/web_global.js"), js.toString(), 2));
            webGlobalJs = true;
        }
        if (css.length() > 0) {
            queue.add(new Queued(new File(outputDir, "assets/css/web_global.css"), css.toString(), 2));
            webGlobalCss = true;
        }
        for (String type : types) {
            DivInfo info = divs.get(type);
            if (!info.global) continue;
            String flat = type.replace('/', '-');
            StringBuilder j2 = new StringBuilder(), c2 = new StringBuilder();
            for (File f : info.js) j2.append(readFile(f)).append('\n');
            for (File f : info.css) c2.append(readFile(f)).append('\n');
            if (j2.length() > 0) queue.add(new Queued(new File(outputDir, "assets/js/" + flat + ".js"), j2.toString(), 2));
            if (c2.length() > 0) queue.add(new Queued(new File(outputDir, "assets/css/" + flat + ".css"), c2.toString(), 2));
        }
    }

    // ==================== 页面组装 ====================

    private void buildJsonPage(Page p) {
        AssetsConfigReader acr = new AssetsConfigReader(p.file);
        acr.Read();
        JsonNode root = acr.getJson();
        String name = root.path("name").asText("");
        if (name.isEmpty()) name = p.name;
        String outRel;
        int depth;
        if (p.index) {
            if (!name.equals("INDEX")) { errors.add("INDEX.json 的 name 键必须为 INDEX"); return; }
            outRel = "index.html";
            depth = 0;
        } else {
            if (!name.matches("^[a-z0-9]+(-[a-z0-9]+)*$")) {
                errors.add("页面名不符合 kebab-case: " + name);
                return;
            }
            registerOutput("pages/" + p.rel + name);
            outRel = "pages/" + p.rel + name + "/index.html";
            depth = depthOf("pages/" + p.rel + name);
        }

        Set<String> pageTypes = new LinkedHashSet<>();
        boolean[] hasMd = {false};
        String content = renderDivGroup("", root.path("page"), "页面 " + name, pageTypes, hasMd);

        StringBuilder pageJs = new StringBuilder(), pageCss = new StringBuilder();
        for (String type : pageTypes) {
            DivInfo info = divs.get(type);
            if (info == null) continue;
            for (File f : info.js) pageJs.append(readFile(f)).append('\n');
            for (File f : info.css) pageCss.append(readFile(f)).append('\n');
        }
        boolean hasPageJs = pageJs.length() > 0, hasPageCss = pageCss.length() > 0;
        // 页面文件以页面名命名（<name>.js/.css），与页面 html 同级；INDEX 页特例放 assets/index/
        String pageFileJs, pageFileCss;
        int pageFileDepth;
        if (p.index) {
            pageFileJs = "assets/index/index.js";
            pageFileCss = "assets/index/index.css";
            pageFileDepth = 2;
        } else {
            pageFileJs = "pages/" + p.rel + name + "/" + name + ".js";
            pageFileCss = "pages/" + p.rel + name + "/" + name + ".css";
            pageFileDepth = depth;
        }
        if (hasPageJs) queue.add(new Queued(new File(outputDir, pageFileJs), pageJs.toString(), pageFileDepth));
        if (hasPageCss) queue.add(new Queued(new File(outputDir, pageFileCss), pageCss.toString(), pageFileDepth));

        boolean themeActive = !root.path("theme").asText("").isEmpty();
        String links = buildLinks(root, hasMd[0], depth) + pageCssTag(hasPageCss, p.index, name, depth);
        String scripts = buildScripts(root, depth, hasPageJs, themeActive, p.index, name);

        String base = readPreset("page/BASE.html");
        Map<String, String> repl = new LinkedHashMap<>();
        repl.put("{{lang}}", root.path("lang").asText("zh-CN"));
        repl.put("{{htmlattrs}}", themeAttr(root));
        repl.put("{{title}}", MarkdownRenderer.escapeHtml(root.path("title").asText(name)));
        String desc = root.path("description").asText("");
        repl.put("{{description}}", desc.isEmpty() ? "" : "  <meta name=\"description\" content=\"" + MarkdownRenderer.escapeHtml(desc) + "\">\n");
        repl.put("{{favicon}}", buildFavicon(root, depth));
        repl.put("{{head}}", buildHead(root));
        repl.put("{{links}}", links);
        repl.put("{{content}}", content);
        repl.put("{{scripts}}", scripts);
        for (Map.Entry<String, String> e : repl.entrySet()) base = base.replace(e.getKey(), e.getValue());
        checkLeftover(base, "BASE.html", "页面 " + name);
        queue.add(new Queued(new File(outputDir, outRel), base, depth));
    }

    private void buildBareMd(Page p) {
        String outRel = "pages/" + p.rel + p.name + "/index.html";
        int depth = depthOf("pages/" + p.rel + p.name);
        String content;
        try {
            content = MarkdownRenderer.render(readFile(p.file), "pages/" + p.rel + p.name + ".md");
        } catch (RuntimeException e) {
            errors.add(e.getMessage());
            return;
        }
        String base = readPreset("page/BASE.html");
        refPreset("md/css/md.css");
        String links = "  <link rel=\"stylesheet\" href=\"" + depthPrefix(depth) + "assets/pre/md/css/md.css\">\n"
                + (webGlobalCss ? "  <link rel=\"stylesheet\" href=\"" + depthPrefix(depth) + "assets/css/web_global.css\">\n" : "");
        String scripts = webGlobalJs ? "  <script src=\"" + depthPrefix(depth) + "assets/js/web_global.js\"></script>\n" : "";
        base = base.replace("{{lang}}", "zh-CN")
                   .replace("{{htmlattrs}}", "")
                   .replace("{{title}}", MarkdownRenderer.escapeHtml(p.name))
                   .replace("{{description}}", "")
                   .replace("{{favicon}}", "")
                   .replace("{{head}}", "")
                   .replace("{{links}}", links)
                   .replace("{{content}}", content)
                   .replace("{{scripts}}", scripts);
        checkLeftover(base, "BASE.html", "页面 " + p.name);
        queue.add(new Queued(new File(outputDir, outRel), base, depth));
    }

    // ---- div 渲染 ----

    private String renderDivGroup(String parentId, JsonNode group, String pageSrc,
                                  Set<String> pageTypes, boolean[] hasMd) {
        if (group == null || !group.isObject()) return "";
        List<Map.Entry<String, JsonNode>> list = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> it = group.fields();
        while (it.hasNext()) list.add(it.next());
        list.sort(Comparator.comparingInt(e -> Integer.parseInt(e.getKey().substring("div-".length()))));
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, JsonNode> e : list)
            sb.append(renderDiv(parentId, e.getKey(), e.getValue(), pageSrc, pageTypes, hasMd));
        return sb.toString();
    }

    private String renderDiv(String parentId, String key, JsonNode div, String pageSrc,
                             Set<String> pageTypes, boolean[] hasMd) {
        String id = parentId.isEmpty() ? key : parentId + "-" + key.substring("div-".length());
        String type = div.path("type").asText();
        DivInfo info = divOf(type);
        if (info == null) errors.add(pageSrc + " 的 div 类型不存在: " + type + "（sets/divs 与 src/assets/divs 均未找到）");

        JsonNode attrs = div.get("attrs");
        String extraCls = (attrs != null && attrs.isObject() && attrs.has("class")) ? attrs.get("class").asText() : "";
        StringBuilder sb = new StringBuilder();
        sb.append("<div id=\"").append(id).append("\" class=\"ssvul-").append(type.replace('/', '-'));
        if (!extraCls.isEmpty()) sb.append(' ').append(extraCls);
        sb.append('"');
        appendAttrs(sb, attrs);
        appendDataAttrs(sb, div.get("params"));
        sb.append(">\n");

        String content = null;
        if (div.has("raw")) {
            content = div.get("raw").asText();
        } else if (div.has("markdown")) {
            hasMd[0] = true;
            content = resolveMd(div.get("markdown").asText(), pageSrc);
        }
        String children = renderDivGroup(id, div.get("divs"), pageSrc, pageTypes, hasMd);

        if (info != null && info.template != null) {
            String t = readFile(info.template);
            sb.append(injectTemplate(t, div.get("params"), content, children, pageSrc, type)).append('\n');
            if (!t.contains("{{content}}") && content != null) sb.append(content).append('\n');
            if (!t.contains("{{children}}") && !children.isEmpty()) sb.append(children);
        } else {
            if (content != null) sb.append(content).append('\n');
            sb.append(children);
        }
        sb.append("</div>\n");
        if (info != null && !info.global && !info.adds) pageTypes.add(type);
        return sb.toString();
    }

    private static void appendAttrs(StringBuilder sb, JsonNode attrs) {
        if (attrs == null || !attrs.isObject()) return;
        Iterator<Map.Entry<String, JsonNode>> it = attrs.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> a = it.next();
            String k = a.getKey();
            if (k.equals("class") || k.equals("id")) continue;   // class 已合并，id 由 div-ID 专属
            sb.append(' ').append(k).append("=\"").append(a.getValue().asText()).append('"');
        }
    }

    private static void appendDataAttrs(StringBuilder sb, JsonNode params) {
        if (params == null || !params.isObject()) return;
        Iterator<Map.Entry<String, JsonNode>> it = params.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> p = it.next();
            sb.append(" data-").append(p.getKey()).append("=\"").append(p.getValue().asText()).append('"');
        }
    }

    private String injectTemplate(String t, JsonNode params, String content, String children,
                                  String pageSrc, String type) {
        String out = t;
        if (params != null && params.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = params.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                out = out.replace("{{" + e.getKey() + "}}", e.getValue().asText());
            }
        }
        out = out.replace("{{content}}", content == null ? "" : content);
        out = out.replace("{{children}}", children);
        int p = out.indexOf("{{");
        if (p >= 0) {
            int q = out.indexOf("}}", p);
            String left = q >= 0 ? out.substring(p, q + 2) : out.substring(p, Math.min(out.length(), p + 30));
            errors.add(pageSrc + " 的 div " + type + " 模板存在未声明占位符: " + left);
        }
        return out;
    }

    private String resolveMd(String field, String pageSrc) {
        if (!field.startsWith("@")) {
            try { return MarkdownRenderer.render(field, pageSrc); }
            catch (RuntimeException e) { errors.add(e.getMessage()); return ""; }
        }
        String path = field.substring(1);
        if (!path.startsWith("pages/") && !path.startsWith("data/")) {
            errors.add(pageSrc + " 的 md 引用路径不合法（须 @pages/ 或 @data/）: " + field);
            return "";
        }
        File f = new File(setsDir, path);
        if (!f.isFile() || !f.getName().endsWith(".md")) {
            errors.add(pageSrc + " 的 md 文件不存在或非 md: " + field);
            return "";
        }
        try { return MarkdownRenderer.render(readFile(f), path); }
        catch (RuntimeException e) { errors.add(e.getMessage()); return ""; }
    }

    // ---- 标签生成 ----

    private String buildLinks(JsonNode root, boolean hasMd, int depth) {
        StringBuilder sb = new StringBuilder();
        JsonNode deps = root.path("deps");
        if (deps.isArray()) for (JsonNode d : deps) sb.append(depTag(d.asText(), depth, true));
        if (hasMd) {
            String mc = root.path("md-css").asText("");
            if (mc.isEmpty()) mc = "pre-assets/md/css/md.css";
            sb.append(depTag(mc, depth, true));
        }
        // theme 键自动带出对应主题 css（md/css/md-code-<theme>.css，不存在则跳过，作者可经 deps 自行引入）
        String theme = root.path("theme").asText("");
        if (!theme.isEmpty()) {
            String tcss = "pre-assets/md/css/md-code-" + theme + ".css";
            if (new File(presetDir, "md/css/md-code-" + theme + ".css").isFile()) {
                sb.append(depTag(tcss, depth, true));
            }
        }
        if (webGlobalCss) sb.append("  <link rel=\"stylesheet\" href=\"").append(depthPrefix(depth)).append("assets/css/web_global.css\">\n");
        return sb.toString();
    }

    private String buildScripts(JsonNode root, int depth, boolean hasPageJs, boolean themeActive,
                                boolean indexPage, String name) {
        StringBuilder sb = new StringBuilder();
        if (webGlobalJs) sb.append("  <script src=\"").append(depthPrefix(depth)).append("assets/js/web_global.js\"></script>\n");
        JsonNode deps = root.path("deps");
        if (deps.isArray()) for (JsonNode d : deps) sb.append(depTag(d.asText(), depth, false));
        List<String> mdJs = new ArrayList<>();
        JsonNode mj = root.path("md-js");
        if (mj.isArray()) for (JsonNode d : mj) mdJs.add(d.asText());
        if (themeActive && !mdJs.contains("pre-assets/md/js/md-theme.js")) mdJs.add("pre-assets/md/js/md-theme.js");
        for (String s : mdJs) {
            if (s.startsWith("pre-assets/")) {
                String suffix = s.substring("pre-assets/".length());
                refPreset(suffix);
                sb.append("  <script src=\"").append(depthPrefix(depth)).append("assets/pre/")
                  .append(suffix).append("\"></script>\n");
            } else if (s.startsWith("http://") || s.startsWith("https://")) {
                sb.append("  <script src=\"").append(s).append("\"></script>\n");
            } else {
                errors.add("md-js 条目形态不合法: " + s);
            }
        }
        if (hasPageJs) {
            String src = indexPage ? depthPrefix(depth) + "assets/index/index.js" : name + ".js";
            sb.append("  <script src=\"").append(src).append("\"></script>\n");
        }
        return sb.toString();
    }

    private String pageCssTag(boolean hasPageCss, boolean indexPage, String name, int depth) {
        if (!hasPageCss) return "";
        String href = indexPage ? depthPrefix(depth) + "assets/index/index.css" : name + ".css";
        return "  <link rel=\"stylesheet\" href=\"" + href + "\">\n";
    }

    private String depTag(String s, int depth, boolean cssPass) {
        if (s.startsWith("global:")) {
            String type = s.substring("global:".length());
            DivInfo info = divs.get(type);
            if (info == null || !info.global) {
                errors.add("global: 引用的不是 .global div（或不存在）: " + type);
                return "";
            }
            String flat = type.replace('/', '-');
            if (cssPass) {
                return info.css.isEmpty() ? "" : "  <link rel=\"stylesheet\" href=\"" + depthPrefix(depth) + "assets/css/" + flat + ".css\">\n";
            }
            return info.js.isEmpty() ? "" : "  <script src=\"" + depthPrefix(depth) + "assets/js/" + flat + ".js\"></script>\n";
        }
        String url;
        if (s.startsWith("pre-assets/")) {
            String suffix = s.substring("pre-assets/".length());
            refPreset(suffix);
            url = depthPrefix(depth) + "assets/pre/" + suffix;
        }
        else if (s.startsWith("http://") || s.startsWith("https://")) url = s;
        else { errors.add("deps 条目形态不合法: " + s); return ""; }
        boolean isCss = s.endsWith(".css");
        boolean isJs = s.endsWith(".js");
        if (!isCss && !isJs) { errors.add("deps 仅支持 .js/.css 文件: " + s); return ""; }
        if (isCss != cssPass) return "";
        return cssPass
                ? "  <link rel=\"stylesheet\" href=\"" + url + "\">\n"
                : "  <script src=\"" + url + "\"></script>\n";
    }

    private String buildFavicon(JsonNode root, int depth) {
        String f = root.path("favicon").asText("");
        if (f.isEmpty()) return "";
        if (f.startsWith("http://") || f.startsWith("https://")) return "  <link rel=\"icon\" href=\"" + f + "\">\n";
        if (f.startsWith("pre-assets/")) {
            String suffix = f.substring("pre-assets/".length());
            refPreset(suffix);
            return "  <link rel=\"icon\" href=\"" + depthPrefix(depth) + "assets/pre/" + suffix + "\">\n";
        }
        if (f.startsWith("@favicon/")) {
            if (localFavicon) { errors.add("local-favicon=1（云端模式）下 favicon 键不能使用 @favicon/ 形态"); return ""; }
            String name = f.substring("@favicon/".length());
            if (!new File(setsDir, "favicon/" + name).isFile()) { errors.add("favicon 文件不存在: sets/favicon/" + name); return ""; }
            return "  <link rel=\"icon\" href=\"" + depthPrefix(depth) + "favicon/" + name + "\">\n";
        }
        for (String b : buckets.keySet()) {
            if (f.startsWith(b + "/"))
                return "  <link rel=\"icon\" href=\"" + buckets.get(b) + "/" + f.substring(b.length() + 1) + "\">\n";
        }
        errors.add("favicon 值形态不合法（允许 http(s)://、pre-assets/、@favicon/、bucket 调用名）: " + f);
        return "";
    }

    private String buildHead(JsonNode root) {
        StringBuilder sb = new StringBuilder();
        if (!root.path("theme").asText("").isEmpty()) {
            sb.append("  <script>").append(readPreset("md/js/theme-head-inline.js").trim()).append("</script>\n");
        }
        JsonNode head = root.path("head");
        if (head.isArray()) for (JsonNode h : head) sb.append("  ").append(h.asText()).append('\n');
        return sb.toString();
    }

    private String themeAttr(JsonNode root) {
        String theme = root.path("theme").asText("");
        return theme.isEmpty() ? "" : " data-theme=\"" + theme + "\"";
    }

    private void checkLeftover(String base, String what, String who) {
        int p = base.indexOf("{{");
        if (p < 0) return;
        int q = base.indexOf("}}", p);
        String left = q >= 0 ? base.substring(p, q + 2) : base.substring(p, Math.min(base.length(), p + 30));
        errors.add(who + " 的 " + what + " 存在未注入占位符: " + left);
    }

    // ==================== 输出替换趟 ====================

    private void writeAll() {
        for (Queued q : queue) {
            try {
                if (q.binary) {
                    Files.createDirectories(q.target.getParentFile().toPath());
                    Files.copy(q.source.toPath(), q.target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    continue;
                }
                String c = q.content;
                c = replacePreAssets(c, q.depth, q.target);
                c = c.replace("@data/", depthPrefix(q.depth) + "assets/data/");
                c = c.replace("@favicon/", depthPrefix(q.depth) + "favicon/");
                c = replacePageRefs(c, q.depth);
                if (!q.target.getName().endsWith(".js")) c = replaceBuckets(c);   // JS 不做 bk/ 替换（误伤风险）
                Files.createDirectories(q.target.getParentFile().toPath());
                Files.writeString(q.target.toPath(), c, StandardCharsets.UTF_8);
            } catch (IOException e) {
                errors.add("写盘失败: " + q.target + " - " + e.getMessage());
            }
        }
        for (String rel : presetCopies) {
            File src = new File(presetDir, rel);
            File dst = new File(outputDir, "assets/pre/" + rel);
            try {
                Files.createDirectories(dst.getParentFile().toPath());
                Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                errors.add("预设复制失败: " + rel + " - " + e.getMessage());
            }
        }
        for (String rel : presetDirCopies) {
            try {
                copyDir(new File(presetDir, rel), new File(outputDir, "assets/pre/" + rel));
            } catch (IOException e) {
                errors.add("预设目录复制失败: " + rel + " - " + e.getMessage());
            }
        }
        if (!errors.isEmpty()) throw new RuntimeException(joinErrors());
    }

    private String replacePreAssets(String c, int depth, File target) {
        String out = c;
        int idx;
        while ((idx = out.indexOf("pre-assets/")) >= 0) {
            int start = idx + "pre-assets/".length();
            int end = start;
            while (end < out.length() && isPathChar(out.charAt(end))) end++;
            String suffix = out.substring(start, end);
            if (suffix.isEmpty() || suffix.contains("..")) {
                errors.add("pre-assets/ 引用非法: \"" + suffix + "\"（" + target.getName() + "）");
                break;
            }
            if (!new File(presetDir, suffix).isFile()) {
                errors.add("预设文件不存在: src/assets/" + suffix);
                break;
            }
            presetCopies.add(suffix);
            out = out.substring(0, idx) + depthPrefix(depth) + "assets/pre/" + out.substring(start);
        }
        return out;
    }

    private String replacePageRefs(String c, int depth) {
        String out = c;
        int idx;
        while ((idx = out.indexOf("@page/")) >= 0) {
            int start = idx + "@page/".length();
            int end = start;
            while (end < out.length() && isPathChar(out.charAt(end))) end++;
            String path = out.substring(start, end);
            if (path.isEmpty() || !pageOutputs.contains("pages/" + path)) {
                errors.add("站内互链目标不存在: @page/" + path);
                break;
            }
            out = out.substring(0, idx) + depthPrefix(depth) + "pages/" + path + "/" + out.substring(end);
        }
        return out;
    }

    /** 引用路径允许的字符（字母数字、点、下划线、斜线、连字符） */
    private static boolean isPathChar(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '.' || ch == '_' || ch == '/' || ch == '-';
    }

    private String replaceBuckets(String c) {
        String out = c;
        List<String> names = new ArrayList<>(buckets.keySet());
        names.sort(Comparator.comparingInt(String::length).reversed());
        for (String n : names) out = out.replace(n + "/", buckets.get(n) + "/");
        return out;
    }

    // ==================== 工具 ====================

    /** 相对路径的目录层数（"pages/a/b" → 3 段 → 深度 3） */
    private static int depthOf(String rel) {
        if (rel.isEmpty()) return 0;
        int d = 1;
        for (int k = 0; k < rel.length(); k++) if (rel.charAt(k) == '/') d++;
        return d;
    }

    private static String depthPrefix(int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("../");
        return sb.toString();
    }

    private static boolean isText(String name) {
        return name.endsWith(".html") || name.endsWith(".htm") || name.endsWith(".css")
                || name.endsWith(".js") || name.endsWith(".md") || name.endsWith(".json")
                || name.endsWith(".txt") || name.endsWith(".svg") || name.endsWith(".xml");
    }

    private static List<File> sortedDirs(File dir) {
        File[] fs = dir.listFiles(File::isDirectory);
        if (fs == null) return Collections.emptyList();
        List<File> l = new ArrayList<>(Arrays.asList(fs));
        l.sort(Comparator.comparing(File::getName));
        return l;
    }

    private static List<File> sortedFiles(File dir) {
        File[] fs = dir.listFiles();
        if (fs == null) return Collections.emptyList();
        List<File> l = new ArrayList<>(Arrays.asList(fs));
        l.sort(Comparator.comparing(File::getName));
        return l;
    }

    /** 程序侧登记预设文件复制（作者内容里的 pre-assets/ 字面量由替换趟另行处理） */
    private void refPreset(String suffix) {
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

    private void copyDir(File src, File dst) throws IOException {
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

    private String readPreset(String rel) {
        File f = new File(presetDir, rel);
        if (!f.isFile()) { errors.add("预设文件缺失: src/assets/" + rel); return ""; }
        return readFile(f);
    }

    private String readFile(File f) {
        try {
            return Files.readString(f.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            errors.add("读取失败: " + f + " - " + e.getMessage());
            return "";
        }
    }

    private String joinErrors() {
        StringBuilder sb = new StringBuilder("站点构建错误（" + errors.size() + " 条）：\n");
        for (String e : errors) sb.append("  ").append(e).append('\n');
        return sb.toString();
    }
}
