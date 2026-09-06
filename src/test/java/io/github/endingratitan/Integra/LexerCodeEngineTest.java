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

/** simple 词法验证器：token 分段、转义安全、accepts 与 U1 词表扩展 */
public class LexerCodeEngineTest {

    private final LexerCodeEngine e = new LexerCodeEngine();

    @Test
    void keywordsNumbersOperators() {
        String html = e.renderCode("int x = 0x1F + 3.14;", "java");
        assertTrue(html.contains("<span class=\"tk-kw\">int</span>"), html);
        assertTrue(html.contains("<span class=\"tk-num\">0x1F</span>"), html);
        assertTrue(html.contains("<span class=\"tk-num\">3.14</span>"), html);
        assertTrue(html.contains("<span class=\"tk-op\">+</span>"), html);
        assertTrue(html.contains("<span class=\"tk-punct\">;</span>"), html);
    }

    @Test
    void stringsCommentsAndEscapes() {
        String html = e.renderCode("String s = \"a\\\"b\"; // 注释\n/* 块 */", "java");
        assertTrue(html.contains("<span class=\"tk-str\">&quot;a\\&quot;b&quot;</span>"), html);
        assertTrue(html.contains("<span class=\"tk-com\">// 注释</span>"), html);
        assertTrue(html.contains("<span class=\"tk-com\">/* 块 */</span>"), html);
        // 转义安全：原始 < > & " 不得裸露
        assertFalse(e.renderCode("if (a < b && c > d)", "java").contains("< b"), "原始 < 必须转义");
        assertTrue(e.renderCode("if (a < b)", "java").contains("&lt;"));
        assertTrue(e.renderCode("x & y", "java").contains("&amp;"));
    }

    @Test
    void multiCharOperatorFirst() {
        String html = e.renderCode("a += b == c", "java");
        assertTrue(html.contains("<span class=\"tk-op\">+=</span>"), html);
        assertTrue(html.contains("<span class=\"tk-op\">==</span>"), html);
    }

    @Test
    void jsTemplateAndLiterals() {
        String html = e.renderCode("let t = `x${y}`; let u = undefined;", "javascript");
        assertTrue(html.contains("<span class=\"tk-tpl\">`x${y}`</span>"), html);
        assertTrue(html.contains("<span class=\"tk-num\">undefined</span>"), html);
    }

    @Test
    void pythonHashCommentAndTripleQuote() {
        String html = e.renderCode("# 注释\ndoc = '''多行\n字符串'''\nprint(True)", "python");
        assertTrue(html.contains("<span class=\"tk-com\"># 注释</span>"), html);
        assertTrue(html.contains("<span class=\"tk-str\">'''多行\n字符串'''</span>"), html);
        assertTrue(html.contains("<span class=\"tk-num\">True</span>"), html);
        assertTrue(html.contains("<span class=\"tk-builtin\">print</span>"), html);
    }

    @Test
    void bashVariableAndComment() {
        String html = e.renderCode("echo $HOME # home", "bash");
        assertTrue(html.contains("<span class=\"tk-var\">$HOME</span>"), html);
        assertTrue(html.contains("<span class=\"tk-com\"># home</span>"), html);
        assertTrue(html.contains("<span class=\"tk-builtin\">echo</span>"), html);
    }

    @Test
    void htmlTagMode() {
        String html = e.renderCode("<div class=\"x\"></div>", "html");
        assertTrue(html.contains("<span class=\"tk-tag\">div</span>"), html);
        assertTrue(html.contains("<span class=\"tk-attr\">class</span>"), html);
        assertTrue(html.contains("<span class=\"tk-str\">&quot;x&quot;</span>"), html);
        assertTrue(html.contains("<span class=\"tk-punct\">&gt;</span>"), html);
    }

    @Test
    void acceptsOnlyKnownLanguages() {
        assertTrue(e.accepts("java"));
        assertTrue(e.accepts("js"));       // 别名
        assertFalse(e.accepts("rust"));
        assertFalse(e.accepts(""));
        // 未接语言：纯转义、无 token span
        assertTrue(e.renderCode("fn main()", "rust").contains("fn main()"));
        assertFalse(e.renderCode("fn main()", "rust").contains("tk-"));
    }

    @Test
    void userWordsExtendAndOverride() {
        LexerCodeEngine u = new LexerCodeEngine(Map.of(
                "mylang", Map.of("Foo", "cls", "bar", "kw"),
                "java", Map.of("record", "builtin")));   // 覆盖内置 java 的 record
        assertTrue(u.accepts("mylang"));
        assertTrue(u.renderCode("Foo bar", "mylang").contains("<span class=\"tk-cls\">Foo</span>"));
        assertTrue(u.renderCode("Foo bar", "mylang").contains("<span class=\"tk-kw\">bar</span>"));
        assertTrue(u.renderCode("record", "java").contains("<span class=\"tk-builtin\">record</span>"));
    }

    @Test
    void cppInheritsCKeywords() {
        String html = e.renderCode("template <class T> struct X {};", "cpp");
        assertTrue(html.contains("<span class=\"tk-kw\">template</span>"), html);
        assertTrue(html.contains("<span class=\"tk-kw\">struct</span>"), html);
    }
}
