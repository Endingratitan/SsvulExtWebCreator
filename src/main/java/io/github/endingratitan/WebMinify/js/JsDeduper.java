/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.WebMinify.js;

import java.util.*;

/**
 * JS 消冗余（队列式，仅深度 0，词法可判定）：
 * ① 文件级：token 归一化全同 → 后出现的重复文件丢弃；
 * ② 项级-函数声明：同名**后到即胜**（hoisting 使位置无关），前驱声明整段移除——"子覆写=父实现清除"；
 * ③ 项级-var 语句：`var x = …;` 与**紧邻前驱完全恒等** → 后一句移除（赋值有序执行，恒等才安全）。
 *
 * 护栏：含顶层 import/export 或无法安全判定的结构 → 原样返回（宁可不删）；keepRemovedAsComments=true
 * 时被删片段以注释回插原位置（内部 *​/ 转义），供 minify=-1 调试对比。
 */
public final class JsDeduper {

    public record Result(String code, List<String> notes) {}

    private JsDeduper() {}

    /** 文件级去重：返回去重后的源文件列表（保序） */
    public static List<String> dedupFiles(List<String> sources) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String s : sources) {
            if (seen.add(signatureOf(s))) out.add(s);
        }
        return out;
    }

    public static String signatureOf(String js) {
        StringBuilder sb = new StringBuilder();
        for (Lex.Tok t : Lex.tokens(js)) {
            if (t.type() == Lex.COMMENT) continue;
            if (t.type() == Lex.NL) { if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n'); continue; }
            sb.append(t.text()).append(' ');
        }
        return sb.toString();
    }

    /** 项级去重（对串联后的整体源码；仅深度 0） */
    public static Result dedupItems(String js, boolean keepRemovedAsComments) {
        List<String> notes = new ArrayList<>();
        List<Lex.Tok> toks = Lex.tokens(js);
        if (toks.isEmpty()) return new Result(js, notes);
        for (Lex.Tok t : toks)   // 模块语义/风险护栏：不碰
            if (t.type() == Lex.KEYWORD && (t.text().equals("import") || t.text().equals("export")))
                return new Result(js, notes);

        List<int[]> removed = new ArrayList<>();          // [start,end)
        Map<String, List<int[]>> fnDecls = new LinkedHashMap<>();
        int[] lastVarStmt = null;                          // 紧邻前一 var 语句 [start,end)
        String lastVarSig = null;
        int depth = 0;

        for (int k = 0; k < toks.size(); k++) {
            Lex.Tok t = toks.get(k);
            if (t.type() == Lex.PUNCT) {
                if (t.text().equals("{")) depth++;
                else if (t.text().equals("}")) depth = Math.max(0, depth - 1);
            }
            if (depth != 0) continue;

            // 函数声明（仅语句位：前驱为 无/NL/;/}/表情景）
            if (t.type() == Lex.KEYWORD && t.text().equals("function")) {
                Lex.Tok prev = prevSig(toks, k);
                boolean stmtPos = prev == null || prev.type() == Lex.NL
                        || (prev.type() == Lex.PUNCT && (prev.text().equals(";") || prev.text().equals("}") || prev.text().equals("{")));
                if (!stmtPos) continue;
                int p = k + 1;
                while (p < toks.size() && toks.get(p).type() == Lex.NL) p++;
                if (p >= toks.size() || toks.get(p).type() != Lex.IDENT) continue;
                String name = toks.get(p).text();
                int q = p + 1;
                while (q < toks.size() && toks.get(q).type() == Lex.NL) q++;
                if (q >= toks.size() || toks.get(q).type() != Lex.PUNCT || !toks.get(q).text().equals("(")) continue;
                int bodyOpen = braceOpenAfterParens(toks, q);
                if (bodyOpen < 0) continue;   // 定位不到函数体：整条不参与消冗余（宁可不删，也不能只删 function 关键字留残句）
                int end = matchBrace(toks, bodyOpen);
                int[] range = {toks.get(k).start(), end};
                List<int[]> prevs = fnDecls.get(name);
                if (prevs != null) { removed.addAll(prevs); prevs.clear(); notes.add("函数声明后到胜: " + name); }
                fnDecls.computeIfAbsent(name, x -> new ArrayList<>()).add(range);
                continue;
            }
            // var 语句（紧邻恒等去重）
            if (t.type() == Lex.KEYWORD && t.text().equals("var")) {
                int semi = -1;
                for (int p = k + 1; p < toks.size(); p++) {
                    if (toks.get(p).type() == Lex.PUNCT && toks.get(p).text().equals(";")) { semi = p; break; }
                    if (toks.get(p).type() == Lex.NL) break;   // 不跨行（保守）
                }
                if (semi < 0) continue;
                String sig = sigRange(toks, k, semi + 1);
                if (lastVarSig != null && lastVarSig.equals(sig)
                        && noGap(toks, lastVarStmt[1], toks.get(k).start())) {
                    removed.add(new int[]{toks.get(k).start(), toks.get(semi).end()});
                    notes.add("var 语句紧邻恒等去重");
                }
                lastVarStmt = new int[]{toks.get(k).start(), toks.get(semi).end()};
                lastVarSig = sig;
            }
        }

        if (removed.isEmpty()) return new Result(js, notes);
        return new Result(splice(js, removed, keepRemovedAsComments), notes);
    }

    // ==================== 工具 ====================

    private static Lex.Tok prevSig(List<Lex.Tok> toks, int k) {
        for (int p = k - 1; p >= 0; p--)
            if (toks.get(p).type() != Lex.COMMENT && toks.get(p).type() != Lex.NL) return toks.get(p);
        return null;
    }

    /** 从 ( 位置找到函数体 {：**只按圆括号配对**定位参数区结束（解构参数 {a}/[b]、默认值里的括号都不影响），
     *  再看其后第一个有效 token 是否为 `{`。曾把参数区的 `{` 也计入深度 → 解构参数永远返回 -1。 */
    private static int braceOpenAfterParens(List<Lex.Tok> toks, int parenIdx) {
        int depth = 0;
        for (int p = parenIdx; p < toks.size(); p++) {
            Lex.Tok t = toks.get(p);
            if (t.type() != Lex.PUNCT) continue;
            if (t.text().equals("(")) depth++;
            else if (t.text().equals(")")) {
                if (--depth == 0) {
                    for (int q = p + 1; q < toks.size(); q++) {
                        if (toks.get(q).type() == Lex.NL || toks.get(q).type() == Lex.COMMENT) continue;
                        return toks.get(q).type() == Lex.PUNCT && toks.get(q).text().equals("{") ? q : -1;
                    }
                    return -1;
                }
            }
        }
        return -1;
    }

    /** 从 { token 下标匹配到 } 的**偏移 end** */
    private static int matchBrace(List<Lex.Tok> toks, int openIdx) {
        int depth = 0;
        for (int p = openIdx; p < toks.size(); p++) {
            Lex.Tok t = toks.get(p);
            if (t.type() != Lex.PUNCT) continue;
            if (t.text().equals("{")) depth++;
            else if (t.text().equals("}") && --depth == 0) return t.end();
        }
        return toks.get(toks.size() - 1).end();
    }

    private static String sigRange(List<Lex.Tok> toks, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int p = from; p < to; p++) {
            if (toks.get(p).type() == Lex.COMMENT || toks.get(p).type() == Lex.NL) continue;
            sb.append(toks.get(p).text()).append(' ');
        }
        return sb.toString();
    }

    /** 两段之间只允许注释/空白/换行（紧邻判定） */
    private static boolean noGap(List<Lex.Tok> toks, int startOff, int endOff) {
        for (Lex.Tok t : toks) {
            if (t.start() >= startOff && t.end() <= endOff
                    && t.type() != Lex.COMMENT && t.type() != Lex.NL) return false;
        }
        return true;
    }

    /** 按移除区间重建源码；keep=true 时被删片段以注释回插（* / 转义） */
    private static String splice(String js, List<int[]> removed, boolean keep) {
        removed.sort(Comparator.comparingInt(r -> r[0]));
        StringBuilder out = new StringBuilder(js.length());
        int pos = 0;
        for (int[] r : removed) {
            if (r[1] <= pos) continue;   // 已被前区间覆盖
            int s = Math.max(r[0], pos);
            out.append(js, pos, s);
            if (keep) {
                String body = js.substring(s, r[1]).replace("*/", "* /");
                out.append("/* ssvul-dedup: removed (overridden/duplicate)\n").append(body).append("\n*/\n");
            }
            pos = r[1];
        }
        out.append(js, pos, js.length());
        return out.toString();
    }
}
