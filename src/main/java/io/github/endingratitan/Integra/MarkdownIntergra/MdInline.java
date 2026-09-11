/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Integra.MarkdownIntergra;

/**
 * 行内解析器（包内私有）。优先级链：\ 转义 → `code` → $math$ → 强调 → 链接/图片 → kbd → 脚注 → 自动链接/原生 HTML。
 * owner 为 MarkdownRenderer（共享错误/模式/转义），fn 为脚注子系统（引用登记）。
 */
class MdInline {

    private static final String PUNCT = "\\`*_{}[]()#+-.!|~><$";

    private final MarkdownRenderer owner;
    private final MdFootnotes fn;

    MdInline(MarkdownRenderer owner, MdFootnotes fn) {
        this.owner = owner;
        this.fn = fn;
    }

    String inline(String s, int lineNo, int depth) {
        StringBuilder out = new StringBuilder();
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            // LaTeX 原生定界符 \( … \)（行内）：置于转义分支之前，避免 \( 被当作转义吃掉
            if (c == '\\' && s.startsWith("\\(", i)) {
                int j = s.indexOf("\\)", i + 2);
                if (j > i + 1) {
                    out.append("<span class=\"md-math\" data-delim=\"latex\">").append(MarkdownRenderer.esc(s.substring(i, j + 2))).append("</span>");
                    i = j + 2;
                    continue;
                }
                if (owner.isStrict()) {
                    owner.error(lineNo, "行内数学未闭合（strict）", "请补 \\) 或改用 \\[ \\] 块数学", "\\(");
                    out.append("\\(");
                    i += 2;
                    continue;
                }
                owner.warn(lineNo, "行内数学未闭合（simple：按行尾收口渲染）");
                out.append("<span class=\"md-math\" data-delim=\"latex\">").append(MarkdownRenderer.esc(s.substring(i))).append("</span>");
                i = n;
                continue;
            }
            if (c == '\\' && i + 1 < n && PUNCT.indexOf(s.charAt(i + 1)) >= 0) {
                out.append(MarkdownRenderer.esc(s.charAt(i + 1)));
                i += 2;
                continue;
            }
            if (c == '`') {
                int j = s.indexOf('`', i + 1);
                if (j > i + 1) {
                    out.append("<code class=\"md-code-inline\">").append(MarkdownRenderer.esc(s.substring(i + 1, j))).append("</code>");
                    i = j + 1;
                    continue;
                }
            }
            if (c == '$' && mathOpen(s, i)) {
                int j = mathClose(s, i);
                if (j > 0) {
                    out.append("<span class=\"md-math\">").append(MarkdownRenderer.esc(s.substring(i, j + 1))).append("</span>");
                    i = j + 1;
                    continue;
                }
                // 货币形态（$ 后随数字，如 $5）：保持字面，两种模式都不报错/不收口
                if (i + 1 < n && Character.isDigit(s.charAt(i + 1))) {
                    out.append('$');
                    i++;
                    continue;
                }
                // 未闭合：strict 报错；simple 栈式回退——已配对的由主循环处理，孤 $ 以行尾为结束点（性能优先，不跨行扫描）
                if (owner.isStrict()) {
                    owner.error(lineNo, "行内数学未闭合（strict）", "请补 $ 或改用 $$ 块数学", "$");
                    out.append('$');
                    i++;
                    continue;
                }
                owner.warn(lineNo, "行内数学未闭合（simple：按行尾收口渲染）");
                out.append("<span class=\"md-math\">").append(MarkdownRenderer.esc(s.substring(i))).append("</span>");
                i = n;
                continue;
            }
            if (depth < 2) {
                int j = -1;
                if (s.startsWith("***", i) && i + 3 < n && !MarkdownRenderer.isWS(s.charAt(i + 3))
                        && (j = findClose(s, i + 3, "***", false)) >= 0) {
                    out.append("<strong><em>").append(inline(s.substring(i + 3, j), lineNo, depth + 1)).append("</em></strong>");
                    i = j + 3;
                    continue;
                }
                if (s.startsWith("**", i) && i + 2 < n && !MarkdownRenderer.isWS(s.charAt(i + 2))
                        && (j = findClose(s, i + 2, "**", false)) >= 0) {
                    out.append("<strong>").append(inline(s.substring(i + 2, j), lineNo, depth + 1)).append("</strong>");
                    i = j + 2;
                    continue;
                }
                if (s.startsWith("__", i) && openUnder(s, i, 2)
                        && (j = findClose(s, i + 2, "__", true)) >= 0) {
                    out.append("<strong>").append(inline(s.substring(i + 2, j), lineNo, depth + 1)).append("</strong>");
                    i = j + 2;
                    continue;
                }
                if (c == '*' && i + 1 < n && !MarkdownRenderer.isWS(s.charAt(i + 1))
                        && (j = findClose(s, i + 1, "*", false)) >= 0) {
                    out.append("<em>").append(inline(s.substring(i + 1, j), lineNo, depth + 1)).append("</em>");
                    i = j + 1;
                    continue;
                }
                if (c == '_' && openUnder(s, i, 1)
                        && (j = findClose(s, i + 1, "_", true)) >= 0) {
                    out.append("<em>").append(inline(s.substring(i + 1, j), lineNo, depth + 1)).append("</em>");
                    i = j + 1;
                    continue;
                }
                if (s.startsWith("~~", i) && i + 2 < n && !MarkdownRenderer.isWS(s.charAt(i + 2))
                        && (j = findClose(s, i + 2, "~~", false)) >= 0) {
                    out.append("<del>").append(inline(s.substring(i + 2, j), lineNo, depth + 1)).append("</del>");
                    i = j + 2;
                    continue;
                }
            }
            if (s.startsWith("![", i)) {
                int close = s.indexOf("](", i + 2);
                if (close >= 0) {
                    int e = s.indexOf(')', close + 2);
                    if (e >= 0) {
                        String alt = s.substring(i + 2, close);
                        String url = s.substring(close + 2, e).trim();
                        if (checkUrl(url, lineNo, "图片")) {
                            out.append("<img class=\"md-img\" src=\"").append(MarkdownRenderer.esc(url))
                               .append("\" alt=\"").append(MarkdownRenderer.esc(alt)).append("\" loading=\"lazy\">");
                            i = e + 1;
                            continue;
                        }
                    }
                }
            }
            if (c == '[') {
                int close = s.indexOf("](", i + 1);
                if (close >= 0) {
                    int e = s.indexOf(')', close + 2);
                    if (e >= 0) {
                        String url = s.substring(close + 2, e).trim();
                        if (checkUrl(url, lineNo, "链接")) {
                            out.append("<a href=\"").append(MarkdownRenderer.esc(url)).append("\">")
                               .append(inline(s.substring(i + 1, close), lineNo, depth)).append("</a>");
                            i = e + 1;
                            continue;
                        }
                    }
                }
            }
            if (s.startsWith("[[", i)) {
                int j = s.indexOf("]]", i + 2);
                if (j >= 0) {
                    out.append("<kbd class=\"md-kbd\">").append(MarkdownRenderer.esc(s.substring(i + 2, j))).append("</kbd>");
                    i = j + 2;
                    continue;
                }
            }
            if (c == '[' && i + 1 < n && s.charAt(i + 1) == '^') {
                int close = s.indexOf(']', i + 2);
                if (close > i + 2) {
                    String label = s.substring(i + 2, close);
                    if (!owner.inFootDef && (label.equals(".") || (label.matches("\\d+") && !label.startsWith("0")))) {
                        fn.registerRef(label, lineNo, out);
                        i = close + 1;
                        continue;
                    }
                    if (!owner.inFootDef && (label.equals("0") || label.matches("\\d+"))) {
                        owner.error(lineNo, "脚注编号必须为正整数: [^" + label + "]", null, label);
                    }
                    // 定义内容内或其他标签 → 字面，落入普通处理
                }
            }
            if (c == '<') {
                int e = s.indexOf('>', i + 1);
                if (e > i + 1) {
                    String inner = s.substring(i + 1, e);
                    if (inner.startsWith("http://") || inner.startsWith("https://")) {
                        out.append("<a href=\"").append(MarkdownRenderer.esc(inner)).append("\">").append(MarkdownRenderer.esc(inner)).append("</a>");
                        i = e + 1;
                        continue;
                    }
                    if (!owner.isStrict() && isRawTag(s, i, e)) {   // simple：行内原生 HTML 直出
                        out.append(s, i, e + 1);
                        i = e + 1;
                        continue;
                    }
                }
            }
            out.append(MarkdownRenderer.esc(c));
            i++;
        }
        return out.toString();
    }

    private boolean checkUrl(String url, int lineNo, String kind) {
        if (url.isEmpty() || url.contains(" ") || url.contains("\t")) {
            owner.error(lineNo, kind + " URL 不能为空或含空白", null, url);
            return false;
        }
        if (!validUrl(url)) {
            owner.error(lineNo, kind + " URL 不符合白名单: " + MarkdownRenderer.snippet(url),
                    "允许: http(s)://、pre-assets/、@data/、@page/、#锚点、已配置的 bucket 调用名（如 bk/...）", url);
            return false;
        }
        return true;
    }

    /** 名字/ 形态视为潜在 bucket 引用，最终由输出替换阶段校验调用名是否已配置 */
    private static boolean validUrl(String u) {
        return u.startsWith("http://") || u.startsWith("https://")
                || u.startsWith("pre-assets/") || u.startsWith("@data/")
                || u.startsWith("@page/") || u.startsWith("#")
                || u.matches("^[A-Za-z0-9_-]+/.*");
    }

    // ---- 行内判定 ----

    /** $ 开标记：仅当"前后都是边界"（行首尾/空白/$）才视为孤立（货币等普通文本），否则为数学候选 */
    private static boolean mathOpen(String s, int i) {
        boolean prevB = i == 0 || MarkdownRenderer.isWS(s.charAt(i - 1)) || s.charAt(i - 1) == '$';
        boolean nextB = i + 1 >= s.length() || MarkdownRenderer.isWS(s.charAt(i + 1)) || s.charAt(i + 1) == '$';
        return !(prevB && nextB);
    }

    /** 闭 $：仅要求前邻紧邻非空白非 $（后邻随意，行尾亦可）；跳过被 \ 转义的 $ */
    private static int mathClose(String s, int open) {
        for (int j = open + 2; j < s.length(); j++) {
            if (s.charAt(j) != '$') continue;
            if (j > 0 && s.charAt(j - 1) == '\\') continue;   // \$ 是转义，不是闭定界符
            char p = s.charAt(j - 1);
            if (!MarkdownRenderer.isWS(p) && p != '$') return j;
        }
        return -1;
    }

    /** '_' 开标记：两侧不能同时是字母数字，且后一字符非空白 */
    private static boolean openUnder(String s, int i, int len) {
        boolean prevAl = i > 0 && MarkdownRenderer.isAlnum(s.charAt(i - 1));
        boolean nextAl = i + len < s.length() && MarkdownRenderer.isAlnum(s.charAt(i + len));
        boolean nextNotWs = i + len < s.length() && !MarkdownRenderer.isWS(s.charAt(i + len));
        return nextNotWs && (!prevAl || !nextAl);
    }

    /** under=true 用 '_' 闭规则（前一字符非空白且两侧不同时为字母数字），否则用 '*' 规则（前一字符非空白） */
    private static int findClose(String s, int from, String mark, boolean under) {
        int ml = mark.length();
        for (int j = from; j + ml <= s.length(); j++) {
            if (!s.startsWith(mark, j)) continue;
            boolean prevNotWs = j == 0 || !MarkdownRenderer.isWS(s.charAt(j - 1));
            if (!prevNotWs) continue;
            if (under) {
                boolean prevAl = j > 0 && MarkdownRenderer.isAlnum(s.charAt(j - 1));
                boolean nextAl = j + ml < s.length() && MarkdownRenderer.isAlnum(s.charAt(j + ml));
                if (prevAl && nextAl) continue;
            }
            return j;
        }
        return -1;
    }

    /** 形如 <tag ...> 的原生 HTML 片段（其间无嵌套 <） */
    private static boolean isRawTag(String s, int i, int e) {
        char c = s.charAt(i + 1);
        if (!Character.isLetter(c) && c != '/' && c != '!') return false;
        int next = s.indexOf('<', i + 1);
        return next == -1 || next > e;
    }
}
