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
 * simple 自编压缩引擎（稳定优先）：
 * ① 词法状态机切 token（字符串/模板/正则内的 //、/* 不误判）；
 * ② 局部名改写（var/let/const、函数参数、嵌套函数名 → a,b,c…；排除属性/对象键/**成员位**（`?.`、方法简写、getter/setter）/全局面/解构/import/eval 等一切风险面）；
 * ③ 空白压缩：去注释（保首块=许可头）、去缩进/空行、安全并合行（ASI 受限集）、最小空格。
 *
 * 稳定护栏：文件含 eval / import / export / 解构声明 / 模板串 ${} → 整文件放弃改名（仅空白压缩）并记 notes；
 * 任何词法异常 → 返回原文（永不破坏构建）。
 */
public final class SimpleJsMinifier implements JsMinifier {

    @Override public String name() { return "simple"; }

    // ==================== token 模型 ====================

    private static final int IDENT = 1, KEYWORD = 2, NUM = 3, STRING = 4, TEMPLATE = 5,
            REGEX = 6, PUNCT = 7, COMMENT = 8, NL = 9;

    private static final Set<String> KEYWORDS = Set.of(
            "var","let","const","function","return","if","else","for","while","do","switch","case","default",
            "break","continue","throw","try","catch","finally","new","delete","typeof","instanceof","in","of",
            "this","void","with","class","extends","super","static","get","set","async","await","yield",
            "import","export","from","true","false","null","undefined");

    private static final Set<String> RESERVED_SHORT = Set.of(
            "do","if","in","for","new","try","var","let","as","of","this","null","true","false","void","with",
            "case","else","enum","export","extends","finally","import","super","class","const","function",
            "return","switch","throw","typeof","while","break","catch","continue","debugger","default",
            "delete","instanceof","yield","await","static","get","set","async");

    /** 换行前若是这些 token，并合行会改变语义（ASI 受限集） */
    private static final Set<String> RESTRICTED_PREV = Set.of(
            "return","throw","break","continue","yield","await","++","--","true","false","null","this","super",
            ")", "]","}");

    /** 成员位前缀：其后紧跟 `(` 的标识符是方法名/访问器名（`{ name(){} }`、`get name(){}`），改名会破坏对象契约 */
    private static final Set<String> MEMBER_PREV = Set.of("{", ",", "get", "set", "static", "async", "*");

    private static final class Tok {
        final int type;
        final String text;
        Tok(int type, String text) { this.type = type; this.text = text; }
    }

    // ==================== 入口 ====================

    @Override
    public Result minify(String js) {
        List<String> notes = new ArrayList<>();
        if (js == null || js.isEmpty()) return new Result(js == null ? "" : js, notes);
        try {
            List<Tok> toks = lex(js);
            String out = emit(toks, notes);
            return new Result(out, notes);
        } catch (RuntimeException e) {
            notes.add("JSMinifier 词法异常，已回退原文: " + e.getMessage());
            return new Result(js, notes);
        }
    }

    // ==================== 词法 ====================

    private static final Set<String> REGEX_PREV = Set.of(
            "=","(","[","{",",",";",":","!","&","|","?","+","-","*","%","<",">","^","~",
            "return","typeof","instanceof","in","of","new","case","do","else","yield","await",
            "&&","||","??","=>","===","==","!=","!==","<=",">=","++","--");

    private List<Tok> lex(String s) {
        List<Tok> out = new ArrayList<>();
        int i = 0, n = s.length();
        String prevSig = "";
        boolean lineStart = true;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\n') { out.add(new Tok(NL, "\n")); lineStart = true; i++; continue; }
            if (c == '\r') { i++; continue; }
            if (Character.isWhitespace(c)) { i++; continue; }
            // Annex B HTML 注释（仅行首）：<!-- 与 --> 到行尾（含 HTML 风格许可头）
            if (lineStart && s.startsWith("<!--", i)) {
                int j = s.indexOf('\n', i); j = j < 0 ? n : j;
                out.add(new Tok(COMMENT, s.substring(i, j))); i = j; continue;
            }
            if (lineStart && s.startsWith("-->", i)) {
                int j = s.indexOf('\n', i); j = j < 0 ? n : j;
                out.add(new Tok(COMMENT, s.substring(i, j))); i = j; continue;
            }
            // 注释
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                int j = s.indexOf('\n', i); j = j < 0 ? n : j;
                out.add(new Tok(COMMENT, s.substring(i, j))); i = j; continue;
            }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int j = s.indexOf("*/", i + 2); j = j < 0 ? n : j + 2;
                out.add(new Tok(COMMENT, s.substring(i, j))); i = j; continue;
            }
            lineStart = false;   // 其后为真实 token
            // 正则（启发式：前一个有效 token 允许表达式起点）
            if (c == '/' && (prevSig.isEmpty() || REGEX_PREV.contains(prevSig))) {
                int j = i + 1; boolean inCls = false;
                while (j < n) {
                    char d = s.charAt(j);
                    if (d == '\\') { j += 2; continue; }
                    if (d == '[') inCls = true;
                    else if (d == ']') inCls = false;
                    else if (d == '/' && !inCls) { j++; break; }
                    if (d == '\n') { j = n; break; }   // 行内正则异常 → 截断（词法异常护栏）
                    j++;
                }
                while (j < n && Character.isLetter(s.charAt(j))) j++;   // flags
                out.add(new Tok(REGEX, s.substring(i, j))); prevSig = "REGEX"; i = j; continue;
            }
            // 字符串
            if (c == '\'' || c == '"') {
                int j = i + 1;
                while (j < n && s.charAt(j) != c) { if (s.charAt(j) == '\\' && j + 1 < n) j++; j++; }
                if (j < n) j++;
                out.add(new Tok(STRING, s.substring(i, j))); prevSig = "STR"; i = j; continue;
            }
            // 模板串
            if (c == '`') {
                int j = i + 1;
                while (j < n && s.charAt(j) != '`') { if (s.charAt(j) == '\\' && j + 1 < n) j++; j++; }
                if (j < n) j++;
                out.add(new Tok(TEMPLATE, s.substring(i, j))); prevSig = "STR"; i = j; continue;
            }
            // 数字
            if (Character.isDigit(c)) {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || ".eExXbBoO_".indexOf(s.charAt(j)) >= 0)) j++;
                out.add(new Tok(NUM, s.substring(i, j))); prevSig = "NUM"; i = j; continue;
            }
            // 标识符/关键字
            if (Character.isLetter(c) || c == '_' || c == '$') {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_' || s.charAt(j) == '$')) j++;
                String w = s.substring(i, j);
                out.add(new Tok(KEYWORDS.contains(w) ? KEYWORD : IDENT, w));
                prevSig = KEYWORDS.contains(w) ? w : "IDENT";
                i = j;
                continue;
            }
            // 标点（多字符操作符优先）
            String[] ops = {"===","!==",">>>=","<<=",">>=","**=","&&=","||=","??=",">>>","=>","==","!=","<=",">=",
                    "&&","||","??","++","--","+=","-=","*=","/=","%=","&=","|=","^=","**","<<",">>","?.","..."};
            String op = null;
            for (String o : ops) if (s.startsWith(o, i)) { op = o; break; }
            if (op != null) { out.add(new Tok(PUNCT, op)); prevSig = op; i += op.length(); continue; }
            out.add(new Tok(PUNCT, String.valueOf(c))); prevSig = String.valueOf(c); i++;
        }
        return out;
    }

    // ==================== 分析与输出 ====================

    private String emit(List<Tok> toks, List<String> notes) {
        // 1) 全局风险扫描
        boolean skipMangle = false;
        boolean templateCode = false;
        for (Tok t : toks) {
            if (t.type == TEMPLATE && t.text.contains("${")) templateCode = true;
            if (t.type == IDENT && t.text.equals("eval")) skipMangle = true;
            if (t.type == KEYWORD && (t.text.equals("import") || t.text.equals("export"))) skipMangle = true;
        }
        if (templateCode) skipMangle = true;

        // 2) 声明收集（函数参数/嵌套函数名/var·let·const，深度>0）+ 解构检测
        Set<String> declared = new LinkedHashSet<>();
        Set<String> excluded = new HashSet<>();
        int depth = 0;
        for (int k = 0; k < toks.size(); k++) {
            Tok t = toks.get(k);
            if (t.type == PUNCT) {
                if (t.text.equals("{")) depth++;
                else if (t.text.equals("}")) depth = Math.max(0, depth - 1);
                continue;
            }
            if (t.type != KEYWORD) continue;
            if (t.text.equals("function")) {
                // function name ( ... )：参数全部为局部；函数名仅深度>0 时改写
                if (k + 1 < toks.size() && toks.get(k + 1).type == IDENT) {
                    if (depth > 0) declared.add(toks.get(k + 1).text);
                    k++;
                }
                int p = k + 1;
                while (p < toks.size() && !(toks.get(p).type == PUNCT && toks.get(p).text.equals("("))) p++;
                p++;
                while (p < toks.size() && !(toks.get(p).type == PUNCT && toks.get(p).text.equals(")"))) {
                    if (toks.get(p).type == IDENT) declared.add(toks.get(p).text);
                    p++;
                }
                continue;
            }
            if (t.text.equals("var") || t.text.equals("let") || t.text.equals("const")) {
                if (depth > 0) {
                    int p = k + 1;
                    while (p < toks.size()) {
                        Tok q = toks.get(p);
                        if (q.type == PUNCT && (q.text.equals("{") || q.text.equals("["))) { skipMangle = true; break; }   // 解构
                        if (q.type == IDENT) { declared.add(q.text); break; }
                        if (!(q.type == NL)) break;
                        p++;
                    }
                }
                continue;
            }
        }
        if (skipMangle) notes.add("JSMinifier：文件含 eval/import/export/解构/模板插值，已跳过改名（仅空白压缩）");

        // 3) 排除风险用法：属性访问 / 对象键与简写 / 标签 / 深度 0 的裸引用（全局面）
        depth = 0;
        boolean waitFnParen = false, fnParamZone = false;
        int fnParenDepth = 0;
        for (int k = 0; k < toks.size(); k++) {
            Tok t = toks.get(k);
            if (t.type == PUNCT) {
                if (t.text.equals("{")) depth++;
                else if (t.text.equals("}")) depth = Math.max(0, depth - 1);
                if (waitFnParen && t.text.equals("(")) { waitFnParen = false; fnParamZone = true; fnParenDepth = 1; continue; }
                if (fnParamZone && t.text.equals("(")) fnParenDepth++;
                if (fnParamZone && t.text.equals(")")) { if (--fnParenDepth == 0) fnParamZone = false; }
                continue;
            }
            if (t.type == KEYWORD && t.text.equals("function")) { waitFnParen = true; continue; }
            if (t.type != IDENT || !declared.contains(t.text)) continue;
            String prev = k > 0 ? toks.get(k - 1).text : "";
            String next = k + 1 < toks.size() ? toks.get(k + 1).text : "";
            boolean inObject = depth > 0 && (prev.equals("{") || prev.equals(","));
            if (prev.equals(".") || prev.equals("?.")) { excluded.add(t.text); continue; }   // obj.name / obj?.name（`?.` 是单个 token，曾漏排除）
            if (next.equals(":")) { excluded.add(t.text); continue; }                   // 键 / 标签
            if (inObject && (next.equals(",") || next.equals("}"))) { excluded.add(t.text); continue; }  // 简写键
            if (next.equals("(") && MEMBER_PREV.contains(prev) && bodyFollows(toks, k + 1)) { excluded.add(t.text); continue; }   // 成员位：方法简写 / getter / setter（须跟函数体，避免误伤块首普通调用）
            if (depth == 0 && !fnParamZone) { excluded.add(t.text); continue; }         // 全局面引用（同名全局绑定）
        }

        // 4) 改名表
        Map<String, String> rename = new HashMap<>();
        if (!skipMangle) {
            Set<String> allNames = new HashSet<>();
            for (Tok t : toks) if (t.type == IDENT) allNames.add(t.text);
            List<String> cands = new ArrayList<>(declared);
            cands.removeAll(excluded);
            cands.sort(Comparator.comparingInt(String::length).reversed());
            Iterator<String> gen = new ShortNameGen(allNames);
            for (String c : cands) if (gen.hasNext()) rename.put(c, gen.next());
        }

        // 5) 输出
        StringBuilder out = new StringBuilder();
        String prevSig = "";
        boolean headerZone = true;      // 首个真实 token 之前的连续注释 = 许可头，全部原样保留
        boolean pendingSpace = false;
        for (int k = 0; k < toks.size(); k++) {
            Tok t = toks.get(k);
            if (t.type == COMMENT) {
                if (headerZone) {
                    out.append(t.text).append('\n');
                    prevSig = "COMMENT";
                }
                continue;
            }
            if (t.type == NL) {
                pendingSpace = false;
                if (headerZone) continue;
                Tok next = nextSig(toks, k);
                if (prevSig.isEmpty() || prevSig.equals("COMMENT")) continue;    // 行首/头注释后
                if (next != null && canJoin(prevSig, next)) continue;            // 安全并合：不加换行不加空格
                out.append('\n');
                prevSig = "\n";
                continue;
            }
            if (headerZone) headerZone = false;
            String text = t.text;
            if (t.type == IDENT) {
                String r = rename.get(text);
                if (r != null) text = r;
            }
            boolean identLike = t.type == IDENT || t.type == KEYWORD || t.type == NUM
                    || t.type == STRING || t.type == TEMPLATE || t.type == REGEX;
            if (pendingSpace || needSpace(prevSig, text, identLike)) out.append(' ');
            out.append(text);
            pendingSpace = false;
            prevSig = text;
        }
        return out.toString().trim();
    }

    /** 换行并合的安全判定：prev 非 ASI 受限，且 next 不是表达式续行起点 */
    private static boolean canJoin(String prev, Tok next) {
        if (RESTRICTED_PREV.contains(prev)) return false;
        if (prev.matches("[A-Za-z0-9_$]+") || prev.equals("STR") || prev.equals("REGEX") || prev.equals("NUM")
                || prev.equals("true") || prev.equals("false") || prev.equals("null")
                || prev.equals("this") || prev.equals("super") || prev.equals("++") || prev.equals("--"))
            return false;
        String nt = next.text;
        if (nt.startsWith("(") || nt.startsWith("[") || nt.startsWith("`")) return false;
        if (nt.startsWith("+") || nt.startsWith("-") || nt.startsWith("/") || nt.startsWith("*")
                || nt.startsWith("%") || nt.startsWith(".") || nt.startsWith("&") || nt.startsWith("|")
                || nt.startsWith("^") || nt.startsWith("?") || nt.startsWith("<") || nt.startsWith(">")
                || nt.startsWith("=") || nt.startsWith("!") || nt.startsWith("~")) return false;
        return true;
    }

    /** parenIdx = `(` 的下标：按圆括号配对找 `)`，再看其后第一个有效 token 是否为 `{`——
     *  这是"对象方法简写 / 类方法"的特征；普通函数调用（`{ doSearch(x); }`）后面是 `;`/`)`，不该被排除。 */
    private static boolean bodyFollows(List<Tok> toks, int parenIdx) {
        int depth = 0;
        for (int p = parenIdx; p < toks.size(); p++) {
            Tok t = toks.get(p);
            if (t.type != PUNCT) continue;
            if (t.text.equals("(")) depth++;
            else if (t.text.equals(")")) {
                if (--depth == 0) {
                    for (int q = p + 1; q < toks.size(); q++) {
                        if (toks.get(q).type == NL || toks.get(q).type == COMMENT) continue;
                        return toks.get(q).type == PUNCT && toks.get(q).text.equals("{");
                    }
                    return false;
                }
            }
        }
        return false;
    }

    private static boolean needSpace(String prev, String text, boolean identLike) {
        if (prev.isEmpty() || prev.equals("\n") || prev.equals("COMMENT")) return false;
        if (!identLike) return false;
        if (prev.equals("(") || prev.equals("[") || prev.equals("{") || prev.equals(",") || prev.equals(";")
                || prev.equals(":") || prev.equals("?") || prev.equals("!") || prev.equals(".")
                || prev.equals("?.")) return false;   // 点号与可选链后不补空格（`obj?. a` 虽合法但难看）
        if (prev.equals("+") && text.equals("+")) return true;    // a + +b
        if (prev.equals("-") && text.equals("-")) return true;
        if (prev.equals("+") && text.startsWith("++")) return true;
        if (prev.equals("-") && text.startsWith("--")) return true;
        return true;
    }

    private static Tok nextSig(List<Tok> toks, int k) {
        for (int p = k + 1; p < toks.size(); p++)
            if (toks.get(p).type != COMMENT && toks.get(p).type != NL) return toks.get(p);
        return null;
    }

    /** 短名生成器：a..z, aa..az, ba..bz…；跳过保留字与文件中已存在名 */
    private static final class ShortNameGen implements Iterator<String> {
        private final Set<String> used;
        private int i = 0;
        ShortNameGen(Set<String> used) { this.used = new HashSet<>(used); }
        @Override public boolean hasNext() { return i < 26 * 26; }
        @Override public String next() {
            while (true) {
                String s = i < 26 ? String.valueOf((char) ('a' + i))
                        : String.valueOf((char) ('a' + i / 26 - 1)) + (char) ('a' + i % 26);
                i++;
                if (!RESERVED_SHORT.contains(s) && !used.contains(s)) { used.add(s); return s; }
            }
        }
    }
}
