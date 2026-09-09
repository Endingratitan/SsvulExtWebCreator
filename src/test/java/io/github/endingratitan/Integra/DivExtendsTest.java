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
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** .extends 继承 / .contract 契约 / 聚合去重 / A 层冲突 / minify 三档 */
public class DivExtendsTest {

    @TempDir
    Path tmp;

    private void write(File f, String s) throws Exception {
        Files.createDirectories(f.getParentFile().toPath());
        Files.writeString(f.toPath(), s);
    }

    private File build(String... config) throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = tmp.resolve("output").toFile();
        write(new File(sets, "Environment.config"),
                String.join("\n", config.length > 0 ? config : new String[]{"cname=example.com"}) + "\n");
        return out;
    }

    @Test
    void extendsInheritsAndChildLast() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = build();
        write(new File(sets, "divs/base/template.html"), "<b>{{content}}</b>");
        write(new File(sets, "divs/base/base.js"), "console.log('base');");
        write(new File(sets, "divs/base/base.css"), ".b{}");
        write(new File(sets, "divs/child/.extends"), "base");
        write(new File(sets, "divs/child/child.js"), "console.log('child');");
        write(new File(sets, "divs/child/child.css"), ".c{}");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"child\",\"markdown\":\"# X\"}}}");
        SiteBuilder.build(sets, out, new File("src/assets"));
        String html = Files.readString(new File(out, "index.html").toPath());
        assertTrue(html.contains("<b>"), html);                       // 继承父模板
        String js = Files.readString(new File(out, "assets/index/index.min.js").toPath());
        assertTrue(js.indexOf("'base'") < js.indexOf("'child'"), js); // 串联父前子后
        String css = Files.readString(new File(out, "assets/index/index.css").toPath());
        assertTrue(css.contains(".b{}") && css.contains(".c{}"), css);
    }

    @Test
    void extendsTemplateOverride() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = build();
        write(new File(sets, "divs/base/template.html"), "<b>{{content}}</b>");
        write(new File(sets, "divs/child/.extends"), "base");
        write(new File(sets, "divs/child/template.html"), "<i>{{content}}</i>");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"child\",\"markdown\":\"# X\"}}}");
        SiteBuilder.build(sets, out, new File("src/assets"));
        String html = Files.readString(new File(out, "index.html").toPath());
        assertTrue(html.contains("<i>"), html);
        assertFalse(html.contains("<b>"), html);   // 子模板覆盖父
    }

    @Test
    void extendsAddsUnionIntoWebGlobal() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = build();
        write(new File(sets, "divs/base/.adds"), "");
        write(new File(sets, "divs/base/base.js"), "console.log('base');");
        write(new File(sets, "divs/child/.extends"), "base");
        write(new File(sets, "divs/child/child.js"), "console.log('child');");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"child\",\"markdown\":\"# X\"}}}");
        SiteBuilder.build(sets, out, new File("src/assets"));
        String js = Files.readString(new File(out, "assets/js/web_global.min.js").toPath());
        assertTrue(js.contains("'base'"), js);
        assertTrue(js.contains("'child'"), js);
    }

    @Test
    void extendsGlobalUnionFlatFile() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = build();
        write(new File(sets, "divs/base/.global"), "");
        write(new File(sets, "divs/base/base.js"), "console.log('G');");
        write(new File(sets, "divs/child/.extends"), "base");
        write(new File(sets, "divs/t/template.html"), "<div>{{content}}</div>");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"deps\":[\"global:child\"],\"page\":{\"div-1\":{\"type\":\"t\"}}}");
        SiteBuilder.build(sets, out, new File("src/assets"));
        assertTrue(new File(out, "assets/js/child.min.js").isFile());   // 子 flat 含父内容
        String js = Files.readString(new File(out, "assets/js/child.min.js").toPath());
        assertTrue(js.contains("'G'"), js);
    }

    @Test
    void extendsMarkConflictErrors() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = build();
        write(new File(sets, "divs/base/.adds"), "");
        write(new File(sets, "divs/child/.extends"), "base");
        write(new File(sets, "divs/child/.global"), "");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"child\",\"markdown\":\"# X\"}}}");
        RuntimeException e = assertThrows(RuntimeException.class, () ->
                SiteBuilder.build(sets, out, new File("src/assets")));
        assertTrue(e.getMessage().contains(".adds/.global 互斥"), e.getMessage());
    }

    @Test
    void extendsMarkConflictOverrideOk() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = build();
        write(new File(sets, "divs/base/.adds"), "");
        write(new File(sets, "divs/base/base.js"), "console.log('base');");
        write(new File(sets, "divs/child/.extends"), "base");
        write(new File(sets, "divs/child/.global"), "");
        write(new File(sets, "divs/child/.contract"), "override\n");
        write(new File(sets, "divs/t/template.html"), "<div>{{content}}</div>");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"deps\":[\"global:child\"],\"page\":{\"div-1\":{\"type\":\"t\"}}}");
        SiteBuilder.build(sets, out, new File("src/assets"));   // override 覆盖父标记，不报错
        assertTrue(new File(out, "assets/js/child.min.js").isFile());
    }

    @Test
    void extendsCycleAndUnknownParentErrors() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = build();
        write(new File(sets, "divs/a/.extends"), "b");
        write(new File(sets, "divs/b/.extends"), "a");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"a\",\"markdown\":\"# X\"}}}");
        RuntimeException e = assertThrows(RuntimeException.class, () ->
                SiteBuilder.build(sets, out, new File("src/assets")));
        assertTrue(e.getMessage().contains("继承环"), e.getMessage());

        File sets2 = tmp.resolve("sets2").toFile();
        File out2 = tmp.resolve("output2").toFile();
        write(new File(sets2, "Environment.config"), "cname=example.com\n");
        write(new File(sets2, "divs/c/.extends"), "nope");
        write(new File(sets2, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"c\",\"markdown\":\"# X\"}}}");
        RuntimeException e2 = assertThrows(RuntimeException.class, () ->
                SiteBuilder.build(sets2, out2, new File("src/assets")));
        assertTrue(e2.getMessage().contains("父类型不存在"), e2.getMessage());
    }

    @Test
    void dedupFunctionLastWinsAndTierMinus1() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = build();
        write(new File(sets, "divs/base/base.js"), "function greet() { return 1; }\n");
        write(new File(sets, "divs/child/.extends"), "base");
        write(new File(sets, "divs/child/child.js"), "function greet() { return 2; }\n");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"child\",\"markdown\":\"# X\"}}}");
        SiteBuilder.build(sets, out, new File("src/assets"));
        String js = Files.readString(new File(out, "assets/index/index.min.js").toPath());
        assertFalse(js.contains("return 1"), js);                    // 父实现被"后到胜"清除
        assertTrue(js.contains("return 2"), js);
        assertTrue(js.indexOf("function") == js.lastIndexOf("function"), js);

        // minify=-1：注释保留档（.js 后缀 + 被删片段注释回插）
        File sets2 = tmp.resolve("sets2").toFile();
        File out2 = tmp.resolve("output2").toFile();
        write(new File(sets2, "Environment.config"), "cname=example.com\nminify=-1\n");
        write(new File(sets2, "divs/base/base.js"), "function greet() { return 1; }\n");
        write(new File(sets2, "divs/child/.extends"), "base");
        write(new File(sets2, "divs/child/child.js"), "function greet() { return 2; }\n");
        write(new File(sets2, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"child\",\"markdown\":\"# X\"}}}");
        SiteBuilder.build(sets2, out2, new File("src/assets"));
        String js2 = Files.readString(new File(out2, "assets/index/index.js").toPath());   // 档 -1 → .js
        assertTrue(js2.contains("ssvul-dedup: removed"), js2);
        assertTrue(js2.contains("return 1"), js2);   // 原文以注释保留
    }

    @Test
    void aLayerLetConstConflictErrors() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = build();
        write(new File(sets, "divs/a/a.js"), "const X = 1;\n");
        write(new File(sets, "divs/b/b.js"), "const X = 2;\n");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"a\",\"markdown\":\"# 1\"}," +
                "\"div-2\":{\"type\":\"b\",\"markdown\":\"# 2\"}}}");
        RuntimeException e = assertThrows(RuntimeException.class, () ->
                SiteBuilder.build(sets, out, new File("src/assets")));
        assertTrue(e.getMessage().contains("let/const/class 重名"), e.getMessage());
    }

    @Test
    void originDedupSharedAncestorOnce() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = build();
        write(new File(sets, "divs/base/base.js"), "console.log('base');\n");
        write(new File(sets, "divs/a/.extends"), "base");
        write(new File(sets, "divs/b/.extends"), "base");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"a\",\"markdown\":\"# 1\"}," +
                "\"div-2\":{\"type\":\"b\",\"markdown\":\"# 2\"}}}");
        SiteBuilder.build(sets, out, new File("src/assets"));
        String js = Files.readString(new File(out, "assets/index/index.min.js").toPath());
        assertTrue(js.indexOf("'base'") >= 0 && js.indexOf("'base'") == js.lastIndexOf("'base'"), js);   // 共享祖先只出一份
    }

    @Test
    void minifyTier1DedupWithoutCompression() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = build("cname=example.com", "minify=1");
        write(new File(sets, "divs/t/template.html"), "<div>{{content}}</div>");
        write(new File(sets, "divs/t/t.js"), "function f() { return 1; }\nfunction f() { return 2; }\n");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"# X\"}}}");
        SiteBuilder.build(sets, out, new File("src/assets"));
        String js = Files.readString(new File(out, "assets/index/index.min.js").toPath());
        assertTrue(js.indexOf("function") == js.lastIndexOf("function"), js);   // 去重生效
        assertFalse(js.contains("return 1"), js);
        assertTrue(js.contains("\n"), js);   // 不压缩（保留换行）
    }

    @Test
    void contractRequiredHookWarnings() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = build();
        write(new File(sets, "divs/w/.contract"), "init\nrender\n");
        write(new File(sets, "divs/w/w.js"),
                "window.SsvulDiv.register('w', { init: null, render: function(){} });\n");
        write(new File(sets, "divs/t/template.html"), "<div>{{content}}</div>");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"w\",\"markdown\":\"# X\"}}}");
        SiteBuilder.build(sets, out, new File("src/assets"));   // 警告不阻断：init 被置 null
    }

    @Test
    void presetTopbarChain() throws Exception {
        // 内置预设链 bar → navbar → topbar：模板继承、js 串联、家族注册名契约不误报
        File sets = tmp.resolve("sets").toFile();
        File out = build();
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"topbar\",\"params\":{\"brand\":\"S\"}," +
                "\"markdown\":\"# X\"}}}");
        SiteBuilder.build(sets, out, new File("src/assets"));
        String html = Files.readString(new File(out, "index.html").toPath());
        assertTrue(html.contains("ssvul-topbar"), html);              // 外层类型类
        assertTrue(html.contains("bar-menu"), html);                  // bar 基类模板继承
        String js = Files.readString(new File(out, "assets/index/index.min.js").toPath());
        assertTrue(js.indexOf("bar-menu") < js.indexOf("navbar ready"), js);   // bar.js → navbar.js
        assertTrue(js.indexOf("navbar ready") < js.indexOf("bar-scrolled"), js);  // → topbar.js（串联顺序）
        assertTrue(js.contains("__ssvulDivInit"), js);                // runtime 注入
        String css = Files.readString(new File(out, "assets/index/index.css").toPath());
        assertTrue(css.contains("position:sticky") || css.contains("position: sticky"), css);   // 压缩档冒号无空格
    }
}
