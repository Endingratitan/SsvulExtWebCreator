/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Settings;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * git 集成（**本机能力层**，与 {@link DotEnv} 同属 `Settings`）：能力探测 / 变更清单 / 首次基线 / 仓库整理。
 *
 * 为什么用 git（tech.md §2/§5 实测钉死）：`sets/` 里如果是仓库，`git status --porcelain -z -uall` 在 300 文件上
 * 只要 **304ms**（比我们自己的 Java stat 遍历 ~2.4ms/文件更快），精确报出"改了哪些文件"，且**纯 touch 不误报**；
 * inotify（WatchService）在本机 `/mnt/d` 上实测**完全无事件**，故不作主路径。
 * 补充实测（2026-09）：`-unormal`／`--no-optional-locks`／`core.untrackedCache` 在本规模**都没有收益**；
 * `git diff-files` 更快（233ms）但**不健全**（用户 `git add` 过就漏报）—— 检测成本与"改了没"无关，永远是全树一次 stat。
 *
 * 安全边界（一条都不能破）：
 * ① 所有调用**统一超时**、失败只警告 → 绝不阻断构建（git 缺失/超时/报错一律降级）；
 * ② `GIT_OPTIONAL_LOCKS=0`：**绝不改写用户的 index**；
 * ③ `GIT_TERMINAL_PROMPT=0`：绝不弹凭据提示；
 * ④ 快照走**临时索引**（`GIT_INDEX_FILE`）→ 不污染用户暂存区；引用放 `refs/ssvul/*` → 不进 `git branch`、不动 HEAD；
 * ⑤ `git-gc=2` 的 `--prune=now` 会清掉用户自己产生的悬空对象（已在 `.env` 注释与文档里写明）。
 */
public final class GitProbe {

    public static final long TIMEOUT_MS = 10_000;         // init/add/gc 等重操作
    public static final long DETECT_TIMEOUT_MS = 2_000;   // 变更清单：交互路径，宁可回退也不能卡
    public static final long GC_MIN_AGE_MS = 7L * 24 * 3600 * 1000;    // 距上次整理 > 7 天
    public static final long GC_PROBE_AGE_MS = 24L * 3600 * 1000;      // 一天内连探测都省掉（省一次 spawn）
    public static final long GC_LOOSE_MAX = 1000;                      // 松散对象 > 1000
    public static final long GC_SIZE_MIN = 5L * 1024 * 1024;           // 且 .git > 5MB

    /** 站点源仓库自己的 .gitignore（缺失才写；不覆盖用户的内容） */
    private static final String GITIGNORE = "# SsvulExtWebCreator：站点源仓库（`sets/` 独立成库，生成器仓库忽略本目录）\n"
            + "*.tmp\n*.swp\n.DS_Store\n";

    private static Boolean available;   // 进程级能力位：一次探测，省掉重复 spawn

    private GitProbe() {}

    public static boolean available() {
        if (available == null) available = run(null, TIMEOUT_MS, null, "git", "--version") != null;
        return available;
    }

    public static boolean isRepo(File dir) { return new File(dir, ".git").exists(); }

    /** 执行 git：成功返回 stdout（可能为空串）；超时/非 0/异常 → null */
    public static String run(File dir, long timeoutMs, Map<String, String> extraEnv, String... args) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(args);
            if (dir != null) pb.directory(dir);
            Map<String, String> e = pb.environment();
            e.put("GIT_OPTIONAL_LOCKS", "0");
            e.put("GIT_TERMINAL_PROMPT", "0");
            if (extraEnv != null) e.putAll(extraEnv);
            p = pb.start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread to = drain(p.getInputStream(), out);
            Thread te = drain(p.getErrorStream(), new ByteArrayOutputStream());
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return null;
            }
            to.join(500);
            te.join(500);
            return p.exitValue() == 0 ? out.toString(StandardCharsets.UTF_8) : null;
        } catch (IOException ex) {
            if (p != null) p.destroyForcibly();
            return null;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            if (p != null) p.destroyForcibly();
            return null;
        }
    }

    /** 后台抽干管道（不抽干的话子进程写满 64KB 缓冲就会卡死） */
    private static Thread drain(InputStream in, ByteArrayOutputStream sink) {
        Thread t = new Thread(() -> {
            try (in) {
                in.transferTo(sink);
            } catch (IOException ignored) {
                // 进程被杀时的正常现象
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * `git status` 的两段结果（**关键设计**：`git init + add -A` 之后全部文件都以"已暂存新增（`A `）"出现，
     * 若不加区分，变更清单会永远报"全变了"）：
     *
     * @param worktree   **确定变了**：工作区与 index 不一致（Y 列非空）或未跟踪（`??`）
     * @param stagedOnly 只进了暂存区（X 非空、Y 为空）——**需按上次构建的源记录复核**才作数
     */
    public record Changes(Set<String> worktree, Set<String> stagedOnly) {}

    /** 变更清单（仓库相对路径，`/` 分隔）；null = 不可用（超时/报错/非仓库） */
    public static Changes status(File repo) {
        String out = run(repo, DETECT_TIMEOUT_MS, null,
                "git", "-c", "core.quotepath=false", "status", "--porcelain", "-z", "-uall", "--no-renames");
        if (out == null) return null;
        Set<String> worktree = new LinkedHashSet<>(), staged = new LinkedHashSet<>();
        for (String rec : out.split("\0")) {
            if (rec.length() < 4) continue;                 // "XY 路径"：2 位状态 + 1 空格
            String path = rec.substring(3).replace('\\', '/');
            char x = rec.charAt(0), y = rec.charAt(1);
            if (y != ' ' || (x == '?' && y == '?')) worktree.add(path);
            else if (x != ' ') staged.add(path);
        }
        return new Changes(worktree, staged);
    }

    /** 护栏：抽查源是否被 .gitignore 忽略（git 会看不见这些改动 → 检测器必须回退） */
    public static boolean anyIgnored(File repo, List<String> relPaths) {
        List<String> cmd = new ArrayList<>(List.of("git", "check-ignore", "-q", "--"));
        cmd.addAll(relPaths);
        return run(repo, DETECT_TIMEOUT_MS, null, cmd.toArray(new String[0])) != null;   // 有被忽略 → exit 0
    }

    /**
     * 首次基线：`git init` + 写 `sets/.gitignore`（缺失时）+ `git add -A`。
     * **不提交**（`git commit` 需要 user.name/email，我们不该替用户设身份；无提交的仓库照样能 status 出变更）。
     */
    public static boolean init(File repo, List<String> warnings) {
        if (run(repo, TIMEOUT_MS, null, "git", "init", "-q") == null) {
            warnings.add("git init 失败（已忽略，改用 stat+哈希检测）: " + repo);
            return false;
        }
        File gi = new File(repo, ".gitignore");
        if (!gi.isFile()) {
            try {
                Files.writeString(gi.toPath(), GITIGNORE, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                warnings.add("sets/.gitignore 写入失败（已忽略）: " + ex.getMessage());
            }
        }
        if (run(repo, TIMEOUT_MS, null, "git", "add", "-A") == null) {
            warnings.add("git add -A 失败：基线未建立，首轮变更清单会把全部源当成新增");
        }
        return true;
    }

    /**
     * 仓库整理（`.env` 的 `git-gc`）：0=关；1=`git gc --quiet`（默认 2 周宽限、不动 reflog）；
     * 2=只保留**本次 + 上次**两代快照（临时索引 write-tree/commit-tree → `refs/ssvul/{last,prev}` → `gc --prune=now`）。
     * 触发条件：距上次 > 7 天，或（松散对象 > 1000 且 .git > 5MB）；一天内不重复探测。
     *
     * @param lastGcAt 上次整理时刻（0 = 从未；由调用方从构建状态里取）
     * @return 整理后的 {@code {gcAt, gcBytes}}；未触发/失败 → null（调用方保持原值）
     */
    public static long[] maybeGc(File repo, int mode, long lastGcAt, List<String> warnings) {
        if (mode == 0 || !isRepo(repo) || !available()) return null;
        long now = System.currentTimeMillis();
        // 检测器用 `git status` 判变更，而 sets/.git 按设计**没有 HEAD 提交**（见 init 的注释）⇒ 真实索引一旦不同步，
        // 所有文件都会被报成"新增/变更"，增量跳过与秒回全部失效（实测真站点每轮 62 个文件被判变更、页面永远全量）。
        // 与 init 的基线口径一致：每轮把**真实索引**同步到工作区（内容快照仍走临时索引，互不影响）。
        run(repo, TIMEOUT_MS, null, "git", "add", "-A");
        long age = lastGcAt <= 0 ? Long.MAX_VALUE : now - lastGcAt;
        if (age < GC_PROBE_AGE_MS) return null;
        long[] before = countObjects(repo);
        boolean trigger = age > GC_MIN_AGE_MS
                || (before != null && before[0] > GC_LOOSE_MAX && before[1] > GC_SIZE_MIN);
        if (!trigger) return null;
        long t0 = System.currentTimeMillis();
        boolean ok = mode == 2 ? snapshotAndGc(repo) : run(repo, TIMEOUT_MS, null, "git", "gc", "--quiet") != null;
        if (!ok) {
            warnings.add("sets/.git 整理失败（已忽略；不影响本次构建）: " + repo);
            return null;
        }
        long[] after = countObjects(repo);
        long bytes = after == null ? 0 : after[1];
        IO.println("已整理 sets/.git：松散对象 " + (before == null ? "?" : before[0]) + " → " + (after == null ? "?" : after[0])
                + "，（" + mb(before == null ? 0 : before[1]) + " → " + mb(bytes)
                + "，" + (System.currentTimeMillis() - t0) + " ms）");
        return new long[]{now, bytes};
    }

    /** 两代快照 + 立即剪枝：全程 plumbing，**不移动 HEAD/分支**，用临时索引避开用户的暂存区 */
    private static boolean snapshotAndGc(File repo) {
        File idx = new File(new File(repo, ".git"), "ssvul-index");
        Map<String, String> env = new LinkedHashMap<>();
        env.put("GIT_INDEX_FILE", idx.getAbsolutePath());
        env.put("GIT_AUTHOR_NAME", "SsvulExtWebCreator");
        env.put("GIT_AUTHOR_EMAIL", "ssvul@localhost");
        env.put("GIT_COMMITTER_NAME", "SsvulExtWebCreator");
        env.put("GIT_COMMITTER_EMAIL", "ssvul@localhost");
        try {
            if (run(repo, TIMEOUT_MS, env, "git", "read-tree", "--empty") == null) return false;
            if (run(repo, TIMEOUT_MS, env, "git", "add", "-A") == null) return false;
            String tree = run(repo, TIMEOUT_MS, env, "git", "write-tree");
            if (tree == null) return false;
            String commit = run(repo, TIMEOUT_MS, env, "git", "commit-tree", tree.trim(), "-m",
                    "ssvul snapshot " + java.time.Instant.now());
            if (commit == null) return false;
            String last = run(repo, TIMEOUT_MS, null, "git", "rev-parse", "-q", "--verify", "refs/ssvul/last");
            if (last != null) run(repo, TIMEOUT_MS, null, "git", "update-ref", "refs/ssvul/prev", last.trim());
            if (run(repo, TIMEOUT_MS, null, "git", "update-ref", "refs/ssvul/last", commit.trim()) == null) return false;
            return run(repo, TIMEOUT_MS, null, "git", "gc", "--prune=now", "--quiet") != null;
        } finally {
            try {
                Files.deleteIfExists(idx.toPath());
            } catch (IOException ignored) {
                // 临时索引残留无害；下次 read-tree --empty 会覆盖
            }
        }
    }

    /** `git count-objects -v` → {松散对象数, 总字节（松散 + 打包）}；失败 null */
    private static long[] countObjects(File repo) {
        String out = run(repo, TIMEOUT_MS, null, "git", "count-objects", "-v");
        if (out == null) return null;
        long loose = 0, kib = 0;
        for (String line : out.split("\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String k = line.substring(0, colon).trim();
            String v = line.substring(colon + 1).trim();
            try {
                if (k.equals("count")) loose = Long.parseLong(v);
                else if (k.equals("size") || k.equals("size-pack")) kib += Long.parseLong(v);
            } catch (NumberFormatException ignored) {
                // 版本差异导致的非数字字段：跳过
            }
        }
        return new long[]{loose, kib * 1024};
    }

    private static String mb(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }
}
