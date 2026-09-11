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
 * 块级**判定**辅助（包内私有）：不产出任何 HTML，只回答"这一行是什么"。
 * 只有 {@link #isBlockStartAt} 需要 owner/fn（脚注定义行也会终止段落）；其余都是纯静态判定。
 * 渲染分别在 {@link MdCodeBlocks}/{@link MdQuotes}/{@link MdLists}/{@link MdTables}。
 */
class MdBlockScan {

    private final MarkdownRenderer owner;
    private final MdFootnotes fn;

    MdBlockScan(MarkdownRenderer owner, MdFootnotes fn) {
        this.owner = owner;
        this.fn = fn;
    }

    /** 段落是否在此行结束（= 该行是某个块的起点） */
    boolean isBlockStartAt(List<String> lines, int i, int end) {
        if (lines == owner.srcLines && fn.defLineIdx.containsKey(i)) return true;
        String t = MarkdownRenderer.stripIndent(lines.get(i));
        if (t.isEmpty()) return true;
        return isFence(t) || t.startsWith(">") || isListStart(t) || MdTables.isTableAhead(lines, i, end)
                || isMathOpen(t) || isMathOpenBracket(t) || headingLevel(t) > 0 || hrType(t) != null || isHtmlBlock(t);
    }

    static boolean isFence(String t) { return t.matches("(`{3,}|~{3,}).*"); }
    static boolean isMathOpen(String t) { return t.startsWith("$$"); }
    static boolean isMathOpenBracket(String t) { return t.startsWith("\\["); }

    /**
     * 分割线特性（v2 收尾）：--- / +++ / ***（与 ___ 同档）各自注册独立 class，
     * 供 CSS 分别定制渲染（可选）；simple 与 strict 都放行。
     */
    static String hrType(String t) {
        if (t.matches("\\*{3,}[ \t]*$") || t.matches("_{3,}[ \t]*$")) return "md-hr-star";
        if (t.matches("-{3,}[ \t]*$")) return "md-hr-dash";
        if (t.matches("\\+{3,}[ \t]*$")) return "md-hr-plus";
        return null;
    }

    static boolean isHtmlBlock(String t) {
        if (!t.startsWith("<")) return false;
        if (t.length() < 2) return false;
        char c = t.charAt(1);
        return Character.isLetter(c) || c == '/' || c == '!';
    }

    static int headingLevel(String t) {
        if (t.isEmpty() || t.charAt(0) != '#') return 0;
        int k = 0;
        while (k < t.length() && t.charAt(k) == '#') k++;
        if (k > 6) return 0;
        if (k < t.length() && t.charAt(k) != ' ' && t.charAt(k) != '\t') return 0;   // 标准：须空格
        return k;
    }

    static boolean isListStart(String t) {
        return t.matches("^[-*+]([ \t].*)?$") || t.matches("^\\d+[.)]([ \t].*)?$");
    }

    /** 列表项内容起始列（`- ` = 2，`1. ` = 3，`12) ` = 4）——嵌套层级判定与续行归属靠它 */
    static int listContentCol(String t) {
        int k = 0;
        if (Character.isDigit(t.charAt(0))) {
            while (k < t.length() && Character.isDigit(t.charAt(k))) k++;
            if (k < t.length() && (t.charAt(k) == '.' || t.charAt(k) == ')')) k++;
        } else {
            k = 1;
        }
        while (k < t.length() && (t.charAt(k) == ' ' || t.charAt(k) == '\t')) k++;
        return k;
    }
}
