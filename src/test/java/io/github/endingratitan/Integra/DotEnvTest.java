/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Integra;

import io.github.endingratitan.Settings.DotEnv;
import io.github.endingratitan.Settings.GitProbe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * `.env`（本机设置）与最小 git 变更清单的门禁：创建一次后**只读**、未知键与非法值只警告、
 * `cache-limit` 真的生效、标准布局下建立 git 基线且清单精确。
 */
public class DotEnvTest {

    @TempDir
    Path tmp;

    private void write(File f, String s) throws Exception {
        Files.createDirectories(f.getParentFile().toPath());
        Files.writeString(f.toPath(), s);
    }

    /** 标准布局：<root>/sets + <root>/output（项目根 = output 的父级） */
    private File[] project() throws Exception {
        File root = tmp.resolve("proj").toFile();
        File sets = new File(root, "sets"), out = new File(root, "output");
        write(new File(sets, "Environment.config"), "cname=example.com\n");
        write(new File(sets, "divs/t/template.html"), "<div>{{content}}</div>");
        write(new File(sets, "divs/t/t.js"), "window.SsvulDiv && SsvulDiv.register('t', { init: function () {} });\n");
        write(new File(sets, "divs/t/t.css"), ".t { color: red }\n");
        for (int i = 0; i < 4; i++) {
            write(new File(sets, "pages/p" + i + ".json"),
                    "{\"name\":\"p" + i + "\",\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"# 标题 " + i + "\\n\"}}}");
        }
        return new File[]{root, sets, out};
    }

    private BuildReport build(File sets, File out) {
        return SiteBuilder.buildReport(sets, out, new File("src/assets"));
    }

    @Test
    void createdOnceThenReadOnly() throws Exception {
        File[] p = project();
        BuildReport r1 = build(p[1], p[2]);
        assertTrue(r1.ok(), String.join("\n", r1.errors()));

        File env = new File(p[0], ".env");
        assertTrue(env.isFile(), ".env 应生成在项目根（output 的父级）");
        String body = Files.readString(env.toPath());
        for (String k : DotEnv.KEYS) assertTrue(body.contains(k + "="), "缺键 " + k + ":\n" + body);
        assertTrue(body.contains("git-gc=2"), "git-gc 默认档须为 2（用户定）:\n" + body);
        assertTrue(body.contains("preview-port=23143"), body);
        assertFalse(body.contains(".ssvul"), "本机设置里不记绝对路径:\n" + body);

        // 用户手写（含注释与自定义顺序）→ 后续构建**不得覆盖**
        String mine = "# 我自己的\nenv-version=1\ndetector=stat\ncache-limit=0\n";
        Files.writeString(env.toPath(), mine);
        BuildReport r2 = build(p[1], p[2]);
        assertTrue(r2.ok(), String.join("\n", r2.errors()));
        assertEquals(mine, Files.readString(env.toPath()), ".env 存在即只读，绝不覆盖");
    }

    @Test
    void unknownAndBadKeysWarnButDoNotBlock() throws Exception {
        File[] p = project();
        Files.writeString(new File(p[0], ".env").toPath(),
                "env-version=1\ndetector=git\nbogus=1\ngit-gc=9\npreview-port=70000\ncache-limit=-3\nthreads=abc\n");
        BuildReport r = build(p[1], p[2]);
        assertTrue(r.ok(), "坏值只警告不阻断: " + r.errors());
        String w = String.join("\n", r.warnings());
        assertTrue(w.contains("未知键") && w.contains("bogus"), w);
        assertTrue(w.contains("git-gc"), w);
        assertTrue(w.contains("preview-port"), w);
        assertTrue(w.contains("cache-limit"), w);
        assertTrue(w.contains("threads"), w);
    }

    @Test
    void threadsKeyAcceptsAutoAndNumbers() throws Exception {
        File[] p = project();
        Files.writeString(new File(p[0], ".env").toPath(), "env-version=1\nthreads=auto\n");
        assertEquals(-1, DotEnv.load(p[0], new java.util.ArrayList<>()).threads, "auto → -1");
        Files.writeString(new File(p[0], ".env").toPath(), "env-version=1\nthreads=0\n");
        assertEquals(0, DotEnv.load(p[0], new java.util.ArrayList<>()).threads, "0 = 串行");
        Files.writeString(new File(p[0], ".env").toPath(), "env-version=1\nthreads=3\n");
        assertEquals(3, DotEnv.load(p[0], new java.util.ArrayList<>()).threads);
        BuildReport r = build(p[1], p[2]);
        assertTrue(r.ok(), String.join("\n", r.errors()));
    }

    @Test
    void cacheLimitSwitchesContentCacheOff() throws Exception {
        File[] p = project();
        Files.writeString(new File(p[0], ".env").toPath(), "env-version=1\ncache-limit=0\n");
        BuildReport r = build(p[1], p[2]);
        assertTrue(r.ok(), String.join("\n", r.errors()));
        assertEquals(0, r.stats().cacheStores.get(), "cache-limit=0 → 内容缓存关闭（不存）");
        assertEquals(0, r.stats().cacheHits.get(), "cache-limit=0 → 内容缓存关闭（不命中）");
        assertTrue(r.stats().diskReads.get() > 0, "关闭缓存后每次读都落盘");
    }

    @Test
    void gitBaselineAndChangeList() throws Exception {
        assumeTrue(GitProbe.available(), "本机无 git，跳过");
        File[] p = project();
        SiteBuilder.BuildOptions opts = new SiteBuilder.BuildOptions(false, true, "git", true);

        BuildReport r1 = SiteBuilder.buildReport(p[1], p[2], new File("src/assets"), opts);
        assertTrue(r1.ok(), String.join("\n", r1.errors()));
        assertTrue(new File(p[1], ".git").exists(), "标准布局 + 显式允许 → 应建立独立仓库");
        assertTrue(Files.readString(new File(p[1], ".gitignore").toPath()).contains("*.tmp"), "sets/.gitignore 缺失即写");
        assertEquals("git", r1.detector(), "有仓库时 auto/git → git 检测器");
        assertNull(GitProbe.run(p[1], 5000, null, "git", "rev-parse", "--verify", "HEAD"),
                "基线只 add，不提交（不依赖 user.name/email）");
        // 首轮 = 刚建立基线 → 清单为空（init 自己写的 .gitignore 也不算"变更"）
        assertTrue(r1.changedFiles().isEmpty(), "刚建立基线 → 无变更: " + r1.changedFiles());
        // git-gc=2 在首轮即触发（从未整理过）：两代快照引用就位，且**不移动 HEAD**（用户分支/提交永不受影响）
        assertNotNull(GitProbe.run(p[1], 5000, null, "git", "rev-parse", "--verify", "refs/ssvul/last"),
                "git-gc=2 应建立 refs/ssvul/last 快照引用（gc 会把它打进 packed-refs，故用 rev-parse 而非看文件）");
        assertNull(GitProbe.run(p[1], 5000, null, "git", "rev-parse", "--verify", "HEAD"),
                "快照是 plumbing 提交，不移动 HEAD（仓库仍无用户提交）");

        // 次轮（源未动）：只暂存的条目（init 后全是 "A "）经记录复核全部排除 → 仍为空清单
        BuildReport r1b = SiteBuilder.buildReport(p[1], p[2], new File("src/assets"), opts);
        assertTrue(r1b.changedFiles().isEmpty(), "源未动 → 清单应为空: " + r1b.changedFiles());

        // 改一个页面 → 清单精确到它（工作区与 index 不一致 = Y 列非空）
        write(new File(p[1], "pages/p0.json"),
                "{\"name\":\"p0\",\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"# 改过了\\n\"}}}");
        BuildReport r2 = SiteBuilder.buildReport(p[1], p[2], new File("src/assets"), opts);
        assertEquals(1, r2.changedFiles().size(), "只改了一个文件: " + r2.changedFiles());
        assertTrue(r2.changedFiles().contains("pages/p0.json"), r2.changedFiles().toString());

        // 新页面（未跟踪 → ??）也要报出来
        write(new File(p[1], "pages/新增.json"),
                "{\"name\":\"新增\",\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"# 新的\\n\"}}}");
        BuildReport r2b = SiteBuilder.buildReport(p[1], p[2], new File("src/assets"), opts);
        assertTrue(r2b.changedFiles().contains("pages/新增.json"), r2b.changedFiles().toString());

        // 用户自己 git add（改了但没提交）→ 仍必须报出来（只暂存条目按记录复核）
        write(new File(p[1], "pages/p1.json"),
                "{\"name\":\"p1\",\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"# p1 改过\\n\"}}}");
        assertNotNull(GitProbe.run(p[1], 5000, null, "git", "add", "-A"), "模拟用户暂存");
        BuildReport r2c = SiteBuilder.buildReport(p[1], p[2], new File("src/assets"), opts);
        assertTrue(r2c.changedFiles().contains("pages/p1.json"), "暂存不提交也不能漏报: " + r2c.changedFiles());

        // stat 检测器同样能报出（快筛：size/mtime 与上次源记录不符）
        BuildReport r3 = SiteBuilder.buildReport(p[1], p[2], new File("src/assets"),
                new SiteBuilder.BuildOptions(false, true, "stat", true));
        assertEquals("stat", r3.detector());
        assertTrue(r3.changedFiles().contains("pages/p0.json"), r3.changedFiles().toString());

        // 普通构建（不采集）→ 零新增开销：detector=off 且清单为空
        BuildReport r4 = build(p[1], p[2]);
        assertEquals("off", r4.detector(), "普通构建不采集变更清单");
        assertTrue(r4.changedFiles().isEmpty());
        assertEquals(0, r4.stats().detectCalls);
    }

    @Test
    void statDetectorVerifiesCandidatesByHash() throws Exception {
        File[] p = project();
        SiteBuilder.BuildOptions detect = new SiteBuilder.BuildOptions(false, true, "stat", false);
        assertTrue(SiteBuilder.buildReport(p[1], p[2], new File("src/assets"), detect).ok());

        // 只改 mtime（内容一字不改）→ 快筛当候选，但**内容哈希终判**应判为"没变"
        File page = new File(p[1], "pages/p0.json");
        Files.setLastModifiedTime(page.toPath(),
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 3000));
        BuildReport touched = SiteBuilder.buildReport(p[1], p[2], new File("src/assets"), detect);
        assertTrue(touched.changedFiles().isEmpty(), "纯 touch 不该报变更: " + touched.changedFiles());
        assertTrue(touched.stats().hashVerifiedSkips > 0, "应记录到一次哈希复核跳过");

        // 真改内容 → 必须报出来
        write(new File(p[1], "pages/p0.json"),
                "{\"name\":\"p0\",\"page\":{\"div-1\":{\"type\":\"t\",\"markdown\":\"# 真改了\\n\"}}}");
        BuildReport edited = SiteBuilder.buildReport(p[1], p[2], new File("src/assets"), detect);
        assertTrue(edited.changedFiles().contains("pages/p0.json"), edited.changedFiles().toString());
    }

    @Test
    void gitNotTouchedWhenNotAllowed() throws Exception {
        assumeTrue(GitProbe.available(), "本机无 git，跳过");
        File[] p = project();
        BuildReport r = SiteBuilder.buildReport(p[1], p[2], new File("src/assets"),
                new SiteBuilder.BuildOptions(false, true, "git", false));
        assertFalse(new File(p[1], ".git").exists(), "未允许时绝不往调用方目录塞仓库");
        assertEquals("stat", r.detector(), "无仓库 → git 检测器回退 stat");
    }

    @Test
    void backfillsNewKeysOnlyInOurOwnFile() throws Exception {
        File[] p = project();
        File env = new File(p[0], ".env");
        // ① 模拟 0.3.3 生成的老文件：带我们的标记、只有 7 个键、用户改过一个值
        String legacy = "# SsvulExtWebCreator 本机设置（生成器侧）\n"
                + "# 我自己的注释\nenv-version=1\ngit=1\ndetector=stat\ngit-gc=2\ncache-limit=8\npreview-port=25000\nthreads=2\n";
        Files.writeString(env.toPath(), legacy);
        assertTrue(build(p[1], p[2]).ok());
        String after = Files.readString(env.toPath());
        assertTrue(after.startsWith(legacy), "已有内容必须一字不改（只允许末尾追加）");
        for (String k : new String[]{"watch=", "poll=", "inject=", "sse-max="}) {
            assertTrue(after.contains(k), "缺失键应被补上: " + k + "\n" + after);
        }
        assertEquals(25000, DotEnv.load(p[0], new java.util.ArrayList<>()).previewPort, "用户改过的值不受影响");

        // ② 键齐了就不再写（内容与 mtime 都不变）
        String first = Files.readString(env.toPath());
        assertTrue(build(p[1], p[2]).ok());
        assertEquals(first, Files.readString(env.toPath()), "补齐过一次后不该再动文件");

        // ③ 用户手写的 .env（没有我们的标记）绝不触碰
        File own = new File(p[0], ".env");
        String mine = "# 我自己写的\nenv-version=1\npreview-port=26000\n";
        Files.writeString(own.toPath(), mine);
        assertTrue(build(p[1], p[2]).ok());
        assertEquals(mine, Files.readString(own.toPath()), "手写文件一碰不碰");
    }

    @Test
    void newPreviewKeysParse() throws Exception {
        File[] p = project();
        Files.writeString(new File(p[0], ".env").toPath(),
                "env-version=1\nwatch=1\npoll=500\ninject=0\nsse-max=4\n");
        DotEnv e = DotEnv.load(p[0], new java.util.ArrayList<>());
        assertEquals("1", e.watch);
        assertEquals(500, e.poll);
        assertEquals(0, e.inject);
        assertEquals(4, e.sseMax);
        Files.writeString(new File(p[0], ".env").toPath(), "env-version=1\nwatch=always\npoll=10\n");
        java.util.List<String> w = new java.util.ArrayList<>();
        DotEnv bad = DotEnv.load(p[0], w);
        assertEquals("auto", bad.watch, "非法 watch → 默认 auto");
        assertEquals(700, bad.poll, "非法 poll（<50）→ 默认 700");
        assertTrue(w.stream().anyMatch(s -> s.contains("watch")), w.toString());
        assertTrue(w.stream().anyMatch(s -> s.contains("poll")), w.toString());
    }

    @Test
    void previewPortComesFromDotEnv() throws Exception {
        File root = tmp.resolve("portproj").toFile();
        Files.createDirectories(root.toPath());
        java.util.List<String> w = new java.util.ArrayList<>();
        assertEquals(23143, DotEnv.load(root, w).previewPort, "缺文件 → 内置默认（23143，>20000 的质数）");
        Files.writeString(new File(root, ".env").toPath(), "env-version=1\npreview-port=25000\n");
        assertEquals(25000, DotEnv.load(root, w).previewPort, "预览端口走 .env");
        Files.writeString(new File(root, ".env").toPath(), "preview-port=abc\n");
        assertEquals(23143, DotEnv.load(root, w).previewPort, "非法值 → 回落默认（只警告）");
        assertTrue(w.stream().anyMatch(s -> s.contains("preview-port")), w.toString());
    }

    @Test
    void rebuildIgnoresManifest() throws Exception {
        File[] p = project();
        assertTrue(build(p[1], p[2]).ok());
        BuildReport warm = build(p[1], p[2]);
        assertEquals(0, warm.stats().filesWritten, "热构建零写盘");

        BuildReport forced = SiteBuilder.buildReport(p[1], p[2], new File("src/assets"),
                new SiteBuilder.BuildOptions(true, false, null, null));
        assertTrue(forced.ok(), String.join("\n", forced.errors()));
        assertTrue(forced.stats().filesWritten > 0, "--rebuild 忽略记录 → 全量重写");
        assertEquals(0, forced.stats().fastSkips, "--rebuild 不走快路径");
    }
}
