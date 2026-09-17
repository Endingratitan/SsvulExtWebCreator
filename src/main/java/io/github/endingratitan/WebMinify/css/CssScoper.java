/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.WebMinify.css;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * **CSS 作用域化器**（纯函数：文本进 → 文本出，不读文件、不认识站点模型）。
 *
 * 关系：与同包 {@link CssDeduper}/{@link CssMinifier} 同族（`WebMinify` = 文本变换层）。
 * 用途：注入页面的 md 主题 css（预设 `md/css/md.css`、callout、代码主题）统一**作用域化**到
 * `md-theme-<hash>` 类下，使"同一页里并存多个不同主题"成立且互不污染（`SiteMdThemes` 负责注册/接线）。
 *
 * 规则（每条都有对应测试）：
 * <ul>
 *   <li>选择器列表按顶层逗号拆分，逐个加作用域前缀；`:root`/`html`/`:host` → 改写为作用域类本身（变量不泄漏全页）</li>
 *   <li>首个复合选择器里的 `.md-body` 前缀**剥掉**（于是 `md.wrap=true|false` 都能命中）</li>
 *   <li>`@media`/`@supports`/`@container`/`@layer`/`@scope`/`@document` 内部照常作用域化，at-rule 自身不动</li>
 *   <li>`@keyframes` 内部（`from`/`to`/`50%`）**绝不前缀**——前缀了整段失效</li>
 *   <li>`@font-face`/`@page`/`@property`/`@counter-style`/`@viewport`/`@font-feature-values` 整块原样</li>
 *   <li>未识别的 at-rule 整块原样（计入 {@link Scoped#opaqueAtRules()}，调用方可据此警告）</li>
 *   <li>注释、字符串（`content:"}"`）、`url(...)`（data-URI 可能含 `{}`）都不会打乱结构判定</li>
 *   <li>坏 css（括号不平衡）**不抛异常**：照常返回并置 {@code balanced=false}，由调用方决定警告与处置</li>
 * </ul>
 * 已知取舍：选择器**内部**的注释会被移到规则之前（语义等效，极罕见）。
 */
public final class CssScoper {

    private CssScoper() { }

    /** 作用域化结果：文本 + 统计（供日志/警告）+ 括号是否平衡（false ⇒ 输入 css 本身有问题） */
    public record Scoped(String css, int rules, int rootSelectors, int mdBodyStripped,
                         int opaqueAtRules, boolean balanced) { }

    private static final int M_SEL = 0;      // 选择器 / at-rule 前奏
    private static final int M_KSEL = 1;     // @keyframes 内的关键帧选择器（不前缀）
    private static final int M_DECLS = 2;    // 声明区（含 @font-face 等整块）
    private static final int M_OPAQUE = 3;   // 未识别 at-rule：整块原样

    private static final List<String> DECL_AT = List.of(
            "font-face", "page", "property", "counter-style", "viewport", "-ms-viewport", "font-feature-values");
    private static final List<String> GROUP_AT = List.of(
            "media", "supports", "container", "layer", "scope", "document", "-moz-document", "starting-style");

    public static Scoped scope(String css, String scopeClass) {
        if (css == null || css.isEmpty() || scopeClass == null || scopeClass.isEmpty()) {
            return new Scoped(css == null ? "" : css, 0, 0, 0, 0, true);
        }
        StringBuilder out = new StringBuilder(css.length() + 512);
        StringBuilder pre = new StringBuilder();
        Deque<int[]> frames = new ArrayDeque<>();       // {mode, baseDepth}
        int mode = M_SEL, depth = 0, i = 0, n = css.length();
        int rules = 0, roots = 0, stripped = 0, opaque = 0;

        while (i < n) {
            char c = css.charAt(i);

            if (c == '/' && i + 1 < n && css.charAt(i + 1) == '*') {          // 注释
                int e = css.indexOf("*/", i + 2);
                e = e < 0 ? n : e + 2;
                if (mode == M_KSEL) pre.append(css, i, e); else out.append(css, i, e);
                i = e;
                continue;
            }
            if (c == '"' || c == '\'') {                                      // 字符串（content:"}"）
                int j = i + 1;
                while (j < n) {
                    char d = css.charAt(j);
                    if (d == '\\') j += 2;
                    else if (d == c) { j++; break; }
                    else j++;
                }
                if (mode == M_SEL || mode == M_KSEL) pre.append(css, i, j); else out.append(css, i, j);
                i = j;
                continue;
            }
            if (mode == M_DECLS || mode == M_OPAQUE) {                        // 声明区：原样 + 括号计数
                if ((c == 'u' || c == 'U') && css.regionMatches(true, i, "url(", 0, 4)) {
                    int j = i + 4;
                    char q = 0;
                    while (j < n) {
                        char d = css.charAt(j);
                        if (q != 0) { if (d == '\\') j++; else if (d == q) q = 0; }
                        else if (d == '"' || d == '\'') q = d;
                        else if (d == ')') { j++; break; }
                        j++;
                    }
                    out.append(css, i, j);
                    i = j;
                    continue;
                }
            }

            if (c == '{') {
                if (mode == M_SEL) {
                    String raw = pre.toString();
                    pre.setLength(0);
                    String p = raw.trim();
                    String lead = raw.substring(0, raw.length() - raw.stripLeading().length());
                    if (p.startsWith("@")) {
                        String name = atName(p);
                        if (name.contains("keyframes")) {
                            out.append(p).append('{');
                            frames.push(new int[]{M_KSEL, ++depth});
                            mode = M_KSEL;
                        } else if (DECL_AT.contains(name)) {
                            out.append(p).append('{');
                            frames.push(new int[]{M_DECLS, ++depth});
                            mode = M_DECLS;
                        } else if (GROUP_AT.contains(name)) {
                            out.append(p).append('{');
                            frames.push(new int[]{M_SEL, ++depth});
                            mode = M_SEL;
                        } else {
                            opaque++;
                            out.append(p).append('{');
                            frames.push(new int[]{M_OPAQUE, ++depth});
                            mode = M_OPAQUE;
                        }
                    } else if (!p.isEmpty()) {
                        rules++;
                        out.append(lead).append(scopeSelectorList(p, scopeClass)).append('{');
                        frames.push(new int[]{M_DECLS, ++depth});
                        mode = M_DECLS;
                    } else {
                        out.append(lead).append('{');
                        frames.push(new int[]{M_DECLS, ++depth});
                        mode = M_DECLS;
                    }
                } else if (mode == M_KSEL) {
                    out.append(pre.toString().trim()).append('{');
                    pre.setLength(0);
                    frames.push(new int[]{M_DECLS, ++depth});
                    mode = M_DECLS;
                } else {
                    depth++;
                    out.append('{');
                }
                i++;
                continue;
            }
            if (c == '}') {                                                   // 统一收口：计数 + 弹栈 + 恢复模式
                out.append('}');
                depth--;
                while (!frames.isEmpty() && depth < frames.peek()[1]) frames.pop();
                mode = frames.isEmpty() ? M_SEL : frames.peek()[0];
                pre.setLength(0);
                i++;
                continue;
            }
            if (mode == M_SEL) {
                if (c == ';') {                                               // 无块 at-rule（@import/@charset）
                    String p = pre.toString().trim();
                    pre.setLength(0);
                    if (!p.isEmpty()) out.append(p).append(';');
                } else {
                    pre.append(c);
                }
            } else if (mode == M_KSEL) {
                pre.append(c);
            } else {
                out.append(c);
            }
            i++;
        }
        if (mode == M_SEL && !pre.toString().trim().isEmpty()) {              // EOF 收尾：别把尾部前奏吞掉
            String p = pre.toString().trim();
            out.append(p.startsWith("@") ? p : scopeSelectorList(p, scopeClass));
        }
        boolean balanced = depth == 0 && frames.isEmpty();
        int[] counts = countRootsAndStripped(out, css, scopeClass);
        roots += counts[0];
        stripped += counts[1];
        return new Scoped(out.toString(), rules, roots, stripped, opaque, balanced);
    }

    /** 括号是否平衡（调用方可用它决定降级；`scope` 内部也据此置位） */
    public static boolean balanced(String css) {
        int d = 0;
        boolean inComment = false;
        char q = 0;
        for (int i = 0; css != null && i < css.length(); i++) {
            char c = css.charAt(i);
            if (inComment) { if (c == '*' && i + 1 < css.length() && css.charAt(i + 1) == '/') { inComment = false; i++; } continue; }
            if (q != 0) { if (c == '\\') i++; else if (c == q) q = 0; continue; }
            if (c == '/' && i + 1 < css.length() && css.charAt(i + 1) == '*') { inComment = true; i++; continue; }
            if (c == '"' || c == '\'') { q = c; continue; }
            if (c == '{') d++;
            else if (c == '}') d--;
        }
        return d == 0;
    }

    /** at-rule 名（`@media (...)` → `media`；`@-webkit-keyframes` → `-webkit-keyframes`） */
    private static String atName(String prelude) {
        int i = 1, n = prelude.length();
        StringBuilder sb = new StringBuilder();
        while (i < n) {
            char c = prelude.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-') sb.append(Character.toLowerCase(c));
            else break;
            i++;
        }
        return sb.toString();
    }

    private static String scopeSelectorList(String list, String cls) {
        List<String> parts = splitTopComma(list);
        StringBuilder sb = new StringBuilder(list.length() + parts.size() * (cls.length() + 1));
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(scopeOne(parts.get(i).trim(), cls));
        }
        return sb.toString();
    }

    private static List<String> splitTopComma(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        char q = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (q != 0) { if (c == '\\') i++; else if (c == q) q = 0; continue; }
            if (c == '"' || c == '\'') { q = c; continue; }
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
            else if (c == ',' && depth == 0) { out.add(s.substring(start, i)); start = i + 1; }
        }
        out.add(s.substring(start));
        return out;
    }

    private static String scopeOne(String sel, String cls) {
        if (sel.isEmpty()) return sel;
        String low = sel.toLowerCase();
        for (String rootish : new String[]{":root", "html", ":host"}) {
            if (low.equals(rootish)) return "." + cls;
            if (low.startsWith(rootish) && isBoundary(low.charAt(rootish.length()))) {
                return "." + cls + sel.substring(rootish.length());
            }
        }
        int end = 0;
        char q = 0;
        while (end < sel.length()) {                       // 首个复合选择器的结束位置
            char c = sel.charAt(end);
            if (q != 0) { if (c == '\\') end++; else if (c == q) q = 0; end++; continue; }
            if (c == '"' || c == '\'') { q = c; end++; continue; }
            if (c == ' ' || c == '>' || c == '+' || c == '~' || c == '\t' || c == '\n') break;
            end++;
        }
        String head = sel.substring(0, end), rest = sel.substring(end);
        int at = head.indexOf(".md-body");
        if (at >= 0) {
            String h2 = head.substring(0, at) + head.substring(at + ".md-body".length());
            if (h2.isEmpty()) return "." + cls + rest;
            if (h2.charAt(0) == ':' || h2.charAt(0) == '.' || h2.charAt(0) == '#' || h2.charAt(0) == '[') {
                return "." + cls + h2 + rest;
            }
            return "." + cls + " " + h2 + rest;
        }
        return "." + cls + " " + sel;
    }

    private static boolean isBoundary(char c) {
        return c == ' ' || c == '>' || c == '+' || c == '~' || c == ':' || c == '.'
                || c == '[' || c == '\t' || c == '\n';
    }

    /** 统计：结果里残留的 `:root`（应为 0）与源里的 `.md-body` 出现次数（剥离计数） */
    private static int[] countRootsAndStripped(StringBuilder out, String src, String cls) {
        int rootsLeft = 0, i = 0;
        String o = out.toString();
        while ((i = o.indexOf(":root", i)) >= 0) { rootsLeft++; i += 5; }
        int stripped = 0;
        i = 0;
        while ((i = src.indexOf(".md-body", i)) >= 0) { stripped++; i += 8; }
        return new int[]{rootsLeft, stripped};
    }
}
