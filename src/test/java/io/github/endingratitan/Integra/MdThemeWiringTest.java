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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * md 主题接线：`md.css`（default/none/global 路径）、div 级就近覆盖、`md.wrap`、同页多主题。
 * 断言口径：产物里只有**作用域化副本**（`assets/css/md-theme-<hash>.css`），原始预设 md.css 不再进产物；
 * 主题类挂在声明它的那个 div 的包装元素上（`md-csr` 这类客户端渲染 md 的 div 同样登记）。
 */
class MdThemeWiringTest {

    private static final String PRESET = "pre-assets/md/css/md.css";

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
        return new File[]{sets, out};
    }

    @Test
    void themeIsScopedAndAttachedToMdDiv() throws Exception {
        File[] s = site("");
        write(new File(s[0], "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"## 标题\\n\\n正文\"}}}");
        SiteBuilder.build(s[0], s[1], new File("src/assets"));
        String cls = SiteMdThemes.classNameOf(PRESET);
        File scoped = new File(s[1], "assets/css/" + cls + ".css");
        assertTrue(scoped.isFile(), "应产出作用域化主题副本");
        assertTrue(Files.readString(scoped.toPath()).contains("." + cls + " h2{"), "副本必须真的带作用域前缀");
        assertFalse(new File(s[1], "assets/pre/md/css/md.css").isFile(), "原始预设 md.css 不应再进产物");
        String html = Files.readString(new File(s[1], "index.html").toPath());
        assertTrue(html.contains("class=\"ssvul-t " + cls + "\""), "主题类应挂在 md div 的包装元素上: " + html);
        assertTrue(html.contains("assets/css/" + cls + ".css"), "应注入 scoped 链接: " + html);
    }

    @Test
    void pageLevelNoneDisablesThemeEntirely() throws Exception {
        File[] s = site("");
        write(new File(s[0], "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"md\":{\"css\":\"none\"}," +
                "\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"## 标题\\n\\n正文\"}}}");
        SiteBuilder.build(s[0], s[1], new File("src/assets"));
        String html = Files.readString(new File(s[1], "index.html").toPath());
        assertFalse(html.contains("md-theme-"), "none ⇒ 不注入主题、不挂类: " + html);
        assertTrue(html.contains("## 标题") || html.contains("标题"), "但 md 内容照常渲染");
        assertFalse(new File(s[1], "assets/css").isDirectory()
                && new File(s[1], "assets/css").list((d, n) -> n.startsWith("md-theme-")).length > 0,
                "none ⇒ 不产出主题文件");
    }

    @Test
    void divLevelCssOverridesPageAndOnlyAffectsThatDiv() throws Exception {
        File[] s = site("");
        write(new File(s[0], "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"md\":{\"css\":\"none\"},\"page\":{" +
                "\"div-1\":{\"type\":\"t\",\"markdown\":\"## 有主题\",\"md\":{\"css\":\"default\"}}," +
                "\"div-2\":{\"type\":\"t\",\"markdown\":\"## 无主题\"}}}");
        SiteBuilder.build(s[0], s[1], new File("src/assets"));
        String cls = SiteMdThemes.classNameOf(PRESET);
        String html = Files.readString(new File(s[1], "index.html").toPath());
        assertTrue(html.contains("class=\"ssvul-t " + cls + "\""), "声明主题的 div 才挂类: " + html);
        int withClass = html.split("class=\"ssvul-t " + cls + "\"", -1).length - 1;
        assertTrue(withClass == 1, "只有一个 div 声明了主题，实际挂类 " + withClass + " 个");
        int links = html.split("assets/css/" + cls + "\\.css", -1).length - 1;
        assertTrue(links == 1, "同类主题只注入一次，实际 " + links + " 次");
    }

    @Test
    void wrapFalseDropsMdBodyWrapper() throws Exception {
        File[] s = site("");
        write(new File(s[0], "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"md\":{\"wrap\":false}," +
                "\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"## 裸内容\\n\\n段落\"}}}");
        SiteBuilder.build(s[0], s[1], new File("src/assets"));
        String html = Files.readString(new File(s[1], "index.html").toPath());
        assertFalse(html.contains("md-body"), "wrap:false ⇒ 不包 <div class=\"md-body\">: " + html);
        assertTrue(html.contains("<h2"), "md 结构照常渲染: " + html);
    }

    @Test
    void twoThemesOnOnePageGetDifferentClasses() throws Exception {
        File[] s = site("");
        write(new File(s[0], "global/md/custom.css"), ".md-body h2{ color: red }\n");
        write(new File(s[0], "pages/INDEX.json"),
                "{\"name\":\"INDEX\",\"page\":{" +
                "\"div-1\":{\"type\":\"t\",\"markdown\":\"## 预设主题\"}," +
                "\"div-2\":{\"type\":\"t\",\"markdown\":\"## 自定义主题\",\"md\":{\"css\":\"global/md/custom.css\"}}}}");
        SiteBuilder.build(s[0], s[1], new File("src/assets"));
        String a = SiteMdThemes.classNameOf(PRESET);
        String b = SiteMdThemes.classNameOf("global/md/custom.css");
        assertNotEquals(a, b, "不同主题必须不同类名");
        String html = Files.readString(new File(s[1], "index.html").toPath());
        assertTrue(html.contains("assets/css/" + a + ".css"), "预设主题链接: " + html);
        assertTrue(html.contains("assets/css/" + b + ".css"), "自定义主题链接: " + html);
        assertTrue(html.contains("class=\"ssvul-t " + a + "\""), "div-1 挂预设类: " + html);
        assertTrue(html.contains("class=\"ssvul-t " + b + "\""), "div-2 挂自定义类: " + html);
        String custom = Files.readString(new File(s[1], "assets/css/" + b + ".css").toPath());
        assertTrue(custom.contains("." + b + " h2{ color: red }"), "自定义主题同样被作用域化: " + custom);
    }
}
