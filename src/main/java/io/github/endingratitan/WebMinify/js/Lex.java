/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.WebMinify.js;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 共享 JS 词法器（供 SimpleJsMinifier/JsDeduper/JsDeclScan 复用）：
 * 状态机识别 标识符/关键字/数字/字符串/模板串/正则/标点/注释/换行，token 携带原文偏移。
 * 注释含 Annex B 行首 HTML 注释（<!-- 与 -->，许可头形态）。
 */
public final class Lex {

    public static final int IDENT = 1, KEYWORD = 2, NUM = 3, STRING = 4, TEMPLATE = 5,
            REGEX = 6, PUNCT = 7, COMMENT = 8, NL = 9;

    public record Tok(int type, String text, int start, int end) {}

    private static final Set<String> KEYWORDS = Set.of(
            "var","let","const","function","return","if","else","for","while","do","switch","case","default",
            "break","continue","throw","try","catch","finally","new","delete","typeof","instanceof","in","of",
            "this","void","with","class","extends","super","static","get","set","async","await","yield",
            "import","export","from","true","false","null","undefined");

    private static final Set<String> REGEX_PREV = Set.of(
            "=","(","[","{",",",";",":","!","&","|","?","+","-","*","%","<",">","^","~",
            "return","typeof","instanceof","in","of","new","case","do","else","yield","await",
            "&&","||","??","=>","===","==","!=","!==","<=",">=","++","--");

    private static final String[] OPS = {"===","!==",">>>=","<<=",">>=","**=","&&=","||=","??=",">>>",
            "=>","==","!=","<=",">=","&&","||","??","++","--","+=","-=","*=","/=","%=","&=","|=","^=",
            "**","<<",">>","?.","..."};

    private Lex() {}

    public static boolean isKeyword(String w) { return KEYWORDS.contains(w); }

    public static List<Tok> tokens(String s) {
        List<Tok> out = new ArrayList<>();
        int i = 0, n = s.length();
        String prevSig = "";
        boolean lineStart = true;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\n') { out.add(new Tok(NL, "\n", i, i + 1)); lineStart = true; i++; continue; }
            if (c == '\r') { i++; continue; }
            if (Character.isWhitespace(c)) { i++; continue; }
            if (lineStart && s.startsWith("<!--", i)) {
                int j = s.indexOf('\n', i); j = j < 0 ? n : j;
                out.add(new Tok(COMMENT, s.substring(i, j), i, j)); i = j; continue;
            }
            if (lineStart && s.startsWith("-->", i)) {
                int j = s.indexOf('\n', i); j = j < 0 ? n : j;
                out.add(new Tok(COMMENT, s.substring(i, j), i, j)); i = j; continue;
            }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                int j = s.indexOf('\n', i); j = j < 0 ? n : j;
                out.add(new Tok(COMMENT, s.substring(i, j), i, j)); i = j; continue;
            }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int j = s.indexOf("*/", i + 2); j = j < 0 ? n : j + 2;
                out.add(new Tok(COMMENT, s.substring(i, j), i, j)); i = j; continue;
            }
            lineStart = false;
            if (c == '/' && (prevSig.isEmpty() || REGEX_PREV.contains(prevSig))) {
                int j = i + 1; boolean inCls = false;
                while (j < n) {
                    char d = s.charAt(j);
                    if (d == '\\') { j += 2; continue; }
                    if (d == '[') inCls = true;
                    else if (d == ']') inCls = false;
                    else if (d == '/' && !inCls) { j++; break; }
                    if (d == '\n') { j = n; break; }
                    j++;
                }
                while (j < n && Character.isLetter(s.charAt(j))) j++;
                out.add(new Tok(REGEX, s.substring(i, j), i, j)); prevSig = "REGEX"; i = j; continue;
            }
            if (c == '\'' || c == '"') {
                int j = i + 1;
                while (j < n && s.charAt(j) != c) { if (s.charAt(j) == '\\' && j + 1 < n) j++; j++; }
                if (j < n) j++;
                out.add(new Tok(STRING, s.substring(i, j), i, j)); prevSig = "STR"; i = j; continue;
            }
            if (c == '`') {
                int j = i + 1;
                while (j < n && s.charAt(j) != '`') { if (s.charAt(j) == '\\' && j + 1 < n) j++; j++; }
                if (j < n) j++;
                out.add(new Tok(TEMPLATE, s.substring(i, j), i, j)); prevSig = "STR"; i = j; continue;
            }
            if (Character.isDigit(c)) {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || ".eExXbBoO_".indexOf(s.charAt(j)) >= 0)) j++;
                out.add(new Tok(NUM, s.substring(i, j), i, j)); prevSig = "NUM"; i = j; continue;
            }
            if (Character.isLetter(c) || c == '_' || c == '$') {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_' || s.charAt(j) == '$')) j++;
                String w = s.substring(i, j);
                out.add(new Tok(KEYWORDS.contains(w) ? KEYWORD : IDENT, w, i, j));
                prevSig = KEYWORDS.contains(w) ? w : "IDENT";
                i = j;
                continue;
            }
            String op = null;
            for (String o : OPS) if (s.startsWith(o, i)) { op = o; break; }
            if (op != null) { out.add(new Tok(PUNCT, op, i, i + op.length())); prevSig = op; i += op.length(); continue; }
            out.add(new Tok(PUNCT, String.valueOf(c), i, i + 1)); prevSig = String.valueOf(c); i++;
        }
        return out;
    }
}
