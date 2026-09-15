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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * **`list` 组件子系统**（包内私有协作件，owner 引用模式）。
 *
 * 关系：与 `PageScope`/`PageHtml` **同族**（`Page*` = "页面级协作者"）——由 `SitePages.renderDiv` 在遇到
 * `type=list` 时调用；**每页一个实例**（持当前页 `PageScope`，用于记 `listAssets`/`listDirs`）。
 * `emitListShards()` 是**全局相**入口（`page == null`，只发射分片、不碰页内状态），由 `SitePages` 转发。
 *
 * 契约：`src` 决定数据来源与渲染时机（`build:page-index` 构建期静态零 JS / `ssvul:inline` / `ssvul:shared`
 * 分片 / `ssvul:json` 纯 CSR / `ssvul:s3` 列目录 / 裸函数名）；条目形状与客户端预设**必须一致**
 * （`.list-item/.list-date/.list-title/.list-tags/.list-tag/.list-excerpt`）——这是唯一剩下的双实现不变量。
 */
final class PageList {

    private final SiteBuilder sb;
    private final PageScope page;

    PageList(SiteBuilder sb, PageScope page) { this.sb = sb; this.page = page; }

    void checkNoReservedBk(JsonNode params, String pageSrc) {
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
    String listBucketAttrs(JsonNode div, String pageSrc) {
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
        if (sb.offline && !page.offlineListWarned) {
            page.offlineListWarned = true;      // 每页只发一次（同页多个列目录列表不重复刷屏）
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
    String listHandle(JsonNode div, StringBuilder out, String pageSrc, boolean[] clientSide) {
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
                    if (page != null) page.listDirs.add(dir);
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
            if (!page.listAssets.contains(asset)) page.listAssets.add(asset);
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
        if (excludeSelf && page.pageLink != null && !page.pageLink.isEmpty())
            entries.removeIf(e -> page.pageLink.equals(e.getOrDefault("link", "")));
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
}
