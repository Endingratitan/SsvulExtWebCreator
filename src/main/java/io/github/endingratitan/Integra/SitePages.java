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

        Set<String> pageTypes = new LinkedHashSet<>();       // 需要 css 的 div 类型
        Set<String> jsTypes = new LinkedHashSet<>();         // 需要 js 的 div 类型（list 的构建期来源只要 css）
        boolean[] hasMd = {false};
        boolean[] hasCode = {false};
        boolean[] hasCallout = {false};
        sb.hasCode = false;
        sb.hasCallout = false;
        sb.copyJsNeeded = false;
        sb.injectCodeuiCss = false;
        sb.injectCodeuiJs = false;
        sb.listAssets.clear();
        sb.mdCsrAssets.clear();
        sb.mdCsrCss.clear();
        sb.mdCsrNeeded = false;
        sb.offlineListWarned = false;
        sb.currentPageLink = p.index ? "" : "pages/" + p.rel + name + "/";
        String content = renderDivGroup("", root.path("page"), "页面 " + name, pageTypes, jsTypes, hasMd, hasCode, hasCallout, sb.mdOptions);
        // 性能门控：本页确有块落到带客户端资源的成员（如 hljs）才注入引擎资源
        if (sb.engineChain.clientAssetsNeeded()) sb.engineAssets = sb.engineChain.autoAssets();
        // CODEUI 注入门控：站点级文件存在 + 本页有代码块；copy-btn 项触发内置复制脚本
        sb.hasCode = hasCode[0];
        sb.injectCodeuiCss = sb.codeuiCssExists && hasCode[0];
        sb.injectCodeuiJs = sb.codeuiJsExists && hasCode[0];
        sb.copyJsNeeded = hasCode[0] && codeUiItems.contains("copy-btn");
        // callout 门控：本页出现 callout 才注入 md-callout.css + 站点级覆写（语言变体按本页 lang 命中，未命中回落 CALLOUT.css）
        sb.hasCallout = hasCallout[0];
        sb.resolveCalloutFiles(root.path("lang").asText("zh-CN"));

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
        sb.hasCallout = false;
        sb.copyJsNeeded = false;
        sb.injectCodeuiCss = false;
        sb.injectCodeuiJs = false;
        sb.offlineListWarned = false;
        String outRel = "pages/" + p.rel + p.name + "/index.html";
        int depth = SiteBuilder.depthOf("pages/" + p.rel + p.name);
        sb.currentPageDepth = depth;
        String content;
        try {
            MarkdownRenderer.MdResult mr = MarkdownRenderer.renderParts(sb.readFile(p.file),
                    "pages/" + p.rel + p.name + ".md", Collections.emptyMap());
            content = MarkdownRenderer.joinResult(mr);
            sb.hasCallout = mr.hasCallout();
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
        sb.resolveCalloutFiles("zh-CN");   // 裸 md 页无 json，lang 与 BASE 模板一致取默认 zh-CN
        String links = "  <link rel=\"stylesheet\" href=\"" + SiteBuilder.depthPrefix(depth) + "assets/pre/md/css/md.css\">\n"
                + tags.calloutBaseLink(depth)
                + "{{WEBGLOBAL_CSS:" + depth + "}}"
                + tags.calloutOverrideLink(depth);
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

        JsonNode attrs = div.get("attrs");
        String extraCls = (attrs != null && attrs.isObject() && attrs.has("class")) ? attrs.get("class").asText() : "";
        StringBuilder sb2 = new StringBuilder();
        sb2.append("<div id=\"").append(id).append("\" class=\"ssvul-").append(type.replace('/', '-'));
        if (!extraCls.isEmpty()) sb2.append(' ').append(extraCls);
        sb2.append('"');
        appendAttrs(sb2, attrs);
        appendDataAttrs(sb2, div.get("params"));
        // 列目录型 list（ssvul:s3）与 md-csr：桶信息在**构建期**解析成 data-bk-*（客户端零配置）；非该来源返回 ""
        if (type.equals("list")) sb2.append(listBucketAttrs(div, pageSrc));
        else if (type.equals("md-csr")) sb2.append(mdCsrAttrs(div, pageSrc));
        sb2.append(" data-depth=\"").append(sb.currentPageDepth).append('"');
        if (info != null && !info.chainTypes.isEmpty())
            sb2.append(" data-family=\"").append(String.join(",", info.chainTypes)).append('"');
        if (type.equals("search")) sb.searchNeeded = true;
        if (type.equals("palette-picker")) sb.themePickerNeeded = true;
        sb2.append(">\n");
        // list：src 决定数据来源与渲染时机（默认构建期静态 HTML，零 JS）
        boolean[] listClient = {false};
        String listItems = null;
        if (type.equals("list")) listItems = listHandle(div, sb2, pageSrc, listClient);

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
            sb2.append(injectTemplate(t, div.get("params"), content, children, footnotes, pageSrc, type)).append('\n');
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
        checkNoReservedBk(params, pageSrc);
        sb.mdCsrNeeded = true;      // 页面含 md-csr → md.css / md-callout.css 按需注入（内容运行时才知）
        // div 声明了 codeui.* → 与构建期 hasCode 同等对待：注入站点 CODEUI.css/js 与复制按钮脚本
        if (params != null && params.isObject()) {
            Iterator<String> ks = params.fieldNames();
            boolean codeui = false;
            while (ks.hasNext()) if (ks.next().startsWith("codeui.")) codeui = true;
            if (codeui) {
                if (params.path("codeui.items").asText("").contains("copy-btn")) sb.copyJsNeeded = true;
                sb.injectCodeuiCss = sb.codeuiCssExists;
                sb.injectCodeuiJs = sb.codeuiJsExists;
            }
        }
        if (sb.mdCsrAssets.isEmpty()) {
            // autoAssets() 给的是 pre-assets/ 前缀形态；mdCsrAssets 存的是预设根相对路径（与 listAssets 同规格）
            for (String a : new PassThroughCodeEngine().autoAssets()) {
                sb.mdCsrAssets.add(a.startsWith("pre-assets/") ? a.substring("pre-assets/".length()) : a);
            }
            sb.mdCsrAssets.addAll(MD_CSR_LIB);
        }
        // math=on：KaTeX 是重资产（css + 字体 + js，约 1MB），内容构建期不可知 → 由作者显式声明后再注入
        if (params != null && "on".equalsIgnoreCase(params.path("math").asText(""))) {
            if (!sb.mdCsrCss.contains("lib/katex/katex.min.css")) sb.mdCsrCss.add("lib/katex/katex.min.css");
            if (!sb.mdCsrAssets.contains("lib/katex/katex.min.js")) sb.mdCsrAssets.add("lib/katex/katex.min.js");
            if (!sb.mdCsrAssets.contains("md/js/md-math.js")) sb.mdCsrAssets.add("md/js/md-math.js");
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
    private void checkNoReservedBk(JsonNode params, String pageSrc) {
        if (params == null || !params.isObject()) return;
        Iterator<String> names = params.fieldNames();
        while (names.hasNext()) {
            String n = names.next();
            if (n.startsWith("bk-")) {
                sb.errors.add(pageSrc + " 的 params." + n + " 是构建期注入的保留键（bk-*），请勿手写");
            }
        }
    }

    /** 列目录型 list（`ssvul:s3`）的**构建期**解析：选 bucket → 校验 → 注入 data-bk-*（name/endpoint/href/prefix/ttl）。
     *  客户端只读属性、零配置。非列目录来源返回 ""。 */
    private String listBucketAttrs(JsonNode div, String pageSrc) {
        JsonNode params = div.get("params");
        checkNoReservedBk(params, pageSrc);
        String src = params != null ? params.path("src").asText("") : "";
        if (!src.equals("ssvul:s3")) return "";
        String known = sb.buckets.isEmpty() ? "（当前没有 bucket）" : String.join(" / ", sb.buckets.keySet());
        // 选桶：params.bucket → 否则站点唯一 bucket → 否则报错
        String name = params.path("bucket").asText("");
        if (name.isEmpty()) {
            if (sb.buckets.size() == 1) {
                name = sb.buckets.keySet().iterator().next();
            } else {
                sb.errors.add(pageSrc + " 的 list src=ssvul:s3 需要 params.bucket 指定用哪个 bucket（可用: " + known + "）");
                return "";
            }
        } else if (!sb.buckets.containsKey(name)) {
            sb.errors.add(pageSrc + " 的 list params.bucket 不存在: " + name + "（可用: " + known + "）");
            return "";
        }
        Map<String, String> attrs = sb.bucketAttrs.getOrDefault(name, Map.of());
        String endpoint = attrs.getOrDefault("endpoint", "");
        if (endpoint.isEmpty()) {
            sb.errors.add(pageSrc + " 的 list src=ssvul:s3 需要给 bucket " + name + " 声明 endpoint，"
                    + "例如 Environment.config 里写: bucket=[" + name + "," + sb.buckets.get(name)
                    + ",endpoint=https://s3.us-east-1.amazonaws.com/my-bucket,prefix=site/blog]");
            return "";
        }
        String dir = params.path("dir").asText("");
        boolean badDir = !dir.isEmpty() && (!dir.matches("[a-z0-9/_-]*") || dir.contains("..") || dir.startsWith("/"));
        if (badDir) {
            sb.errors.add(pageSrc + " 的 list ssvul:s3 dir 不合法（小写字母数字/_-，禁止 .. 与前导 /）: " + dir);
        }
        // 教学式校验：一次把该列表的参数问题都报出来（不因 dir 出错就吞掉 max/cache 的错误）
        checkRange(params, "max", 1, 1000, pageSrc);
        checkRange(params, "pages", 1, 20, pageSrc);
        // TTL：params.cache（本次）＞ session-ttl（站点）＞ 内置 86400，构建期折叠成一个数字给客户端
        int ttl = sb.sessionTtl;
        String cache = params.path("cache").asText("");
        if (!cache.isEmpty()) {
            try {
                ttl = Integer.parseInt(cache);
                if (ttl < 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                sb.errors.add(pageSrc + " 的 list params.cache 须为非负整数（秒；0=不缓存）: " + cache);
                ttl = sb.sessionTtl;
            }
        }
        if (sb.offline && !sb.offlineListWarned) {
            sb.offlineListWarned = true;      // 每页只发一次（同页多个列目录列表不重复刷屏）
            sb.warn(pageSrc + " 的 list src=ssvul:s3 运行时需要联网（offline=1 的站点无法离线使用该列表）；"
                    + "需离线请改用 build:page-index / ssvul:json");
        }
        return " data-bk-name=\"" + name + "\" data-bk-endpoint=\"" + endpoint + "\" data-bk-href=\"" + sb.buckets.get(name)
                + "\" data-bk-prefix=\"" + joinPrefix(attrs.getOrDefault("prefix", ""), badDir ? "" : dir)
                + "\" data-bk-ttl=\"" + ttl + "\"";
    }

    /** 可选数值参数的构建期范围校验（缺省不校验，客户端预设自带默认值） */
    private void checkRange(JsonNode params, String key, int min, int max, String pageSrc) {
        String v = params.path(key).asText("");
        if (v.isEmpty()) return;
        try {
            int n = Integer.parseInt(v);
            if (n < min || n > max) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            sb.errors.add(pageSrc + " 的 list params." + key + " 须为 " + min + ".." + max + " 的整数: " + v);
        }
    }

    /** 存储侧根前缀 + 页面 dir → 生效前缀（空段自动跳过，不出双斜线） */
    private static String joinPrefix(String base, String dir) {
        String b = base == null ? "" : base, d = dir == null ? "" : dir;
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        while (d.startsWith("/")) d = d.substring(1);
        if (b.isEmpty()) return d;
        return d.isEmpty() ? b : b + "/" + d;
    }

    /** 处理 list div。返回构建期渲染的条目 HTML（build:* 来源），客户端来源返回 null。
     *  src：build:page-index（默认，构建期静态 HTML、零 JS）| ssvul:inline | ssvul:shared | ssvul:json | ssvul:s3 | 作者函数名（裸名）。 */
    private String listHandle(JsonNode div, StringBuilder out, String pageSrc, boolean[] clientSide) {
        JsonNode params = div.get("params");
        String src = params != null ? params.path("src").asText("") : "";
        String raw = src.isEmpty() ? "build:page-index" : src;              // 不写 src → 构建期静态列表
        String fields = params != null ? params.path("fields").asText("date,title,excerpt") : "date,title,excerpt";
        String dir = params != null ? params.path("dir").asText("") : "";
        String pattern = params != null ? params.path("pattern").asText("*") : "*";
        if (raw.startsWith("build:")) {
            String preset = raw.substring("build:".length());
            if (!preset.equals("page-index")) {
                sb.errors.add(pageSrc + " 的 list src 未知的构建期预设: " + raw + "（当前可用: build:page-index）");
                return null;
            }
            return listItemsHtml(listEntries(dir, pattern, true), fields,
                    params != null ? params.path("empty").asText("") : "");
        }
        if (raw.startsWith("ssvul:")) {
            String preset = raw.substring("ssvul:".length());
            if (preset.equals("inline")) {
                // 构建期把条目内联进页面（零请求、file:// 可用、离线可用）；</script> 防注入转义
                String json = listJson(listEntries(dir, pattern, true)).replace("<", "\\u003c");
                out.append("<script type=\"application/json\" class=\"list-data\">").append(json).append("</script>\n");
            } else if (preset.equals("shared")) {
                if (!dir.matches("[a-z0-9/_-]*") || dir.contains("..") || dir.startsWith("/")) {
                    sb.errors.add(pageSrc + " 的 list ssvul:shared dir 不合法（小写字母数字/_-，禁止 .. 与前导 /）: " + dir);
                } else {
                    sb.listDirs.put(dir, true);
                }
            } else if (preset.equals("json")) {
                // 纯 CSR：数据由**作者维护的 JSON 文件**提供；构建期不生成任何数据，只注入预设 js
                // （构建跑一次之后，只上传/覆盖那个 JSON 即可更新列表——"不重建更新"）
                String file = params != null ? params.path("file").asText("") : "";
                if (file.isBlank()) {
                    sb.errors.add(pageSrc + " 的 list ssvul:json 需要在 params 里给 file（JSON 文件路径）");
                } else if (file.contains("..")) {
                    sb.errors.add(pageSrc + " 的 list ssvul:json file 不允许包含 ..: " + file);
                }
            } else if (preset.equals("s3")) {
                // 纯 CSR 列目录：构建期不生成任何数据，只把桶信息注入 data-bk-*（见 listBucketAttrs）
                // —— 站点构建一次之后，往桶里加/删/改文件即刷新可见，无需重建
            } else {
                sb.errors.add(pageSrc + " 的 list src 未知的官方预设: " + raw
                        + "（当前可用: ssvul:inline / ssvul:shared / ssvul:json / ssvul:s3）");
                return null;
            }
            clientSide[0] = true;
            String asset = "div-libs/list/" + preset + ".js";
            if (!sb.listAssets.contains(asset)) sb.listAssets.add(asset);
            return null;
        }
        clientSide[0] = true;   // 作者自己的函数：构建期只写 data-src，js 由作者引入
        return null;
    }

    /** list 条目：dir/pattern 过滤 → 日期倒序（同日按标题码点序，无日期最后）→ 可选排除列表页自身。
     *  过滤/排序只有这一份实现（客户端不再排序，故两侧算法无需对齐）。 */
    private List<Map<String, String>> listEntries(String dir, String pattern, boolean excludeSelf) {
        String pfx = dir == null || dir.isEmpty() ? "" : dir + "/";
        List<Map<String, String>> entries = new ArrayList<>();
        for (Map<String, String> e : sb.pageIndex) {
            String link = e.getOrDefault("link", "");
            if (link.isEmpty()) continue;                                    // INDEX（站点根）
            if (!pfx.isEmpty() && !link.startsWith(pfx)) continue;
            String t = e.getOrDefault("type", "");
            if ("*.md".equals(pattern) && !t.equals("md")) continue;
            if ("*.json".equals(pattern) && !t.equals("json")) continue;
            entries.add(e);
        }
        entries.sort(Comparator
                .comparing((Map<String, String> e) -> e.getOrDefault("date", ""), Comparator.reverseOrder())
                .thenComparing(e -> e.getOrDefault("title", "")));
        if (excludeSelf && sb.currentPageLink != null && !sb.currentPageLink.isEmpty())
            entries.removeIf(e -> sb.currentPageLink.equals(e.getOrDefault("link", "")));
        return entries;
    }

    /** 条目 HTML：**构建期渲染与客户端预设必须产出同一套标记**（不变量；客户端对应 SsvulList.item）。
     *  emptyText 是"内置对接函数"自己的约定参数（0 条时显示），不是 list 组件的参数。 */
    private String listItemsHtml(List<Map<String, String>> entries, String fields, String emptyText) {
        String on = "," + (fields == null || fields.isBlank() ? "date,title,excerpt" : fields) + ",";
        StringBuilder out = new StringBuilder();
        if (entries.isEmpty() && emptyText != null && !emptyText.isBlank()) {
            return "<li class=\"list-empty\">" + MarkdownRenderer.escapeHtml(emptyText) + "</li>\n";
        }
        for (Map<String, String> e : entries) {
            out.append("<li class=\"list-item\">\n");
            String date = e.getOrDefault("date", "");
            if (on.contains(",date,") && !date.isEmpty())
                out.append("<span class=\"list-date\">").append(MarkdownRenderer.escapeHtml(date)).append("</span>\n");
            if (on.contains(",title,")) {
                String title = e.getOrDefault("title", "");
                if (title.isEmpty()) title = e.getOrDefault("link", "");
                out.append("<a class=\"list-title\" href=\"@page/").append(pageRef(e.getOrDefault("link", ""))).append("\">")
                   .append(MarkdownRenderer.escapeHtml(title)).append("</a>\n");
            }
            String tags = e.getOrDefault("tags", "");
            if (on.contains(",tags,") && !tags.isEmpty()) {
                out.append("<span class=\"list-tags\">");
                for (String t : tags.split(",")) if (!t.isBlank())
                    out.append("<span class=\"list-tag\">").append(MarkdownRenderer.escapeHtml(t.trim())).append("</span>");
                out.append("</span>\n");
            }
            String ex = e.getOrDefault("excerpt", "");
            if (on.contains(",excerpt,") && !ex.isEmpty())
                out.append("<p class=\"list-excerpt\">").append(MarkdownRenderer.escapeHtml(ex)).append("</p>\n");
            out.append("</li>\n");
        }
        return out.toString();
    }

    /** 索引 link（pages/blog/post/）→ @page 引用（blog/post）：替换趟补深度前缀并校验目标存在 */
    private static String pageRef(String link) {
        String s = link;
        if (s.startsWith("pages/")) s = s.substring("pages/".length());
        if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** 条目 JSON（内联与分片同形）：只带展示需要的字段，**不含 text 全文** */
    private String listJson(List<Map<String, String>> entries) {
        List<Map<String, String>> out = new ArrayList<>();
        for (Map<String, String> e : entries) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("link", e.getOrDefault("link", ""));
            m.put("type", e.getOrDefault("type", ""));
            m.put("title", e.getOrDefault("title", ""));
            m.put("date", e.getOrDefault("date", ""));
            m.put("excerpt", e.getOrDefault("excerpt", ""));
            m.put("tags", e.getOrDefault("tags", ""));
            out.add(m);
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(out);
        } catch (Exception e) {
            sb.errors.add("list 数据序列化失败: " + e.getMessage());
            return "[]";
        }
    }

    /** ssvul:shared 分片：每个声明的 dir 一份（assets/data/list/<dir>.json，dir 空 → index.json）。
     *  条目已按约定排好序（客户端不再排序）；不含 text 全文。 */
    void emitListShards() {
        if (sb.listDirs.isEmpty()) return;
        for (String dir : sb.listDirs.keySet()) {
            String name = dir.isEmpty() ? "index.json" : dir + ".json";
            String json = listJson(listEntries(dir, "*", false));
            sb.queue.add(new SiteBuilder.Queued(new File(sb.outputDir, "assets/data/list/" + name), json, 3));
        }
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
