/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * 项目来源: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Integra;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class MarkdownRendererTest {

    @Test
    void headingAndEmphasis() {
        String html = MarkdownRenderer.render("# 标题\n\n**粗** *斜* _中文斜_ my_var `code` ~~删~~ [[Ctrl]]+[[C]]\n");
        assertTrue(html.startsWith("<div class=\"md-body\">"));
        assertTrue(html.contains("<h1 id=\"s1\">标题</h1>"));
        assertTrue(html.contains("<strong>粗</strong>"));
        assertTrue(html.contains("<em>斜</em>"));
        assertTrue(html.contains("<em>中文斜</em>"));
        assertTrue(html.contains("my_var"));   // 下划线不误伤
        assertTrue(html.contains("<code class=\"md-code-inline\">code</code>"));
        assertTrue(html.contains("<del>删</del>"));
        assertTrue(html.contains("<kbd class=\"md-kbd\">Ctrl</kbd>"));
    }

    @Test
    void mathRules() {
        String html = MarkdownRenderer.render("数学 $x^2$ 与 价格 $5 与 \\$6\n\n$$E=mc^2$$\n");
        assertTrue(html.contains("<span class=\"md-math\">$x^2$</span>"));
        assertTrue(html.contains("价格 $5"));                       // 孤立 $ 为普通文本
        assertTrue(html.contains("$6"));                            // \$ 转义输出字面 $
        assertTrue(html.contains("<div class=\"md-math-block\">$$E=mc^2$$</div>"));
    }

    @Test
    void tableQuoteAndFence() {
        String html = MarkdownRenderer.render(
                "| a | b |\n| :--- | ---: |\n| 1 | 2 |\n\n> 引文\n\n```java\nint x=1;\n```\n");
        assertTrue(html.contains("<table class=\"md-table\">"));
        assertTrue(html.contains("class=\"md-al-r\""));             // 右对齐列
        assertTrue(html.contains("<blockquote>"));
        assertTrue(html.contains("<code class=\"md-code language-java\">int x=1;</code>"));
    }

    @Test
    void tocBuildTime() {
        String html = MarkdownRenderer.render("# 标题\n\n## 一\n\n### 子\n\n## 二\n", "t", Map.of("toc", "true"));
        assertTrue(html.contains("<nav class=\"md-toc\">"), html);
        assertTrue(html.contains("<li class=\"toc-h2\"><a href=\"#s2\">一</a></li>"), html);
        assertTrue(html.contains("<li class=\"toc-h3\"><a href=\"#s3\">子</a></li>"), html);
        assertFalse(html.contains("href=\"#s1\""), html);   // h1 不入目录
        assertTrue(html.indexOf("md-toc") < html.indexOf("<h1"), html);   // 目录在正文顶部

        assertFalse(MarkdownRenderer.render("# 标题\n\n## 一\n").contains("md-toc"));   // 默认关闭
        assertFalse(MarkdownRenderer.render("段落\n", "t", Map.of("toc", "true")).contains("md-toc"));   // 无标题无目录
    }

    @Test
    void headingAnchors() {
        String html = MarkdownRenderer.render("# 一\n\n## 二\n\n### 三\n\n[跳](#s2)\n");
        assertTrue(html.contains("<h1 id=\"s1\">一</h1>"));
        assertTrue(html.contains("<h2 id=\"s2\">二</h2>"));
        assertTrue(html.contains("<h3 id=\"s3\">三</h3>"));
        assertTrue(html.contains("href=\"#s2\""));   // 锚点链接放行不校验
    }

    @Test
    void nestedListAndStrictErrors() {
        assertTrue(MarkdownRenderer.render("- a\n  - b\n").contains("<ul>"));
        Map<String, String> strict = Map.of("mode", "strict");

        RuntimeException deep = assertThrows(RuntimeException.class, () ->
                MarkdownRenderer.render("- a\n  - b\n    - c\n", "t", strict));
        assertTrue(deep.getMessage().contains("列表嵌套超过两层"));
        assertTrue(deep.getMessage().contains("▸ 本行"));   // 错误上下文三行显示

        // --- / +++ / *** 分割线特性：两种模式都放行，各自 class（v2 收尾）
        assertTrue(MarkdownRenderer.render("---\n", "t", strict).contains("md-hr-dash"));
        assertTrue(MarkdownRenderer.render("+++\n", "t", strict).contains("md-hr-plus"));
        assertTrue(MarkdownRenderer.render("***\n", "t").contains("md-hr-star"));
        assertTrue(MarkdownRenderer.render("___\n", "t").contains("md-hr-star"));
    }

    @Test
    void errorCollectionMultiple() {
        RuntimeException e = assertThrows(RuntimeException.class, () ->
                MarkdownRenderer.render("[x](a b)\n\n[x](//x)\n\n[x](bad)\n", "t", Map.of("mode", "strict")));
        String msg = e.getMessage();
        assertTrue(msg.contains("第 1 行"), msg);
        assertTrue(msg.contains("第 3 行"), msg);
        assertTrue(msg.contains("第 5 行"), msg);   // 一次报多条
    }

    @Test
    void simpleModeLenient() {
        // --- 分隔线（GitHub 行为；v2 收尾后带 class）
        String dash = MarkdownRenderer.render("---\n", "t");
        assertTrue(dash.contains("<hr class=\"md-hr-dash\">"), "实际输出: " + dash);
        // 块级 HTML 直出
        assertTrue(MarkdownRenderer.render("<div class='x'>hi</div>\n", "t")
                .contains("<div class='x'>hi</div>"));
        // 行内 HTML 直出
        assertTrue(MarkdownRenderer.render("a <b>bold</b> c\n", "t")
                .contains("a <b>bold</b> c"));
        // 3 层列表不再报错
        assertTrue(MarkdownRenderer.render("- a\n  - b\n    - c\n", "t").contains("<ul>"));
        // 未闭合围栏不报错（吞到文件尾）
        assertTrue(MarkdownRenderer.render("```java\nint x;\n", "t").contains("language-java"));
        // 表格列数不一致不报错（按表头列数截断）
        assertTrue(MarkdownRenderer.render("| a | b |\n|---|---|\n| 1 |\n", "t")
                .contains("<table class=\"md-table\">"));
    }

    @Test
    void listFenceAndTableEscape() {
        String out = MarkdownRenderer.render(
                "- 项\n  ```java\n  int x;\n  ```\n- 后项\n\n- 表项\n  | a |\n| --- |\n| 1 |\n");
        assertTrue(out.contains("language-java"), out);
        assertTrue(out.contains("int x;"), out);            // 围栏内容完整
        assertTrue(out.contains("后项"), out);
        assertTrue(out.contains("<table class=\"md-table\">"), out);   // 表格逃逸后仍成表
    }

    @Test
    void codeEngineSwap() {
        assertTrue(MarkdownRenderer.render("```java\nint x;\n```\n").contains("int x;"));
        MarkdownRenderer.setCodeEngine((code, lang) -> "<b>" + code + "</b>");
        try {
            String swapped = MarkdownRenderer.render("```java\nint x;\n```\n");
            assertTrue(swapped.contains("<b>int x;</b>"));
        } finally {
            MarkdownRenderer.setCodeEngine(new PassThroughCodeEngine());
        }
    }

    @Test
    void crlfBomAndHardBreak() {
        String html = MarkdownRenderer.render("\uFEFF# 标题\r\n\r\n行一  \r\n行二\r\n");
        assertTrue(html.contains("<h1 id=\"s1\">标题</h1>"));
        assertTrue(html.contains("行一<br>\n行二"));
    }

    @Test
    void hashWithoutSpaceIsParagraph() {
        String html = MarkdownRenderer.render("#标题\n");
        assertTrue(html.contains("<p>#标题</p>"));
        assertFalse(html.contains("<h1>"));
    }

    @Test
    void linkWhitelist() {
        String ok = MarkdownRenderer.render(
                "[a](@page/about) [b](@data/x.md) [c](https://x.com) [d](bk/x.png) [e](#锚)\n");
        assertTrue(ok.contains("href=\"@page/about\""));
        assertTrue(ok.contains("href=\"@data/x.md\""));
        assertTrue(ok.contains("href=\"https://x.com\""));
        assertTrue(ok.contains("href=\"bk/x.png\""));
        assertTrue(ok.contains("href=\"#锚\""));

        RuntimeException bad = assertThrows(RuntimeException.class, () ->
                MarkdownRenderer.render("[x](//bad)\n", "t"));
        assertTrue(bad.getMessage().contains("白名单"));

        // 名字/ 形态按"潜在 bucket 调用名"放行（最终由输出替换阶段校验）
        String nameLike = MarkdownRenderer.render("[x](images/a.png)\n");
        assertTrue(nameLike.contains("href=\"images/a.png\""));
    }

    // ==================== simple/strict 行为矩阵（⑥） ====================

    @Test
    void quoteDepthMatrix() {
        String md = "> 一\n> > 二\n> > > 三\n";
        assertTrue(MarkdownRenderer.render(md).contains("<blockquote>"));   // simple 压平不抛
        RuntimeException e = assertThrows(RuntimeException.class, () ->
                MarkdownRenderer.render(md, "t", Map.of("mode", "strict")));
        assertTrue(e.getMessage().contains("引用嵌套超过两层"));
    }

    @Test
    void quoteMissingGtMatrix() {
        String md = "> 一\n缺 > 的行\n";
        assertTrue(MarkdownRenderer.render(md).contains("<blockquote>"));   // simple 警告按段落
        RuntimeException e = assertThrows(RuntimeException.class, () ->
                MarkdownRenderer.render(md, "t", Map.of("mode", "strict")));
        assertTrue(e.getMessage().contains("引用块内每行需以 > 开头"));
    }

    @Test
    void blockMathMultilineAndUnclosed() {
        String html = MarkdownRenderer.render("$$\na + b\n$$\n");
        assertTrue(html.contains("md-math-block"), html);
        assertTrue(html.contains("a + b"), html);
        // 未闭合：两模式都报错
        RuntimeException e1 = assertThrows(RuntimeException.class, () -> MarkdownRenderer.render("$$\na + b\n", "t"));
        assertTrue(e1.getMessage().contains("块数学未闭合"));
        RuntimeException e2 = assertThrows(RuntimeException.class, () ->
                MarkdownRenderer.render("$$\na + b\n", "t", Map.of("mode", "strict")));
        assertTrue(e2.getMessage().contains("块数学未闭合"));
    }

    @Test
    void emptyHeadingWarnsBothModes() {
        assertTrue(MarkdownRenderer.render("# \n\n正文\n").contains("<h1"));
        assertTrue(MarkdownRenderer.render("# \n\n正文\n", "t", Map.of("mode", "strict")).contains("<h1"));
    }

    @Test
    void footnoteSurplusStrictUpgrades() {
        String md = "正文[^1]\n\n[^1]: 甲\n[^2]: 乙\n";
        assertTrue(MarkdownRenderer.render(md).contains("md-footnotes"));   // simple 仅警告
        RuntimeException e = assertThrows(RuntimeException.class, () ->
                MarkdownRenderer.render(md, "t", Map.of("mode", "strict")));
        assertTrue(e.getMessage().contains("脚注定义未被引用"));
    }

    @Test
    void inlineMathUnclosedMatrix() {
        // simple：孤 $ 以行尾为结束点收口渲染（栈式回退 + 警告）
        assertTrue(MarkdownRenderer.render("公式 $a + b\n").contains("<span class=\"md-math\">$a + b</span>"));
        RuntimeException e = assertThrows(RuntimeException.class, () ->
                MarkdownRenderer.render("公式 $a + b\n", "t", Map.of("mode", "strict")));
        assertTrue(e.getMessage().contains("行内数学未闭合"));
    }

    // ==================== LaTeX 原生定界符（v2 收尾） ====================

    @Test
    void latexInlineMath() {
        String html = MarkdownRenderer.render("公式 \\(a + b\\) 完\n");
        assertTrue(html.contains("<span class=\"md-math\" data-delim=\"latex\">\\(a + b\\)</span>"), html);
    }

    @Test
    void latexBlockMath() {
        String html = MarkdownRenderer.render("\\[\na + b\n\\]\n");
        assertTrue(html.contains("md-math-block"), html);
        assertTrue(html.contains("data-delim=\"latex-block\""), html);
        assertTrue(html.contains("a + b"), html);
    }

    @Test
    void latexMathUnclosedMatrix() {
        // 行内 \( 未闭合：simple 收口渲染；strict 报错
        assertTrue(MarkdownRenderer.render("公式 \\(a + b\n").contains("data-delim=\"latex\""));
        RuntimeException e1 = assertThrows(RuntimeException.class, () ->
                MarkdownRenderer.render("公式 \\(a + b\n", "t", Map.of("mode", "strict")));
        assertTrue(e1.getMessage().contains("行内数学未闭合"));
        // 块级 \[ 未闭合：两模式都报错
        RuntimeException e2 = assertThrows(RuntimeException.class, () -> MarkdownRenderer.render("\\[\na + b\n", "t"));
        assertTrue(e2.getMessage().contains("块数学未闭合"));
        RuntimeException e3 = assertThrows(RuntimeException.class, () ->
                MarkdownRenderer.render("\\[\na + b\n", "t", Map.of("mode", "strict")));
        assertTrue(e3.getMessage().contains("块数学未闭合"));
    }

    @Test
    void dollarPathUnchangedByLatexFeature() {
        // $/$$ 路径不带 data-delim（默认定界符，输出保持既有形态）
        String html = MarkdownRenderer.render("数学 $x^2$\n\n$$E=mc^2$$\n");
        assertTrue(html.contains("<span class=\"md-math\">$x^2$</span>"), html);
        assertTrue(html.contains("<div class=\"md-math-block\">$$E=mc^2$$</div>"), html);
    }

    @Test
    void hasCodeInResult() {
        MarkdownRenderer.MdResult mr = MarkdownRenderer.renderParts("```java\nint x;\n```\n", "t", Map.of());
        assertTrue(mr.hasCode());
        MarkdownRenderer.MdResult plain = MarkdownRenderer.renderParts("段落\n", "t", Map.of());
        assertFalse(plain.hasCode());
    }
}
