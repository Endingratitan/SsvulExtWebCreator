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
import io.github.endingratitan.Integra.MarkdownIntergra.MarkdownRenderer;

import java.io.File;
import java.util.*;

/**
 * 页面组装（包内私有）：全局文件生成（web_global/.global 三态）→ json/裸md 页面构建 →
 * div 树渲染（模板注入/{{footnotes}}）→ md 解析委托。owner 为 SiteBuilder，tags 为标签生成器。
 */
class SitePages {

    private final SiteBuilder sb;
    private final SiteTags tags;
    private final PageHtml html;
    private final PageList list;
    private final PageScope page;      // 页内私有状态（0.4.0；全局方法用不到，为 null）

    /** 全局相实例（buildGlobals / emitSearchIndex / emitListShards 用）：没有"当前页" */
    SitePages(SiteBuilder sb) {
        this.sb = sb;
        this.html = new PageHtml(sb); this.list = new PageList(sb, null); this.tags = null;
        this.page = null;
    }

    /** 单页实例（并行渲染：**每页一个**，互不共享页内状态） */
    SitePages(SiteBuilder sb, SiteTags tags, PageScope page) {
        this.sb = sb;
        this.html = new PageHtml(sb); this.list = new PageList(sb, page); this.tags = tags;
        this.page = page;
    }

    /** 渲染一页（并行任务的入口）：裸 md 与 json 页在此分派 */
    void render(SiteScan.Page p) {
        if (p.bareMd) buildBareMd(p); else buildJsonPage(p);
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
        AssetsConfigReader acr = new AssetsConfigReader(p.file, sb.stats, sb.readFile(p.file));
        long tj = System.nanoTime();
        acr.Read();
        sb.stats.nsJson.add(System.nanoTime() - tj);
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
        page.depth = depth;

        page.mdOptions = new LinkedHashMap<>();
        page.pageGlobalRefs.clear();
        page.themeClasses.clear();
        page.mdCss = sb.mdCssDefault;                 // 站点级默认 → 页面 md.css → div md.css
        page.mdWrap = sb.mdWrapDefault;
        JsonNode mo = root.path("md");
        if (mo.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = mo.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String mk = e.getKey();
                if (mk.equals("css")) { page.mdCss = e.getValue().asText(""); continue; }
                if (mk.equals("wrap")) { page.mdWrap = e.getValue().asBoolean(true); continue; }
                page.mdOptions.put(mk, e.getValue().asText());
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
                else page.mdOptions.put("codeui.bg", bg);
            }
            if (cu.path("rounded").asBoolean(false)) page.mdOptions.put("codeui.rounded", "true");
            String lp = cu.path("label-pos").asText("");
            if (!lp.isEmpty()) page.mdOptions.put("codeui.label-pos", lp);
        }
        page.mdOptions.put("codeui.items", String.join(",", codeUiItems));   // 空 = 无外壳（字节兼容）

        // 页面级引擎链（缺省 = hljs；未知名警告剔除并回退 hljs）；本页 md 渲染全程使用
        List<String> engNames = new ArrayList<>();
        JsonNode eng = root.path("engine");
        if (eng.isTextual()) engNames.add(eng.asText());
        else if (eng.isArray()) for (JsonNode e : eng) if (e.isTextual()) engNames.add(e.asText());
        page.engineChain = EngineRegistry.resolve(engNames, sb::warn);
        page.engineAssets = List.of();

        Set<String> pageTypes = new LinkedHashSet<>();       // 需要 css 的 div 类型
        Set<String> jsTypes = new LinkedHashSet<>();         // 需要 js 的 div 类型（list 的构建期来源只要 css）
        boolean[] hasMd = {false};
        boolean[] hasCode = {false};
        boolean[] hasCallout = {false};
        page.hasCode = false;
        page.hasCallout = false;
        page.copyJsNeeded = false;
        page.injectCodeuiCss = false;
        page.injectCodeuiJs = false;
        page.listAssets.clear();
        page.mdCsrAssets.clear();
        page.mdCsrCss.clear();
        page.mdCsrNeeded = false;
        page.offlineListWarned = false;
        page.pageLink = p.index ? "" : "pages/" + p.rel + name + "/";
        String content = renderDivGroup("", root.path("page"), "页面 " + name, pageTypes, jsTypes, hasMd, hasCode, hasCallout, page.mdOptions);
        // 性能门控：本页确有块落到带客户端资源的成员（如 hljs）才注入引擎资源
        if (page.engineChain.clientAssetsNeeded()) page.engineAssets = page.engineChain.autoAssets();
        // CODEUI 注入门控：站点级文件存在 + 本页有代码块；copy-btn 项触发内置复制脚本
        page.hasCode = hasCode[0];
        page.injectCodeuiCss = sb.codeuiCssExists && hasCode[0];
        page.injectCodeuiJs = sb.codeuiJsExists && hasCode[0];
        page.copyJsNeeded = hasCode[0] && codeUiItems.contains("copy-btn");
        // callout 门控：本页出现 callout 才注入 md-callout.css + 站点级覆写（语言变体按本页 lang 命中，未命中回落 CALLOUT.css）
        page.hasCallout = hasCallout[0];
        page.resolveCalloutFiles(root.path("lang").asText("zh-CN"));

        // 页面级 js/css：origin 去重 + 项级去重 + runtime（聚合助手）；js 只收"需要 js"的类型
        List<SiteScan.DivInfo> cssDivs = new ArrayList<>();
        for (String type : pageTypes) {
            SiteScan.DivInfo info = sb.scan.divOf(type);
            if (info != null) cssDivs.add(info);
        }
        List<SiteScan.DivInfo> jsDivs = new ArrayList<>();
        for (String type : jsTypes) {
            SiteScan.DivInfo info = sb.scan.divOf(type);
            if (info != null) jsDivs.add(info);
        }
        String pageJsText = jsDivs.isEmpty() ? "" : sb.aggregateDivJs(jsDivs);
        String pageCssText = cssDivs.isEmpty() ? "" : sb.aggregateDivCss(cssDivs);
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
        if (hasPageJs) page.queueOwned(new File(sb.outputDir, pageFileJs), pageJsText, pageFileDepth);
        if (hasPageCss) page.queueOwned(new File(sb.outputDir, pageFileCss), pageCssText, pageFileDepth);

        boolean themeActive = !root.path("theme").asText("").isEmpty();
        String links = tags.buildLinks(root, hasMd[0], depth, page.themeClasses)
                + tags.pageCssTag(hasPageCss, p.index, name, depth);
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
        page.queueOwned(new File(sb.outputDir, outRel), base, depth);
    }

    void buildBareMd(SiteScan.Page p) {
        page.mdOptions = new LinkedHashMap<>();    // 裸 md 无 json，全用默认
        page.pageGlobalRefs.clear();
        page.themeClasses.clear();
        page.mdCss = sb.mdCssDefault;
        page.mdWrap = sb.mdWrapDefault;
        page.mdOptions.put("wrap", String.valueOf(page.mdWrap));
        // 裸页不注入引擎资源（性能优先：省流量）；渲染仍走默认 hljs 链（纯转义，与 v1 输出一致）
        page.engineChain = EngineRegistry.resolve(List.of("hljs"), sb::warn);
        page.engineAssets = List.of();
        page.hasCode = false;
        page.hasCallout = false;
        page.copyJsNeeded = false;
        page.injectCodeuiCss = false;
        page.injectCodeuiJs = false;
        page.offlineListWarned = false;
        String outRel = "pages/" + p.rel + p.name + "/index.html";
        int depth = SiteBuilder.depthOf("pages/" + p.rel + p.name);
        page.depth = depth;
        String content;
        try {
            long tm = System.nanoTime();
            MarkdownRenderer.MdResult mr = MarkdownRenderer.renderParts(sb.readFile(p.file),
                    "pages/" + p.rel + p.name + ".md", page.mdOptions);
            sb.stats.nsMd.add(System.nanoTime() - tm);
            sb.stats.mdCalls.incrementAndGet();
            content = MarkdownRenderer.joinResult(mr);
            page.hasCallout = mr.hasCallout();
            if (mr.hasCode()) {   // 裸页有代码块时同样注入站点级 CODEUI
                page.hasCode = true;
                page.injectCodeuiCss = sb.codeuiCssExists;
                page.injectCodeuiJs = sb.codeuiJsExists;
            }
        } catch (RuntimeException e) {
            sb.errors.add(e.getMessage());
            return;
        }
        String base = sb.readPreset("page/BASE.html");
        String themeCls = sb.refTheme(page.mdCss);       // 裸页没有 div 可挂类 → 挂到 <html>
        if (themeCls != null) page.themeClasses.add(themeCls);
        page.resolveCalloutFiles("zh-CN");   // 裸 md 页无 json，lang 与 BASE 模板一致取默认 zh-CN
        String links = (themeCls == null ? "" : "  <link rel=\"stylesheet\" href=\""
                    + SiteBuilder.depthPrefix(depth) + "assets/css/" + themeCls + ".css\">\n")
                + tags.calloutBaseLink(depth)
                + "{{WEBGLOBAL_CSS:" + depth + "}}"
                + tags.calloutOverrideLink(depth);
        String scripts = "{{WEBGLOBAL_JS:" + depth + "}}";
        base = base.replace("{{lang}}", "zh-CN")
                   .replace("{{htmlattrs}}", themeCls == null ? "" : " class=\"" + themeCls + "\"")
                   .replace("{{title}}", MarkdownRenderer.escapeHtml(p.name))
                   .replace("{{description}}", "")
                   .replace("{{favicon}}", "")
                   .replace("{{head}}", "")
                   .replace("{{links}}", links)
                   .replace("{{content}}", content)
                   .replace("{{scripts}}", scripts);
        tags.checkLeftover(base, "BASE.html", "页面 " + p.name);
        page.queueOwned(new File(sb.outputDir, outRel), base, depth);
    }

    // ---- div 渲染 ----

    private String renderDivGroup(String parentId, JsonNode group, String pageSrc,
                                  Set<String> pageTypes, Set<String> jsTypes, boolean[] hasMd, boolean[] hasCode, boolean[] hasCallout,
                                  Map<String, String> inheritedMd) {
        if (group == null || !group.isObject()) return "";
        List<Map.Entry<String, JsonNode>> list = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> it = group.fields();
        while (it.hasNext()) list.add(it.next());
        list.sort(Comparator.comparingInt(e -> Integer.parseInt(e.getKey().substring("div-".length()))));
        StringBuilder sb2 = new StringBuilder();
        for (Map.Entry<String, JsonNode> e : list)
            sb2.append(renderDiv(parentId, e.getKey(), e.getValue(), pageSrc, pageTypes, jsTypes, hasMd, hasCode, hasCallout, inheritedMd));
        return sb2.toString();
    }

    private String renderDiv(String parentId, String key, JsonNode div, String pageSrc,
                             Set<String> pageTypes, Set<String> jsTypes, boolean[] hasMd, boolean[] hasCode, boolean[] hasCallout,
                             Map<String, String> inheritedMd) {
        String id = parentId.isEmpty() ? key : parentId + "-" + key.substring("div-".length());
        String type = div.path("type").asText();
        SiteScan.DivInfo info = sb.scan.divOf(type);
        if (info == null) sb.errors.add(pageSrc + " 的 div 类型不存在: " + type + "（sets/divs 与 src/assets/divs 均未找到）");
        else sb.checkContract(info);

        // md 配置（div `md` > 页面 `md` > Environment.config > 内置默认）；主题类必须在包装标签输出前算好
        Map<String, String> mdOpts = inheritedMd;
        String divTheme = null;                      // null = 继承页面级
        Boolean divWrap = null;
        JsonNode mdNode = div.get("md");
        if (mdNode != null && mdNode.isObject()) {
            Map<String, String> over = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> mit = mdNode.fields();
            while (mit.hasNext()) {
                Map.Entry<String, JsonNode> me = mit.next();
                String mk = me.getKey();
                if (mk.equals("css")) divTheme = me.getValue().asText("");
                else if (mk.equals("wrap")) divWrap = me.getValue().asBoolean(true);
                else over.put(mk, me.getValue().asText());
            }
            if (!over.isEmpty()) { mdOpts = new LinkedHashMap<>(inheritedMd); mdOpts.putAll(over); }
        }
        String themeSpec = divTheme != null ? divTheme : page.mdCss;
        boolean mdWrapHere = divWrap != null ? divWrap : page.mdWrap;
        // 只有"真的会产出 md 结构"的 div 才登记主题：带 markdown 的 div，以及 md-csr（内容在客户端渲染）
        boolean rendersMd = div.has("markdown") || type.equals("md-csr") || type.endsWith("/md-csr");
        String themeCls = rendersMd ? sb.refTheme(themeSpec) : null;
        if (themeCls != null) page.themeClasses.add(themeCls);
        if (rendersMd) {
            mdOpts = new LinkedHashMap<>(mdOpts);
            mdOpts.put("wrap", String.valueOf(mdWrapHere));
        }

        JsonNode attrs = div.get("attrs");
        String extraCls = (attrs != null && attrs.isObject() && attrs.has("class")) ? attrs.get("class").asText() : "";
        StringBuilder sb2 = new StringBuilder();
        sb2.append("<div id=\"").append(id).append("\" class=\"ssvul-").append(type.replace('/', '-'));
        if (!extraCls.isEmpty()) sb2.append(' ').append(extraCls);
        if (themeCls != null) sb2.append(' ').append(themeCls);       // md 主题作用域类（一律挂，见 tech.md）
        sb2.append('"');
        html.appendAttrs(sb2, attrs);
        html.appendDataAttrs(sb2, div.get("params"));
        // 列目录型 list（ssvul:s3）与 md-csr：桶信息在**构建期**解析成 data-bk-*（客户端零配置）；非该来源返回 ""
        if (type.equals("list")) sb2.append(list.listBucketAttrs(div, pageSrc));
        else if (type.equals("md-csr")) sb2.append(mdCsrAttrs(div, pageSrc));
        sb2.append(" data-depth=\"").append(page.depth).append('"');
        if (info != null && !info.chainTypes.isEmpty())
            sb2.append(" data-family=\"").append(String.join(",", info.chainTypes)).append('"');
        if (type.equals("search")) { sb.searchNeeded = true; if (page != null) page.searchNeeded = true; }
        if (type.equals("palette-picker")) page.themePickerNeeded = true;
        sb2.append(">\n");
        // list：src 决定数据来源与渲染时机（默认构建期静态 HTML，零 JS）
        boolean[] listClient = {false};
        String listItems = null;
        if (type.equals("list")) listItems = list.listHandle(div, sb2, pageSrc, listClient);


        boolean hasTemplate = info != null && info.template != null;
        String content = null;
        String footnotes = "";
        if (div.has("raw")) {
            content = div.get("raw").asText();
        } else if (div.has("markdown")) {
            hasMd[0] = true;
            long t0 = System.nanoTime();
            MarkdownRenderer.MdResult mr = resolveMd(div.get("markdown").asText(), pageSrc, mdOpts, id + "-");
            sb.stats.nsMd.add(System.nanoTime() - t0);
            sb.stats.mdCalls.incrementAndGet();
            if (mr.hasCode()) hasCode[0] = true;
            if (mr.hasCallout()) hasCallout[0] = true;
            if (hasTemplate) {
                content = mr.mdBody();
                footnotes = mr.footnotes();   // 模板含 {{footnotes}} 时注入该处
            } else {
                content = MarkdownRenderer.joinResult(mr);   // 无模板：脚注区并入 md-body 末尾
            }
        }
        String children = renderDivGroup(id, div.get("divs"), pageSrc, pageTypes, jsTypes, hasMd, hasCode, hasCallout, mdOpts);

        if (hasTemplate) {
            String t = sb.readFile(info.template);
            boolean tplHasItems = t.contains("{{items}}");
            if (tplHasItems) t = t.replace("{{items}}", listItems == null ? "" : listItems);   // list 模板通道（客户端来源时先留空，由预设函数填充）
            sb2.append(html.injectTemplate(t, div.get("params"), content, children, footnotes, pageSrc, type)).append('\n');
            if (!t.contains("{{content}}") && content != null) sb2.append(content).append('\n');
            if (!t.contains("{{footnotes}}") && !footnotes.isEmpty()) sb2.append(footnotes).append('\n');
            if (!t.contains("{{children}}") && !children.isEmpty()) sb2.append(children);
            if (listItems != null && !tplHasItems) sb2.append(listItems).append('\n');   // 模板未写 {{items}} → 兜底追加
        } else {
            if (content != null) sb2.append(content).append('\n');
            if (listItems != null) sb2.append(listItems).append('\n');
            sb2.append(children);
        }
        sb2.append("</div>\n");
        if (info != null && !info.global && !info.adds) {
            pageTypes.add(type);
            boolean needsJs = !"list".equals(type) || listClient[0];   // list 的构建期来源只要 css（零 JS）
            if (needsJs) jsTypes.add(type);
        }
        return sb2.toString();
    }

    // ==================== list 组件（src 决定数据来源与渲染时机） ====================

    /** md-csr 客户端渲染库（对应 Integra/MarkdownIntergra 的 JS 镜像）+ hljs 三件套。
     *  走**预设资产路径**（assets/pre/div-libs/md-csr/…）：全站共享一份字节、路径稳定 → 可哈希、可长期缓存
     *  （⑧ 的"低频预设路径内嵌 6 位哈希 + immutable 一年"正是为这类资产准备的）。 */
    private static final List<String> MD_CSR_LIB = List.of(
            "div-libs/md-csr/md-renderer.js", "div-libs/md-csr/md-callouts.js", "div-libs/md-csr/md-scan.js", "div-libs/md-csr/md-tables.js",
            "div-libs/md-csr/md-blocks.js", "div-libs/md-csr/md-inline.js", "div-libs/md-csr/md-code.js", "div-libs/md-csr/md-quotes.js",
            "div-libs/md-csr/md-footnotes.js", "div-libs/md-csr/md-lists.js");

    /** md-csr 的构建期解析：① 登记本页要注入的库资产（含 hljs，代码块上色刚需）；② 若 params.bucket 指定了桶，
     *  注入 data-bk-href（取已知路径的 md 正文只需公开读基址，不需要列目录 endpoint）。 */
    private String mdCsrAttrs(JsonNode div, String pageSrc) {
        JsonNode params = div.get("params");
        list.checkNoReservedBk(params, pageSrc);
        page.mdCsrNeeded = true;      // 页面含 md-csr → md.css / md-callout.css 按需注入（内容运行时才知）
        // div 声明了 codeui.* → 与构建期 hasCode 同等对待：注入站点 CODEUI.css/js 与复制按钮脚本
        if (params != null && params.isObject()) {
            Iterator<String> ks = params.fieldNames();
            boolean codeui = false;
            while (ks.hasNext()) if (ks.next().startsWith("codeui.")) codeui = true;
            if (codeui) {
                if (params.path("codeui.items").asText("").contains("copy-btn")) page.copyJsNeeded = true;
                page.injectCodeuiCss = sb.codeuiCssExists;
                page.injectCodeuiJs = sb.codeuiJsExists;
            }
        }
        if (page.mdCsrAssets.isEmpty()) {
            // autoAssets() 给的是 pre-assets/ 前缀形态；mdCsrAssets 存的是预设根相对路径（与 listAssets 同规格）
            for (String a : new PassThroughCodeEngine().autoAssets()) {
                page.mdCsrAssets.add(a.startsWith("pre-assets/") ? a.substring("pre-assets/".length()) : a);
            }
            page.mdCsrAssets.addAll(MD_CSR_LIB);
        }
        // math=on：KaTeX 是重资产（css + 字体 + js，约 1MB），内容构建期不可知 → 由作者显式声明后再注入
        if (params != null && "on".equalsIgnoreCase(params.path("math").asText(""))) {
            if (!page.mdCsrCss.contains("lib/katex/katex.min.css")) page.mdCsrCss.add("lib/katex/katex.min.css");
            if (!page.mdCsrAssets.contains("lib/katex/katex.min.js")) page.mdCsrAssets.add("lib/katex/katex.min.js");
            if (!page.mdCsrAssets.contains("md/js/md-math.js")) page.mdCsrAssets.add("md/js/md-math.js");
        }
        if (params == null || !params.isObject() || !params.has("bucket")) return "";
        String name = params.path("bucket").asText("");
        String known = sb.buckets.isEmpty() ? "（当前没有 bucket）" : String.join(" / ", sb.buckets.keySet());
        if (!sb.buckets.containsKey(name)) {
            sb.errors.add(pageSrc + " 的 md-csr params.bucket 不存在: " + name + "（可用: " + known + "）");
            return "";
        }
        return " data-bk-name=\"" + name + "\" data-bk-href=\"" + sb.buckets.get(name) + "\"";
    }

    /** `bk-` 是构建期注入的保留前缀：作者手写会在 HTML 里产生重复属性（浏览器只认第一个）→ 直接报错 */

    private void registerOutput(String out) {
        if (!sb.pageOutputs.add(out)) sb.errors.add("页面输出路径冲突: " + out);
    }

    private MarkdownRenderer.MdResult resolveMd(String field, String pageSrc,
                                                Map<String, String> options, String anchorPrefix) {
        MarkdownRenderer.MdResult empty = new MarkdownRenderer.MdResult("", "");
        if (!field.startsWith("@")) {
            try { return MarkdownRenderer.renderParts(field, pageSrc, options, anchorPrefix, page.engineChain); }
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
        try { return MarkdownRenderer.renderParts(sb.readFile(f), path, options, anchorPrefix, page.engineChain); }
        catch (RuntimeException e) { sb.errors.add(e.getMessage()); return empty; }
    }

    /** 全局相：发射 list 分片（实现在 PageList；这里只转发，保持 run() 的调用点不变） */
    void emitListShards() { list.emitListShards(); }

    // ==================== search / list 数据 ====================

    void emitSearchIndex() {
        if (!sb.searchNeeded) return;
        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(sb.pageIndex);
            sb.queue.add(new SiteBuilder.Queued(new File(sb.outputDir, "assets/data/search-index.json"), json, 2));
        } catch (Exception e) {
            sb.errors.add("搜索索引序列化失败: " + e.getMessage());
        }
    }

}
