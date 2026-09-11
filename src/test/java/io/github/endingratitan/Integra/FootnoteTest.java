/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Integra;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class FootnoteTest {

    @Test
    void explicitPairEndMode() {
        String html = MarkdownRenderer.render("正文[^1]。\n\n[^1]: 显式注\n");
        assertTrue(html.contains("<sup id=\"fnref-1\"><a href=\"#fn-1\">1</a></sup>"), html);
        assertTrue(html.contains("<div class=\"md-footnotes\">"));
        assertTrue(html.contains("<li id=\"fn-1\">显式注 <a href=\"#fnref-1\" class=\"md-fn-back\">↩</a></li>"));
        assertFalse(html.contains("[^1]:"));   // 定义行已从正文剔除
    }

    @Test
    void oversizedFootnoteNumberErrors() {
        // R8：超长编号曾裸抛 NumberFormatException（定义侧与引用侧各一处 parseInt）
        RuntimeException def = assertThrows(RuntimeException.class, () ->
                MarkdownRenderer.render("正文[^1]\n\n[^99999999999999999999]: 注\n", "t"));
        assertTrue(def.getMessage().contains("脚注编号过大"), def.getMessage());
        RuntimeException ref = assertThrows(RuntimeException.class, () ->
                MarkdownRenderer.render("正文[^99999999999999999999]\n", "t"));
        assertTrue(ref.getMessage().contains("脚注编号过大"), ref.getMessage());
    }

    @Test
    void autoAndLazyPairing() {
        // [^.] 自动编号（空位补全：1 被占用 → 2、3）；[.^] 懒惰定义按顺序配给
        String html = MarkdownRenderer.render("a[^1]b[^.]c[^.]\n\n[^1]: 一\n[.^]: 懒一\n[.^]: 懒二\n");
        assertTrue(html.contains("<sup id=\"fnref-1\"><a href=\"#fn-1\">1</a></sup>"), html);
        assertTrue(html.contains("<sup id=\"fnref-2\"><a href=\"#fn-2\">2</a></sup>"), html);
        assertTrue(html.contains("<sup id=\"fnref-3\"><a href=\"#fn-3\">3</a></sup>"), html);
        assertTrue(html.contains("<li id=\"fn-2\">懒一"), html);
        assertTrue(html.contains("<li id=\"fn-3\">懒二"), html);
        assertTrue(html.indexOf("<li id=\"fn-1\">") < html.indexOf("<li id=\"fn-2\">"), html);
    }

    @Test
    void unmatchedExplicitRefKeepsNumberWithLazyDef() {
        // [^9] 无显式定义 → 进懒惰池与 [.^] 配对；显示编号保留 9
        String html = MarkdownRenderer.render("x[^9]\n\n[.^]: 配给它\n");
        assertTrue(html.contains("<sup id=\"fnref-9\"><a href=\"#fn-9\">9</a></sup>"), html);
        assertTrue(html.contains("<li id=\"fn-9\">配给它"), html);
    }

    @Test
    void autoSkipsReservedExplicitNumbers() {
        // 显式编号（含未配对的）全部占位，[^.] 不抢 9
        String html = MarkdownRenderer.render("x[^9]y[^.]\n\n[.^]: 懒一\n[.^]: 懒二\n");
        assertTrue(html.contains("<sup id=\"fnref-9\"><a href=\"#fn-9\">9</a></sup>"), html);
        assertTrue(html.contains("<sup id=\"fnref-1\"><a href=\"#fn-1\">1</a></sup>"), html);   // 补最小空位 1
    }

    @Test
    void undefinedRefErrors() {
        RuntimeException e = assertThrows(RuntimeException.class, () ->
                MarkdownRenderer.render("x[^7]\n", "t"));
        assertTrue(e.getMessage().contains("脚注引用未定义"), e.getMessage());
        assertTrue(e.getMessage().contains("第 1 行"), e.getMessage());
        assertTrue(e.getMessage().contains("▸ 本行"), e.getMessage());
    }

    @Test
    void surplusDefWarns() {
        String html = MarkdownRenderer.render("x[^1]\n\n[^1]: 一\n[^2]: 多余\n");
        assertTrue(html.contains("<li id=\"fn-1\">一"), html);
        assertFalse(html.contains("多余"), html);   // 多余定义不渲染
    }

    @Test
    void duplicateDefErrors() {
        RuntimeException e = assertThrows(RuntimeException.class, () ->
                MarkdownRenderer.render("x[^1]\n\n[^1]: 一\n[^1]: 二\n", "t"));
        assertTrue(e.getMessage().contains("脚注编号重复定义"), e.getMessage());
    }

    @Test
    void zeroRefErrors() {
        RuntimeException e = assertThrows(RuntimeException.class, () ->
                MarkdownRenderer.render("x[^0]\n", "t"));
        assertTrue(e.getMessage().contains("脚注编号必须为正整数"), e.getMessage());
    }

    @Test
    void multiRefSameNote() {
        String html = MarkdownRenderer.render("a[^1] b[^1]\n\n[^1]: 注\n");
        assertTrue(html.contains("id=\"fnref-1\""), html);
        assertTrue(html.contains("id=\"fnref-1-2\""), html);   // 第二处引用带后缀
    }

    @Test
    void refsInCodeAndMathLiteral() {
        String html = MarkdownRenderer.render("`[^1]` $[^2]$\n\n[^1]: 一\n[^2]: 二\n");
        assertTrue(html.contains("<code class=\"md-code-inline\">[^1]</code>"), html);
        assertTrue(html.contains("<span class=\"md-math\">$[^2]$</span>"), html);
    }

    @Test
    void defContentInlineAndNoNested() {
        String html = MarkdownRenderer.render("x[^1]\n\n[^1]: **粗** 见 [^2]\n");
        assertTrue(html.contains("<li id=\"fn-1\"><strong>粗</strong> 见 [^2]"), html);
    }

    @Test
    void defLineSplitsParagraph() {
        String html = MarkdownRenderer.render("前文\n[^1]: 注\n后文\n\nx[^1]\n");
        assertTrue(html.contains("<p>前文</p>"), html);
        assertTrue(html.contains("<p>后文</p>"), html);
    }

    @Test
    void inlineMode() {
        String html = MarkdownRenderer.render("x[^1] y\n\n[^1]: 就地\n", "t",
                Map.of("footnote-display", "inline"));
        assertFalse(html.contains("md-footnotes"), html);
        assertTrue(html.contains("<div class=\"md-footnote\" id=\"fn-1\">就地"), html);
        assertTrue(html.contains("href=\"#fn-1\""), html);
    }

    @Test
    void sortedAscending() {
        String html = MarkdownRenderer.render("x[^3] y[^1] z[^2]\n\n[^1]: 一\n[^2]: 二\n[^3]: 三\n");
        int i1 = html.indexOf("<li id=\"fn-1\">");
        int i2 = html.indexOf("<li id=\"fn-2\">");
        int i3 = html.indexOf("<li id=\"fn-3\">");
        assertTrue(i1 > 0 && i1 < i2 && i2 < i3, html);
    }

    @Test
    void bareMdAutoJoinInsideMdBody() {
        String html = MarkdownRenderer.render("x[^1]\n\n[^1]: 注\n");
        assertTrue(html.indexOf("<div class=\"md-footnotes\">") < html.lastIndexOf("</div>"), html);
    }
}
