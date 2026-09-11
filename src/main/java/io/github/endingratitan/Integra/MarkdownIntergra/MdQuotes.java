/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Integra.MarkdownIntergra;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 引用块与 callout（包内私有）。
 *
 * 引用：逐行剥 `>` 组成子视图，再交回 {@link MdBlocks#renderBlocks} 递归渲染（嵌套深度 = quoteDepth）；
 * 空行后仍是 `>` 通常续同一引用，但下一段以 callout 标记开头时**断成两块**
 * （否则第二个 callout 会被吞成字面文本）。
 * callout：看引用**首个非空行**的 `[!TYPE] 可选标题`；类型表见 {@link Callouts}，
 * 非内置类型警告一次并按扩展类型渲染（样式由 `sets/global/callout/CALLOUT[.<lang>].css` 提供）。
 * 深度上限见 {@link MdBlocks#NEST_LIMIT}：simple 压平继续渲染（警告一次），strict 报错并丢该行。
 */
class MdQuotes {

    /** callout 标记：引用首行 `[!TYPE]` + 可选标题（标题走行内解析） */
    private static final Pattern CALLOUT = Pattern.compile("^\\[!([A-Za-z][A-Za-z0-9-]*)\\]\\s*(.*)$");

    private final MarkdownRenderer owner;
    private final MdBlocks blocks;

    MdQuotes(MarkdownRenderer owner, MdBlocks blocks) {
        this.owner = owner;
        this.blocks = blocks;
    }

    /** 引用块（含 callout）：返回消费到的下一行下标 */
    int renderQuote(StringBuilder out, List<String> lines, int start, int end,
                    int listDepth, int quoteDepth, int base) {
        boolean flatten = quoteDepth >= MdBlocks.NEST_LIMIT;
        if (flatten) {
            if (owner.isStrict()) {
                owner.error(base + start + 1, "引用嵌套超过 " + MdBlocks.NEST_LIMIT + " 层", "请压缩层级", lines.get(start).trim());
                return start + 1;
            }
            if (!owner.quoteFlattenWarned) {   // simple：压平继续渲染，警告一次
                owner.warn(base + start + 1, "引用嵌套超过 " + MdBlocks.NEST_LIMIT + " 层（simple：继续渲染，仅提示一次）");
                owner.quoteFlattenWarned = true;
            }
        }
        List<String> sub = new ArrayList<>();
        int i = start;
        while (i < end) {
            String l = lines.get(i);
            if (l.trim().isEmpty()) {
                int j = i;
                while (j < end && lines.get(j).trim().isEmpty()) j++;
                // 空行后仍是 `>` 通常继续同一引用；但下一段以 callout 标记开头时断成两块（否则第二个 callout 会被吞成字面文本）
                if (j < end && MarkdownRenderer.stripIndent(lines.get(j)).startsWith(">")
                        && !startsCallout(MarkdownRenderer.stripIndent(lines.get(j)))) { sub.add(""); i = j; continue; }
                break;
            }
            String t = MarkdownRenderer.stripIndent(l);
            if (!t.startsWith(">")) {
                if (owner.isStrict()) owner.error(base + i + 1, "引用块内每行需以 > 开头", "请补 > 或以空行结束引用块", t);
                else owner.warn(base + i + 1, "引用块内缺 > 行（simple：按段落继续）");
                break;
            }
            t = t.substring(1);
            if (t.startsWith(" ")) t = t.substring(1);
            sub.add(t);
            i++;
        }
        // callout：只看引用首个非空行（`[!TYPE] 可选标题`；转义 \[!TYPE\] 首字符是 \ 天然不匹配）
        boolean isCallout = false;
        String calloutType = null, calloutTitle = null;
        int markerLine = base + start + 1;
        if (!sub.isEmpty()) {
            Matcher m = CALLOUT.matcher(sub.get(0));
            if (m.matches()) {
                String t2 = m.group(1).toLowerCase(Locale.ROOT);
                if (Callouts.kebab(t2)) {
                    isCallout = true;
                    calloutType = t2;
                    String rest = m.group(2).trim();
                    calloutTitle = rest.isEmpty() ? null : rest;
                    sub.remove(0);   // 标记行不进正文
                    if (!Callouts.known(t2) && owner.calloutWarned.add(t2)) {
                        owner.warn(markerLine, "非内置 callout 类型 " + t2
                                + "（已按扩展类型渲染；可在 sets/global/callout/CALLOUT.css 提供样式）");
                    }
                    if (sub.isEmpty()) owner.warn(markerLine, "callout 无内容: [" + t2 + "]");
                }
            }
        }
        if (isCallout) {
            owner.hadCallout = true;
            out.append("<blockquote class=\"md-callout md-callout-").append(calloutType)
               .append("\" data-callout=\"").append(calloutType).append("\">\n");
            String text = calloutTitle != null ? calloutTitle
                    : (owner.calloutTitleOn ? Callouts.label(calloutType) : null);   // callout-title=none 时不注入默认标签
            if (text != null && !text.isEmpty()) {
                // 默认标签：包一层 .md-callout-label + 标题加 .md-callout-title-default
                // （换语言规则只作用于 default 类 → 作者自定义标题不会被 ::after 追加第二段文字；视觉验收抓出来的）
                boolean isDefault = calloutTitle == null;
                out.append("<p class=\"md-callout-title");
                if (isDefault) out.append(" md-callout-title-default");
                out.append("\">");
                if (isDefault) {
                    out.append("<span class=\"md-callout-label\">")
                       .append(MarkdownRenderer.esc(text)).append("</span>");
                } else {
                    out.append(owner.inline(text, markerLine, 0));
                }
                out.append("</p>\n");
            }
        } else {
            out.append("<blockquote>\n");
        }
        blocks.renderBlocks(out, sub, listDepth, flatten ? quoteDepth : quoteDepth + 1, base + start);
        out.append("</blockquote>\n");
        return i;
    }

    /** 引用行（已剥缩进，形如 `> [!type]`）是否以 callout 标记开头——用于空行断块判定 */
    private static boolean startsCallout(String rawLine) {
        if (!rawLine.startsWith(">")) return false;
        String t = rawLine.substring(1);
        if (t.startsWith(" ")) t = t.substring(1);
        Matcher m = CALLOUT.matcher(t);
        return m.matches() && Callouts.kebab(m.group(1).toLowerCase(Locale.ROOT));
    }
}
