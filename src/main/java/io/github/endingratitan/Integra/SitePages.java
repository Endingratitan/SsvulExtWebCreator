/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Integra;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.File;
import java.util.*;

/**
 * 页面组装（包内私有）：全局文件生成（web_global/.global 三态）→ json/裸md 页面构建 →
 * div 树渲染（模板注入/{{footnotes}}）→ md 解析委托。owner 为 SiteBuilder，tags 为标签生成器。
 */
class SitePages {

    private final SiteBuilder sb;
    private final SiteTags tags;

    SitePages(SiteBuilder sb, SiteTags tags) {
        this.sb = sb;
        this.tags = tags;
    }

    // ==================== 全局文件 ====================

    void buildGlobals() {
        // 页面渲染完成后聚合：.adds → web_global；.global → 独立 flat 文件（origin 去重 + 项级去重 + runtime）
        List<String> types = new ArrayList<>(sb.divs.keySet());
        Collections.sort(types);
        List<SiteScan.DivInfo> addsDivs = new ArrayList<>();
        for (String type : types) {
            SiteScan.DivInfo e = sb.scan.divOf(type);
            if (e != null && e.adds) addsDivs.add(e);
        }
        if (!addsDivs.isEmpty()) {
            String js = sb.aggregateDivJs(addsDivs);
            String css = sb.aggregateDivCss(addsDivs);
            if (!js.isEmpty()) {
                sb.queue.add(new SiteBuilder.Queued(new File(sb.outputDir, "assets/js/web_global" + sb.jsSuffix()), js, 2));
                sb.webGlobalJs = true;
            }
            if (!css.isEmpty()) {
                sb.queue.add(new SiteBuilder.Queued(new File(sb.outputDir, "assets/css/web_global.css"), css, 2));
                sb.webGlobalCss = true;
            }
        }
        for (String type : types) {
            SiteScan.DivInfo e = sb.scan.divOf(type);
            if (e == null || !e.global) continue;
            if (e.js.isEmpty() && e.css.isEmpty()) continue;
            String flat = type.replace('/', '-');
            String js = sb.aggregateDivJs(List.of(e));
            String css = sb.aggregateDivCss(List.of(e));
            if (!js.isEmpty()) sb.queue.add(new SiteBuilder.Queued(new File(sb.outputDir, "assets/js/" + flat + sb.jsSuffix()), js, 2));
            if (!css.isEmpty()) sb.queue.add(new SiteBuilder.Queued(new File(sb.outputDir, "assets/css/" + flat + ".css"), css, 2));
        }
    }

    // ==================== 页面构建 ====================

    void buildJsonPage(SiteScan.Page p) {
        AssetsConfigReader acr = new AssetsConfigReader(p.file);
        acr.Read();
        JsonNode root = acr.getJson();
        String name = root.path("name").asText("");
        if (name.isEmpty()) name = p.name;
        String outRel;
        int depth;
        if (p.index) {
            if (!name.equals("INDEX")) { sb.errors.add("INDEX.json 的 name 键必须为 INDEX"); return; }
            outRel = "index.html";
            depth = 0;
        } else {
            if (!name.matches("^[a-z0-9]+(-[a-z0-9]+)*$")) {
                sb.errors.add("页面名不符合 kebab-case: " + name);
                return;
            }
            registerOutput("pages/" + p.rel + name);
            outRel = "pages/" + p.rel + name + "/index.html";
            depth = SiteBuilder.depthOf("pages/" + p.rel + name);
        }
        sb.currentPageDepth = depth;

        sb.mdOptions = new LinkedHashMap<>();
        sb.pageGlobalRefs.clear();
        JsonNode mo = root.path("md-options");
        if (mo.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = mo.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                sb.mdOptions.put(e.getKey(), e.getValue().asText());
            }
        }

        // code-ui：items 数组 + 块级属性；全部默认关/缺省（解析后注入 options 通道给渲染层）
        List<String> codeUiItems = new ArrayList<>();
        JsonNode cu = root.path("code-ui");
        if (cu.isObject()) {
            JsonNode it = cu.path("items");
            if (it.isArray()) for (JsonNode e : it) if (e.isTextual()) codeUiItems.add(e.asText());
            String bg = cu.path("bg").asText("");
            if (!bg.isEmpty()) {
                boolean ok = bg.startsWith("pre-assets/") || bg.startsWith("http://") || bg.startsWith("https://")
                        || sb.buckets.keySet().stream().anyMatch(b -> bg.startsWith(b + "/"));
                if (!ok) sb.errors.add("code-ui.bg 形态不合法（pre-assets/、http(s)://、bucket 调用名）: " + bg);
                else sb.mdOptions.put("codeui.bg", bg);
            }
            if (cu.path("rounded").asBoolean(false)) sb.mdOptions.put("codeui.rounded", "true");
            String lp = cu.path("label-pos").asText("");
            if (!lp.isEmpty()) sb.mdOptions.put("codeui.label-pos", lp);
        }
        sb.mdOptions.put("codeui.items", String.join(",", codeUiItems));   // 空 = 无外壳（字节兼容）

        // 页面级引擎链（缺省 = hljs；未知名警告剔除并回退 hljs）；本页 md 渲染全程使用
        List<String> engNames = new ArrayList<>();
        JsonNode eng = root.path("engine");
        if (eng.isTextual()) engNames.add(eng.asText());
        else if (eng.isArray()) for (JsonNode e : eng) if (e.isTextual()) engNames.add(e.asText());
        sb.engineChain = EngineRegistry.resolve(engNames, sb::warn);
        sb.engineAssets = List.of();
        MarkdownRenderer.setCodeEngine(sb.engineChain);

        Set<String> pageTypes = new LinkedHashSet<>();
        boolean[] hasMd = {false};
        boolean[] hasCode = {false};
        sb.hasCode = false;
        sb.copyJsNeeded = false;
        sb.injectCodeuiCss = false;
        sb.injectCodeuiJs = false;
        String content = renderDivGroup("", root.path("page"), "页面 " + name, pageTypes, hasMd, hasCode, sb.mdOptions);
        // 性能门控：本页确有块落到带客户端资源的成员（如 hljs）才注入引擎资源
        if (sb.engineChain.clientAssetsNeeded()) sb.engineAssets = sb.engineChain.autoAssets();
        // CODEUI 注入门控：站点级文件存在 + 本页有代码块；copy-btn 项触发内置复制脚本
        sb.hasCode = hasCode[0];
        sb.injectCodeuiCss = sb.codeuiCssExists && hasCode[0];
        sb.injectCodeuiJs = sb.codeuiJsExists && hasCode[0];
        sb.copyJsNeeded = hasCode[0] && codeUiItems.contains("copy-btn");

        // 页面级 js/css：origin 去重 + 项级去重 + runtime（聚合助手）
        List<SiteScan.DivInfo> pageDivs = new ArrayList<>();
        for (String type : pageTypes) {
            SiteScan.DivInfo info = sb.scan.divOf(type);
            if (info != null) pageDivs.add(info);
        }
        String pageJsText = pageDivs.isEmpty() ? "" : sb.aggregateDivJs(pageDivs);
        String pageCssText = pageDivs.isEmpty() ? "" : sb.aggregateDivCss(pageDivs);
        boolean hasPageJs = !pageJsText.isEmpty(), hasPageCss = !pageCssText.isEmpty();
        // 页面文件以页面名命名（<name>.js/.css），与页面 html 同级；INDEX 页特例放 assets/index/
        String pageFileJs, pageFileCss;
        int pageFileDepth;
        if (p.index) {
            pageFileJs = "assets/index/index" + sb.jsSuffix();
            pageFileCss = "assets/index/index.css";
            pageFileDepth = 2;
        } else {
            pageFileJs = "pages/" + p.rel + name + "/" + name + sb.jsSuffix();
            pageFileCss = "pages/" + p.rel + name + "/" + name + ".css";
            pageFileDepth = depth;
        }
        if (hasPageJs) sb.queue.add(new SiteBuilder.Queued(new File(sb.outputDir, pageFileJs), pageJsText, pageFileDepth));
        if (hasPageCss) sb.queue.add(new SiteBuilder.Queued(new File(sb.outputDir, pageFileCss), pageCssText, pageFileDepth));

        boolean themeActive = !root.path("theme").asText("").isEmpty();
        String links = tags.buildLinks(root, hasMd[0], depth) + tags.pageCssTag(hasPageCss, p.index, name, depth);
        String scripts = tags.buildScripts(root, depth, hasPageJs, themeActive, p.index, name);

        String base = sb.readPreset("page/BASE.html");
        Map<String, String> repl = new LinkedHashMap<>();
        repl.put("{{lang}}", root.path("lang").asText("zh-CN"));
        repl.put("{{htmlattrs}}", tags.themeAttr(root));
        repl.put("{{title}}", MarkdownRenderer.escapeHtml(root.path("title").asText(name)));
        String desc = root.path("description").asText("");
        repl.put("{{description}}", desc.isEmpty() ? "" : "  <meta name=\"description\" content=\"" + MarkdownRenderer.escapeHtml(desc) + "\">\n");
        repl.put("{{favicon}}", tags.buildFavicon(root, depth));
        repl.put("{{head}}", tags.buildHead(root));
        repl.put("{{links}}", links);
        repl.put("{{content}}", content);
        repl.put("{{scripts}}", scripts);
        for (Map.Entry<String, String> e : repl.entrySet()) base = base.replace(e.getKey(), e.getValue());
        tags.checkLeftover(base, "BASE.html", "页面 " + name);
        sb.queue.add(new SiteBuilder.Queued(new File(sb.outputDir, outRel), base, depth));
    }

    void buildBareMd(SiteScan.Page p) {
        sb.mdOptions = Collections.emptyMap();   // 裸 md 无 json，全用默认
        sb.pageGlobalRefs.clear();
        // 裸页不注入引擎资源（性能优先：省流量）；渲染仍走默认 hljs 链（纯转义，与 v1 输出一致）
        sb.engineChain = EngineRegistry.resolve(List.of("hljs"), sb::warn);
        sb.engineAssets = List.of();
        MarkdownRenderer.setCodeEngine(sb.engineChain);
        sb.hasCode = false;
        sb.copyJsNeeded = false;
        sb.injectCodeuiCss = false;
        sb.injectCodeuiJs = false;
        String outRel = "pages/" + p.rel + p.name + "/index.html";
        int depth = SiteBuilder.depthOf("pages/" + p.rel + p.name);
        sb.currentPageDepth = depth;
        String content;
        try {
            MarkdownRenderer.MdResult mr = MarkdownRenderer.renderParts(sb.readFile(p.file),
                    "pages/" + p.rel + p.name + ".md", Collections.emptyMap());
            content = MarkdownRenderer.joinResult(mr);
            if (mr.hasCode()) {   // 裸页有代码块时同样注入站点级 CODEUI
                sb.hasCode = true;
                sb.injectCodeuiCss = sb.codeuiCssExists;
                sb.injectCodeuiJs = sb.codeuiJsExists;
            }
        } catch (RuntimeException e) {
            sb.errors.add(e.getMessage());
            return;
        }
        String base = sb.readPreset("page/BASE.html");
        sb.refPreset("md/css/md.css");
        String links = "  <link rel=\"stylesheet\" href=\"" + SiteBuilder.depthPrefix(depth) + "assets/pre/md/css/md.css\">\n"
                + "{{WEBGLOBAL_CSS:" + depth + "}}";
        String scripts = "{{WEBGLOBAL_JS:" + depth + "}}";
        base = base.replace("{{lang}}", "zh-CN")
                   .replace("{{htmlattrs}}", "")
                   .replace("{{title}}", MarkdownRenderer.escapeHtml(p.name))
                   .replace("{{description}}", "")
                   .replace("{{favicon}}", "")
                   .replace("{{head}}", "")
                   .replace("{{links}}", links)
                   .replace("{{content}}", content)
                   .replace("{{scripts}}", scripts);
        tags.checkLeftover(base, "BASE.html", "页面 " + p.name);
        sb.queue.add(new SiteBuilder.Queued(new File(sb.outputDir, outRel), base, depth));
    }

    // ---- div 渲染 ----

    private String renderDivGroup(String parentId, JsonNode group, String pageSrc,
                                  Set<String> pageTypes, boolean[] hasMd, boolean[] hasCode,
                                  Map<String, String> inheritedMd) {
        if (group == null || !group.isObject()) return "";
        List<Map.Entry<String, JsonNode>> list = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> it = group.fields();
        while (it.hasNext()) list.add(it.next());
        list.sort(Comparator.comparingInt(e -> Integer.parseInt(e.getKey().substring("div-".length()))));
        StringBuilder sb2 = new StringBuilder();
        for (Map.Entry<String, JsonNode> e : list)
            sb2.append(renderDiv(parentId, e.getKey(), e.getValue(), pageSrc, pageTypes, hasMd, hasCode, inheritedMd));
        return sb2.toString();
    }

    private String renderDiv(String parentId, String key, JsonNode div, String pageSrc,
                             Set<String> pageTypes, boolean[] hasMd, boolean[] hasCode,
                             Map<String, String> inheritedMd) {
        String id = parentId.isEmpty() ? key : parentId + "-" + key.substring("div-".length());
        String type = div.path("type").asText();
        SiteScan.DivInfo info = sb.scan.divOf(type);
        if (info == null) sb.errors.add(pageSrc + " 的 div 类型不存在: " + type + "（sets/divs 与 src/assets/divs 均未找到）");
        else sb.checkContract(info);

        JsonNode attrs = div.get("attrs");
        String extraCls = (attrs != null && attrs.isObject() && attrs.has("class")) ? attrs.get("class").asText() : "";
        StringBuilder sb2 = new StringBuilder();
        sb2.append("<div id=\"").append(id).append("\" class=\"ssvul-").append(type.replace('/', '-'));
        if (!extraCls.isEmpty()) sb2.append(' ').append(extraCls);
        sb2.append('"');
        appendAttrs(sb2, attrs);
        appendDataAttrs(sb2, div.get("params"));
        sb2.append(" data-depth=\"").append(sb.currentPageDepth).append('"');
        if (info != null && !info.chainTypes.isEmpty())
            sb2.append(" data-family=\"").append(String.join(",", info.chainTypes)).append('"');
        if (type.equals("search")) sb.searchNeeded = true;
        if (type.equals("palette-picker")) sb.themePickerNeeded = true;
        sb2.append(">\n");
        if (type.equals("shower")) {
            boolean shared = div.path("params").path("shared").asBoolean(false);
            if (shared) {
                // 共享模式：按 dir 分片发射 assets/data/shower/<dir>.json（只发射实际声明的目录），客户端按 data-* 过滤
                String dir = div.path("params").path("dir").asText("");
                if (!dir.matches("[a-z0-9/_-]*") || dir.contains("..") || dir.startsWith("/")) {
                    sb.errors.add(pageSrc + " 的 shower 共享模式 dir 不合法（小写字母数字/_-，禁止 .. 与前导 /）: " + dir);
                } else {
                    sb.showerDirs.put(dir, true);
                }
            } else {
                // 内联注入数据（零请求、file:// 预览可用、离线可用）；</script> 防注入转义
                String j = showerJson(div.get("params"), id).replace("<", "\\u003c");
                sb2.append("<script type=\"application/json\" class=\"shower-data\">").append(j).append("</script>\n");
            }
        }

        // div 级 md-options：级联覆盖（只覆盖本 div 写出的键，其余继承页面级/父 div）
        Map<String, String> mdOpts = inheritedMd;
        JsonNode mo = div.get("md-options");
        if (mo != null && mo.isObject()) {
            mdOpts = new LinkedHashMap<>(inheritedMd);
            Iterator<Map.Entry<String, JsonNode>> mit = mo.fields();
            while (mit.hasNext()) {
                Map.Entry<String, JsonNode> me = mit.next();
                mdOpts.put(me.getKey(), me.getValue().asText());
            }
        }

        boolean hasTemplate = info != null && info.template != null;
        String content = null;
        String footnotes = "";
        if (div.has("raw")) {
            content = div.get("raw").asText();
        } else if (div.has("markdown")) {
            hasMd[0] = true;
            MarkdownRenderer.MdResult mr = resolveMd(div.get("markdown").asText(), pageSrc, mdOpts, id + "-");
            if (mr.hasCode()) hasCode[0] = true;
            if (hasTemplate) {
                content = mr.mdBody();
                footnotes = mr.footnotes();   // 模板含 {{footnotes}} 时注入该处
            } else {
                content = MarkdownRenderer.joinResult(mr);   // 无模板：脚注区并入 md-body 末尾
            }
        }
        String children = renderDivGroup(id, div.get("divs"), pageSrc, pageTypes, hasMd, hasCode, mdOpts);

        if (hasTemplate) {
            String t = sb.readFile(info.template);
            sb2.append(injectTemplate(t, div.get("params"), content, children, footnotes, pageSrc, type)).append('\n');
            if (!t.contains("{{content}}") && content != null) sb2.append(content).append('\n');
            if (!t.contains("{{footnotes}}") && !footnotes.isEmpty()) sb2.append(footnotes).append('\n');
            if (!t.contains("{{children}}") && !children.isEmpty()) sb2.append(children);
        } else {
            if (content != null) sb2.append(content).append('\n');
            sb2.append(children);
        }
        sb2.append("</div>\n");
        if (info != null && !info.global && !info.adds) pageTypes.add(type);
        return sb2.toString();
    }

    private void registerOutput(String out) {
        if (!sb.pageOutputs.add(out)) sb.errors.add("页面输出路径冲突: " + out);
    }

    private static void appendAttrs(StringBuilder sb2, JsonNode attrs) {
        if (attrs == null || !attrs.isObject()) return;
        Iterator<Map.Entry<String, JsonNode>> it = attrs.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> a = it.next();
            String k = a.getKey();
            if (k.equals("class") || k.equals("id")) continue;   // class 已合并，id 由 div-ID 专属
            sb2.append(' ').append(k).append("=\"").append(a.getValue().asText()).append('"');
        }
    }

    private static void appendDataAttrs(StringBuilder sb2, JsonNode params) {
        if (params == null || !params.isObject()) return;
        Iterator<Map.Entry<String, JsonNode>> it = params.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> p = it.next();
            sb2.append(" data-").append(p.getKey()).append("=\"").append(p.getValue().asText()).append('"');
        }
    }

    private String injectTemplate(String t, JsonNode params, String content, String children,
                                  String footnotes, String pageSrc, String type) {
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
        out = out.replace("{{footnotes}}", footnotes == null ? "" : footnotes);
        int p = out.indexOf("{{");
        if (p >= 0) {
            int q = out.indexOf("}}", p);
            String left = q >= 0 ? out.substring(p, q + 2) : out.substring(p, Math.min(out.length(), p + 30));
            sb.errors.add(pageSrc + " 的 div " + type + " 模板存在未声明占位符: " + left);
        }
        return out;
    }

    private MarkdownRenderer.MdResult resolveMd(String field, String pageSrc,
                                                Map<String, String> options, String anchorPrefix) {
        MarkdownRenderer.MdResult empty = new MarkdownRenderer.MdResult("", "");
        if (!field.startsWith("@")) {
            try { return MarkdownRenderer.renderParts(field, pageSrc, options, anchorPrefix); }
            catch (RuntimeException e) { sb.errors.add(e.getMessage()); return empty; }
        }
        String path = field.substring(1);
        if (!path.startsWith("pages/") && !path.startsWith("data/")) {
            sb.errors.add(pageSrc + " 的 md 引用路径不合法（须 @pages/ 或 @data/）: " + field);
            return empty;
        }
        File f = new File(sb.setsDir, path);
        if (!f.isFile() || !f.getName().endsWith(".md")) {
            sb.errors.add(pageSrc + " 的 md 文件不存在或非 md: " + field);
            return empty;
        }
        try { return MarkdownRenderer.renderParts(sb.readFile(f), path, options, anchorPrefix); }
        catch (RuntimeException e) { sb.errors.add(e.getMessage()); return empty; }
    }


    // ==================== search/shower 数据 ====================

    void emitSearchIndex() {
        if (!sb.searchNeeded) return;
        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(sb.pageIndex);
            sb.queue.add(new SiteBuilder.Queued(new File(sb.outputDir, "assets/data/search-index.json"), json, 2));
        } catch (Exception e) {
            sb.errors.add("搜索索引序列化失败: " + e.getMessage());
        }
    }

    /** 共享 shower 索引：只发射 shared:true 实际声明的目录，每个 dir 一份分片（assets/data/shower/<dir>.json，dir 空 → index.json）。
     *  去 text 全文的精简条目；pattern/order/count 仍由客户端按 data-* 过滤/排序/截断。 */
    void emitShowerIndexes() {
        if (sb.showerDirs.isEmpty()) return;
        for (String dir : sb.showerDirs.keySet()) {
            String pfx = dir.isEmpty() ? "" : dir + "/";
            List<Map<String, String>> entries = new ArrayList<>();
            for (Map<String, String> e : sb.pageIndex) {
                String link = e.get("link");
                if (!pfx.isEmpty() && !(link.equals(pfx) || link.startsWith(pfx))) continue;
                Map<String, String> m = new LinkedHashMap<>();
                m.put("link", link);
                m.put("type", e.getOrDefault("type", ""));
                m.put("title", e.getOrDefault("title", ""));
                m.put("date", e.getOrDefault("date", ""));
                m.put("excerpt", e.getOrDefault("excerpt", ""));
                m.put("tags", e.getOrDefault("tags", ""));
                entries.add(m);
            }
            String name = dir.isEmpty() ? "index.json" : dir + ".json";
            try {
                String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(entries);
                sb.queue.add(new SiteBuilder.Queued(new File(sb.outputDir, "assets/data/shower/" + name), json, 3));
            } catch (Exception e) {
                sb.errors.add("shower 共享索引序列化失败（dir=" + dir + "）: " + e.getMessage());
            }
        }
    }

    /** shower 数据：dir/pattern 过滤 pageIndex → order 排序（random 用 seed）→ count 截断 → 总文件 */
    private String showerJson(JsonNode params, String id) {
        String dir = params != null ? params.path("dir").asText("") : "";
        String pattern = params != null ? params.path("pattern").asText("*.md") : "*.md";
        int count = params != null ? params.path("count").asInt(5) : 5;
        String order = params != null ? params.path("order").asText("date") : "date";
        long seed = params != null ? params.path("seed").asLong(-1) : -1;
        String fields = params != null ? params.path("fields").asText("title,date,excerpt") : "title,date,excerpt";
        boolean manual = params != null && params.path("manual").asBoolean(false);
        List<Map<String, String>> entries = new ArrayList<>();
        for (Map<String, String> e : sb.pageIndex) {
            String link = e.get("link");
            String pfx = dir.isEmpty() ? "" : dir + "/";   // dir 形如 pages/blog（已含 pages/ 前缀）
            if (!pfx.isEmpty() && !(link.equals(pfx) || link.startsWith(pfx))) continue;
            if (pattern.equals("*.md") && !"md".equals(e.get("type"))) continue;
            if (pattern.equals("*.json") && !"json".equals(e.get("type"))) continue;
            entries.add(e);
        }
        int total = entries.size();
        switch (order) {
            case "name" -> entries.sort(Comparator.comparing(e -> e.get("title")));
            case "random" -> Collections.shuffle(entries, new java.util.Random(seed < 0 ? System.nanoTime() : seed));
            default -> entries.sort(Comparator.comparing(e -> e.get("date"), Comparator.reverseOrder()));
        }
        if (entries.size() > count) entries = new ArrayList<>(entries.subList(0, count));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", 1);
        data.put("id", id);
        data.put("total", total);
        data.put("manual", manual);
        data.put("params", Map.of("dir", dir, "pattern", pattern, "count", count, "order", order, "fields", fields));
        data.put("entries", entries);
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data);
        } catch (Exception e) {
            sb.errors.add("shower 数据序列化失败: " + e.getMessage());
            return "{}";
        }
    }
}
