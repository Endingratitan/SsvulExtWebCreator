/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * 项目来源: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Integra;

import java.util.*;
import java.util.regex.*;

/**
 * 脚注子系统（包内私有）：定义行解析（掩码法）→ 引用注册 → 编号配对 → 占位符替换 → 脚注区生成。
 * 状态挂在实例上，owner 为 MarkdownRenderer（共享错误/警告/行内渲染）。
 */
class MdFootnotes {

    static final String FN_MARK = "\u0000FN";     // 引用占位符（正文内）
    static final String FND_MARK = "\u0000FXD";   // 定义占位符（inline 模式；不得以 FN_MARK 为前缀）

    static class DefRec {
        final int lineNo;
        final String content;
        Integer num;                       // 最终显示编号（配对后确定）
        DefRec(int lineNo, String content) { this.lineNo = lineNo; this.content = content; }
    }

    static class RefRec {
        final String label;                // "1" 或 "."
        final int lineNo;
        final int seq;                     // 在 refs 中的序号（占位符用）
        int num = -1;                      // 最终显示编号
        DefRec def;                        // 配到的定义
        RefRec(String label, int lineNo, int seq) { this.label = label; this.lineNo = lineNo; this.seq = seq; }
    }

    private final MarkdownRenderer owner;

    boolean inlineFn;                                        // footnote-display=inline
    final Map<Integer, DefRec> explicitDefs = new LinkedHashMap<>();  // 编号 → 定义
    final List<DefRec> lazyDefs = new ArrayList<>();                  // [.^] 定义（按顺序）
    final List<DefRec> allDefs = new ArrayList<>();                   // 全部定义（按出现顺序）
    final Map<Integer, Integer> defLineIdx = new HashMap<>();         // 顶层行索引 → allDefs 下标
    final List<RefRec> refs = new ArrayList<>();                      // 引用记录

    MdFootnotes(MarkdownRenderer owner) { this.owner = owner; }

    /** Phase 0：扫描顶层定义行（掩码记录，不删行以保行号）；重复定义/空内容/编号 0 报错 */
    void parseFootnotes(List<String> lines) {
        Pattern exp = Pattern.compile("^\\[\\^(\\d+)\\]:\\s*(.*)$");
        Pattern lazy = Pattern.compile("^\\[\\.\\^\\]:\\s*(.*)$");
        for (int idx = 0; idx < lines.size(); idx++) {
            String t = MarkdownRenderer.stripIndent(lines.get(idx));
            Matcher m = exp.matcher(t);
            String content = null;
            if (m.matches()) {
                int num = Integer.parseInt(m.group(1));
                content = m.group(2).trim();
                if (num == 0) { owner.error(idx + 1, "脚注编号必须为正整数: [^0]", null, t); continue; }
                if (content.isEmpty()) { owner.error(idx + 1, "脚注定义内容为空", null, t); continue; }
                if (explicitDefs.containsKey(num)) { owner.error(idx + 1, "脚注编号重复定义: [^" + num + "]", null, t); continue; }
                DefRec d = new DefRec(idx + 1, content);
                explicitDefs.put(num, d);
                allDefs.add(d);
                defLineIdx.put(idx, allDefs.size() - 1);
            } else {
                Matcher m2 = lazy.matcher(t);
                if (m2.matches()) {
                    content = m2.group(1).trim();
                    if (content.isEmpty()) { owner.error(idx + 1, "脚注定义内容为空", null, t); continue; }
                    DefRec d = new DefRec(idx + 1, content);
                    lazyDefs.add(d);
                    allDefs.add(d);
                    defLineIdx.put(idx, allDefs.size() - 1);
                }
            }
        }
    }

    /** 行内解析器回调：登记引用并埋占位符 */
    void registerRef(String label, int lineNo, StringBuilder out) {
        RefRec r = new RefRec(label, lineNo, refs.size());
        refs.add(r);
        out.append(FN_MARK).append(r.seq).append('\u0000');
    }

    /** inline 模式：在定义行原位置埋占位符，Phase 3 替换为脚注块 */
    void emitInlineDefMarker(StringBuilder out, int defIdx) {
        out.append(FND_MARK).append(defIdx).append('\u0000');
    }

    /**
     * Phase 3：编号分配（显式配对 → 懒惰池按顺序配 → [^.] 空位补全）→ 替换正文占位符 →
     * end 模式生成文末脚注区（inline 模式返回空串）。
     */
    String finalizeFootnotes(StringBuilder content) {
        // 1) 显式配对（未配对显式引用保留用户编号；[^.] 自动补空位）
        Set<Integer> reserved = new TreeSet<>();
        for (RefRec r : refs) if (!r.label.equals(".")) reserved.add(Integer.parseInt(r.label));
        List<RefRec> unmatchedRefs = new ArrayList<>();
        for (RefRec r : refs) {
            if (!r.label.equals(".")) {
                int n = Integer.parseInt(r.label);
                if (explicitDefs.containsKey(n)) {
                    r.num = n;
                    r.def = explicitDefs.get(n);
                    explicitDefs.get(n).num = n;   // 回填编号（inline 模式就地渲染需要）
                } else {
                    unmatchedRefs.add(r);
                }
            } else {
                unmatchedRefs.add(r);
            }
        }
        Set<Integer> referencedNums = new HashSet<>();
        for (RefRec r : refs) if (r.def != null) referencedNums.add(r.num);
        List<DefRec> unmatchedDefs = new ArrayList<>();
        for (Map.Entry<Integer, DefRec> e : explicitDefs.entrySet()) {
            if (!referencedNums.contains(e.getKey())) unmatchedDefs.add(e.getValue());
        }
        unmatchedDefs.addAll(lazyDefs);
        // 2) 懒惰池按顺序配对
        int k = 0;
        for (RefRec r : unmatchedRefs) {
            if (k < unmatchedDefs.size()) {
                int n = r.label.equals(".") ? nextFree(reserved) : Integer.parseInt(r.label);
                reserved.add(n);
                r.num = n;
                r.def = unmatchedDefs.get(k);
                unmatchedDefs.get(k).num = n;
                k++;
            } else {
                owner.error(r.lineNo, "脚注引用未定义: [^" + r.label + "]",
                        "补写定义行（如 [^" + r.label + "]: 内容）或移除引用", null);
                r.num = -1;
            }
        }
        for (; k < unmatchedDefs.size(); k++) {
            DefRec d = unmatchedDefs.get(k);
            String msg = "脚注定义未被引用（多余第 " + (k - unmatchedRefs.size() + 1) + " 个）: " + d.content;
            if (owner.isStrict()) owner.error(d.lineNo, msg, "请删除或补引用", d.content);
            else owner.warn(d.lineNo, msg);
            d.num = -1;
        }
        // 3) 替换引用占位符
        Map<Integer, Integer> occ = new HashMap<>();
        int idx;
        while ((idx = content.indexOf(FN_MARK)) >= 0) {
            int end = content.indexOf("\u0000", idx + FN_MARK.length());
            int seq = Integer.parseInt(content.substring(idx + FN_MARK.length(), end));
            RefRec r = refs.get(seq);
            String html;
            if (r.num < 0) {
                html = "[^" + r.label + "]";   // 未定义 → 原样字面
            } else {
                int c = occ.merge(r.num, 1, Integer::sum);
                String p = owner.anchorPrefix;   // div 前缀：多 md div 页面脚注 id 不重复（fnref-N → div-1-fnref-N）
                String id = c == 1 ? p + "fnref-" + r.num : p + "fnref-" + r.num + "-" + c;
                html = "<sup id=\"" + id + "\"><a href=\"#" + p + "fn-" + r.num + "\">" + r.num + "</a></sup>";
            }
            content.replace(idx, end + 1, html);
        }
        // 4) 替换定义占位符（inline 模式）
        while ((idx = content.indexOf(FND_MARK)) >= 0) {
            int end = content.indexOf("\u0000", idx + FND_MARK.length());
            int di = Integer.parseInt(content.substring(idx + FND_MARK.length(), end));
            DefRec d = allDefs.get(di);
            String html;
            if (d.num != null && d.num > 0) {
                String p = owner.anchorPrefix;
                html = "<div class=\"md-footnote\" id=\"" + p + "fn-" + d.num + "\">" + renderDefContent(d)
                     + " <a href=\"#" + p + "fnref-" + d.num + "\" class=\"md-fn-back\">↩</a></div>";
            } else {
                html = "<div class=\"md-footnote\">" + renderDefContent(d) + "</div>";
            }
            content.replace(idx, end + 1, html);
        }
        // 5) end 模式脚注区（按编号升序）
        if (inlineFn) return "";
        Map<Integer, DefRec> byNum = new TreeMap<>();
        for (RefRec r : refs) if (r.num > 0 && r.def != null) byNum.putIfAbsent(r.num, r.def);
        if (byNum.isEmpty()) return "";
        StringBuilder fn = new StringBuilder("<div class=\"md-footnotes\">\n<hr>\n<ol>\n");
        for (Map.Entry<Integer, DefRec> e : byNum.entrySet()) {
            int n = e.getKey();
            String p = owner.anchorPrefix;
            fn.append("<li id=\"").append(p).append("fn-").append(n).append("\">").append(renderDefContent(e.getValue()))
              .append(" <a href=\"#").append(p).append("fnref-").append(n).append("\" class=\"md-fn-back\">↩</a></li>\n");
        }
        fn.append("</ol>\n</div>\n");
        return fn.toString();
    }

    private String renderDefContent(DefRec d) {
        owner.inFootDef = true;
        try {
            return owner.inline(d.content, d.lineNo, 0);
        } finally {
            owner.inFootDef = false;
        }
    }

    private static int nextFree(Set<Integer> used) {
        int n = 1;
        while (used.contains(n)) n++;
        return n;
    }
}
