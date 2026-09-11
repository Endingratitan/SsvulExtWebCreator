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
 * CSS 同选择器去重（继承链串联场景）：规范化选择器文本相同 → **后到胜，前驱整块删除**。
 *
 * **这是既定特性、不是等价变换**：后规则未声明的属性会随前驱一起消失，所以子 div 用同名选择器时
 * 必须把需要的声明全部重写（见 docs/UserWrite/div-guide.md §5）。省流量的收益优先于等价性。
 * at-rule（@media/@keyframes 等）整块保守跳过；无块 at-rule（@import/@charset/@layer a,b;）以 `;` 为界，
 * 否则会把紧随其后的一条规则吞进 at-rule 段、使其不参与去重。
 * keepRemovedAsComments=true 时被删规则以注释回插（* / 转义）；取原文必须用**输入偏移**（输出偏移在删除后已漂移）。
 */
public final class CssDeduper {

    private CssDeduper() {}

    public static String dedup(String css, boolean keepRemovedAsComments) {
        List<Seg> segs = segment(css);
        Map<String, int[]> last = new HashMap<>();       // 规范化选择器 → {输出start, 输出end, 输入start, 输入end}
        StringBuilder out = new StringBuilder(css.length());
        for (Seg s : segs) {
            if (!s.rule) { out.append(css, s.start, s.end); continue; }
            String sel = normalize(css.substring(s.start, s.bodyStart));
            int[] prev = last.put(sel, new int[]{out.length(), out.length(), s.start, s.end});   // 占位，稍后填
            if (prev != null) {
                // 前驱区间置空（保留注释位）；取被删原文用输入偏移 prev[2..3]，替换区间用输出偏移 prev[0..1]
                if (keepRemovedAsComments) {
                    String old = css.substring(prev[2], prev[3]).replace("*/", "* /");
                    String ins = "/* ssvul-css-dedup: removed (overridden by later same selector)\n" + old + "\n*/\n";
                    out.replace(prev[0], prev[1], ins);
                    adjust(last, prev[1], ins.length() - (prev[1] - prev[0]));
                } else {
                    out.replace(prev[0], prev[1], "");
                    adjust(last, prev[1], -(prev[1] - prev[0]));
                }
            }
            int start = out.length();
            out.append(css, s.start, s.end);
            last.put(sel, new int[]{start, out.length(), s.start, s.end});
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

    /** at-rule 段边界：带块（@media/@keyframes…）到配对 `}`；无块（@import/@charset/@layer a,b;）到 `;`。
     *  扫描**跳过注释与引号**——否则 `@import url("a;b.css");` 里引号内的 `;` 会被误判成段边界
     *  （引号内的 `{`/`}` 同理）。谁先出现用谁：块 at-rule 的条件区里不会出现裸 `;`。 */
    private static int blockEnd(String css, int from, int n) {
        for (int p = from; p < n; p++) {
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
            if (c == ';') return p + 1;
            if (c == '{') return blockEndFromOpen(css, p, n);
        }
        return n;
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
