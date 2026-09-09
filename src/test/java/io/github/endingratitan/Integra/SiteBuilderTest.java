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

/** 端到端管线测试：依赖项目根的 page.schema.json 与 src/assets 预设（gradle 工作目录为项目根） */
public class SiteBuilderTest {

    @TempDir
    Path tmp;

    private void write(File f, String s) throws Exception {
        Files.createDirectories(f.getParentFile().toPath());
        Files.writeString(f.toPath(), s);
    }

    @Test
    void fullPipeline() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = tmp.resolve("output").toFile();
        write(new File(sets, "Environment.config"),
                "cname=example.com\nreadme=1\nbucket=(bk,https://bucket.example.com)\n");
        write(new File(sets, "divs/navbar/template.html"), "<nav>{{brand}}</nav>");
        write(new File(sets, "divs/navbar/nav.js"), "console.log(1);");
        write(new File(sets, "divs/navbar/nav.css"), ".nav{}");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"navbar\",\"params\":{\"brand\":\"Ssvul\"}," +
                "\"markdown\":\"# 你好\\n\\n**粗** $x^2$ 与 \\\\$5\\n\\n[站内](@page/blog/post) ![图](bk/img/logo.png)\"}}}");
        write(new File(sets, "pages/blog/post.md"), "# 博文\n");
        write(new File(sets, "pages/about.json"),
                "{\"name\":\"about\",\"page\":{\"div-1\":{\"type\":\"navbar\",\"params\":{\"brand\":\"A\"}}}}");
        write(new File(sets, "data/readme/README.md"), "# R\n![图](bk/img/x.png)\n");
        write(new File(sets, "data/lib/data.json"), "{\"a\":1}");

        SiteBuilder.build(sets, out, new File("src/assets"));

        assertTrue(new File(out, "index.html").isFile());
        assertTrue(new File(out, "CNAME").isFile());
        assertTrue(new File(out, "pages/blog/post/index.html").isFile());
        assertTrue(new File(out, "README.md").isFile());
        assertTrue(new File(out, "assets/data/lib/data.json").isFile());
        assertTrue(new File(out, "assets/pre/md/css/md.css").isFile());   // 预设按需复制

        String index = Files.readString(new File(out, "index.html").toPath());
        assertTrue(index.contains("<nav>Ssvul</nav>"), index);
        assertTrue(index.contains("<span class=\"md-math\">$x^2$</span>"));
        assertTrue(index.contains("$5"), index);                            // \$ 转义
        assertTrue(index.contains("https://bucket.example.com/img/logo.png")); // bucket 替换
        assertTrue(index.contains("href=\"pages/blog/post/\""));            // @page 深度 0
        assertTrue(new File(out, "assets/index/index.min.js").isFile());   // INDEX 页文件特例目录（minify 默认开）
        assertTrue(new File(out, "assets/index/index.css").isFile());
        assertTrue(index.contains("src=\"assets/index/index.min.js\""), index);
        assertTrue(index.contains("href=\"assets/index/index.css\""), index);

        // 普通页面：<name>.min.js/css 与 html 同级
        assertTrue(new File(out, "pages/about/about.min.js").isFile());
        assertTrue(new File(out, "pages/about/about.css").isFile());
        String about = Files.readString(new File(out, "pages/about/index.html").toPath());
        assertTrue(about.contains("src=\"about.min.js\""), about);
        assertTrue(about.contains("href=\"about.css\""), about);

        String readme = Files.readString(new File(out, "README.md").toPath());
        assertTrue(readme.contains("https://bucket.example.com/img/x.png")); // readme 替换（深度 0）
    }

    @Test
    void errorsAbortBeforeWrite() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = tmp.resolve("output").toFile();
        write(new File(sets, "Environment.config"), "cname=example.com\n");
        write(new File(sets, "data/lib/x.js"), "console.log(1);");   // data 禁 js
        RuntimeException e = assertThrows(RuntimeException.class, () ->
                SiteBuilder.build(sets, out, new File("src/assets")));
        assertTrue(e.getMessage().contains("data 目录禁止 js/css"));
        assertFalse(out.exists());   // 报错时不写盘
    }

    @Test
    void missingCnameErrors() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = tmp.resolve("output").toFile();
        write(new File(sets, "Environment.config"), "readme=1\n");
        RuntimeException e = assertThrows(RuntimeException.class, () ->
                SiteBuilder.build(sets, out, new File("src/assets")));
        assertTrue(e.getMessage().contains("缺少必填键 cname"));
    }

    @Test
    void missingConfigFileReportsOnce() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = tmp.resolve("output").toFile();
        sets.mkdirs();
        RuntimeException e = assertThrows(RuntimeException.class, () ->
                SiteBuilder.build(sets, out, new File("src/assets")));
        assertTrue(e.getMessage().contains("缺少 sets/Environment.config"), e.getMessage());
        assertFalse(e.getMessage().contains("缺少必填键 cname"), e.getMessage());   // 同一根因不重复报
    }

    @Test
    void badPageLinkErrors() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = tmp.resolve("output").toFile();
        write(new File(sets, "Environment.config"), "cname=example.com\n");
        write(new File(sets, "divs/t/template.html"), "<div>{{content}}</div>");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"[x](@page/no-such)\"}}}");
        RuntimeException e = assertThrows(RuntimeException.class, () ->
                SiteBuilder.build(sets, out, new File("src/assets")));
        // div 类型不存在 + 站内互链目标不存在 都会被收集
        assertTrue(e.getMessage().contains("站内互链目标不存在"), e.getMessage());
    }

    @Test
    void outerOfflineSubstitution() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = tmp.resolve("output").toFile();
        write(new File(sets, "Environment.config"),
                "cname=example.com\noffline=1\nbucket=(bk,https://bucket.example.com)\n");
        write(new File(sets, "outer/bk/img/logo.png"), "PNGDATA");
        write(new File(sets, "divs/t/template.html"), "<div>{{content}}</div>");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"![图](bk/img/logo.png)\"}}}");
        SiteBuilder.build(sets, out, new File("src/assets"));
        String index = Files.readString(new File(out, "index.html").toPath());
        assertTrue(index.contains("assets/outer/bk/img/logo.png"), index);
        assertFalse(index.contains("bucket.example.com"), index);   // 本地替换后不再出现线上域名
        assertTrue(new File(out, "assets/outer/bk/img/logo.png").isFile());
    }

    @Test
    void outerOfflineMissErrors() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = tmp.resolve("output").toFile();
        write(new File(sets, "Environment.config"),
                "cname=example.com\noffline=1\nbucket=(bk,https://bucket.example.com)\n");
        write(new File(sets, "divs/t/template.html"), "<div>{{content}}</div>");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"![图](bk/missing.png)\"}}}");
        RuntimeException e = assertThrows(RuntimeException.class, () ->
                SiteBuilder.build(sets, out, new File("src/assets")));
        assertTrue(e.getMessage().contains("未在 outer 镜像"), e.getMessage());
    }

    @Test
    void outerOnlineKeepsUrl() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = tmp.resolve("output").toFile();
        write(new File(sets, "Environment.config"),
                "cname=example.com\nbucket=(bk,https://bucket.example.com)\n");
        write(new File(sets, "outer/bk/img/logo.png"), "PNGDATA");   // outer 存在 → 对照警告生效（未命中的引用）
        write(new File(sets, "divs/t/template.html"), "<div>{{content}}</div>");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"![图](bk/other.png)\"}}}");
        SiteBuilder.build(sets, out, new File("src/assets"));   // 不抛异常，仅警告
        String index = Files.readString(new File(out, "index.html").toPath());
        assertTrue(index.contains("https://bucket.example.com/other.png"), index);
    }

    @Test
    void divMdOptionsCascadeAndPrefixedIds() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = tmp.resolve("output").toFile();
        write(new File(sets, "Environment.config"), "cname=example.com\n");
        write(new File(sets, "divs/t/template.html"), "<section>{{content}}</section>{{footnotes}}");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"md-options\":{\"toc\":true}," +
                "\"page\":{" +
                "\"div-1\":{\"type\":\"t\",\"md-options\":{\"footnote-display\":\"inline\",\"toc\":false}," +
                "\"markdown\":\"# 甲\\n\\n## 小节甲\\n\\n注[^1]\\n\\n[^1]: 甲注\"}," +
                "\"div-2\":{\"type\":\"t\",\"markdown\":\"# 乙\\n\\n## 小节乙\\n\\n注[^2]\\n\\n[^2]: 乙注\"}}}");
        SiteBuilder.build(sets, out, new File("src/assets"));
        String index = Files.readString(new File(out, "index.html").toPath());
        // 锚点 div 前缀：两个 div 的标题 id 互不重复，且不再有裸 s1
        assertTrue(index.contains("id=\"div-1-s1\""), index);
        assertTrue(index.contains("id=\"div-2-s2\""), index);
        assertFalse(index.contains("id=\"s1\""), index);
        // 级联：div-1 覆盖 toc=false 无目录；div-2 继承页面 toc=true 生成唯一目录，链接带前缀
        assertTrue(index.indexOf("md-toc") >= 0 && index.indexOf("md-toc") == index.lastIndexOf("md-toc"), index);
        assertTrue(index.contains("href=\"#div-2-s2\""), index);
        // 脚注：div-1 inline 就地显示（带前缀 id）；div-2 继承 end 且页面只有一处脚注区
        assertTrue(index.contains("id=\"div-1-fn-1\""), index);
        assertTrue(index.indexOf("md-footnotes") >= 0
                && index.indexOf("md-footnotes") == index.lastIndexOf("md-footnotes"), index);
    }

    @Test
    void divMdOptionsStrictApplies() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = tmp.resolve("output").toFile();
        write(new File(sets, "Environment.config"), "cname=example.com\n");
        write(new File(sets, "divs/t/template.html"), "<section>{{content}}</section>");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"t\"," +
                "\"md-options\":{\"mode\":\"strict\"},\"markdown\":\"- a\\n  - b\\n    - c\\n\"}}}");
        RuntimeException e = assertThrows(RuntimeException.class, () ->
                SiteBuilder.build(sets, out, new File("src/assets")));
        assertTrue(e.getMessage().contains("列表嵌套超过两层"), e.getMessage());   // div 级 strict 真的生效
    }

    @Test
    void engineDefaultHljsAutoInjected() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = tmp.resolve("output").toFile();
        write(new File(sets, "Environment.config"), "cname=example.com\n");
        write(new File(sets, "divs/t/template.html"), "<section>{{content}}</section>");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"```java\\nint x = 1;\\n```\"}}}");
        SiteBuilder.build(sets, out, new File("src/assets"));
        String index = Files.readString(new File(out, "index.html").toPath());
        for (String a : new String[]{"assets/pre/lib/hljs/hljs.min.js",
                "assets/pre/md/js/token-map.js", "assets/pre/md/js/md-highlight.js"}) {
            assertTrue(index.indexOf(a) >= 0 && index.indexOf(a) == index.lastIndexOf(a), "缺或重复: " + a);
        }
    }

    @Test
    void engineSimpleNoClientAssets() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = tmp.resolve("output").toFile();
        write(new File(sets, "Environment.config"), "cname=example.com\n");
        write(new File(sets, "divs/t/template.html"), "<section>{{content}}</section>");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"engine\":\"simple\",\"page\":{\"div-1\":{\"type\":\"t\"," +
                "\"markdown\":\"```java\\nint x = 1;\\n```\"}}}");
        SiteBuilder.build(sets, out, new File("src/assets"));
        String index = Files.readString(new File(out, "index.html").toPath());
        assertTrue(index.contains("<span class=\"tk-kw\">int</span>"), index);   // 构建期上色
        assertFalse(index.contains("hljs.min.js"), index);                      // 零客户端资产
        assertFalse(index.contains("token-map.js"), index);
    }

    @Test
    void engineChainFallbackToHljs() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = tmp.resolve("output").toFile();
        write(new File(sets, "Environment.config"), "cname=example.com\n");
        write(new File(sets, "divs/t/template.html"), "<section>{{content}}</section>");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"engine\":[\"simple\",\"hljs\"],\"page\":{\"div-1\":{\"type\":\"t\"," +
                "\"markdown\":\"```java\\nint x = 1;\\n```\\n\\n```rust\\nfn main() {}\\n```\"}}}");
        SiteBuilder.build(sets, out, new File("src/assets"));
        String index = Files.readString(new File(out, "index.html").toPath());
        assertTrue(index.contains("<span class=\"tk-kw\">int</span>"), index);   // simple 接 java
        assertTrue(index.contains("fn main() {}"), index);                      // rust 落给 hljs（转义壳）
        assertTrue(index.contains("hljs.min.js"), index);                       // 客户端资产因回退而注入
    }

    @Test
    void engineUnknownNameWarnsAndFallsBack() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = tmp.resolve("output").toFile();
        write(new File(sets, "Environment.config"), "cname=example.com\n");
        write(new File(sets, "divs/t/template.html"), "<section>{{content}}</section>");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"engine\":[\"nope\"],\"page\":{\"div-1\":{\"type\":\"t\"," +
                "\"markdown\":\"```java\\nint x = 1;\\n```\"}}}");
        SiteBuilder.build(sets, out, new File("src/assets"));   // 未知名 → 警告 + 回退 hljs，构建不失败
        String index = Files.readString(new File(out, "index.html").toPath());
        assertFalse(index.contains("tk-kw"), index);
        assertTrue(index.contains("hljs.min.js"), index);
    }

    @Test
    void engineWordsU1ExtendsSimple() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = tmp.resolve("output").toFile();
        write(new File(sets, "Environment.config"),
                "cname=example.com\nengine-words=(mylang,data/engine/words.txt)\n");
        write(new File(sets, "data/engine/words.txt"), "# 注释\nFoo:cls\nbar\n");
        write(new File(sets, "divs/t/template.html"), "<section>{{content}}</section>");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"engine\":\"simple\",\"page\":{\"div-1\":{\"type\":\"t\"," +
                "\"markdown\":\"```mylang\\nFoo bar\\n```\"}}}");
        SiteBuilder.build(sets, out, new File("src/assets"));
        String index = Files.readString(new File(out, "index.html").toPath());
        assertTrue(index.contains("<span class=\"tk-cls\">Foo</span>"), index);
        assertTrue(index.contains("<span class=\"tk-kw\">bar</span>"), index);   // 缺省 tokenid = kw
        assertFalse(index.contains("hljs.min.js"), index);
    }

    @Test
    void engineWordsBadTokenErrors() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = tmp.resolve("output").toFile();
        write(new File(sets, "Environment.config"),
                "cname=example.com\nengine-words=(mylang,data/engine/words.txt)\n");
        write(new File(sets, "data/engine/words.txt"), "Bad:bogus\n");
        write(new File(sets, "divs/t/template.html"), "<section>{{content}}</section>");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"# 无\"}}}");
        RuntimeException e = assertThrows(RuntimeException.class, () ->
                SiteBuilder.build(sets, out, new File("src/assets")));
        assertTrue(e.getMessage().contains("token 未知"), e.getMessage());
    }

    @Test
    void engineInvalidTypeRejectedBySchema() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = tmp.resolve("output").toFile();
        write(new File(sets, "Environment.config"), "cname=example.com\n");
        write(new File(sets, "divs/t/template.html"), "<section>{{content}}</section>");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"engine\":123,\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"# 无\"}}}");
        RuntimeException e = assertThrows(RuntimeException.class, () ->
                SiteBuilder.build(sets, out, new File("src/assets")));
        assertTrue(e.getMessage().contains("校验失败"), e.getMessage());
    }

    // ==================== 配置 js/css 鲁棒性 ====================

    @Test
    void depsInvalidFormsError() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = tmp.resolve("output").toFile();
        write(new File(sets, "Environment.config"), "cname=example.com\n");
        write(new File(sets, "divs/t/template.html"), "<section>{{content}}</section>");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"deps\":[\"pre-assets/md/css/x.png\",\"http://x.com/a.txt\"]," +
                "\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"# 无\"}}}");
        RuntimeException e = assertThrows(RuntimeException.class, () ->
                SiteBuilder.build(sets, out, new File("src/assets")));
        assertTrue(e.getMessage().contains("deps 仅支持 .js/.css"), e.getMessage());
        // schema 层先拦裸词（形态白名单 anyOf），报"校验失败"
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"deps\":[\"foo\"],\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"# 无\"}}}");
        RuntimeException e2 = assertThrows(RuntimeException.class, () ->
                SiteBuilder.build(sets, out, new File("src/assets")));
        assertTrue(e2.getMessage().contains("校验失败"), e2.getMessage());
    }

    @Test
    void mdJsInvalidEntryErrors() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = tmp.resolve("output").toFile();
        write(new File(sets, "Environment.config"), "cname=example.com\n");
        write(new File(sets, "divs/t/template.html"), "<section>{{content}}</section>");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"md-js\":[\"nope.js\"],\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"# 无\"}}}");
        RuntimeException e = assertThrows(RuntimeException.class, () ->
                SiteBuilder.build(sets, out, new File("src/assets")));
        assertTrue(e.getMessage().contains("md-js 条目形态不合法"), e.getMessage());
    }

    @Test
    void globalRefToNonGlobalDivErrors() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = tmp.resolve("output").toFile();
        write(new File(sets, "Environment.config"), "cname=example.com\n");
        write(new File(sets, "divs/navbar/template.html"), "<nav>{{brand}}</nav>");   // 普通 div，非 .global
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"deps\":[\"global:navbar\"]," +
                "\"page\":{\"div-1\":{\"type\":\"navbar\",\"params\":{\"brand\":\"S\"}}}}");
        RuntimeException e = assertThrows(RuntimeException.class, () ->
                SiteBuilder.build(sets, out, new File("src/assets")));
        assertTrue(e.getMessage().contains("global: 引用的不是 .global div"), e.getMessage());
    }

    @Test
    void divUnknownFileErrors() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = tmp.resolve("output").toFile();
        write(new File(sets, "Environment.config"), "cname=example.com\n");
        write(new File(sets, "divs/bad/x.png"), "PNG");   // div 目录只允许 js/css/template/.global/.adds
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"# 无\"}}}");
        RuntimeException e = assertThrows(RuntimeException.class, () ->
                SiteBuilder.build(sets, out, new File("src/assets")));
        assertTrue(e.getMessage().contains("含未知文件"), e.getMessage());
    }

    @Test
    void globalAddsConflictErrors() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = tmp.resolve("output").toFile();
        write(new File(sets, "Environment.config"), "cname=example.com\n");
        write(new File(sets, "divs/mix/.global"), "");
        write(new File(sets, "divs/mix/.adds"), "");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"# 无\"}}}");
        RuntimeException e = assertThrows(RuntimeException.class, () ->
                SiteBuilder.build(sets, out, new File("src/assets")));
        assertTrue(e.getMessage().contains("不能同时有 .global 与 .adds"), e.getMessage());
    }

    @Test
    void addsAndGlobalDivAggregation() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = tmp.resolve("output").toFile();
        write(new File(sets, "Environment.config"), "cname=example.com\n");
        write(new File(sets, "divs/adder/.adds"), "");
        write(new File(sets, "divs/adder/adder.js"), "console.log('A');");
        write(new File(sets, "divs/adder/adder.css"), ".a{}");
        write(new File(sets, "divs/gdiv/.global"), "");
        write(new File(sets, "divs/gdiv/g.js"), "console.log('G');");
        write(new File(sets, "divs/gdiv/g.css"), ".g{}");
        write(new File(sets, "divs/t/template.html"), "<section>{{content}}</section>");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"deps\":[\"global:gdiv\"]," +
                "\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"# 无\"}}}");
        SiteBuilder.build(sets, out, new File("src/assets"));
        String wgJs = Files.readString(new File(out, "assets/js/web_global.min.js").toPath());
        String wgCss = Files.readString(new File(out, "assets/css/web_global.css").toPath());
        assertTrue(wgJs.contains("console.log('A');"), wgJs);            // .adds 进 web_global
        assertTrue(wgCss.contains(".a{}"), wgCss);
        assertFalse(wgJs.contains("console.log('G');"), wgJs);           // .global 不进 web_global
        assertTrue(Files.readString(new File(out, "assets/js/gdiv.min.js").toPath()).contains("console.log('G');"));
        assertTrue(Files.readString(new File(out, "assets/css/gdiv.css").toPath()).contains(".g{}"));
        String index = Files.readString(new File(out, "index.html").toPath());
        assertTrue(index.contains("assets/js/web_global.min.js"), index);
        assertTrue(index.contains("assets/js/gdiv.min.js"), index);
        assertTrue(index.contains("assets/css/gdiv.css"), index);
    }

    @Test
    void minifyOffKeepsPlainJsNames() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = tmp.resolve("output").toFile();
        write(new File(sets, "Environment.config"), "cname=example.com\nminify=0\n");
        write(new File(sets, "divs/navbar/nav.js"), "console.log(1);");
        write(new File(sets, "divs/navbar/template.html"), "<nav>{{brand}}</nav>");
        write(new File(sets, "pages/about.json"),
                "{\"name\":\"about\",\"page\":{\"div-1\":{\"type\":\"navbar\",\"params\":{\"brand\":\"A\"}}}}");
        SiteBuilder.build(sets, out, new File("src/assets"));
        assertTrue(new File(out, "pages/about/about.js").isFile());   // minify=0 → 原后缀（字节兼容）
        String about = Files.readString(new File(out, "pages/about/index.html").toPath());
        assertTrue(about.contains("src=\"about.js\""), about);
    }

    @Test
    void minifyPreservesHeaderAndShrinks() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = tmp.resolve("output").toFile();
        write(new File(sets, "Environment.config"), "cname=example.com\n");
        write(new File(sets, "divs/navbar/nav.js"),
                "/* 许可头示例 */\n// 行注释\nfunction greet(name) {\n  return 'hi ' + name;   // 尾注释\n}\nconsole.log(greet('a'));\n");
        write(new File(sets, "divs/navbar/template.html"), "<nav>{{brand}}</nav>");
        write(new File(sets, "pages/about.json"),
                "{\"name\":\"about\",\"page\":{\"div-1\":{\"type\":\"navbar\",\"params\":{\"brand\":\"A\"}}}}");
        SiteBuilder.build(sets, out, new File("src/assets"));
        String js = Files.readString(new File(out, "pages/about/about.min.js").toPath());
        assertTrue(js.startsWith("/* 许可头示例 */"), js);          // 首块注释（许可头）保留
        assertFalse(js.contains("尾注释"), js);                      // 其余注释删除
        assertFalse(js.contains("  return"), js);                    // 缩进删除
        assertTrue(js.contains("'hi '"), js);                        // 字符串字面量保留
        assertTrue(js.length() < 170, "压缩应明显变小，实际 " + js.length() + ": " + js);
    }

    @Test
    void engineAssetsDedupWithManualMdJs() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = tmp.resolve("output").toFile();
        write(new File(sets, "Environment.config"), "cname=example.com\n");
        write(new File(sets, "divs/t/template.html"), "<section>{{content}}</section>");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"md-js\":[\"pre-assets/md/js/token-map.js\"]," +
                "\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"```java\\nint x;\\n```\"}}}");
        SiteBuilder.build(sets, out, new File("src/assets"));   // 手写与 engine 自动资源重复：警告去重，不报错
        String index = Files.readString(new File(out, "index.html").toPath());
        assertTrue(index.indexOf("token-map.js") >= 0 && index.indexOf("token-map.js") == index.lastIndexOf("token-map.js"), index);
        assertTrue(index.contains("hljs.min.js"), index);
    }

    @Test
    void depsAndMdJsDuplicateDedup() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = tmp.resolve("output").toFile();
        write(new File(sets, "Environment.config"), "cname=example.com\n");
        write(new File(sets, "divs/t/template.html"), "<section>{{content}}</section>");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\"," +
                "\"deps\":[\"pre-assets/md/js/md-math.js\",\"pre-assets/md/js/md-math.js\"]," +
                "\"md-js\":[\"pre-assets/md/js/md-highlight.js\",\"pre-assets/md/js/md-highlight.js\"]," +
                "\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"# 无\"}}}");
        SiteBuilder.build(sets, out, new File("src/assets"));   // 重复条目：警告去重，不报错
        String index = Files.readString(new File(out, "index.html").toPath());
        assertTrue(index.indexOf("md-math.js") >= 0 && index.indexOf("md-math.js") == index.lastIndexOf("md-math.js"), index);
        assertTrue(index.indexOf("md-highlight.js") >= 0 && index.indexOf("md-highlight.js") == index.lastIndexOf("md-highlight.js"), index);
    }

    // ==================== code-ui（items 开关 + CODEUI 逃生舱 + 门控） ====================

    @Test
    void codeUiDefaultByteCompat() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = tmp.resolve("output").toFile();
        write(new File(sets, "Environment.config"), "cname=example.com\n");
        write(new File(sets, "divs/t/template.html"), "<section>{{content}}</section>");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"```java\\nint x;\\n```\"}}}");
        SiteBuilder.build(sets, out, new File("src/assets"));
        String index = Files.readString(new File(out, "index.html").toPath());
        assertFalse(index.contains("md-code-block"), index);   // 无 code-ui → 与现状字节兼容
        assertFalse(index.contains("md-copy.js"), index);
    }

    @Test
    void codeUiItemsStructureAndClasses() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = tmp.resolve("output").toFile();
        write(new File(sets, "Environment.config"), "cname=example.com\nbucket=(bk,https://bucket.example.com)\n");
        write(new File(sets, "divs/t/template.html"), "<section>{{content}}</section>");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"code-ui\":{\"items\":[\"lang-label\",\"mac-dots\",\"copy-btn\"]," +
                "\"rounded\":true,\"bg\":\"bk/x.png\",\"label-pos\":\"tl\"}," +
                "\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"```java\\nint x;\\n```\"}}}");
        SiteBuilder.build(sets, out, new File("src/assets"));
        String index = Files.readString(new File(out, "index.html").toPath());
        assertTrue(index.contains("class=\"md-code-block codeui-lang codeui-dots codeui-copy codeui-bg codeui-rounded\""), index);
        assertTrue(index.contains("data-lang=\"java\""), index);
        assertTrue(index.contains("data-items=\"lang-label,mac-dots,copy-btn\""), index);
        assertTrue(index.contains("<span class=\"md-code-lang pos-tl\">java</span>"), index);
        assertTrue(index.contains("<span class=\"md-code-dots\" aria-hidden=\"true\"><i></i><i></i><i></i></span>"), index);
        assertTrue(index.contains("<button type=\"button\" class=\"md-code-copy\">复制</button>"), index);
        assertTrue(index.contains("background-image:url(https://bucket.example.com/x.png);"), index);   // 替换趟联动
        assertFalse(index.contains("has-copy"), index);   // label-pos=tl 无冲突类
        assertTrue(index.indexOf("md-copy.js") >= 0 && index.indexOf("md-copy.js") == index.lastIndexOf("md-copy.js"), index);
    }

    @Test
    void codeUiCopyJsOnlyWhenRequested() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = tmp.resolve("output").toFile();
        write(new File(sets, "Environment.config"), "cname=example.com\n");
        write(new File(sets, "divs/t/template.html"), "<section>{{content}}</section>");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"code-ui\":{\"items\":[\"lang-label\"]}," +
                "\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"```java\\nint x;\\n```\"}}}");
        SiteBuilder.build(sets, out, new File("src/assets"));
        String index = Files.readString(new File(out, "index.html").toPath());
        assertTrue(index.contains("codeui-lang"), index);
        assertFalse(index.contains("md-copy.js"), index);   // 未请求 copy-btn → 零脚本
    }

    @Test
    void codeUiUnknownItemWarnsAndKeptInDataItems() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = tmp.resolve("output").toFile();
        write(new File(sets, "Environment.config"), "cname=example.com\n");
        write(new File(sets, "divs/t/template.html"), "<section>{{content}}</section>");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"code-ui\":{\"items\":[\"my-badge\"]}," +
                "\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"```java\\nint x;\\n```\"}}}");
        SiteBuilder.build(sets, out, new File("src/assets"));   // 未知 item：警告跳过，构建不失败
        String index = Files.readString(new File(out, "index.html").toPath());
        assertTrue(index.contains("data-items=\"my-badge\""), index);   // 保留给 CODEUI.js 实现
        assertFalse(index.contains("md-code-badge"), index);            // 生成器不渲染未知 item
    }

    @Test
    void codeUiBgInvalidErrors() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = tmp.resolve("output").toFile();
        write(new File(sets, "Environment.config"), "cname=example.com\n");
        write(new File(sets, "divs/t/template.html"), "<section>{{content}}</section>");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"code-ui\":{\"bg\":\"foo/x.png\"}," +
                "\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"```java\\nint x;\\n```\"}}}");
        RuntimeException e = assertThrows(RuntimeException.class, () ->
                SiteBuilder.build(sets, out, new File("src/assets")));
        assertTrue(e.getMessage().contains("code-ui.bg 形态不合法"), e.getMessage());
    }

    @Test
    void globalCodeuiInjectionGatedByCode() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = tmp.resolve("output").toFile();
        write(new File(sets, "Environment.config"), "cname=example.com\n");
        write(new File(sets, "global/codeui/CODEUI.css"), ".custom{}");
        write(new File(sets, "global/codeui/CODEUI.js"), "console.log('ui');");
        write(new File(sets, "divs/t/template.html"), "<section>{{content}}</section>");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"```java\\nint x;\\n```\"}}}");
        write(new File(sets, "pages/about.json"),
                "{\"name\":\"about\",\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"# 无代码\"}}}");
        SiteBuilder.build(sets, out, new File("src/assets"));
        String index = Files.readString(new File(out, "index.html").toPath());
        String about = Files.readString(new File(out, "pages/about/index.html").toPath());
        assertTrue(index.contains("assets/global/codeui/CODEUI.css"), index);   // 有代码块 → 注入
        assertTrue(index.contains("assets/global/codeui/CODEUI.js"), index);
        assertFalse(about.contains("CODEUI"), about);                          // 无代码块 → 零注入
        assertTrue(new File(out, "assets/global/codeui/CODEUI.css").isFile()); // 站点级文件已复制
    }

    @Test
    void globalCodeuiUnknownFileErrors() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = tmp.resolve("output").toFile();
        write(new File(sets, "Environment.config"), "cname=example.com\n");
        write(new File(sets, "global/codeui/x.png"), "PNG");
        write(new File(sets, "divs/t/template.html"), "<section>{{content}}</section>");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"# 无\"}}}");
        RuntimeException e = assertThrows(RuntimeException.class, () ->
                SiteBuilder.build(sets, out, new File("src/assets")));
        assertTrue(e.getMessage().contains("仅允许 CODEUI.css/CODEUI.js"), e.getMessage());
    }

    @Test
    void globalUnknownDomainWarnsOnly() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = tmp.resolve("output").toFile();
        write(new File(sets, "Environment.config"), "cname=example.com\n");
        write(new File(sets, "global/fonts/f.txt"), "x");
        write(new File(sets, "divs/t/template.html"), "<section>{{content}}</section>");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"# 无\"}}}");
        SiteBuilder.build(sets, out, new File("src/assets"));   // 未知域：仅警告（未来兼容）
    }

    @Test
    void codeUiSchemaRejectsBadValues() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = tmp.resolve("output").toFile();
        write(new File(sets, "Environment.config"), "cname=example.com\n");
        write(new File(sets, "divs/t/template.html"), "<section>{{content}}</section>");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"code-ui\":{\"label-pos\":\"xx\"}," +
                "\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"# 无\"}}}");
        RuntimeException e = assertThrows(RuntimeException.class, () ->
                SiteBuilder.build(sets, out, new File("src/assets")));
        assertTrue(e.getMessage().contains("校验失败"), e.getMessage());
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"code-ui\":{\"unknown-key\":1}," +
                "\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"# 无\"}}}");
        RuntimeException e2 = assertThrows(RuntimeException.class, () ->
                SiteBuilder.build(sets, out, new File("src/assets")));
        assertTrue(e2.getMessage().contains("校验失败"), e2.getMessage());
    }

    @Test
    void pageIndexLinkResolvesToRoot() throws Exception {
        File sets = tmp.resolve("sets").toFile();
        File out = tmp.resolve("output").toFile();
        write(new File(sets, "Environment.config"), "cname=example.com\n");
        write(new File(sets, "divs/t/template.html"), "<section>{{content}}</section>");
        write(new File(sets, "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"# 首页\"}}}");
        write(new File(sets, "pages/sub/x.json"),
                "{\"name\":\"x\",\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"[回首页](@page/INDEX)\"}}}");
        SiteBuilder.build(sets, out, new File("src/assets"));
        String x = Files.readString(new File(out, "pages/sub/x/index.html").toPath());
        assertTrue(x.contains("href=\"../../../\""), x);   // INDEX 特判页 → 站点根，按深度前缀解析
    }
}
