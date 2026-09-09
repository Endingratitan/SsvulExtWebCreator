/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.WebMinify.css;

/**
 * CSS 空白压缩（安全档）：去注释（保留首块=许可头）、折叠空白、去 { } : ; , 两侧多余空格；
 * 字符串内不动。CSS 无 ASI 问题，比 JS 压缩简单得多。
 */
public final class CssMinifier {

    private CssMinifier() {}

    public static String minify(String css) {
        StringBuilder out = new StringBuilder(css.length());
        int i = 0, n = css.length();
        boolean headerZone = true;       // 首个真实字符前的注释 = 许可头，保留
        boolean pendingWs = false;
        String prev = "";
        while (i < n) {
            char c = css.charAt(i);
            if (c == '/' && i + 1 < n && css.charAt(i + 1) == '*') {
                int j = css.indexOf("*/", i + 2);
                j = j < 0 ? n : j + 2;
                if (headerZone) out.append(css, i, j).append('\n');
                i = j;
                continue;
            }
            if (Character.isWhitespace(c)) { pendingWs = out.length() > 0; i++; continue; }
            headerZone = false;
            if (pendingWs && needSpace(prev, c)) out.append(' ');
            pendingWs = false;
            out.append(c);
            prev = String.valueOf(c);
            if (c == '"' || c == '\'') {   // 字符串原样
                int j = i + 1;
                while (j < n && css.charAt(j) != c) { if (css.charAt(j) == '\\' && j + 1 < n) j++; j++; }
                if (j < n) j++;
                out.append(css, i + 1, j);
                i = j - 1;
                prev = "str";
            }
            i++;
        }
        return out.toString().trim();
    }

    private static boolean needSpace(String prev, char next) {
        if (prev.isEmpty()) return false;
        char p = prev.charAt(0);
        if (next == '{' || next == '}' || next == ':' || next == ';' || next == ',' || next == '>') return false;
        if (p == '{' || p == '}' || p == ':' || p == ';' || p == ',' || p == '>' || p == '(') return false;
        if (next == '(') return p != ':' && p != ',';
        return true;
    }
}
