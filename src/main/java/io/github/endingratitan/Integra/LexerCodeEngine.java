/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Integra;

import io.github.endingratitan.Integra.MarkdownIntergra.MarkdownRenderer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * simple 构建期词法引擎（技术验证器）。
 *
 * 定位：验证两条链路——① 构建期 token 输出与 canonical 类 tk-* / CSS 变量层贯通；② 引擎链的按语言回退。
 * 范围：仅词法（L1）——注释/字符串/数字/关键字/内建/操作符/标点 + html 标签模式；无语法树（L2 结构感知、L3 语义留待演进）。
 * 已知局限：正则内部、模板字符串 ${} 插值内部、bash $() 内部按普通文本/字符串粗处理（文档已注明）。
 *
 * 扩展（U1 词表）：构造时传入 userWords（lang → word → tokenid）与内置表合并、同词覆盖；
 * 用户声明的语言即使不在内置集合也进入 accepts。
 */
public class LexerCodeEngine implements CodeEngine {

    /** canonical token id 全集（与 token-map.js / CSS 变量层对齐）；U1 词表校验用 */
    static final Set<String> TOKEN_IDS = Set.of(
            "kw", "str", "num", "com", "builtin", "cls", "fn", "fndef", "var",
            "param", "attr", "tag", "meta", "op", "punct", "tpl", "regex");

    private static final Map<String, Map<String, String>> BUILTIN = buildBuiltin();

    /** 语言名 → 词表（word → tokenid）；含别名与 U1 扩展 */
    private final Map<String, Map<String, String>> words = new LinkedHashMap<>();

    public LexerCodeEngine() { this(Map.of()); }

    public LexerCodeEngine(Map<String, Map<String, String>> userWords) {
        for (Map.Entry<String, Map<String, String>> e : BUILTIN.entrySet())
            words.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
        for (Map.Entry<String, Map<String, String>> e : userWords.entrySet())
            words.computeIfAbsent(e.getKey(), k -> new LinkedHashMap<>()).putAll(e.getValue());
    }

    @Override public String name() { return "simple"; }

    /** 仅接受内置/用户词表覆盖的语言；未知或空语言拒接（交给链的下一环） */
    @Override public boolean accepts(String language) { return words.containsKey(language); }

    @Override
    public String renderCode(String code, String language) {
        Map<String, String> table = words.get(language);
        if (table == null) return MarkdownRenderer.escapeHtml(code);   // accepts 已拦，防御性兜底
        String s = code == null ? "" : code;
        boolean html = "html".equals(language);
        boolean py = "python".equals(language) || "py".equals(language);
        boolean sh = "bash".equals(language) || "sh".equals(language);
        StringBuilder out = new StringBuilder(s.length() + 64);
        StringBuilder plain = new StringBuilder();
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            // 1) 注释
            if (!html) {
                if (s.startsWith("//", i)) { flush(plain, out); i = consumeLine(out, s, i, n); continue; }
                if (s.startsWith("/*", i)) { flush(plain, out); i = consumeTo(out, s, i, n, "*/", "com"); continue; }
                if (c == '#' && (py || sh)) { flush(plain, out); i = consumeLine(out, s, i, n); continue; }
            } else {
                if (s.startsWith("<!--", i)) { flush(plain, out); i = consumeTo(out, s, i, n, "-->", "com"); continue; }
                if (c == '<' && i + 1 < n && (Character.isLetter(s.charAt(i + 1)) || s.charAt(i + 1) == '/')) {
                    flush(plain, out);
                    int j = s.indexOf('>', i);
                    j = j < 0 ? n : j + 1;
                    out.append(renderHtmlTag(s.substring(i, j)));
                    i = j;
                    continue;
                }
            }
            // 2) python 三引号
            if (py && (s.startsWith("\"\"\"", i) || s.startsWith("'''", i))) {
                flush(plain, out);
                String q = s.substring(i, i + 3);
                int j = s.indexOf(q, i + 3);
                j = j < 0 ? n : j + 3;
                span(out, "str", s.substring(i, j));
                i = j;
                continue;
            }
            // 3) 字符串（js 模板串 → tpl）
            if (c == '\'' || c == '"' || (c == '`' && !html)) {
                flush(plain, out);
                String tk = c == '`' ? "tpl" : "str";
                int j = i + 1;
                while (j < n && s.charAt(j) != c) {
                    if (s.charAt(j) == '\\' && j + 1 < n) j++;
                    j++;
                }
                if (j < n) j++;
                span(out, tk, s.substring(i, j));
                i = j;
                continue;
            }
            // 4) 数字
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1)))) {
                flush(plain, out);
                int j = i;
                if (c == '0' && i + 1 < n && "xXbB".indexOf(s.charAt(i + 1)) >= 0) {
                    j += 2;
                    while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) j++;
                } else {
                    boolean dot = false, exp = false;
                    while (j < n) {
                        char d = s.charAt(j);
                        if (Character.isDigit(d) || d == '_') j++;
                        else if (d == '.' && !dot && !exp) { dot = true; j++; }
                        else if ((d == 'e' || d == 'E') && !exp
                                && j + 1 < n && (Character.isDigit(s.charAt(j + 1))
                                || ((s.charAt(j + 1) == '+' || s.charAt(j + 1) == '-')
                                && j + 2 < n && Character.isDigit(s.charAt(j + 2))))) {
                            exp = true; j++;
                            if (s.charAt(j) == '+' || s.charAt(j) == '-') j++;
                        } else break;
                    }
                }
                span(out, "num", s.substring(i, j));
                i = j;
                continue;
            }
            // 5) bash 变量 $name
            if (sh && c == '$' && i + 1 < n && (Character.isLetter(s.charAt(i + 1)) || s.charAt(i + 1) == '_')) {
                flush(plain, out);
                int j = i + 1;
                while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) j++;
                span(out, "var", s.substring(i, j));
                i = j;
                continue;
            }
            // 6) 标识符
            if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) j++;
                String w = s.substring(i, j);
                String tok = table.get(w);
                if (tok != null) { flush(plain, out); span(out, tok, w); }
                else plain.append(w);
                i = j;
                continue;
            }
            // 7) 操作符（多字符优先）
            String op = matchOp(s, i);
            if (op != null) { flush(plain, out); span(out, "op", op); i += op.length(); continue; }
            // 8) 标点
            if ("()[]{};,.:".indexOf(c) >= 0) { flush(plain, out); span(out, "punct", String.valueOf(c)); i++; continue; }
            plain.append(c);
            i++;
        }
        flush(plain, out);
        return out.toString();
    }

    // ==================== 工具 ====================

    private static int consumeLine(StringBuilder out, String s, int i, int n) {
        int j = s.indexOf('\n', i);
        j = j < 0 ? n : j;
        span(out, "com", s.substring(i, j));
        return j;
    }

    private static int consumeTo(StringBuilder out, String s, int i, int n, String end, String tk) {
        int j = s.indexOf(end, i + end.length() - 1);
        j = j < 0 ? n : j + end.length();
        span(out, tk, s.substring(i, j));
        return j;
    }

    private static void span(StringBuilder out, String tk, String text) {
        out.append("<span class=\"tk-").append(tk).append("\">").append(MarkdownRenderer.escapeHtml(text)).append("</span>");
    }

    private static void flush(StringBuilder plain, StringBuilder out) {
        if (plain.length() > 0) {
            out.append(MarkdownRenderer.escapeHtml(plain.toString()));
            plain.setLength(0);
        }
    }

    private static final String[] OPS = {
            "<<=", ">>=", ">>>=", "===", "!==", "**=", "//=", "&&=", "||=", "??=", "...",
            ">>>", "<<", ">>", "<=", ">=", "==", "!=", "&&", "||", "++", "--",
            "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "->", "=>", "::", "**", "//", "??", "?.",
            "+", "-", "*", "/", "%", "=", "<", ">", "!", "&", "|", "^", "~", "?"};

    private static String matchOp(String s, int i) {
        for (String op : OPS) if (s.startsWith(op, i)) return op;
        return null;
    }

    /** html 标签段渲染：&lt;name attr="str"&gt; → punct/tag/attr/op/str/punct */
    private static String renderHtmlTag(String t) {
        StringBuilder out = new StringBuilder();
        int i = 0, n = t.length();
        span(out, "punct", "<");
        i = 1;
        if (i < n && t.charAt(i) == '/') { span(out, "punct", "/"); i++; }
        int j = i;
        while (j < n && (Character.isLetterOrDigit(t.charAt(j)) || t.charAt(j) == '-')) j++;
        if (j > i) span(out, "tag", t.substring(i, j));
        i = j;
        while (i < n) {
            char c = t.charAt(i);
            if (c == '>') { span(out, "punct", ">"); i++; break; }
            if (Character.isWhitespace(c)) { out.append(c); i++; continue; }
            if (Character.isLetter(c) || c == '-') {
                j = i;
                while (j < n && (Character.isLetterOrDigit(t.charAt(j)) || t.charAt(j) == '-' || t.charAt(j) == ':')) j++;
                span(out, "attr", t.substring(i, j));
                i = j;
                continue;
            }
            if (c == '=') { span(out, "op", "="); i++; continue; }
            if (c == '"' || c == '\'') {
                j = i + 1;
                while (j < n && t.charAt(j) != c) j++;
                if (j < n) j++;
                span(out, "str", t.substring(i, j));
                i = j;
                continue;
            }
            out.append(MarkdownRenderer.escapeHtml(c));
            i++;
        }
        return out.toString();
    }

    // ==================== 内置词表 ====================

    private static Map<String, String> w(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private static Map<String, Map<String, String>> buildBuiltin() {
        Map<String, Map<String, String>> b = new LinkedHashMap<>();

        Map<String, String> java = w(
                "abstract","kw","assert","kw","boolean","kw","break","kw","byte","kw","case","kw",
                "catch","kw","char","kw","class","kw","const","kw","continue","kw","default","kw",
                "do","kw","double","kw","else","kw","enum","kw","extends","kw","final","kw","finally","kw",
                "float","kw","for","kw","goto","kw","if","kw","implements","kw","import","kw",
                "instanceof","kw","int","kw","interface","kw","long","kw","native","kw","new","kw",
                "package","kw","private","kw","protected","kw","public","kw","return","kw","short","kw",
                "static","kw","strictfp","kw","super","kw","switch","kw","synchronized","kw","this","kw",
                "throw","kw","throws","kw","transient","kw","try","kw","void","kw","volatile","kw",
                "while","kw","var","kw","record","kw","sealed","kw","permits","kw","yield","kw",
                "true","num","false","num","null","num",
                "String","cls","Integer","cls","Long","cls","Double","cls","Boolean","cls","Math","cls","Object","cls",
                "System","builtin","println","builtin","print","builtin");
        b.put("java", java);

        Map<String, String> js = w(
                "async","kw","await","kw","break","kw","case","kw","catch","kw","class","kw","const","kw",
                "continue","kw","debugger","kw","default","kw","delete","kw","do","kw","else","kw","export","kw",
                "extends","kw","finally","kw","for","kw","function","kw","get","kw","if","kw","import","kw",
                "in","kw","instanceof","kw","let","kw","new","kw","of","kw","return","kw","set","kw","static","kw",
                "super","kw","switch","kw","this","kw","throw","kw","try","kw","typeof","kw","var","kw",
                "void","kw","while","kw","with","kw","yield","kw",
                "true","num","false","num","null","num","undefined","num","NaN","num","Infinity","num",
                "console","builtin","document","builtin","window","builtin","Math","builtin","JSON","builtin",
                "Promise","builtin","Array","builtin","Object","builtin","String","builtin","Number","builtin",
                "Boolean","builtin","parseInt","builtin","parseFloat","builtin","isNaN","builtin","setTimeout","builtin");
        b.put("javascript", js);
        b.put("js", js);

        Map<String, String> py = w(
                "and","kw","as","kw","assert","kw","async","kw","await","kw","break","kw","class","kw",
                "continue","kw","def","kw","del","kw","elif","kw","else","kw","except","kw","finally","kw",
                "for","kw","from","kw","global","kw","if","kw","import","kw","in","kw","is","kw","lambda","kw",
                "nonlocal","kw","not","kw","or","kw","pass","kw","raise","kw","return","kw","try","kw",
                "while","kw","with","kw","yield","kw","match","kw","case","kw",
                "True","num","False","num","None","num",
                "print","builtin","len","builtin","range","builtin","str","builtin","int","builtin",
                "float","builtin","list","builtin","dict","builtin","set","builtin","tuple","builtin",
                "open","builtin","isinstance","builtin","super","builtin","type","builtin",
                "self","param");
        b.put("python", py);
        b.put("py", py);

        Map<String, String> c = w(
                "auto","kw","break","kw","case","kw","char","kw","const","kw","continue","kw","default","kw",
                "do","kw","double","kw","else","kw","enum","kw","extern","kw","float","kw","for","kw",
                "goto","kw","if","kw","inline","kw","int","kw","long","kw","register","kw","restrict","kw",
                "return","kw","short","kw","signed","kw","sizeof","kw","static","kw","struct","kw","switch","kw",
                "typedef","kw","union","kw","unsigned","kw","void","kw","volatile","kw","while","kw",
                "NULL","num",
                "printf","builtin","scanf","builtin","malloc","builtin","free","builtin","size_t","builtin");
        b.put("c", c);

        Map<String, String> cpp = new LinkedHashMap<>(c);
        for (String[] e : new String[][]{
                {"class","kw"},{"namespace","kw"},{"template","kw"},{"typename","kw"},{"using","kw"},
                {"new","kw"},{"delete","kw"},{"this","kw"},{"virtual","kw"},{"override","kw"},
                {"final","kw"},{"public","kw"},{"private","kw"},{"protected","kw"},{"operator","kw"},
                {"friend","kw"},{"constexpr","kw"},{"nullptr","num"},{"try","kw"},{"catch","kw"},
                {"throw","kw"},{"bool","kw"},{"true","num"},{"false","num"},
                {"std","builtin"},{"cout","builtin"},{"cin","builtin"},{"endl","builtin"},
                {"vector","builtin"},{"string","builtin"},{"map","builtin"},{"iostream","builtin"}})
            cpp.put(e[0], e[1]);
        b.put("cpp", cpp);
        b.put("c++", cpp);

        Map<String, String> bash = w(
                "if","kw","then","kw","else","kw","elif","kw","fi","kw","for","kw","while","kw",
                "until","kw","do","kw","done","kw","case","kw","esac","kw","function","kw",
                "select","kw","in","kw","time","kw","coproc","kw",
                "true","num","false","num",
                "echo","builtin","printf","builtin","cd","builtin","ls","builtin","grep","builtin",
                "sed","builtin","awk","builtin","exit","builtin","return","builtin","export","builtin",
                "local","builtin","source","builtin","read","builtin","shift","builtin","set","builtin",
                "unset","builtin","declare","builtin");
        b.put("bash", bash);
        b.put("sh", bash);

        // html 的 tag/attr 在 renderHtmlTag 里按位置渲染，词表仅作 accepts 依据
        b.put("html", w("html","tag"));

        return b;
    }
}
