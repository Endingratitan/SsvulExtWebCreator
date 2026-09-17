/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.WebMinify;

import io.github.endingratitan.WebMinify.css.CssDeduper;
import io.github.endingratitan.WebMinify.js.JsDeclScan;
import io.github.endingratitan.WebMinify.js.JsDeduper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** WebMinify 去重/扫描单元测试 */
public class WebMinifyDedupTest {

    @Test
    void functionDeclLastWins() {
        String js = "function f() { return 1; }\nvar x = f;\nfunction f() { return 2; }\n";
        String out = JsDeduper.dedupItems(js, false).code();
        assertFalse(out.contains("return 1"), out);      // 前驱被清除
        assertTrue(out.contains("return 2"), out);
        assertTrue(out.contains("var x"), out);
        assertTrue(out.indexOf("function") == out.lastIndexOf("function"), out);
    }

    @Test
    void adjacentIdenticalVarDedup() {
        String js = "var a = 1;\nvar a = 1;\nvar b = 2;\n";
        String out = JsDeduper.dedupItems(js, false).code();
        assertTrue(out.indexOf("var a") == out.lastIndexOf("var a"), out);   // 紧邻恒等去重
        assertTrue(out.contains("var b"), out);
    }

    @Test
    void nonAdjacentVarKept() {
        String js = "var a = 1;\nconsole.log('x');\nvar a = 1;\n";
        String out = JsDeduper.dedupItems(js, false).code();
        assertTrue(out.indexOf("var a") != out.lastIndexOf("var a"), out);   // 中间隔了代码 → 保留
    }

    @Test
    void nestedFunctionUntouched() {
        String js = "function outer() {\n  function inner() { return 1; }\n  function inner() { return 2; }\n  return inner;\n}\n";
        String out = JsDeduper.dedupItems(js, false).code();
        assertTrue(out.contains("return 1"), out);   // 深度>0 不碰
        assertTrue(out.contains("return 2"), out);
    }

    @Test
    void keepRemovedAsCommentsEscapes() {
        String js = "function f() { /* x */ return 1; }\nfunction f() { return 2; }\n";
        String out = JsDeduper.dedupItems(js, true).code();
        assertTrue(out.contains("ssvul-dedup: removed"), out);
        assertTrue(out.contains("return 1"), out);
        assertTrue(out.contains("return 2"), out);
    }

    @Test
    void moduleFilesSkipped() {
        String js = "export function f() {}\nfunction f() {}\n";
        String out = JsDeduper.dedupItems(js, false).code();
        assertEquals(js, out);   // import/export → 原样
    }

    @Test
    void declScanTopLevelOnly() {
        var names = JsDeclScan.topLevelBlockScoped("const A = 1;\nlet B = 2;\nclass C {}\nfunction g() { let A = 9; }\n");
        assertTrue(names.contains("A"));
        assertTrue(names.contains("B"));
        assertTrue(names.contains("C"));
        assertFalse(names.contains("g"));
    }

    @Test
    void cssDedupKeepsWithinOneFileRepeats() {
        // 回归（2026-09）：同一个 css 文件里同名选择器分两次写是正常写法
        // （真站点 index.css 有 32 个重复选择器，曾被整块删除 → 字体/横幅/主题大面积错乱）
        String css = ".a { color: red; }\n.b { color: blue; }\n.a { color: green; }\n";
        String out = CssDeduper.dedup(css, false);
        assertTrue(out.contains("red"), out);
        assertTrue(out.contains("green"), out);
        assertTrue(out.contains("blue"), out);
    }

    @Test
    void cssDedupAcrossChunksLastWins() {
        // div 继承链的既定语义：后一块（子 div）的同名选择器删掉前一块（父 div）的前驱
        String a = ".a{color:red;margin:0}\n";
        String b = ".a{color:blue}\n";
        String out = CssDeduper.dedup(a + b, false, new int[]{0, a.length()});
        assertFalse(out.contains("red"), out);
        assertTrue(out.contains("blue"), out);
    }

    @Test
    void cssDedupAtRuleSkipped() {
        String css = "@media (min-width: 1px) { .a { color: red; } .a { color: blue; } }\n";
        String out = CssDeduper.dedup(css, false);
        assertTrue(out.contains("red"), out);   // at-rule 整块保守
    }

    @Test
    void functionDeclWithDestructuredParamsRemovedWhole() {
        // R3：参数区含 {} 时曾定位不到函数体 → 兜底只删 `function` 关键字，留下 ` init({a}) {…}` 语法残句
        String js = "function init({a}) { return a; }\nfunction init(b) { return b; }\n";
        String out = JsDeduper.dedupItems(js, false).code();
        assertFalse(out.contains("init({a})"), out);
        assertFalse(out.contains("return a"), out);
        assertTrue(out.contains("return b"), out);
    }

    @Test
    void cssDedupKeepCommentsSurvivesMultipleCollisions() {
        // R6：-1 档（keep）曾拿输出偏移去 substring 输入原文 → 第二次冲突就 StringIndexOutOfBounds
        // 跨块冲突（每块一条规则）才能触发覆盖删除
        String c1 = ".a{color:red}\n", c2 = ".b{x:1}\n", c3 = ".a{color:blue}\n", c4 = ".a{color:green}\n";
        String css = c1 + c2 + c3 + c4;
        String out = CssDeduper.dedup(css, true,
                new int[]{0, c1.length(), (c1 + c2).length(), (c1 + c2 + c3).length()});
        assertEquals(2, count(out, "ssvul-css-dedup: removed"), out);
        assertTrue(out.contains(".a{color:red}"), out);      // 注释里回插的是被删原文，不是错位片段
        assertTrue(out.contains(".a{color:blue}"), out);
        assertTrue(out.contains(".a{color:green}"), out);
    }

    @Test
    void cssBlocklessAtRuleDoesNotSwallowNextRule() {
        // R9：无块 at-rule 曾把紧随其后的一条规则吞进 at-rule 段 → 该规则不参与去重
        String c1 = "@import \"x.css\";\n", c2 = ".a{color:red;margin:0}\n", c3 = ".a{color:blue}\n";
        String out = CssDeduper.dedup(c1 + c2 + c3, false, new int[]{0, c1.length(), (c1 + c2).length()});
        assertTrue(out.contains("@import \"x.css\";"), out);
        assertEquals(1, count(out, ".a{"), out);             // 前驱被删（修复前两条都在）
        assertFalse(out.contains("margin:0"), out);          // 后到胜：同名前驱整块删除=既定特性（见 div-guide §5）
    }

    @Test
    void cssBlocklessAtRuleSkipsQuotedSemicolon() {
        // R9 后续：blockEnd 曾用裸 indexOf(';') → 引号内的 ; 会截断 at-rule 段，使其后规则不参与去重
        String c1 = "@import url(\"a;b.css\");\n", c2 = ".a{color:red;margin:0}\n", c3 = ".a{color:blue}\n";
        String out = CssDeduper.dedup(c1 + c2 + c3, false, new int[]{0, c1.length(), (c1 + c2).length()});
        assertTrue(out.contains("@import url(\"a;b.css\");"), out);
        assertEquals(1, count(out, ".a{"), out);
    }

    private static int count(String s, String sub) {
        int n = 0;
        for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + sub.length())) n++;
        return n;
    }

    @Test
    void dedupFilesDropsIdenticalCopies() {
        List<String> files = List.of("function f() { return 1; }\n", "function f() { return 1; }\n", "var x = 2;\n");
        List<String> out = JsDeduper.dedupFiles(files);
        assertEquals(2, out.size());
    }
}
