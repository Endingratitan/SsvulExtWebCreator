/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Integra.MarkdownIntergra;

import java.util.List;

/**
 * 围栏代码块与块数学（包内私有）。
 *
 * 代码块：info 首词作语言 → 交 {@link MarkdownRenderer#engine}（本次渲染的引擎链）上色；
 * 引擎全员拒接或抛错时**降级为纯文本**（警告/收集式报错），构建永不失败；
 * 页面配了 code-ui 时再套一层外壳（items 顺序 = DOM 顺序，未知 item 警告一次并交给 CODEUI.js）。
 * 块数学：`$$…$$` 或 `\[…\]`，未闭合两模式都报错（内容仍原样转义输出）。
 */
class MdCodeBlocks {

    private static final java.util.regex.Pattern LANG = java.util.regex.Pattern.compile("[A-Za-z0-9_+-]+");

    /** 与 Java 正则 \\s 等价的 ASCII 空白集（isWS 还含 Unicode 空格，语义不完全一致，故单独判） */
    private static boolean isRegexWS(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 0x0B;
    }

    private final MarkdownRenderer owner;

    MdCodeBlocks(MarkdownRenderer owner) {
        this.owner = owner;
    }

    /** 块数学：$$…$$ 或 \[…\]（单行或多行，以定界符起、行尾定界符收）；未闭合两模式都报错 */
    int renderMathBlock(StringBuilder out, List<String> lines, int i, int end, int base) {
        String first = MarkdownRenderer.stripIndent(lines.get(i));
        boolean bracket = first.startsWith("\\[");
        String close = bracket ? "\\]" : "$$";
        StringBuilder sb = new StringBuilder(first);
        int j = i + 1;
        boolean closed = first.trim().length() >= 4 && first.trim().endsWith(close);
        while (!closed && j < end) {
            String l = lines.get(j);
            sb.append('\n').append(l);
            j++;
            if (l.trim().endsWith(close)) { closed = true; break; }
        }
        if (!closed) owner.error(base + i + 1, "块数学未闭合", "请在末尾补 " + close, first);
        out.append("<div class=\"md-math-block\"");
        if (bracket) out.append(" data-delim=\"latex-block\"");   // 非默认定界符才标注（$ 路径保持既有输出）
        out.append(">").append(MarkdownRenderer.esc(sb.toString())).append("</div>\n");
        return j;
    }

    /** 围栏代码块：返回消费到的下一行下标；内容原样收集（内部不再解析 md） */
    int renderFenced(StringBuilder out, List<String> lines, int i, int end, int base) {
        String t = MarkdownRenderer.stripIndent(lines.get(i));
        char fc = t.charAt(0);
        int len = 0;
        while (len < t.length() && t.charAt(len) == fc) len++;
        String lang = "";
        String info = t.substring(len).trim();
        if (!info.isEmpty()) {
            int sp = 0;
            while (sp < info.length() && !isRegexWS(info.charAt(sp))) sp++;   // 首个词（替代 split 的每次编译）
            String first = info.substring(0, sp);
            if (LANG.matcher(first).matches()) lang = first;
        }
        int j = i + 1;
        StringBuilder content = new StringBuilder();
        boolean closed = false;
        while (j < end) {
            String lt = MarkdownRenderer.stripIndent(lines.get(j));
            if (!lt.isEmpty() && lt.charAt(0) == fc) {
                int k = 0;
                while (k < lt.length() && lt.charAt(k) == fc) k++;
                if (k >= len && lt.substring(k).trim().isEmpty()) { closed = true; j++; break; }
            }
            if (content.length() > 0) content.append('\n');
            content.append(lines.get(j));
            j++;
        }
        if (!closed && owner.isStrict()) owner.error(base + i + 1, "未闭合的代码块", "请在末尾补上 " + fc, t);
        String rendered;
        try {
            rendered = owner.engine.renderCode(content.toString(), lang);
            if (rendered == null) {   // 引擎链全员拒接该语言：纯文本兜底 + 警告，构建不失败
                owner.warn(base + i + 1, "无引擎接受语言 \"" + lang + "\"，该块已按纯文本输出");
                rendered = MarkdownRenderer.esc(content.toString());
            }
        } catch (RuntimeException e) {
            owner.error(base + i + 1, "代码引擎渲染失败: " + e.getMessage(), "该块已按纯文本输出", lang);
            rendered = MarkdownRenderer.esc(content.toString());
        }
        owner.hadCode = true;
        boolean ui = !owner.codeUiItems.isEmpty() || owner.codeUiBg != null || owner.codeUiRounded;
        String pre = "<pre class=\"md-pre\"><code class=\"md-code";
        if (!lang.isEmpty()) pre += " language-" + lang;
        pre += "\">" + rendered + "</code></pre>\n";
        if (!ui) { out.append(pre); return j; }

        // code-ui 外壳：块容器 + items（数组顺序 = DOM 顺序）+ 块级属性类
        boolean hasLang = owner.codeUiItems.contains("lang-label");
        boolean hasDots = owner.codeUiItems.contains("mac-dots");
        boolean hasCopy = owner.codeUiItems.contains("copy-btn");
        StringBuilder b = new StringBuilder("<div class=\"md-code-block");
        if (hasLang) b.append(" codeui-lang");
        if (hasDots) b.append(" codeui-dots");
        if (hasCopy) b.append(" codeui-copy");
        if (owner.codeUiBg != null) b.append(" codeui-bg");
        if (owner.codeUiRounded) b.append(" codeui-rounded");
        if (hasCopy && "tr".equals(owner.codeUiLabelPos)) b.append(" has-copy");
        b.append("\" data-lang=\"").append(MarkdownRenderer.esc(lang))
         .append("\" data-items=\"").append(MarkdownRenderer.esc(String.join(",", owner.codeUiItems))).append('"');
        if (owner.codeUiBg != null) b.append(" style=\"background-image:url(").append(MarkdownRenderer.esc(owner.codeUiBg)).append(");\"");
        b.append(">\n");
        for (String it : owner.codeUiItems) {
            switch (it) {
                case "lang-label" -> {
                    if (!lang.isEmpty())
                        b.append("<span class=\"md-code-lang pos-").append(owner.codeUiLabelPos).append("\">")
                         .append(MarkdownRenderer.esc(lang)).append("</span>\n");
                }
                case "mac-dots" ->
                        b.append("<span class=\"md-code-dots\" aria-hidden=\"true\"><i></i><i></i><i></i></span>\n");
                case "copy-btn" ->
                        b.append("<button type=\"button\" class=\"md-code-copy\">复制</button>\n");
                default -> {
                    if (!owner.codeUiWarnedUnknown) {   // 未知 item：警告一次 + 跳过（CODEUI.js 可经 data-items 接手实现）
                        owner.warn(base + i + 1, "code-ui 未知 item（已跳过，可由 CODEUI.js 按 data-items 实现）: " + it);
                        owner.codeUiWarnedUnknown = true;
                    }
                }
            }
        }
        b.append(pre).append("</div>\n");
        out.append(b);
        return j;
    }
}
