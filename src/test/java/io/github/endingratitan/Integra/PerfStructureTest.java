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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 性能**结构门禁**（0.3.3 的成果靠计数钉住，不靠时间断言——时间随机器/文件系统抖动，计数不会）。
 * 覆盖：schema 每进程只编译一次、二次构建零写盘、写集包含被跳过项、压缩/内容缓存命中、
 * 改动一个页面只重写受影响产物。
 */
public class PerfStructureTest {

    @TempDir
    Path tmp;

    private void write(File f, String s) throws Exception {
        Files.createDirectories(f.getParentFile().toPath());
        Files.writeString(f.toPath(), s);
    }

    /** 48 页（>16×3 → 触发真正的多线程渲染；12 页会按工作量收敛成串行） */
    private File[] bigSite() throws Exception {
        File sets = tmp.resolve("bigsets").toFile(), out = tmp.resolve("bigout").toFile();
        write(new File(sets, "Environment.config"), "cname=example.com\n");
        write(new File(sets, "divs/t/template.html"), "<div class=\"t\">{{content}}</div>");
        write(new File(sets, "divs/t/t.js"), "window.SsvulDiv && SsvulDiv.register('t', { init: function () {} });\n");
        write(new File(sets, "divs/t/t.css"), ".t { color: red }\n");
        for (int i = 0; i < 48; i++) {
            write(new File(sets, "pages/p" + i + ".json"),
                    "{\"name\":\"p" + i + "\",\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"# 页 " + i + "\\n\\n正文 **粗** 与 `码`\\n\"}}}");
        }
        return new File[]{sets, out};
    }

    /** 并行渲染必须与串行**逐字节一致**（页面相 0.4.0 起多线程；这是允许并行的前提） */
    @Test
    void renderParallelMatchesSerialExactly() throws Exception {
        File[] s = bigSite();
        File outPar = tmp.resolve("render-par").toFile();
        BuildReport par = SiteBuilder.buildReport(s[0], outPar, new File("src/assets"),
                new SiteBuilder.BuildOptions(false, false, null, false, 4));
        BuildReport ser = SiteBuilder.buildReport(s[0], s[1], new File("src/assets"),
                new SiteBuilder.BuildOptions(false, false, null, false, 0));
        assertTrue(par.ok() && ser.ok(), String.join("\n", par.errors()) + String.join("\n", ser.errors()));
        assertTrue(ser.written().size() >= 48 * 2, "夹具应产出 48 页的 html+css/js: " + ser.written().size());
        assertEquals(ser.written(), par.written(), "写集必须完全一致");
        List<String> rels = new ArrayList<>(par.written());
        Collections.sort(rels);
        for (String rel : rels) {
            assertArrayEquals(Files.readAllBytes(new File(s[1], rel).toPath()),
                    Files.readAllBytes(new File(outPar, rel).toPath()), "产物字节不一致: " + rel);
        }
        // 警告是**多线程写**的：允许顺序不同，但集合必须相同（丢警告=静默降级，必须抓到）
        List<String> wSer = new ArrayList<>(ser.warnings());
        List<String> wPar = new ArrayList<>(par.warnings());
        Collections.sort(wSer);
        Collections.sort(wPar);
        assertEquals(wSer, wPar, "警告集合必须一致（顺序允许不同）");
    }

    /** 12 页共用同一 div 集合（同一 JS/CSS 聚合 → 压缩缓存应命中） */
    private File[] site() throws Exception {
        File sets = tmp.resolve("sets").toFile(), out = tmp.resolve("out").toFile();
        write(new File(sets, "Environment.config"), "cname=example.com\n");
        write(new File(sets, "divs/t/template.html"), "<div>{{content}}</div>");
        write(new File(sets, "divs/t/t.js"), "window.SsvulDiv && SsvulDiv.register('t', { init: function () {} });\n");
        write(new File(sets, "divs/t/t.css"), ".t { color: red }\n");
        for (int i = 0; i < 12; i++) {
            write(new File(sets, "pages/p" + i + ".json"),
                    "{\"name\":\"p" + i + "\",\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"# 标题 " + i + "\\n\\n正文 **粗**\\n\"}}}");
        }
        return new File[]{sets, out};
    }

    /**
     * 并发度策略（纯函数）：auto = 核数封顶 8，再按工作量收敛（每 16 个产物一个线程）；
     * 0 = 强制串行；显式值优先。
     */
    @Test
    void ioThreadPolicy() {
        assertEquals(1, SiteBuilder.ioThreads(0, 1000, 16), "threads=0 → 强制串行");
        assertEquals(8, SiteBuilder.ioThreads(-1, 1000, 16), "auto：16 核 −1 = 15 → 封顶 8");
        assertEquals(8, SiteBuilder.ioThreads(-1, 1000, 64), "auto 上限恒为 8（留余量）");
        assertEquals(3, SiteBuilder.ioThreads(-1, 1000, 4), "auto：4 核 −1 = 3（**留一个核**）");
        assertEquals(1, SiteBuilder.ioThreads(-1, 1000, 2), "auto：2 核 −1 = 1 → 串行（要并行请显式 threads=2）");
        assertEquals(1, SiteBuilder.ioThreads(-1, 1000, 1), "单核 → 串行，不建池");
        assertEquals(3, SiteBuilder.ioThreads(-1, 39, 16), "工作量收敛：39 个产物 → 3 线程");
        assertEquals(1, SiteBuilder.ioThreads(-1, 12, 16), "小站 → 退回串行（不为 12 个文件建池）");
        assertEquals(2, SiteBuilder.ioThreads(2, 1000, 16), "显式值优先于 auto");
        assertEquals(1, SiteBuilder.ioThreads(0, 4, 16), "显式 0 永远串行");
    }

    /** 并行写盘必须与串行**逐字节一致**（这是允许并行的前提，也是最容易悄悄坏掉的地方） */
    @Test
    void parallelWriteMatchesSerialByteForByte() throws Exception {
        File[] s = site();
        File outPar = tmp.resolve("out-par").toFile();
        SiteBuilder.BuildOptions par = new SiteBuilder.BuildOptions(false, false, null, false, 4);
        SiteBuilder.BuildOptions ser = new SiteBuilder.BuildOptions(false, false, null, false, 0);
        BuildReport a = SiteBuilder.buildReport(s[0], outPar, new File("src/assets"), par);
        BuildReport b = SiteBuilder.buildReport(s[0], s[1], new File("src/assets"), ser);
        assertTrue(a.ok() && b.ok(), String.join("\n", a.errors()) + String.join("\n", b.errors()));
        assertEquals(b.written(), a.written(), "写集必须完全一致");
        List<String> rels = new ArrayList<>(a.written());
        Collections.sort(rels);
        for (String rel : rels) {
            assertArrayEquals(Files.readAllBytes(new File(s[1], rel).toPath()),
                    Files.readAllBytes(new File(outPar, rel).toPath()), "产物字节不一致: " + rel);
        }
        assertEquals(b.stats().filesWritten, a.stats().filesWritten, "写入文件数一致");
    }

    @Test
    void structuralCountsHold() throws Exception {
        File[] s = site();

        // ---- 首次构建：schema 只编译一次；全部写入 ----
        BuildReport first = SiteBuilder.buildReport(s[0], s[1], new File("src/assets"));
        assertTrue(first.ok(), String.join("\n", first.errors()));
        BuildStats a = first.stats();
        assertTrue(a.schemaCompiles.get() <= 1, "schema 每进程最多编译一次，实际 " + a.schemaCompiles.get());
        assertTrue(a.filesWritten > 0, "首次构建应当写盘");
        assertEquals(0, a.filesSkipped, "首次构建没有可跳过的文件");
        assertEquals(a.filesWritten, first.written().size(), "写集应等于本次写出的文件数");
        assertTrue(a.jsonParses.get() >= 12, "12 个页面 json 至少各解析一次，实际 " + a.jsonParses.get());

        // ---- 二次构建（内容未变）：零写盘 + 全走 manifest 快路径 + 写集仍完整 ----
        BuildReport second = SiteBuilder.buildReport(s[0], s[1], new File("src/assets"));
        assertTrue(second.ok(), String.join("\n", second.errors()));
        BuildStats b = second.stats();
        assertEquals(0, b.filesWritten, "内容未变时不应写任何文件");
        assertEquals(b.filesSkipped, second.written().size(),
                "写集必须**包含被跳过写入的文件**（否则将来孤儿清理会把它们删掉）");
        assertTrue(b.fastSkips > 0, "应走 manifest 快路径（不读文件即跳过）");
        assertTrue(second.written().contains("pages/p0/index.html"),
                "写集应含真实产物路径: " + second.written());

        // ---- 缓存：多页共用同一聚合 → 压缩只做一次；模板/BASE 命中内容缓存 ----
        assertTrue(b.minifyHits.get() > 0, "同一 JS 聚合应命中压缩结果缓存（命中 " + b.minifyHits.get() + "）");
        assertTrue(b.minifyCalls.get() <= 12, "压缩调用数应远小于页面数×2，实际 " + b.minifyCalls.get());
        assertTrue(b.cacheHits.get() > 0, "模板/预设/BASE 应命中内容缓存（命中 " + b.cacheHits.get() + "）");
        assertTrue(b.schemaCompiles.get() == 0, "同一进程内 schema 已缓存，不应再编译");

        // ---- 改一个页面：只重写受影响产物，其余仍跳过 ----
        write(new File(s[0], "pages/p0.json"),
                "{\"name\":\"p0\",\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"# 改过了\\n\"}}}");
        BuildReport third = SiteBuilder.buildReport(s[0], s[1], new File("src/assets"));
        assertTrue(third.ok(), String.join("\n", third.errors()));
        BuildStats c = third.stats();
        assertTrue(c.filesWritten > 0, "改过的页面必须重写");
        assertTrue(c.filesSkipped > c.filesWritten, "未受影响的产物仍应跳过（写 " + c.filesWritten + " / 跳过 " + c.filesSkipped + "）");
        assertTrue(Files.readString(new File(s[1], "pages/p0/index.html").toPath()).contains("改过了"),
                "改动必须落到产物");
    }
}
