/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.WebMinify.css;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CssScoper} 边界测试：作用域前缀 / 逗号分组 / at-rule 分类（组、关键帧、声明块、未知）/
 * `:root` 改写 / `.md-body` 剥离 / 注释与字符串与 `url()` 的结构防误判 / 坏 css 降级。
 * 末尾一条是**真实预设 md.css 的回归**（残留 `:root` 与 `.md-body` 必须为 0）。
 */
class CssScoperTest {

    private static final String CLS = "md-theme-0123456789abcdef";

    private static String sc(String css) {
        CssScoper.Scoped r = CssScoper.scope(css, CLS);
        assertTrue(r.balanced(), "合法输入应判定为括号平衡: " + css);
        return r.css().trim();
    }

    @Test
    void scopesSelectorsAndStripsMdBody() {
        assertEquals("." + CLS + " h2{a:1}", sc(".md-body h2{a:1}"));
        assertEquals("." + CLS + "{a:1}", sc(".md-body{a:1}"));
        assertEquals("." + CLS, sc(".md-body"));
        assertEquals("." + CLS + ".x p{a:1}", sc(".md-body.x p{a:1}"));
        assertEquals("." + CLS + ">p{a:1}", sc(".md-body>p{a:1}"));
        assertEquals("." + CLS + ":before{content:\"}\"}", sc(".md-body:before{content:\"}\"}"));
        assertEquals("." + CLS + " h1, ." + CLS + " h2{a:1}", sc("h1,h2{a:1}"));
        assertEquals("." + CLS + " .a, ." + CLS + " .b p{a:1}", sc(".a, .b p{a:1}"));
    }

    @Test
    void rewritesRootSelectorsToScope() {
        assertEquals("." + CLS + "{--x:1}", sc(":root{--x:1}"));
        assertEquals("." + CLS + " .a{--x:1}", sc(":root .a{--x:1}"));
        assertEquals("." + CLS + " .a{--x:1}", sc("html .a{--x:1}"));
    }

    @Test
    void keepsAtRulesAndScopesTheirInnerSelectors() {
        assertEquals("@media (max-width:600px){." + CLS + " p{a:1}}", sc("@media (max-width:600px){.md-body p{a:1}}"));
        assertEquals("@supports (display:grid){@media print{." + CLS + " .x{a:1}}}",
                sc("@supports (display:grid){@media print{.x{a:1}}}"));
        assertEquals("@layer base{." + CLS + " p{a:1}}", sc("@layer base{.md-body p{a:1}}"));
        // 组 at-rule 闭合后必须回到选择器模式（内部状态回退，曾漏 depth--/弹栈）
        assertEquals("@media print{." + CLS + " p{a:1}}\n." + CLS + " h2{b:2}",
                sc("@media print{.md-body p{a:1}}\n.md-body h2{b:2}"));
    }

    @Test
    void neverPrefixesKeyframesOrDeclBlocks() {
        assertEquals("@keyframes spin{from{a:1}to{b:2}50%{c:3}}", sc("@keyframes spin{from{a:1}to{b:2}50%{c:3}}"));
        assertEquals("@-webkit-keyframes spin{from{a:1}}", sc("@-webkit-keyframes spin{from{a:1}}"));
        assertEquals("@font-face{font-family:x;src:url(y.woff2)}", sc("@font-face{font-family:x;src:url(y.woff2)}"));
        assertEquals("@import url(a.css);", sc("@import url(a.css);"));
        assertEquals("@charset \"utf-8\";", sc("@charset \"utf-8\";"));
    }

    @Test
    void commentsAndUrlsDoNotBreakStructure() {
        assertEquals("/* c */ ." + CLS + " h3{b:2}", sc("/* c */ .md-body h3{b:2}"));
        assertEquals("/* .md-body h2{a:1} */\n." + CLS + " h3{b:2}",
                sc("/* .md-body h2{a:1} */\n.md-body h3{b:2}"));
        // data-URI 里的 {} 与 ; 不能打乱括号计数
        String in = ".md-body{background:url(data:image/svg+xml;utf8,<svg>{x}</svg>)}";
        String got = sc(in);
        assertTrue(got.startsWith("." + CLS + "{background:url("), got);
        assertTrue(got.endsWith(")}"), got);
    }

    @Test
    void degradesInsteadOfThrowingOnBrokenCss() {
        CssScoper.Scoped r = CssScoper.scope(".md-body h2{ a:1 ", CLS);
        assertFalse(r.balanced(), "未闭合的块应判定为不平衡");
        assertFalse(r.css().isEmpty(), "坏 css 也要返回文本（由调用方警告），不得抛异常");
        assertFalse(CssScoper.balanced(".a{"), "balanced() 对未闭合输入应为 false");
        assertTrue(CssScoper.balanced("@media print{.a{x:1}}"), "balanced() 对合法输入应为 true");
    }

    @Test
    void realPresetMdCssHasNoResidualRootOrMdBody() throws Exception {
        Path p = Path.of("src/assets/md/css/md.css");
        Assumptions.assumeTrue(Files.isRegularFile(p), "预设 md.css 不存在则跳过");
        String src = Files.readString(p, StandardCharsets.UTF_8);
        CssScoper.Scoped r = CssScoper.scope(src, CLS);
        assertTrue(r.balanced(), "预设 md.css 应括号平衡");
        assertEquals(0, count(r.css(), ":root"), "作用域化后不应残留 :root");
        assertEquals(0, count(r.css(), ".md-body"), "作用域化后不应残留 .md-body");
        assertTrue(r.rules() > 100, "预设 md.css 规则数应 >100，实际 " + r.rules());
        assertEquals(0, r.opaqueAtRules(), "预设 md.css 不应有未识别 at-rule");
        assertTrue(r.css().length() < src.length() * 2, "体积增长应在两倍以内，实际 "
                + src.length() + " → " + r.css().length());
        assertTrue(r.css().contains("." + CLS + " h2{"), "代表性规则应被作用域化");
    }

    private static int count(String s, String sub) {
        int c = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { c++; i += sub.length(); }
        return c;
    }
}
