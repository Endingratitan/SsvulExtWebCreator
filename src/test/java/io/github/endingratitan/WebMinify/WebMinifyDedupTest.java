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
    void cssDedupSameSelectorLastWins() {
        String css = ".a { color: red; }\n.b { color: blue; }\n.a { color: green; }\n";
        String out = CssDeduper.dedup(css, false);
        assertFalse(out.contains("red"), out);
        assertTrue(out.contains("green"), out);
        assertTrue(out.contains("blue"), out);
    }

    @Test
    void cssDedupAtRuleSkipped() {
        String css = "@media (min-width: 1px) { .a { color: red; } .a { color: blue; } }\n";
        String out = CssDeduper.dedup(css, false);
        assertTrue(out.contains("red"), out);   // at-rule 整块保守
    }

    @Test
    void dedupFilesDropsIdenticalCopies() {
        List<String> files = List.of("function f() { return 1; }\n", "function f() { return 1; }\n", "var x = 2;\n");
        List<String> out = JsDeduper.dedupFiles(files);
        assertEquals(2, out.size());
    }
}
