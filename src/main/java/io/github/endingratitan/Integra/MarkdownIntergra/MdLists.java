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

/**
 * 列表（有序/无序/任务）（包内私有）。
 *
 * 每个列表项收集"续行子视图"后交回 {@link MdBlocks#renderBlocks} 递归渲染（嵌套层级靠缩进列判定）；
 * 子视图内遇到**围栏或表格**则"逃逸"——把控制权交还主循环，避免把代码块/表格吞进列表项。
 * 深度上限见 {@link MdBlocks#NEST_LIMIT}：strict 超限报错并**降级为普通段落**（不丢内容），
 * simple 超限继续渲染并只提示一次。任务列表 `- [x]` 生成 `disabled` checkbox。
 */
class MdLists {

    private final MarkdownRenderer owner;
    private final MdBlocks blocks;

    MdLists(MarkdownRenderer owner, MdBlocks blocks) {
        this.owner = owner;
        this.blocks = blocks;
    }

    /** 列表：同层同类型（有序/无序 + 内容列一致）的项归为同一列表，返回消费到的下一行下标 */
    int renderList(StringBuilder out, List<String> lines, int i, int end,
                   int listDepth, int quoteDepth, int base) {
        int levels = listDepth + 1;
        if (levels > MdBlocks.NEST_LIMIT) {
            if (owner.isStrict()) {
                owner.error(base + i + 1, "列表嵌套超过 " + MdBlocks.NEST_LIMIT + " 层", "请压缩列表层级",
                        MarkdownRenderer.stripIndent(lines.get(i)).trim());
                return blocks.renderFallbackLine(out, lines, i, end, base);   // 降级为普通段落：报错但不丢内容
            }
            if (!owner.listFlattenWarned) {   // simple：继续渲染，仅提示一次（与引用的软上限同档）
                owner.warn(base + i + 1, "列表嵌套超过 " + MdBlocks.NEST_LIMIT + " 层（simple：继续渲染，仅提示一次）");
                owner.listFlattenWarned = true;
            }
        }
        String t = MarkdownRenderer.stripIndent(lines.get(i));
        boolean ordered = Character.isDigit(t.charAt(0));
        int col = MdBlockScan.listContentCol(t);
        out.append(ordered ? "<ol>\n" : "<ul>\n");
        while (i < end) {
            while (i < end && lines.get(i).trim().isEmpty()) i++;
            if (i >= end) break;
            String cur = MarkdownRenderer.stripIndent(lines.get(i));
            if (!MdBlockScan.isListStart(cur)) break;
            if (Character.isDigit(cur.charAt(0)) != ordered || MdBlockScan.listContentCol(cur) != col) break;
            i = renderListItem(out, lines, i, end, col, listDepth, quoteDepth, base);
        }
        out.append(ordered ? "</ol>\n" : "</ul>\n");
        return i;
    }

    /** 单个列表项（含任务框与续行子视图） */
    int renderListItem(StringBuilder out, List<String> lines, int i, int end, int col,
                       int listDepth, int quoteDepth, int base) {
        String t = MarkdownRenderer.stripIndent(lines.get(i));
        String rawContent = t.substring(Math.min(col, t.length()));
        String content = rawContent.trim();
        boolean task = false, checked = false;
        if (content.matches("^\\[[ xX]\\]([ \t].*)?$")) {
            task = true;
            checked = content.charAt(1) == 'x' || content.charAt(1) == 'X';
        }
        String firstRaw = task ? rawContent.replaceFirst("^\\[[ xX]\\][ \t]*", "") : rawContent;
        out.append("<li");
        if (task) out.append(" class=\"md-task\"");
        out.append(">");
        if (task) out.append("<input type=\"checkbox\" disabled").append(checked ? " checked" : "").append("> ");
        int firstIdx = i;
        i++;
        // 续行收集为子视图（段落/嵌套列表/引用/标题等；围栏与表格触发"逃逸"交给主循环）
        List<String> sub = new ArrayList<>();
        sub.add(firstRaw);
        while (i < end) {
            String l = lines.get(i);
            if (l.trim().isEmpty()) {
                int j = i;
                while (j < end && lines.get(j).trim().isEmpty()) j++;
                if (j < end && MarkdownRenderer.leadingSpaces(lines.get(j)) >= col) {
                    for (int k = i; k < j; k++) sub.add("");   // 逐行加入保行号
                    i = j;
                    continue;
                }
                break;
            }
            if (MarkdownRenderer.leadingSpaces(l) < col) break;
            String stripped = l.substring(Math.min(col, l.length()));
            String inner = MarkdownRenderer.stripIndent(stripped);
            if (MdBlockScan.isFence(inner) || MdTables.isTableAhead(lines, i, end)) break;   // 逃逸：交给主循环
            sub.add(stripped);   // 保留相对缩进，嵌套层级判定不被抹平
            i++;
        }
        if (!sub.isEmpty()) {
            out.append('\n');
            blocks.renderBlocks(out, sub, listDepth + 1, quoteDepth, base + firstIdx);
        }
        out.append("</li>\n");
        return i;
    }
}
