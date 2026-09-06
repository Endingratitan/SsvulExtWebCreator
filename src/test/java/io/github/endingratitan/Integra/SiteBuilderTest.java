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
        assertTrue(new File(out, "assets/index/index.js").isFile());   // INDEX 页文件特例目录
        assertTrue(new File(out, "assets/index/index.css").isFile());
        assertTrue(index.contains("src=\"assets/index/index.js\""), index);
        assertTrue(index.contains("href=\"assets/index/index.css\""), index);

        // 普通页面：<name>.js/css 与 html 同级
        assertTrue(new File(out, "pages/about/about.js").isFile());
        assertTrue(new File(out, "pages/about/about.css").isFile());
        String about = Files.readString(new File(out, "pages/about/index.html").toPath());
        assertTrue(about.contains("src=\"about.js\""), about);
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
}
