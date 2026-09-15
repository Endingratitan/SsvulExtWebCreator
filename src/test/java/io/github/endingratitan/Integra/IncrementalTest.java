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
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * **增量跳过（E）的门禁**：每个用例都做两件事 ——
 * ① 断言"哪几页被渲染 / 跳过"（判据是否精确）；
 * ② 断言**增量产物与 `--rebuild` 全量产物逐字节相同**（依赖归属不完备时，增量会静默出旧产物，
 * 只有字节比对不会放过它）。
 *
 * 覆盖的场景都取**用户真正会做的 `sets/` 侧操作**：改被引用的 md / 改 div 的 css / 新增 `.extends` /
 * 改 `Environment.config` / `touch`（内容没变）/ 增页 / 删页 / 改未被引用的 `sets/data/**` / `--verify 0`。
 */
public class IncrementalTest {

    @TempDir
    Path tmp;

    private void write(File f, String s) throws Exception {
        Files.createDirectories(f.getParentFile().toPath());
        Files.writeString(f.toPath(), s);
    }

    /** 5 页夹具：p0 引用 `data/inc.md` 与 div `t`；p1~p3 用 div `t`；p4 用 div `u` */
    private File[] site() throws Exception {
        File sets = tmp.resolve("sets").toFile(), out = tmp.resolve("output").toFile();
        write(new File(sets, "Environment.config"), "cname=example.com\n");
        for (String d : new String[]{"t", "u"}) {
            write(new File(sets, "divs/" + d + "/template.html"), "<div class=\"" + d + "\">{{content}}</div>");
            write(new File(sets, "divs/" + d + "/" + d + ".js"),
                    "window.SsvulDiv && SsvulDiv.register('" + d + "', { init: function () {} });\n");
            write(new File(sets, "divs/" + d + "/" + d + ".css"), "." + d + " { color: red }\n");
        }
        write(new File(sets, "data/inc.md"), "# 引用文\n\n正文\n");
        write(new File(sets, "data/other.json"), "{\"k\":1}\n");
        write(new File(sets, "pages/p0.json"),
                "{\"name\":\"p0\",\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"@data/inc.md\"}}}");
        for (int i = 1; i <= 3; i++) {
            write(new File(sets, "pages/p" + i + ".json"),
                    "{\"name\":\"p" + i + "\",\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"# 页 " + i + "\\n\"}}}");
        }
        write(new File(sets, "pages/p4.json"),
                "{\"name\":\"p4\",\"page\":{\"div-1\":{\"type\":\"u\",\"markdown\":\"# 页 4\\n\"}}}");
        return new File[]{sets, out};
    }

    private BuildReport build(File[] s) { return SiteBuilder.buildReport(s[0], s[1], new File("src/assets")); }

    private BuildReport build(File[] s, int verify) {
        return SiteBuilder.buildReport(s[0], s[1], new File("src/assets"),
                new SiteBuilder.BuildOptions(false, false, null, false, null, verify));
    }

    private int rendered(BuildReport r) { return r.stats().pagesRendered.get(); }

    private int skipped(BuildReport r) { return r.stats().pagesSkipped.get(); }

    /** 增量产物 vs 另一个目录里的 `--rebuild` 全量产物：写集 + 逐字节 */
    private void assertSameAsFull(File[] s, BuildReport inc, String dir) throws Exception {
        File outFull = tmp.resolve(dir).toFile();
        BuildReport full = SiteBuilder.buildReport(s[0], outFull, new File("src/assets"),
                new SiteBuilder.BuildOptions(true, false, null, false));
        assertTrue(full.ok(), String.join("\n", full.errors()));
        assertEquals(full.written(), inc.written(), "写集必须一致");
        for (String rel : new TreeSet<>(full.written())) {
            assertArrayEquals(Files.readAllBytes(new File(outFull, rel).toPath()),
                    Files.readAllBytes(new File(s[1], rel).toPath()), "增量与全量不一致: " + rel);
        }
    }

    private File[] first() throws Exception {
        File[] s = site();
        assertTrue(build(s).ok());
        return s;
    }

    @Test
    void editReferencedMdRerendersOnlyThatPage() throws Exception {
        File[] s = first();
        write(new File(s[0], "data/inc.md"), "# 引用文（改过）\n");
        BuildReport inc = build(s);
        assertTrue(inc.ok(), String.join("\n", inc.errors()));
        assertEquals(1, rendered(inc), "只有引用它的那一页该重渲染");
        assertEquals(4, skipped(inc));
        assertTrue(Files.readString(new File(s[1], "pages/p0/index.html").toPath()).contains("引用文（改过）"));
        assertSameAsFull(s, inc, "full-md");
    }

    @Test
    void editDivCssRerendersOnlyItsUsers() throws Exception {
        File[] s = first();
        write(new File(s[0], "divs/t/t.css"), ".t { color: blue }\n");
        BuildReport inc = build(s);
        assertEquals(4, rendered(inc), "用 t 的 4 页重渲染，用 u 的那页跳过");
        assertEquals(1, skipped(inc));
        assertSameAsFull(s, inc, "full-css");
    }

    @Test
    void addedExtendsForcesFullRender() throws Exception {
        File[] s = first();
        write(new File(s[0], "divs/u/.extends"), "t\n");     // 全新文件 + 结构读取 → 无法精确归因 → 全量
        BuildReport inc = build(s);
        assertTrue(inc.ok(), String.join("\n", inc.errors()));
        assertEquals(5, rendered(inc), "div 继承结构变了 → 全量");
        assertSameAsFull(s, inc, "full-extends");
    }

    @Test
    void configChangeForcesFullRender() throws Exception {
        File[] s = first();
        write(new File(s[0], "Environment.config"), "cname=other.example.com\n");
        BuildReport inc = build(s);
        assertEquals(5, rendered(inc), "配置指纹变了 → 全量");
        assertSameAsFull(s, inc, "full-config");
    }

    @Test
    void touchOnlyDoesNoWork() throws Exception {
        File[] s = first();
        File page = new File(s[0], "pages/p1.json");          // 只动 mtime，内容一字不改
        Files.setLastModifiedTime(page.toPath(),
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
        BuildReport inc = build(s);
        assertEquals(0, rendered(inc), "纯 touch 经内容哈希复核应判为未变");
        assertEquals(0, inc.stats().filesWritten, "不该写任何产物");
        assertTrue(inc.stats().hashVerifiedSkips > 0, "应记录到一次哈希复核跳过");
    }

    @Test
    void addedPageRendersOnlyIt() throws Exception {
        File[] s = first();
        write(new File(s[0], "pages/p5.json"),
                "{\"name\":\"p5\",\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"# 新页\\n\"}}}");
        BuildReport inc = build(s);
        assertTrue(inc.ok(), String.join("\n", inc.errors()));
        assertEquals(1, rendered(inc), "只有新页该渲染");
        assertEquals(5, skipped(inc));
        assertTrue(new File(s[1], "pages/p5/index.html").isFile(), "新页产物必须出现");
        assertSameAsFull(s, inc, "full-add");
    }

    @Test
    void deletedPageKeepsOthersSkippedAndWarnsOrphan() throws Exception {
        File[] s = first();
        assertTrue(new File(s[0], "pages/p4.json").delete());
        BuildReport inc = build(s);
        assertTrue(inc.ok(), String.join("\n", inc.errors()));
        assertEquals(0, rendered(inc), "其余页依赖未变 → 全跳过");
        assertEquals(4, skipped(inc));
        assertTrue(inc.warnings().stream().anyMatch(w -> w.contains("已不再生成")),
                "删页后必须给出孤儿警告: " + inc.warnings());
    }

    /**
     * 未被任何页面引用的 `sets/data/**`：**不该重渲染任何页**（没有页面依赖它），
     * 但产物里的**副本必须被刷新**（写盘相按来源 size/mtime 比对；`scanData` 是无条件入队的）。
     * 这条正是"精确归因"的样子：变化被检出 → 一般路径 → 页全跳过 → 副本重写。
     */
    @Test
    void unreferencedDataChangeRefreshesCopyWithoutRerendering() throws Exception {
        File[] s = first();
        write(new File(s[0], "data/other.json"), "{\"k\":2}\n");
        BuildReport inc = build(s);
        assertTrue(inc.ok(), String.join("\n", inc.errors()));
        assertEquals(0, rendered(inc), "没有页面引用它 → 不该重渲染任何页");
        assertTrue(Files.readString(new File(s[1], "assets/data/other.json").toPath()).contains("\"k\":2"),
                "但产物里的那份副本必须被刷新");
        assertSameAsFull(s, inc, "full-data");
    }

    @Test
    void verifyZeroDoesNotRepairProducts() throws Exception {
        File[] s = first();
        File victim = new File(s[1], "pages/p1/p1.css");
        assertTrue(victim.isFile(), "夹具应产出页级 css: " + victim);
        assertTrue(victim.delete());
        BuildReport inc = build(s, 0);                          // --verify 0：不校验产物
        assertEquals(0, rendered(inc), "verify=0 → 不做产物校验，因而不会发现缺失、也不重渲染");
        assertFalse(victim.isFile(), "verify=0 明确不修（这是它换来的速度）");
        BuildReport healed = build(s);                           // 默认 verify=1：自愈
        assertEquals(1, rendered(healed), "默认档应发现产物缺失 → 重渲染归属页");
        assertTrue(victim.isFile(), "默认档应把产物修回");
    }
}
