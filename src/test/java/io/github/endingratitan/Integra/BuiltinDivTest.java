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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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
        assertTrue(!html.contains("list-data") && !html.contains("assets/pre/div-libs/list/"), "构建期来源不内联数据、不注入预设函数");
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
        assertTrue(html.contains("assets/pre/div-libs/list/inline.js"), html);       // 预设按名自动注入
        assertTrue(new File(s[1], "assets/pre/div-libs/list/inline.js").isFile());   // 按需复制
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
        assertTrue(new File(s[1], "assets/pre/div-libs/list/shared.js").isFile());
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
        assertTrue(!html.contains("assets/pre/div-libs/list/"), "作者函数不由构建期注入预设");
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
    void listJsonPresetPureCsr() throws Exception {
        // ssvul:json = 纯 CSR：构建期**不生成数据**，只注入预设 js；数据由作者维护（sets/data → assets/data 照常复制）
        File[] s = site("");
        write(new File(s[0], "data/blog/index.json"),
                "[{\"link\":\"pages/blog/a/\",\"title\":\"甲文\",\"date\":\"2026-09-11\"}]");
        write(new File(s[0], "pages/csr.json"),
                "{\"name\":\"csr\",\"page\":{\"div-1\":{\"type\":\"list\",\"params\":" +
                "{\"src\":\"ssvul:json\",\"file\":\"assets/data/blog/index.json\",\"fields\":\"date,title\"}}}}");
        SiteBuilder.build(s[0], s[1], new File("src/assets"));
        String html = Files.readString(new File(s[1], "pages/csr/index.html").toPath());
        assertTrue(html.contains("data-src=\"ssvul:json\""), html);
        assertTrue(html.contains("data-file=\"assets/data/blog/index.json\""), html);
        assertTrue(html.contains("assets/pre/div-libs/list/json.js"), html);       // 预设按名自动注入
        assertTrue(!html.contains("list-data"), "纯 CSR 不内联数据");
        assertTrue(new File(s[1], "assets/data/blog/index.json").isFile(), "作者维护的数据文件按 data 规则复制");
        assertTrue(!new File(s[1], "assets/data/list").exists(), "不发射任何分片");
    }

    @Test
    void listJsonPresetRequiresFile() throws Exception {
        File[] s = site("");
        write(new File(s[0], "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"list\",\"params\":{\"src\":\"ssvul:json\"}}}}");
        try {
            SiteBuilder.build(s[0], s[1], new File("src/assets"));
            fail("应当因缺 file 参数而失败");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("需要在 params 里给 file"), e.getMessage());
        }
    }

    @Test
    void listS3PresetInjectsBucketAttrs() throws Exception {
        // ssvul:s3 = 纯 CSR 列目录：构建期只把桶信息解析成 data-bk-*（客户端零配置），不生成任何数据
        File[] s = site("bucket=[bk,https://cdn.example.com/,endpoint=https://s3.example.com/my-bucket,prefix=site]\n"
                + "session-ttl=1200\n");
        write(new File(s[0], "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{" +
                "\"div-1\":{\"type\":\"list\",\"params\":{\"src\":\"ssvul:s3\",\"dir\":\"blog\",\"pattern\":\"*.html\"}}," +
                "\"div-2\":{\"type\":\"list\",\"params\":{\"src\":\"ssvul:s3\",\"cache\":\"60\",\"max\":\"50\",\"pages\":\"1\"}}}}");
        SiteBuilder.build(s[0], s[1], new File("src/assets"));
        String html = Files.readString(new File(s[1], "index.html").toPath());
        assertTrue(html.contains("data-src=\"ssvul:s3\""), html);
        assertTrue(html.contains("assets/pre/div-libs/list/s3.js"), html);                 // 预设按名自动注入
        assertTrue(html.contains("data-bk-name=\"bk\""), html);
        assertTrue(html.contains("data-bk-href=\"https://cdn.example.com\""), html);   // href 末尾斜线已规范化
        assertTrue(html.contains("data-bk-endpoint=\"https://s3.example.com/my-bucket\""), html);
        assertTrue(html.contains("data-bk-prefix=\"site/blog\""), html);          // bucket prefix + params.dir
        assertTrue(html.contains("data-bk-ttl=\"1200\""), html);                  // 第二个列表用站点 session-ttl
        assertTrue(html.contains("data-bk-ttl=\"60\""), html);                    // 第一个列表被 params.cache 覆盖
        assertTrue(html.contains("data-bk-prefix=\"site\""), html);               // 未写 dir → 只有 bucket prefix
        assertTrue(!html.contains("list-data"), "列目录来源不内联数据");
        assertTrue(!new File(s[1], "assets/data/list").exists(), "不发射任何分片");
    }

    @Test
    void listS3PresetRequiresEndpoint() throws Exception {
        File[] s = site("bucket=[bk,https://cdn.example.com]\n");
        write(new File(s[0], "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"list\",\"params\":{\"src\":\"ssvul:s3\"}}}}");
        try {
            SiteBuilder.build(s[0], s[1], new File("src/assets"));
            fail("应当因 bucket 缺 endpoint 而失败");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("需要给 bucket bk 声明 endpoint"), e.getMessage());
        }
    }

    @Test
    void listS3PresetBucketSelection() throws Exception {
        // 多桶未指定 → 报错并列出可用调用名；指定不存在的桶 → 报错
        File[] s = site("bucket=[a,https://a.example.com,endpoint=https://s3.example.com/a]\n"
                + "bucket=[b,https://b.example.com,endpoint=https://s3.example.com/b]\n");
        write(new File(s[0], "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"list\",\"params\":{\"src\":\"ssvul:s3\"}}}}");
        try {
            SiteBuilder.build(s[0], s[1], new File("src/assets"));
            fail("多桶未指定应当失败");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("需要 params.bucket 指定"), e.getMessage());
            assertTrue(e.getMessage().contains("a / b"), e.getMessage());
        }
        File[] s2 = site("bucket=[a,https://a.example.com,endpoint=https://s3.example.com/a]\n");
        write(new File(s2[0], "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"list\",\"params\":{\"src\":\"ssvul:s3\",\"bucket\":\"nope\"}}}}");
        try {
            SiteBuilder.build(s2[0], s2[1], new File("src/assets"));
            fail("不存在的 bucket 应当失败");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("params.bucket 不存在"), e.getMessage());
        }
    }

    @Test
    void listS3PresetRejectsReservedAndBadParams() throws Exception {
        File[] s = site("bucket=[bk,https://cdn.example.com,endpoint=https://s3.example.com/bk]\n");
        write(new File(s[0], "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"list\",\"params\":" +
                "{\"src\":\"ssvul:s3\",\"bk-ttl\":\"1\"}}}}");
        try {
            SiteBuilder.build(s[0], s[1], new File("src/assets"));
            fail("手写 data-bk-* 应当失败");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("保留键"), e.getMessage());
        }
        File[] s2 = site("bucket=[bk,https://cdn.example.com,endpoint=https://s3.example.com/bk]\n");
        write(new File(s2[0], "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"list\",\"params\":" +
                "{\"src\":\"ssvul:s3\",\"max\":\"5000\",\"cache\":\"x\",\"dir\":\"../x\"}}}}");
        try {
            SiteBuilder.build(s2[0], s2[1], new File("src/assets"));
            fail("非法 max/cache/dir 应当失败");
        } catch (RuntimeException e) {
            String m = e.getMessage();
            assertTrue(m.contains("params.max 须为 1..1000"), m);
            assertTrue(m.contains("params.cache 须为非负整数"), m);
            assertTrue(m.contains("ssvul:s3 dir 不合法"), m);
        }
    }

    @Test
    void mdCsrInjectsLibraryAndOptionalMath() throws Exception {
        // md-csr：库 + hljs 自动注入；math=on 才注入 KaTeX（重资产按需）；无构建期 md 也要 md.css
        File[] s = site("");
        write(new File(s[0], "pages/reader.json"),
                "{\"name\":\"reader\",\"page\":{" +
                "\"div-1\":{\"type\":\"md-csr\",\"params\":{\"file\":\"data/a.md\"}}," +
                "\"div-2\":{\"type\":\"md-csr\",\"params\":{\"file\":\"data/b.md\",\"math\":\"on\"}}}}");
        SiteBuilder.build(s[0], s[1], new File("src/assets"));
        String html = Files.readString(new File(s[1], "pages/reader/index.html").toPath());
        assertTrue(html.contains("assets/pre/div-libs/md-csr/md-renderer.js"), html);
        assertTrue(html.contains("assets/pre/div-libs/md-csr/md-footnotes.js"), html);       // 10 个库文件都在
        String themeCls = SiteMdThemes.classNameOf("pre-assets/md/css/md.css");
        assertTrue(html.contains("assets/css/" + themeCls + ".css"), "md-csr 也要 md 主题（scoped 副本）: " + html);
        assertTrue(html.contains(themeCls), "主题类要挂在 md-csr div 的包装元素上: " + html);
        assertTrue(html.contains("assets/pre/lib/hljs/hljs.min.js"), "代码块上色：hljs 自动注入");
        assertTrue(html.contains("assets/pre/lib/katex/katex.min.css"), "math=on → KaTeX css");
        assertTrue(html.contains("assets/pre/lib/katex/katex.min.js"), "math=on → KaTeX js");
        assertTrue(html.contains("assets/pre/md/js/md-math.js"), "math=on → md-math 预设");
        assertTrue(new File(s[1], "assets/pre/lib/katex/fonts").isDirectory(), "KaTeX 字体目录连带复制");
        assertTrue(new File(s[1], "assets/pre/div-libs/md-csr/md-footnotes.js").isFile());
    }

    @Test
    void mdCsrWithoutMathSkipsKatex() throws Exception {
        // 按需注入：没写 math=on 就不该为一个可能不含公式的页面拖 1MB KaTeX
        File[] s = site("");
        write(new File(s[0], "pages/plain.json"),
                "{\"name\":\"plain\",\"page\":{\"div-1\":{\"type\":\"md-csr\",\"params\":{\"file\":\"data/a.md\"}}}}");
        SiteBuilder.build(s[0], s[1], new File("src/assets"));
        String html = Files.readString(new File(s[1], "pages/plain/index.html").toPath());
        assertTrue(!html.contains("katex"), "未声明 math=on → 不注入 KaTeX: " + html);
        assertTrue(!new File(s[1], "assets/pre/lib/katex").exists(), "KaTeX 也不复制");
    }

    @Test
    void listS3PresetOfflineWarnsOncePerPage() throws Exception {
        // R16：列目录运行时必然联网 → offline=1 的站点每页只警告一次（同页多个列表不刷屏）
        File[] s = site("bucket=[bk,https://cdn.example.com,endpoint=https://s3.example.com/bk]\noffline=1\n");
        write(new File(s[0], "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{" +
                "\"div-1\":{\"type\":\"list\",\"params\":{\"src\":\"ssvul:s3\"}}," +
                "\"div-2\":{\"type\":\"list\",\"params\":{\"src\":\"ssvul:s3\"}}}}");
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream old = System.out;
        System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try {
            SiteBuilder.build(s[0], s[1], new File("src/assets"));
        } finally {
            System.setOut(old);
        }
        String log = buf.toString(StandardCharsets.UTF_8);
        assertTrue(log.contains("运行时需要联网"), log);
        assertEquals(1, log.split("运行时需要联网", -1).length - 1, "同页只警告一次: " + log);
    }

    @Test
    void sessionTtlValidated() throws Exception {
        File[] s = site("bucket=[bk,https://cdn.example.com,endpoint=https://s3.example.com/bk]\nsession-ttl=2h\n");
        write(new File(s[0], "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"# X\"}}}");
        try {
            SiteBuilder.build(s[0], s[1], new File("src/assets"));
            fail("非法 session-ttl 应当失败");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("session-ttl 值须为非负整数"), e.getMessage());
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
