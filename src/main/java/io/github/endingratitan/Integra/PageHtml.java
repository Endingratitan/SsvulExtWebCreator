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

import java.util.Iterator;
import java.util.Map;

/**
 * **div 的 HTML 组装助手**（包内私有协作件，owner 引用模式）。
 *
 * 关系：与 `PageScope` **同族**（`Page*` = "页面级协作者"）——`PageScope` 持页内状态，本类只做纯组装：
 * 属性 / `data-*` 追加、模板占位符注入（`{{content}}`/`{{children}}`/`{{footnotes}}` + params 同名占位符）。
 * 由 `SitePages.renderDiv` 调用，不持有跨页状态。
 * ⚠️ 占位符替换是**全量 replace**：模板里任何位置（含注释）的 `{{...}}` 都会被替换（list 曾因此条目翻倍）。
 */
final class PageHtml {

    private final SiteBuilder sb;

    PageHtml(SiteBuilder sb) { this.sb = sb; }

    void appendAttrs(StringBuilder sb2, JsonNode attrs) {
        if (attrs == null || !attrs.isObject()) return;
        Iterator<Map.Entry<String, JsonNode>> it = attrs.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> a = it.next();
            String k = a.getKey();
            if (k.equals("class") || k.equals("id")) continue;   // class 已合并，id 由 div-ID 专属
            sb2.append(' ').append(k).append("=\"").append(a.getValue().asText()).append('"');
        }
    }

    void appendDataAttrs(StringBuilder sb2, JsonNode params) {
        if (params == null || !params.isObject()) return;
        Iterator<Map.Entry<String, JsonNode>> it = params.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> p = it.next();
            sb2.append(" data-").append(p.getKey()).append("=\"").append(p.getValue().asText()).append('"');
        }
    }

    String injectTemplate(String t, JsonNode params, String content, String children,
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
}
