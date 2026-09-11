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

/** 内置 div：search 索引 / list 列表（四种来源与自我排除）/ 其余客户端 div 装配 */
public class BuiltinDivTest {

    @TempDir
    Path tmp;

    private void write(File f, String s) throws Exception {
        Files.createDirectories(f.getParentFile().toPath());
        Files.writeString(f.toPath(), s);
    }

    private File[] site(String envExtra) throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = tmp.resolve("output").toFile();
        write(new File(sets, "Environment.config"), "cname=example.com\n" + envExtra);
        write(new File(sets, "divs/t/template.html"), "<div>{{content}}</div>");
        write(new File(sets, "pages/blog/a.md"), "# 甲文\n\n正文甲\n");
        write(new File(sets, "pages/blog/b.json"),
                "{\"name\":\"b\",\"title\":\"乙文\",\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"# 乙\\n\\n正文乙\"}}}");
        return new File[]{sets, out};
    }

    @Test
    void searchIndexGeneratedWithEntries() throws Exception {
        File[] s = site("");
        write(new File(s[0], "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"search\",\"params\":{\"placeholder\":\"搜\"}}}}");
        SiteBuilder.build(s[0], s[1], new File("src/assets"));
        File idx = new File(s[1], "assets/data/search-index.json");
        assertTrue(idx.isFile());
        String json = Files.readString(idx.toPath());
        assertTrue(json.contains("甲文"), json);
        assertTrue(json.contains("乙文"), json);
        assertTrue(json.contains("pages/blog/a/"), json);
        String html = Files.readString(new File(s[1], "index.html").toPath());
        assertTrue(html.contains("data-depth=\"0\""), html);
        assertTrue(html.contains("search-index.json") || html.contains("placeholder=\"搜\""), html);
    }

    @Test
    void listBuildPageIndexStaticZeroJs() throws Exception {
        // 默认 src=build:page-index：构建期直接出静态条目，且本页**不注入 list 的 js**（零 JS 页面）
        File[] s = site("");
        write(new File(s[0], "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"list\",\"params\":" +
                "{\"dir\":\"pages/blog\",\"pattern\":\"*.md\",\"fields\":\"date,title,excerpt\"}}}}");
        SiteBuilder.build(s[0], s[1], new File("src/assets"));
        String html = Files.readString(new File(s[1], "index.html").toPath());
        assertTrue(html.contains("<ul class=\"list-items\">"), html);
        assertTrue(html.contains("<li class=\"list-item\">"), html);
        assertTrue(html.contains("<span class=\"list-date\">"), html);
        assertTrue(html.contains("<a class=\"list-title\" href=\"pages/blog/a/\">甲文</a>"), html);
        assertTrue(html.contains("<p class=\"list-excerpt\">"), html);
        assertTrue(html.contains("data-family=\"list\""), html);
        assertTrue(!html.contains("list-data") && !html.contains("assets/pre/list/"), "构建期来源不内联数据、不注入预设函数");
        assertTrue(!new File(s[1], "assets/index/index.min.js").isFile(), "构建期 list 不应产生页面 js");
        assertTrue(Files.readString(new File(s[1], "assets/index/index.css").toPath()).contains(".list-item"), "只要 css");
    }

    @Test
    void listEntriesSortedByTitleWhenSameDate() throws Exception {
        // 构建期是唯一的排序实现（客户端不排序）：日期倒序 → 同日按标题码点序（乙 U+4E59 < 甲 U+7532）
        File[] s = site("");
        write(new File(s[0], "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"list\",\"params\":{\"dir\":\"pages/blog\",\"pattern\":\"*\"}}}}");
        SiteBuilder.build(s[0], s[1], new File("src/assets"));
        String html = Files.readString(new File(s[1], "index.html").toPath());
        int i1 = html.indexOf("乙文"), i2 = html.indexOf("甲文");
        assertTrue(i1 >= 0 && i1 < i2, html);
    }

    @Test
    void listInlinePreset() throws Exception {
        File[] s = site("");
        write(new File(s[0], "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"list\",\"params\":" +
                "{\"src\":\"ssvul:inline\",\"dir\":\"pages/blog\",\"pattern\":\"*\"}}}}");
        SiteBuilder.build(s[0], s[1], new File("src/assets"));
        String html = Files.readString(new File(s[1], "index.html").toPath());
        assertTrue(html.contains("data-src=\"ssvul:inline\""), html);
        assertTrue(html.contains("<script type=\"application/json\" class=\"list-data\">"), html);
        assertTrue(html.contains("assets/pre/list/inline.js"), html);       // 预设按名自动注入
        assertTrue(new File(s[1], "assets/pre/list/inline.js").isFile());   // 按需复制
        String data = html.substring(html.indexOf("class=\"list-data\">") + "class=\"list-data\">".length());
        data = data.substring(0, data.indexOf("</script>"));
        assertTrue(data.startsWith("["), data);                             // 内联 = 条目数组（与分片同形）
        assertTrue(data.contains("\"link\":\"pages/blog/a/\""), data);
        assertTrue(!data.contains("\"text\""), data);                       // 不带全文（省流量）
        String js = Files.readString(new File(s[1], "assets/index/index.min.js").toPath());
        assertTrue(js.contains("register('list'") || js.contains("register(\"list\""), js);
    }

    @Test
    void listSharedShard() throws Exception {
        File[] s = site("");
        write(new File(s[0], "pages/a.json"),
                "{\"name\":\"a\",\"page\":{\"div-1\":{\"type\":\"list\",\"params\":" +
                "{\"src\":\"ssvul:shared\",\"dir\":\"pages/blog\"}}}}");
        write(new File(s[0], "pages/b.json"),
                "{\"name\":\"b\",\"page\":{\"div-1\":{\"type\":\"list\",\"params\":{\"src\":\"ssvul:shared\"}}}}");
        SiteBuilder.build(s[0], s[1], new File("src/assets"));
        File shard = new File(s[1], "assets/data/list/pages/blog.json");
        assertTrue(shard.isFile());
        String json = Files.readString(shard.toPath());
        assertTrue(json.contains("甲文") && json.contains("\"link\":\"pages/blog/a/\""), json);
        assertTrue(!json.contains("\"text\""), json);
        assertTrue(new File(s[1], "assets/data/list/index.json").isFile());   // dir 空 → index.json
        assertTrue(new File(s[1], "assets/pre/list/shared.js").isFile());
        String html = Files.readString(new File(s[1], "pages/a/index.html").toPath());
        assertTrue(html.contains("data-src=\"ssvul:shared\""), html);
        assertTrue(!html.contains("list-data"), "共享来源不内联数据");
    }

    @Test
    void listAuthorFunctionBareName() throws Exception {
        // 裸函数名：构建期只写 data-src（js 由作者自己引入），仍需 list 组件本身
        File[] s = site("");
        write(new File(s[0], "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"list\",\"params\":{\"src\":\"我的取数函数\"}}}}");
        SiteBuilder.build(s[0], s[1], new File("src/assets"));
        String html = Files.readString(new File(s[1], "index.html").toPath());
        assertTrue(html.contains("data-src=\"我的取数函数\""), html);
        assertTrue(!html.contains("assets/pre/list/"), "作者函数不由构建期注入预设");
        assertTrue(new File(s[1], "assets/index/index.min.js").isFile(), "裸名来源需要 list 组件 js");
    }

    @Test
    void listBadSrcErrors() throws Exception {
        File[] s = site("");
        write(new File(s[0], "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"list\",\"params\":{\"src\":\"ssvul:nope\"}}}}");
        try {
            SiteBuilder.build(s[0], s[1], new File("src/assets"));
            fail("应当因未知预设而失败");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("未知的官方预设"), e.getMessage());
        }
    }

    @Test
    void listSharedBadDir() throws Exception {
        File[] s = site("");
        write(new File(s[0], "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"list\",\"params\":" +
                "{\"src\":\"ssvul:shared\",\"dir\":\"../x\"}}}}");
        try {
            SiteBuilder.build(s[0], s[1], new File("src/assets"));
            fail("应当因 dir 不合法而失败");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("ssvul:shared dir 不合法"), e.getMessage());
        }
    }

    @Test
    void listReservedDataDir() throws Exception {
        File[] s = site("");
        write(new File(s[0], "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"list\",\"params\":{\"src\":\"ssvul:shared\"}}}}");
        write(new File(s[0], "data/list/x.json"), "{}");   // 生成器保留目录，碰撞报错
        try {
            SiteBuilder.build(s[0], s[1], new File("src/assets"));
            fail("应当因保留目录碰撞而失败");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("sets/data/list 为生成器保留目录"), e.getMessage());
        }
    }

    @Test
    void breadcrumbPagerBacktotopAssembled() throws Exception {
        File[] s = site("");
        write(new File(s[0], "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{" +
                "\"div-1\":{\"type\":\"breadcrumb\"}," +
                "\"div-2\":{\"type\":\"pager\",\"params\":{\"current\":1,\"total\":3}}," +
                "\"div-3\":{\"type\":\"backtotop\"}}}");
        SiteBuilder.build(s[0], s[1], new File("src/assets"));
        String js = Files.readString(new File(s[1], "assets/index/index.min.js").toPath());
        assertTrue(js.contains("register('breadcrumb'") || js.contains("register(\"breadcrumb\""), js);
        assertTrue(js.contains("pager") && js.contains("backtotop"), js);
        assertTrue(js.contains("__ssvulDivInit"), js);   // runtime 注入
    }

    @Test
    void newThemesAutoLinked() throws Exception {
        File[] s = site("");
        write(new File(s[0], "pages/sepia.json"),
                "{\"name\":\"sepia\",\"theme\":\"sepia\",\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"# X\"}}}");
        write(new File(s[0], "pages/green.json"),
                "{\"name\":\"green\",\"theme\":\"green\",\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"# X\"}}}");
        SiteBuilder.build(s[0], s[1], new File("src/assets"));
        assertTrue(Files.readString(new File(s[1], "pages/sepia/index.html").toPath())
                .contains("md-code-sepia.css"));
        assertTrue(Files.readString(new File(s[1], "pages/green/index.html").toPath())
                .contains("md-code-green.css"));
    }

    @Test
    void palettePickerAssembled() throws Exception {
        File[] s = site("");
        write(new File(s[0], "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"palette-picker\"," +
                "\"params\":{\"palettes\":\"clear,sepia\",\"labels\":\"默认,护眼\"}}}}");
        SiteBuilder.build(s[0], s[1], new File("src/assets"));
        String js = Files.readString(new File(s[1], "assets/index/index.min.js").toPath());
        // palette-picker = 整页主题切换（v0.3.0 修复：旧版 setPalette 只改 code-ui 配色变量）
        assertTrue(js.contains("SsvulTheme.set"), js);
        // runtime 必须前置（div js 顶层即调 register，后置会 ReferenceError 全脚本中止——真实浏览器踩过）
        assertTrue(js.indexOf("__ssvulDivInit") < js.indexOf("register('palette-picker'"), js);
        String html = Files.readString(new File(s[1], "index.html").toPath());
        assertTrue(html.contains("ssvul-palette-picker"), html);
        // 使用 palette-picker 的页面必须全量链接所有主题 css，否则按钮切换无效果
        assertTrue(html.contains("md-code-dark.css") && html.contains("md-code-sepia.css")
                && html.contains("md-code-green.css"), html);
    }

}
