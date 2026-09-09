/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.WebMinify.js;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** simple minifier：词法安全、改名、头注释保留、全预设回归、node --check 语法校验（无 node 跳过） */
public class JSMinifierTest {

    private final SimpleJsMinifier m = new SimpleJsMinifier();

    private String min(String js) { return m.minify(js).code(); }

    @Test
    void stringsTemplatesRegexProtected() {
        // 字符串里的 // 与 /* 不得当注释删掉
        assertTrue(min("var a = 'http://x.com/*';").contains("'http://x.com/*'"));
        // 正则里的 // 不得误判
        String r = min("var r = /https?:\\/\\/x/gi;");
        assertTrue(r.contains("/https?:\\/\\/x/gi"), r);
        // 模板串内容保留
        assertTrue(min("var t = `a // b ${'/*'}`;").contains("`a // b ${'/*'}`"));
    }

    @Test
    void asiSafety() {
        // return 后换行必须保留（否则 return undefined 语义变化）
        String s = min("function f() {\n  return\n  1;\n}");
        assertTrue(s.contains("return\n"), s);
        // a\n(b) 类续行不得并合
        String s2 = min("var a = 1;\n(a + b);");
        assertFalse(s2.contains("1;(a"), s2);
    }

    @Test
    void renamesLocalsNotPropertiesOrKeys() {
        String js = "function greet(name, count) {\n" +
                "  var msg = 'hi ' + name + count;\n" +
                "  var obj = { msg: msg, name: name };\n" +
                "  window.msg = msg;\n" +
                "  return obj.msg;\n" +
                "}\nconsole.log(greet('a', 2));";
        String out = min(js);
        assertTrue(out.contains("function greet("), out);   // 顶层函数名不碰（全局面）
        assertTrue(out.contains("var msg"), out);            // 键/属性名出现 → 整名保守保留
        assertTrue(out.contains("name:name"), out);          // 对象键（同名值）保留
        assertTrue(out.contains("window.msg"), out);         // 属性 msg 保留
        assertFalse(out.contains("obj"), out);               // obj 是纯局部（obj.msg 的 obj 是引用方）→ 一致改写
        assertFalse(out.contains("count"), out);             // 纯局部参数被改写
        assertTrue(out.contains("console.log"), out);
    }

    @Test
    void topLevelVarIsGlobalSurfaceKept() {
        // 深度 0 的 var 可能被其它聚合脚本引用 → 不碰
        String out = min("var shared = 1;\nfunction f() { var local = shared; return local; }");
        assertTrue(out.contains("var shared"), out);
        assertFalse(out.contains("local"), out);   // 嵌套局部被改写
    }

    @Test
    void evalAndTemplateCodeSkipMangle() {
        String out = min("function f() { var x = eval('x'); return x; }");
        assertTrue(out.contains("var x"), out);   // 含 eval → 放弃改名
        assertTrue(m.minify("var t = `${a}`;").notes().stream().anyMatch(n -> n.contains("跳过改名")));
    }

    @Test
    void headerCommentPreserved() {
        String js = "/* 许可头 */\n// 头说明行\nvar a = 1; /* 块注释 */";
        String out = min(js);
        assertTrue(out.startsWith("/* 许可头 */"), out);
        assertTrue(out.contains("// 头说明行"), out);   // 头区连续注释全部保留（许可 + 文件说明）
        assertFalse(out.contains("块注释"), out);        // 代码后的注释删除
    }

    @Test
    void reservedNamesNeverGenerated() {
        // 大量声明 → 生成短名不得撞保留字（do/if/in 等）
        StringBuilder js = new StringBuilder("function f(a,b,c,d,e,g,h,i,j,k,l,m,n,o,p,q,r,s,t,u,v,w,x,y,z){");
        for (int k = 0; k < 60; k++) js.append("var v").append(k).append("=").append(k).append(";");
        js.append("return v0;}");
        String out = min(js.toString());
        assertFalse(out.contains("var do="), out);
        assertFalse(out.contains("var if="), out);
        assertFalse(out.contains("var in="), out);
    }

    @Test
    void presetRegressionAllOwnedJs() throws Exception {
        // 遍历 src/assets 全部自维护 js（排除 vendor lib/）：压缩无异常 + 头注释保留 + 关键字面量仍在
        List<String> checks = List.of("'ssvul-theme'", "'已复制'", "'palettechange'", "'ssvulhighlight'");
        List<String> tested = new ArrayList<>();
        File root = new File("src/assets");
        List<File> files = new ArrayList<>();
        collect(root, files);
        for (File f : files) {
            String rel = root.toPath().relativize(f.toPath()).toString().replace('\\', '/');
            if (!f.getName().endsWith(".js") || rel.startsWith("lib/")) continue;
            String src = Files.readString(f.toPath());
            String out = min(src);
            assertTrue(out.startsWith("/*") || out.startsWith("//"), "头注释保留: " + rel);
            for (String c : checks)
                if (src.contains(c)) assertTrue(out.contains(c), rel + " 丢失字面量 " + c);
            tested.add(rel);
        }
        assertFalse(tested.isEmpty());
    }

    @Test
    void nodeSyntaxCheckWhenAvailable() throws Exception {
        boolean hasNode;
        try {
            Process p = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            hasNode = p.waitFor() == 0;
        } catch (Exception e) {
            hasNode = false;
        }
        Assumptions.assumeTrue(hasNode, "node 不可用，跳过语法校验");
        Path dir = Files.createTempDirectory("jsmin");
        List<File> all = new ArrayList<>();
        collect(new File("src/assets"), all);
        int checked = 0;
        for (File f : all) {
            String rel = new File("src/assets").toPath().relativize(f.toPath()).toString().replace('\\', '/');
            if (!f.getName().endsWith(".js") || rel.startsWith("lib/")) continue;
            File tmp = dir.resolve(rel).toFile();
            Files.createDirectories(tmp.getParentFile().toPath());
            Files.writeString(tmp.toPath(), min(Files.readString(f.toPath())));
            Process p = new ProcessBuilder("node", "--check", tmp.getAbsolutePath()).redirectErrorStream(true).start();
            String err = new String(p.getInputStream().readAllBytes());
            assertEquals(0, p.waitFor(), rel + " 压缩产物语法错误: " + err);
            checked++;
        }
        assertTrue(checked > 0);
    }

    private static void collect(File dir, List<File> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) collect(f, out);
            else out.add(f);
        }
    }
}
