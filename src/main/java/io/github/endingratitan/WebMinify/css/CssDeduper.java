/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.WebMinify.css;

import java.util.*;

/**
 * CSS 同选择器去重（继承链串联场景）：规范化选择器文本相同 → 后者胜，删前驱（级联语义保证等价）。
 * at-rule（@media/@keyframes 等）整块保守跳过；keepRemovedAsComments=true 时被删规则以注释回插（* / 转义）。
 */
public final class CssDeduper {

    private CssDeduper() {}

    public static String dedup(String css, boolean keepRemovedAsComments) {
        List<Seg> segs = segment(css);
        Map<String, int[]> last = new HashMap<>();       // 规范化选择器 → 输出区间
        StringBuilder out = new StringBuilder(css.length());
        for (Seg s : segs) {
            if (!s.rule) { out.append(css, s.start, s.end); continue; }
            String sel = normalize(css.substring(s.start, s.bodyStart));
            int[] prev = last.put(sel, new int[]{out.length(), out.length()});   // 占位，稍后填
            if (prev != null) {
                // 前驱区间置空（保留注释位）
                if (keepRemovedAsComments) {
                    String old = css.substring(prev[0], prev[1]).replace("*/", "* /");
                    String ins = "/* ssvul-css-dedup: removed (overridden by later same selector)\n" + old + "\n*/\n";
                    // 前驱已是输出区间——重建：标记为待替换更稳妥，这里直接替换区间内容
                    out.replace(prev[0], prev[1], ins);
                    int shift = ins.length() - (prev[1] - prev[0]);
                    adjust(last, prev[1], shift);
                } else {
                    out.replace(prev[0], prev[1], "");
                    int shift = -(prev[1] - prev[0]);
                    adjust(last, prev[1], shift);
                }
            }
            int start = out.length();
            out.append(css, s.start, s.end);
            last.put(sel, new int[]{start, out.length()});
        }
        return out.toString();
    }

    private static void adjust(Map<String, int[]> last, int from, int shift) {
        for (int[] r : last.values()) {
            if (r[0] >= from) r[0] += shift;
            if (r[1] >= from) r[1] += shift;
        }
    }

    /** 规范化选择器：去注释、折叠空白、首尾 trim */
    private static String normalize(String sel) {
        StringBuilder sb = new StringBuilder();
        boolean ws = false;
        for (int i = 0; i < sel.length(); i++) {
            char c = sel.charAt(i);
            if (c == '/' && i + 1 < sel.length() && sel.charAt(i + 1) == '*') {
                int j = sel.indexOf("*/", i + 2);
                i = j < 0 ? sel.length() - 1 : j + 1;
                continue;
            }
            if (Character.isWhitespace(c)) { ws = true; continue; }
            if (ws && sb.length() > 0) sb.append(' ');
            ws = false;
            sb.append(c);
        }
        return sb.toString().trim();
    }

    // ==================== 分段 ====================

    private record Seg(int start, int end, boolean rule, int bodyStart) {}

    private static List<Seg> segment(String css) {
        List<Seg> out = new ArrayList<>();
        int i = 0, n = css.length();
        while (i < n) {
            char c = css.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '/' && i + 1 < n && css.charAt(i + 1) == '*') {
                int j = css.indexOf("*/", i + 2); j = j < 0 ? n : j + 2;
                out.add(new Seg(i, j, false, -1));
                i = j;
                continue;
            }
            if (c == '@') {   // at-rule：整块保守保留
                int j = blockEnd(css, i, n);
                out.add(new Seg(i, j, false, -1));
                i = j;
                continue;
            }
            // 规则：选择器 → { 体 }
            int open = css.indexOf('{', i);
            if (open < 0) {   // 游离文本：原样
                out.add(new Seg(i, n, false, -1));
                break;
            }
            int end = blockEndFromOpen(css, open, n);
            out.add(new Seg(i, end, true, open));
            i = end;
        }
        return out;
    }

    private static int blockEnd(String css, int from, int n) {
        int open = css.indexOf('{', from);
        if (open < 0) return n;
        return blockEndFromOpen(css, open, n);
    }

    private static int blockEndFromOpen(String css, int open, int n) {
        int depth = 0;
        for (int p = open; p < n; p++) {
            char c = css.charAt(p);
            if (c == '/' && p + 1 < n && css.charAt(p + 1) == '*') {
                int j = css.indexOf("*/", p + 2);
                p = j < 0 ? n - 1 : j + 1;
                continue;
            }
            if (c == '"' || c == '\'') {
                int j = p + 1;
                while (j < n && css.charAt(j) != c) { if (css.charAt(j) == '\\' && j + 1 < n) j++; j++; }
                p = j;
                continue;
            }
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return p + 1;
        }
        return n;
    }
}
