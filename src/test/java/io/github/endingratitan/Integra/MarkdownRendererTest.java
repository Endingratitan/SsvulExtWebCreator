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

        RuntimeException dash = assertThrows(RuntimeException.class, () ->
                MarkdownRenderer.render("---\n", "t", strict));
        assertTrue(dash.getMessage().contains("请改用 ***"));
    }

    @Test
    void errorCollectionMultiple() {
        RuntimeException e = assertThrows(RuntimeException.class, () ->
                MarkdownRenderer.render("---\n\n[x](a b)\n\n[bad](//x)\n", "t", Map.of("mode", "strict")));
        String msg = e.getMessage();
        assertTrue(msg.contains("第 1 行"), msg);
        assertTrue(msg.contains("第 3 行"), msg);
        assertTrue(msg.contains("第 5 行"), msg);   // 一次报多条
    }

    @Test
    void simpleModeLenient() {
        // --- 分隔线（GitHub 行为）
        assertTrue(MarkdownRenderer.render("---\n", "t").contains("<hr>"));
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
}
