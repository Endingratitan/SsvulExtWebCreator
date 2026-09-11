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

/** 内置 div：search 索引 / shower 数据（排序+随机种子）/ 其余客户端 div 装配 */
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
    void showerDataSortedByTitleAndDataJsonAttr() throws Exception {
        File[] s = site("");
        write(new File(s[0], "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"shower\",\"params\":" +
                "{\"dir\":\"pages/blog\",\"count\":2,\"order\":\"name\",\"pattern\":\"*\"}}}}");
        SiteBuilder.build(s[0], s[1], new File("src/assets"));
        String html = Files.readString(new File(s[1], "index.html").toPath());
        assertTrue(html.contains("shower-data"), html);                          // 内联注入（零请求/file:// 可用）
        int i1 = html.indexOf("乙文"), i2 = html.indexOf("甲文");
        assertTrue(i1 >= 0 && i1 < i2, html);                                    // name 排序
        assertTrue(html.contains("data-family=\"shower\""), html);
    }

    @Test
    void showerInlineParamsKeyOrderStable() throws Exception {
        // R4：params 曾用 Map.of（迭代顺序随 JVM 的 ImmutableCollections SALT 变）→ 同源构建字节不同，
        // 会让 v3-⑧ 的内嵌哈希/immutable 缓存/build-manifest 失效；现固定为契约顺序
        File[] s = site("");
        write(new File(s[0], "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"shower\",\"params\":" +
                "{\"dir\":\"pages/blog\",\"pattern\":\"*.md\",\"count\":3,\"order\":\"date\",\"fields\":\"title,date,excerpt\"}}}}");
        SiteBuilder.build(s[0], s[1], new File("src/assets"));
        String html = Files.readString(new File(s[1], "index.html").toPath());
        assertTrue(html.contains("\"params\":{\"dir\":\"pages/blog\",\"pattern\":\"*.md\",\"count\":3,"
                + "\"order\":\"date\",\"fields\":\"title,date,excerpt\"}"), html);
    }

    @Test
    void showerRandomSeedReproducible() throws Exception {
        File[] s = site("");
        write(new File(s[0], "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"shower\",\"params\":" +
                "{\"dir\":\"pages/blog\",\"order\":\"random\",\"seed\":42}}}}");
        SiteBuilder.build(s[0], s[1], new File("src/assets"));
        String j1 = Files.readString(new File(s[1], "index.html").toPath());
        File out2 = tmp.resolve("output2").toFile();
        SiteBuilder.build(s[0], out2, new File("src/assets"));
        String j2 = Files.readString(new File(out2, "index.html").toPath());
        assertEquals(j1, j2);   // 固定 seed 可复现
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

    @Test
    void showerSharedIndex() throws Exception {
        File[] s = site("");
        write(new File(s[0], "pages/a.json"),
                "{\"name\":\"a\",\"page\":{\"div-1\":{\"type\":\"shower\"," +
                "\"params\":{\"dir\":\"\",\"pattern\":\"*\",\"count\":1,\"shared\":true}}}}");
        write(new File(s[0], "pages/b.json"),
                "{\"name\":\"b\",\"page\":{\"div-1\":{\"type\":\"shower\"," +
                "\"params\":{\"dir\":\"pages\",\"count\":2,\"shared\":true}}}}");
        SiteBuilder.build(s[0], s[1], new File("src/assets"));
        // 按目录分片：dir 空 → index.json（全站）；dir=pages → pages.json；未声明的目录不发射
        File idx = new File(s[1], "assets/data/shower/index.json");
        assertTrue(idx.isFile());
        String json = Files.readString(idx.toPath());
        assertTrue(json.contains("甲文") && json.contains("乙文"), json);
        assertTrue(!json.contains("\"text\""), json);                       // 精简索引不带全文
        assertTrue(json.contains("\"title\":\"a\"") && json.contains("\"title\":\"b\""), json);
        String pagesJson = Files.readString(new File(s[1], "assets/data/shower/pages.json").toPath());
        assertTrue(pagesJson.contains("甲文") && pagesJson.contains("\"link\":\"pages/blog/a/\""), pagesJson);
        assertTrue(!new File(s[1], "assets/data/shower/pages/blog.json").isFile());   // 只发射声明的目录
        // 共享模式页面不内联数据，仅打 data-shared 标记（客户端过滤）
        String html = Files.readString(new File(s[1], "pages/a/index.html").toPath());
        assertTrue(!html.contains("shower-data"), html);
        assertTrue(html.contains("data-shared=\"true\""), html);
        assertTrue(html.contains("data-dir") && html.contains("data-count"), html);
        String htmlB = Files.readString(new File(s[1], "pages/b/index.html").toPath());
        assertTrue(!htmlB.contains("shower-data") && htmlB.contains("data-shared=\"true\""), htmlB);
    }

    @Test
    void showerSharedBadDir() throws Exception {
        File[] s = site("");
        write(new File(s[0], "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"shower\"," +
                "\"params\":{\"dir\":\"../x\",\"shared\":true}}}}");
        try {
            SiteBuilder.build(s[0], s[1], new File("src/assets"));
            fail("应当因 dir 不合法而失败");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("shower 共享模式 dir 不合法"), e.getMessage());
        }
    }

    @Test
    void showerSharedReservedDataDir() throws Exception {
        File[] s = site("");
        write(new File(s[0], "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"shower\"," +
                "\"params\":{\"shared\":true}}}}");
        write(new File(s[0], "data/shower/x.json"), "{}");   // 生成器保留目录，碰撞报错
        try {
            SiteBuilder.build(s[0], s[1], new File("src/assets"));
            fail("应当因保留目录碰撞而失败");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("sets/data/shower 为生成器保留目录"), e.getMessage());
        }
    }
}
