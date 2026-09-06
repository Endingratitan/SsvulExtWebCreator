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
import java.util.*;

/**
 * 页面标签生成（包内私有）：links/scripts/favicon/head/theme 与占位符校验。
 * owner 为 SiteBuilder（共享配置与错误）。
 */
class SiteTags {

    private final SiteBuilder sb;

    SiteTags(SiteBuilder sb) { this.sb = sb; }

    String buildLinks(JsonNode root, boolean hasMd, int depth) {
        StringBuilder sb2 = new StringBuilder();
        Set<String> seen = new LinkedHashSet<>();
        JsonNode deps = root.path("deps");
        if (deps.isArray()) for (JsonNode d : deps) {
            String s = d.asText();
            if (!seen.add(s)) { sb.warn("deps 重复条目已去重: " + s); continue; }
            sb2.append(depTag(s, depth, true));
        }
        if (hasMd) {
            String mc = root.path("md-css").asText("");
            if (mc.isEmpty()) mc = "pre-assets/md/css/md.css";
            sb2.append(depTag(mc, depth, true));
        }
        // theme 键自动带出对应主题 css（md/css/md-code-<theme>.css，不存在则跳过，作者可经 deps 自行引入）
        String theme = root.path("theme").asText("");
        if (!theme.isEmpty()) {
            String tcss = "pre-assets/md/css/md-code-" + theme + ".css";
            if (new File(sb.presetDir, "md/css/md-code-" + theme + ".css").isFile()) {
                sb2.append(depTag(tcss, depth, true));
            }
        }
        if (sb.webGlobalCss) sb2.append("  <link rel=\"stylesheet\" href=\"").append(SiteBuilder.depthPrefix(depth)).append("assets/css/web_global.css\">\n");
        // 站点级 CODEUI.css：存在 + 本页有代码块才注入（顺序在主题 css 之后，便于覆盖）
        if (sb.injectCodeuiCss) {
            sb2.append("  <link rel=\"stylesheet\" href=\"").append(SiteBuilder.depthPrefix(depth))
               .append("assets/global/codeui/CODEUI.css\">\n");
        }
        return sb2.toString();
    }

    String buildScripts(JsonNode root, int depth, boolean hasPageJs, boolean themeActive,
                        boolean indexPage, String name) {
        StringBuilder sb2 = new StringBuilder();
        if (sb.webGlobalJs) sb2.append("  <script src=\"").append(SiteBuilder.depthPrefix(depth)).append("assets/js/web_global").append(sb.jsSuffix()).append("\"></script>\n");
        JsonNode deps = root.path("deps");
        if (deps.isArray()) {
            Set<String> seenDeps = new LinkedHashSet<>();
            for (JsonNode d : deps) {
                String s = d.asText();
                if (!seenDeps.add(s)) { sb.warn("deps 重复条目已去重: " + s); continue; }
                sb2.append(depTag(s, depth, false));
            }
        }
        List<String> mdJs = new ArrayList<>();
        JsonNode mj = root.path("md-js");
        if (mj.isArray()) for (JsonNode d : mj) {
            String s = d.asText();
            if (mdJs.contains(s)) { sb.warn("md-js 重复条目已去重: " + s); continue; }
            mdJs.add(s);
        }
        if (themeActive && !mdJs.contains("pre-assets/md/js/md-theme.js")) mdJs.add("pre-assets/md/js/md-theme.js");
        for (String s : mdJs) {
            if (s.startsWith("pre-assets/")) {
                String suffix = s.substring("pre-assets/".length());
                sb.refPreset(suffix);
                sb2.append("  <script src=\"").append(SiteBuilder.depthPrefix(depth)).append("assets/pre/")
                  .append(suffix).append("\"></script>\n");
            } else if (s.startsWith("http://") || s.startsWith("https://")) {
                sb2.append("  <script src=\"").append(s).append("\"></script>\n");
            } else {
                sb.errors.add("md-js 条目形态不合法: " + s);
            }
        }
        // 引擎自动资源（hljs 三件套等）：性能门控后注入；与用户 md-js 重复 → 警告并跳过
        for (String a : sb.engineAssets) {
            if (mdJs.contains(a)) {
                sb.warn("md-js 与 engine 自动资源重复，已跳过手写项: " + a);
                continue;
            }
            if (a.startsWith("pre-assets/")) {
                String suffix = a.substring("pre-assets/".length());
                sb.refPreset(suffix);
                sb2.append("  <script src=\"").append(SiteBuilder.depthPrefix(depth)).append("assets/pre/")
                  .append(suffix).append("\"></script>\n");
            } else {
                sb.errors.add("engine 自动资源形态不合法: " + a);
            }
        }
        // copy-btn 内置行为预设（仅 code-ui items 含 copy-btn 且本页有代码块）
        if (sb.copyJsNeeded) {
            sb.refPreset("md/js/md-copy.js");
            sb2.append("  <script src=\"").append(SiteBuilder.depthPrefix(depth)).append("assets/pre/md/js/md-copy.js\"></script>\n");
        }
        // 站点级 CODEUI.js：存在 + 本页有代码块；最后注入（引擎/内置脚本先执行，用户可覆盖与挂事件）
        if (sb.injectCodeuiJs) {
            sb2.append("  <script src=\"").append(SiteBuilder.depthPrefix(depth))
               .append("assets/global/codeui/CODEUI.js\"></script>\n");
        }
        if (hasPageJs) {
            String src = indexPage ? SiteBuilder.depthPrefix(depth) + "assets/index/index" + sb.jsSuffix() : name + sb.jsSuffix();
            sb2.append("  <script src=\"").append(src).append("\"></script>\n");
        }
        return sb2.toString();
    }

    String pageCssTag(boolean hasPageCss, boolean indexPage, String name, int depth) {
        if (!hasPageCss) return "";
        String href = indexPage ? SiteBuilder.depthPrefix(depth) + "assets/index/index.css" : name + ".css";
        return "  <link rel=\"stylesheet\" href=\"" + href + "\">\n";
    }

    private String depTag(String s, int depth, boolean cssPass) {
        if (s.startsWith("global:")) {
            String type = s.substring("global:".length());
            SiteScan.DivInfo info = sb.divs.get(type);
            if (info == null || !info.global) {
                sb.errors.add("global: 引用的不是 .global div（或不存在）: " + type);
                return "";
            }
            String flat = type.replace('/', '-');
            if (cssPass) {
                return info.css.isEmpty() ? "" : "  <link rel=\"stylesheet\" href=\"" + SiteBuilder.depthPrefix(depth) + "assets/css/" + flat + ".css\">\n";
            }
            return info.js.isEmpty() ? "" : "  <script src=\"" + SiteBuilder.depthPrefix(depth) + "assets/js/" + flat + sb.jsSuffix() + "\"></script>\n";
        }
        String url;
        if (s.startsWith("pre-assets/")) {
            String suffix = s.substring("pre-assets/".length());
            sb.refPreset(suffix);
            url = SiteBuilder.depthPrefix(depth) + "assets/pre/" + suffix;
        }
        else if (s.startsWith("http://") || s.startsWith("https://")) url = s;
        else { sb.errors.add("deps 条目形态不合法: " + s); return ""; }
        boolean isCss = s.endsWith(".css");
        boolean isJs = s.endsWith(".js");
        if (!isCss && !isJs) { sb.errors.add("deps 仅支持 .js/.css 文件: " + s); return ""; }
        if (isCss != cssPass) return "";
        return cssPass
                ? "  <link rel=\"stylesheet\" href=\"" + url + "\">\n"
                : "  <script src=\"" + url + "\"></script>\n";
    }

    String buildFavicon(JsonNode root, int depth) {
        String f = root.path("favicon").asText("");
        if (f.isEmpty()) return "";
        if (f.startsWith("http://") || f.startsWith("https://")) return "  <link rel=\"icon\" href=\"" + f + "\">\n";
        if (f.startsWith("pre-assets/")) {
            String suffix = f.substring("pre-assets/".length());
            sb.refPreset(suffix);
            return "  <link rel=\"icon\" href=\"" + SiteBuilder.depthPrefix(depth) + "assets/pre/" + suffix + "\">\n";
        }
        if (f.startsWith("@favicon/")) {
            if (sb.localFavicon) { sb.errors.add("local-favicon=1（云端模式）下 favicon 键不能使用 @favicon/ 形态"); return ""; }
            String name = f.substring("@favicon/".length());
            if (!new File(sb.setsDir, "favicon/" + name).isFile()) { sb.errors.add("favicon 文件不存在: sets/favicon/" + name); return ""; }
            return "  <link rel=\"icon\" href=\"" + SiteBuilder.depthPrefix(depth) + "favicon/" + name + "\">\n";
        }
        for (String b : sb.buckets.keySet()) {
            if (f.startsWith(b + "/"))
                return "  <link rel=\"icon\" href=\"" + sb.buckets.get(b) + "/" + f.substring(b.length() + 1) + "\">\n";
        }
        sb.errors.add("favicon 值形态不合法（允许 http(s)://、pre-assets/、@favicon/、bucket 调用名）: " + f);
        return "";
    }

    String buildHead(JsonNode root) {
        StringBuilder sb2 = new StringBuilder();
        if (!root.path("theme").asText("").isEmpty()) {
            sb2.append("  <script>").append(sb.readPreset("md/js/theme-head-inline.js").trim()).append("</script>\n");
        }
        JsonNode head = root.path("head");
        if (head.isArray()) for (JsonNode h : head) sb2.append("  ").append(h.asText()).append('\n');
        return sb2.toString();
    }

    String themeAttr(JsonNode root) {
        String theme = root.path("theme").asText("");
        return theme.isEmpty() ? "" : " data-theme=\"" + theme + "\"";
    }

    void checkLeftover(String base, String what, String who) {
        int p = base.indexOf("{{");
        if (p < 0) return;
        int q = base.indexOf("}}", p);
        String left = q >= 0 ? base.substring(p, q + 2) : base.substring(p, Math.min(base.length(), p + 30));
        sb.errors.add(who + " 的 " + what + " 存在未注入占位符: " + left);
    }
}
