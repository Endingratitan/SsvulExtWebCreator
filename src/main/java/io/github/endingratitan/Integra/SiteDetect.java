/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Integra;

import io.github.endingratitan.Settings.GitProbe;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * **变更检测**（包内私有协作件，owner 引用模式 —— 与 `SiteScan`/`SitePages`/`SiteTags`/`SiteWrite`/`SiteIncremental` 同构）。
 *
 * 关系：它是 `SiteBuilder` 的**前置阶段**（`SiteSetup` 装设置 → **`SiteDetect` 判"哪些源变了"** → `SiteScan` 扫描 →
 * `SitePages` 渲染 → `SiteWrite` 写盘），与 `SiteScan` 对称；`SiteIncremental` 把它的产出（变更清单）当作
 * 跳过判据的输入，并用 `visited`（本轮遍历到的文件）做"删除检测 / 惰性文件记源"。
 *
 * 判据分层（tech.md §2 定稿）：① 有 git 仓库 → `git status --porcelain` 最快且自带变更清单；
 * ② 否则 **单趟** `readdir` + 每项一次 `readAttributes`（**按条目并行** —— 按目录分派对扁平站点无效，
 * 实测 300 文件 1029ms → 46ms），候选再按**内容哈希**终判（`touch` 不误报）；
 * ③ 预设侧（`assets:`）只 stat 页依赖里出现过的键（见 `SiteIncremental.detectAssets`）。
 */
final class SiteDetect {

    private final SiteBuilder sb;

    SiteDetect(SiteBuilder sb) { this.sb = sb; }

    Set<String> changedFiles = new LinkedHashSet<>();  // 变更清单（相对 sets/；仅采集过才有意义）
    String detectorUsed = "off";                       // off=未采集 | git | stat
    boolean detectWanted;                              // 本次是否采集变更清单（预览/--detect；普通构建零开销）
    String detectorPref = "auto";                      // auto | git | stat（CLI --git/--no-git/--detector 覆盖 .env）
    Boolean gitInitForced;                             // --git-init 0/1：覆盖"仅标准布局自动建库"（null = 未指定）
    boolean gitRepo;                                   // 本次是否可用 git 检测（init/护栏/gc 之后的结果）

    /** ③ git：首次基线（init + add -A）→ 忽略护栏 → 仓库整理 → 变更清单（**按需**：普通构建零开销） */
    void loadGitState() {
        gitRepo = resolveRepo(true);
        // E（增量跳过）默认启用 → **每次构建都必须知道"哪些源变了"**（检测相已实测：单趟按条目并行，
        // 300 文件 46ms/3 线程）。`--detect` 现在只决定"要不要把那一行打出来"，不再决定要不要检测。
        detectNow();
    }

    /** `.env` 的 git 能力位：文件里写 `git=0` = 明确不用 git；文件不存在则按探测结果 */
    boolean gitEnabled() {
        return sb.dotenv.exists ? sb.dotenv.git == 1 : GitProbe.available();
    }

    /**
     * 判定"能不能用 git 检测"（含首次基线 / 忽略护栏 / 仓库整理）。
     * @param allowInit 是否允许建基线（**只给构建用**：watch 每轮轮询绝不能建库/整理仓库）
     */
    boolean resolveRepo(boolean allowInit) {
        boolean repo = gitEnabled() && GitProbe.isRepo(sb.setsDir);
        if (allowInit && gitEnabled() && gitInitForced != null && gitInitForced && !repo && sb.setsDir.isDirectory()) {
            if (GitProbe.init(sb.setsDir, sb.warnings)) {
                repo = true;
                recordBaseline();   // 基线刚建立 = "当前的源就是基线"，别让 init 自己（含 .gitignore）被报成变更
                IO.println("已在 " + sb.setsDir.getName() + "/ 建立独立 git 仓库（基线 git add -A，未提交；生成器仓库忽略该目录）");
            }
        }
        if (repo && GitProbe.anyIgnored(sb.setsDir, List.of("Environment.config", "pages", "divs"))) {
            sb.warn("sets/ 的源被 .gitignore 忽略（git 看不见这些改动）→ 变更检测回退 stat+哈希");
            repo = false;
        }
        if (allowInit && repo) {
            long[] gc = GitProbe.maybeGc(sb.setsDir, sb.dotenv.gitGc, sb.manifest.gcAt, sb.warnings);
            if (gc != null) {                       // 触发了整理 → 记下时刻与体积（机器状态，写在依赖记录里）
                sb.manifest.gcAt = gc[0];
                sb.manifest.gcBytes = gc[1];
            }
        }
        return repo;
    }

    /** 跑一次检测（写 `changedFiles` / `detectorUsed`）；构建与 watch 共用同一条路径 */
    void detectNow() {
        if (gitRepo && !detectorPref.equals("stat")) detectByGit();
        else detectByStat();
        sb.inc.detectAssets();     // E 方案(c)：预设侧（只 stat 页依赖里出现过的 assets: 键）
        sb.inc.detected = true;
    }



    /** 站点身份（规范化绝对路径）：manifest 文件名与 `site` 块都用它 */


    private void detectByGit() {
        GitProbe.Changes ch = GitProbe.status(sb.setsDir);
        if (ch == null) {
            sb.warn("git status 不可用（超时或报错）→ 变更检测回退 stat+哈希");
            detectByStat();
            return;
        }
        Set<String> out = new LinkedHashSet<>(ch.worktree());
        // 只进暂存区的条目（含 init 后那批 "A "）不可直接信：与上次构建的源记录复核，相同才排除（保守不误报）
        for (String rel : ch.stagedOnly()) {
            if (sourceChanged(rel)) out.add(rel);
        }
        for (String rel : out) {          // E：全新的非页面源文件 → 全量（与 stat 检测器同一条规则）
            if (rel.startsWith("pages/")) continue;
            if (!sb.manifest.sources.containsKey("sets:" + rel)) { sb.inc.forceFull = true; break; }
        }
        changedFiles = out;
        detectorUsed = "git";
        sb.stats.detectCalls++;
    }

    /**
     * 快筛 + **内容哈希终判**（tech.md §2 的判据分层）：size/mtime 不符 = 候选；候选若大小相同且记录里有 sha，
     * 就读一次内容按哈希复核 —— `touch`（只改 mtime）不再引发无谓重建，"同长度改写 + mtime 复原"也不再漏判。
     * 源文件的 sha 是 `readFile` 顺手算的（实测只占读盘成本的 2–7%，见 `build/ShaProbe.java`）。
     *
     * @param a 已读到的属性（**由调用方传入以免重复 stat**；null = 读不到，按老行为不当变更）
     */
    private boolean sourceChanged(String rel, java.nio.file.attribute.BasicFileAttributes a) {
        String key = "sets:" + rel;
        DepsManifest.Rec r = sb.manifest.sources.get(key);
        if (r != null && a != null && a.size() == r.size && a.lastModifiedTime().toMillis() == r.mtime) return false;
        if (r != null && r.sha != null && a != null && a.size() == r.size) {
            String sha = sha256File(new File(sb.setsDir, rel));
            if (sha != null && sha.equals(r.sha)) {
                sb.stats.hashVerifiedSkips++;
                return false;                       // 内容没变（典型：纯 touch）→ 不算变更
            }
        }
        return true;
    }

    /** 旧签名（git 的"只暂存"条目复核用：那里本来就要单独 stat 一次） */
    private boolean sourceChanged(String rel) {
        return sourceChanged(rel, SiteBuilder.attrs(new File(sb.setsDir, rel)));
    }

    /** 候选文件的哈希复核（只对候选读一次；失败按"变了"处理，保守） */
    private static String sha256File(File f) {
        try {
            return DepsManifest.recSha(Files.readAllBytes(f.toPath()));
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * stat 快筛：与上次构建的**源记录**（size+mtime）比对，不符即候选（含首次构建：无记录 = 全是候选）。
     *
     * 边界（诚实说明）：快筛**绝不单独用于跳过写盘**（写盘与否由产物记录的内容哈希把关），它只产出
     * "哪些源可能变了"的清单；"同长度改写 + mtime 被复原"这种刻意构造会漏判 —— 要绝对可靠请用
     * `detector=git`（git 有 racy-timestamp 保护）或 `--rebuild`。
     */
    private void detectByStat() {
        Set<String> ch = new LinkedHashSet<>();
        ExecutorService pool = sb.ioPool(sb.ioThreads(64));   // 条目数未知，按"至少有点活"取策略；池只用于目录内批次
        try {
            detectWalk(sb.setsDir, ch, pool);
        } finally {
            if (pool != null) pool.shutdownNow();
        }
        // E：① 删除/改名检测（BUG-003：老实现只枚举"存在"的文件 → 看不见删除）——与源记录差集，**零额外 I/O**
        for (String key : new ArrayList<>(sb.manifest.sources.keySet())) {
            if (!key.startsWith("sets:")) continue;
            String rel = key.substring(5);
            if (!sb.inc.visited.containsKey(rel)) ch.add(rel);
        }
        // E：② "全新的、非页面的"源文件（新 `.extends`/`.contract`/新 div/新 global）无法精确归因 → 全量
        for (String rel : ch) {
            if (rel.startsWith("pages/")) continue;
            if (!sb.manifest.sources.containsKey("sets:" + rel)) { sb.inc.forceFull = true; break; }
        }
        changedFiles = ch;
        detectorUsed = "stat";
        sb.stats.detectCalls++;
    }

    /**
     * **单趟**递归扫描（2026-09 重量后重写，探针 `build/EProbe.java`）：`listFiles` 一次拿名字 +
     * **每项一次 `readAttributes`**（同时得到 isDirectory/size/mtime）→ 老实现"遍历（每项一次 `isDirectory()`=一次 stat）
     * 再逐文件 stat 比对"把**同一批条目 stat 了两遍**，且并行只能按目录分派，对 bigsite 的扁平结构
     * （300 个页面文件全在 `pages/blog/`）几乎无效（1029ms → 762ms）。现在目录内条目**按序批量并行**、
     * 结果按下标回填 → **顺序与老实现逐项一致**（目录内按名排序 + 深度优先）。实测 300 文件：343ms 串行 / 46ms（3 线程）。
     */
    private void detectWalk(File dir, Set<String> changed, ExecutorService pool) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        Arrays.sort(fs, Comparator.comparing(File::getName));
        int n = fs.length;
        java.nio.file.attribute.BasicFileAttributes[] as = new java.nio.file.attribute.BasicFileAttributes[n];
        if (pool != null && n >= 8) {                    // 小目录不值得派任务
            List<Callable<Void>> tasks = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                final int k = i;
                tasks.add(() -> {
                    as[k] = SiteBuilder.attrs(fs[k]);
                    return null;
                });
            }
            try {
                for (Future<Void> f : pool.invokeAll(tasks)) f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // 单条目失败由 as[k]==null 表达（与老行为一致：读不到 = 当它不存在）
            }
        } else {
            for (int i = 0; i < n; i++) as[i] = SiteBuilder.attrs(fs[i]);
        }
        for (int i = 0; i < n; i++) {
            File f = fs[i];
            if (as[i] == null) continue;                 // 老实现里 `isFile()` 也会是 false → 不进清单
            if (as[i].isDirectory()) {
                String nm = f.getName();
                if (nm.equals(".git") || nm.equals(".ssvul")) continue;
                detectWalk(f, changed, pool);
                continue;
            }
            if (!as[i].isRegularFile()) continue;
            String key = sb.sourceKeyOf(f);
            if (key == null) continue;
            String rel = key.substring(key.indexOf(':') + 1);
            sb.inc.visited.put(rel, new long[]{as[i].size(), as[i].lastModifiedTime().toMillis()});   // E：删除检测/惰性记源两用
            if (sourceChanged(rel, as[i])) changed.add(rel);
        }
    }

    /** init 后把**当前全部源**记入基线（size+mtime）：初始化本身不该被下一句检测报成"全变了" */
    private void recordBaseline() {
        List<File> all = new ArrayList<>();
        collectFiles(sb.setsDir, all);
        for (File f : all) {
            String key = sb.sourceKeyOf(f);
            if (key == null || sb.manifest.sources.containsKey(key)) continue;
            DepsManifest.Rec r = new DepsManifest.Rec();
            r.size = f.length();
            r.mtime = f.lastModified();
            sb.manifest.putSource(key, r);
        }
    }

    /** sets/ 下全部常规文件（**排序**：遍历顺序不能随文件系统变；跳过 .git/.ssvul） */
    private static void collectFiles(File dir, List<File> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        Arrays.sort(fs, Comparator.comparing(File::getName));
        for (File f : fs) {
            if (f.isDirectory()) {
                String n = f.getName();
                if (n.equals(".git") || n.equals(".ssvul")) continue;
                collectFiles(f, out);
            } else if (f.isFile()) {
                out.add(f);
            }
        }
    }
}
