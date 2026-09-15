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
 * 表格（包内私有）：表头 + 分隔行（决定对齐）+ 数据行。
 *
 * 分隔行与表头列数不一致、或数据行列数不一致时，**strict 报错、simple 容忍**（按表头列数截断）；
 * 无表头的 `|` 行不会被当成表格（起点判定见 {@link #isTableAhead}）。
 */
class MdTables {

    private final MarkdownRenderer owner;

    MdTables(MarkdownRenderer owner) {
        this.owner = owner;
    }

    /** 表格：返回消费到的下一行下标 */
    int renderTable(StringBuilder out, List<String> lines, int i, int end, int base) {
        List<String> header = tableCells(lines.get(i));
        List<String> delim = tableCells(lines.get(i + 1));
        if (delim.size() != header.size() && owner.isStrict()) {
            owner.error(base + i + 2, "表格分隔行列数与表头不一致", null, lines.get(i + 1));
        }
        String[] align = new String[header.size()];
        for (int k = 0; k < header.size() && k < delim.size(); k++) {
            String d = delim.get(k).trim();
            boolean l = d.startsWith(":"), r = d.endsWith(":");
            align[k] = (l && r) ? "md-al-c" : l ? "md-al-l" : r ? "md-al-r" : "md-al-l";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"md-table-wrap\">\n<table class=\"md-table\">\n<thead>\n<tr>\n");
        for (int k = 0; k < header.size(); k++)
            sb.append("<th").append(alignCls(align[k])).append(">")
              .append(owner.inline(header.get(k).trim(), base + i + 1, 0)).append("</th>\n");
        sb.append("</tr>\n</thead>\n<tbody>\n");
        int j = i + 2;
        while (j < end && !lines.get(j).trim().isEmpty() && lines.get(j).indexOf('|') >= 0) {
            List<String> cells = tableCells(lines.get(j));
            if (cells.size() != header.size() && owner.isStrict()) {
                owner.error(base + j + 1, "表格行列数与表头不一致（" + cells.size() + " vs " + header.size() + "）",
                        null, lines.get(j));
            }
            sb.append("<tr>\n");
            int m = Math.min(cells.size(), header.size());
            for (int k = 0; k < m; k++)
                sb.append("<td").append(alignCls(align[k])).append(">")
                  .append(owner.inline(cells.get(k).trim(), base + j + 1, 0)).append("</td>\n");
            sb.append("</tr>\n");
            j++;
        }
        sb.append("</tbody>\n</table>\n</div>\n");
        out.append(sb);
        return j;
    }

    // ---- 判定与切分（同时供段落/列表的"块起点"判定复用，故为包内静态） ----

    /** 当前行 + 下一行（分隔行）是否构成表格起点 */
    static boolean isTableAhead(List<String> lines, int i, int end) {
        if (i + 1 >= end) return false;
        return lines.get(i).trim().contains("|") && isDelimRow(lines.get(i + 1));
    }

    private static final java.util.regex.Pattern DELIM_CELL = java.util.regex.Pattern.compile(":?-+:?");

    static boolean isDelimRow(String line) {
        String t = line.trim();
        if (t.isEmpty() || t.indexOf('|') < 0 || t.indexOf('-') < 0) return false;
        for (String cell : tableCells(t)) {
            if (!DELIM_CELL.matcher(cell.trim()).matches()) return false;
        }
        return true;
    }

    /** 拆分表格行，剥离首尾管道产生的空单元格 */
    static List<String> tableCells(String line) {
        List<String> cells = splitCells(line);
        if (line.startsWith("|") && !cells.isEmpty()) cells.remove(0);
        if (line.endsWith("|") && !cells.isEmpty()) cells.remove(cells.size() - 1);
        return cells;
    }

    /** 按未转义的 `|` 切分（`\|` 视为字面量） */
    static List<String> splitCells(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean esc = false;
        for (int k = 0; k < line.length(); k++) {
            char ch = line.charAt(k);
            if (esc) { cur.append(ch); esc = false; continue; }
            if (ch == '\\') { esc = true; continue; }
            if (ch == '|') { cells.add(cur.toString()); cur.setLength(0); continue; }
            cur.append(ch);
        }
        cells.add(cur.toString());
        return cells;
    }

    static String alignCls(String a) { return "md-al-l".equals(a) ? "" : " class=\"" + a + "\""; }
}
