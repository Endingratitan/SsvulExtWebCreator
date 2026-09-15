/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Integra;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * 输出写盘与替换趟（包内私有）：文本逐文件做 pre-assets/@data/@favicon/@page/bucket 替换（按深度）→
 * UTF-8 写盘；二进制直拷；预设文件与连带目录（KaTeX fonts）按需复制。
 * owner 为 SiteBuilder（共享队列/错误/桶配置）。
 *
 * 0.3.3 起写盘相并行（准备串行 → 判定/比较/落盘并行 → 按序合并），见 {@link #writeAll()}。
 */
class SiteWrite {

    private final SiteBuilder sb;

    SiteWrite(SiteBuilder sb) { this.sb = sb; }

    /**
     * 写盘相（0.3.3 起**并行**）：
     * ① **串行准备**：跑替换趟（会往 `queue`/`presetCopies` 追加离线复制项，必须按序）+ 压缩（有 P4 缓存），
     *    产出 `Job` 列表；
     * ② **并行执行**：每个 `Job` 自己完成"快路径判定（stat）→ 慢比较（读）→ 落盘"，结果只写在自己身上；
     * ③ **按序合并**：计数/依赖记录/错误一律按 Job 顺序汇总 → 与串行版**逐字节等价且可复现**。
     *
     * 为什么这么切：实测（`build/ParProbe.java`，302 文件 / `/mnt/d`）stat 462→32ms、读 505→40ms、写 448→69ms
     * （8 线程），而替换趟只有 2–4ms —— **并行只该给 I/O，不该给这条必须保序的准备趟**。
     */
    void writeAll() {
        // **目标去重是并发正确性的前提**：同一路径可能被排多次（典型：offline 模式下多页引用同一个 outer 镜像，
        // `replaceBuckets` 每见一次引用就追加一个队列项）。串行时"后写覆盖前写"无害；并行时两个 job 会同时写
        // 同一文件 → `Files.copy` 竞态报错（实测 example-sets 直接构建失败）。保留**最后一次**（= 串行语义），
        // 位置沿用首次出现 → 记录与错误的合并顺序仍然确定。
        Map<String, Job> byRel = new LinkedHashMap<>();
        Map<String, Set<String>> pendingRefs = new LinkedHashMap<>();   // E：正文引用（待 byRel 齐了再过滤）
        for (int qi = 0; qi < sb.queue.size(); qi++) {   // 索引循环：替换趟可能追加离线复制项
            SiteBuilder.Queued q = sb.queue.get(qi);
            String rel = sb.relOf(q.target);
            sb.written.add(rel);                  // 写集：**含被跳过写入的**（孤儿清理/依赖记录的前提）
            if (q.binary) {
                byRel.put(rel, Job.binary(q.source, q.target, rel));
                continue;
            }
            String c = q.content;
            long t0 = System.nanoTime();
            c = replacePreAssets(c, q.depth, q.target);
            c = c.replace("@data/", SiteBuilder.depthPrefix(q.depth) + "assets/data/");
            c = c.replace("@favicon/", SiteBuilder.depthPrefix(q.depth) + "favicon/");
            c = replacePageRefs(c, q.depth);
            if (!q.target.getName().endsWith(".js")) c = replaceBuckets(c, q.depth);   // JS 不做 bk/ 替换（误伤风险）
            sb.stats.nsReplace += System.nanoTime() - t0;
            // minify：生成物 js（含站点 CODEUI.js）；vendor lib/ 与 raw 资产不碰
            if (sb.compressOn() && q.target.getName().endsWith(".js")
                    && !q.target.getPath().replace('\\', '/').contains("/lib/")) {
                t0 = System.nanoTime();
                c = sb.minifyJs(c);
                sb.stats.nsMinify += System.nanoTime() - t0;
            }
            sb.addOwnerOut(q.owner, rel);                     // E：本页自己的产物
            collectRefs(pendingRefs, q.owner, c);             // E：本页引用的预设/outer 副本
            byRel.put(rel, Job.text(q.target, rel, c.getBytes(StandardCharsets.UTF_8)));
        }
        for (String rel : sb.presetCopies) {
            File src = new File(sb.presetDir, rel);
            File dst = new File(sb.outputDir, "assets/pre/" + rel);
            String drel = sb.relOf(dst);
            sb.written.add(drel);
            sb.inc.addPresetOut(rel, drel);               // E：副本落进**每个引用它的页**的 outs（跳过重放要用）
            if (sb.compressOn() && rel.endsWith(".js") && !rel.startsWith("lib/")) {
                // 官方预设 js（md/js 等）在输出副本压缩（保许可头）；vendor lib/ 与 css 原样
                long t0 = System.nanoTime();
                byte[] bytes = sb.minifyJs(sb.readFile(src)).getBytes(StandardCharsets.UTF_8);
                sb.stats.nsMinify += System.nanoTime() - t0;
                byRel.put(drel, Job.text(dst, drel, bytes));
            } else {
                byRel.put(drel, Job.binary(src, dst, drel));
            }
        }
        for (String rel : sb.presetDirCopies) {           // 连带目录（katex fonts 等）也走同一 Job 机制
            addDirJobs(new File(sb.presetDir, rel), new File(sb.outputDir, "assets/pre/" + rel), byRel);
        }
        // E：正文引用只在"确实会被复制"时才计为这一页的产物（否则跳过重放会引入幻影产物 → 永远判缺失）
        for (Map.Entry<String, Set<String>> e : pendingRefs.entrySet()) {
            for (String r : e.getValue()) if (byRel.containsKey(r)) sb.addOwnerOut(e.getKey(), r);
        }
        List<Job> jobs = new ArrayList<>(byRel.values());

        int threads = sb.ioThreads(jobs.size());
        ExecutorService pool = sb.ioPool(threads);
        try {
            if (pool == null) {
                for (Job j : jobs) runJob(j);              // 串行：同一套代码路径，不做两套实现
            } else {
                List<Callable<Void>> tasks = new ArrayList<>(jobs.size());
                for (Job j : jobs) tasks.add(() -> {
                    runJob(j);
                    return null;
                });
                for (Future<Void> f : pool.invokeAll(tasks)) {
                    try {
                        f.get();
                    } catch (Exception ignored) {
                        // runJob 自己吞异常并写进 j.error（合并阶段按序上报）
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (pool != null) pool.shutdownNow();
        }
        merge(jobs);
        if (!sb.errors.isEmpty()) throw new RuntimeException(sb.joinErrors());
    }

    /** 一个待落盘项：判定与 I/O 全在并行阶段完成，结果只写在自己身上（合并阶段按序汇总 → 确定性） */
    private static final class Job {
        final File source;      // != null → 二进制拷贝
        final File target;
        final String rel;
        final byte[] bytes;     // 文本内容；null = 二进制
        boolean fast, compared, written;
        String error;
        DepsManifest.Rec rec;
        long nsSha, nsStat, nsCompare, nsWriteIo;
        int shaCalls, statCalls;

        private Job(File source, File target, String rel, byte[] bytes) {
            this.source = source;
            this.target = target;
            this.rel = rel;
            this.bytes = bytes;
        }

        static Job text(File target, String rel, byte[] bytes) { return new Job(null, target, rel, bytes); }

        static Job binary(File source, File target, String rel) { return new Job(source, target, rel, null); }
    }

    /** 单件全流程：sha →（记录比对 + stat 快路径）→（读+逐字节比较）→ 落盘；异常收集不外抛 */
    private void runJob(Job j) {
        try {
            if (j.bytes != null) {
                long t0 = System.nanoTime();
                String sha = DepsManifest.recSha(j.bytes);   // 记录用 64 位哈希前缀（与记录同规格比较）
                j.nsSha += System.nanoTime() - t0;
                j.shaCalls++;
                if (!sb.rebuildAll) {
                    DepsManifest.Rec rec = sb.manifest.output(j.rel);
                    if (rec != null && sha.equals(rec.sha) && rec.size == j.bytes.length) {
                        t0 = System.nanoTime();
                        BasicFileAttributes a = SiteBuilder.attrs(j.target);
                        j.nsStat += System.nanoTime() - t0;
                        j.statCalls++;
                        if (a != null && a.isRegularFile() && a.size() == rec.size
                                && a.lastModifiedTime().toMillis() == rec.mtime) {
                            j.fast = true;
                            return;                       // 内容与上次一致 + 磁盘未被外部改动 → O(1) 跳过
                        }
                    }
                    t0 = System.nanoTime();
                    boolean same = j.target.isFile() && j.target.length() == j.bytes.length
                            && Arrays.equals(Files.readAllBytes(j.target.toPath()), j.bytes);
                    j.nsCompare += System.nanoTime() - t0;
                    j.compared = true;
                    if (same) {
                        j.rec = sb.textRec(j.target, j.bytes.length, sha);
                        return;
                    }
                }
                t0 = System.nanoTime();
                Files.createDirectories(j.target.getParentFile().toPath());
                Files.write(j.target.toPath(), j.bytes);
                j.nsWriteIo += System.nanoTime() - t0;
                j.written = true;
                j.rec = sb.textRec(j.target, j.bytes.length, sha);
                return;
            }
            long t0 = System.nanoTime();
            BasicFileAttributes da = SiteBuilder.attrs(j.target);
            j.nsStat += System.nanoTime() - t0;
            j.statCalls++;
            long sSize = j.source.length(), sMtime = j.source.lastModified();
            if (!sb.rebuildAll) {
                DepsManifest.Rec rec = sb.manifest.output(j.rel);
                if (rec != null && rec.sha == null && rec.srcSize == sSize && rec.srcMtime == sMtime
                        && da != null && da.isRegularFile() && da.size() == rec.size
                        && da.lastModifiedTime().toMillis() == rec.mtime) {
                    j.fast = true;
                    return;
                }
                t0 = System.nanoTime();
                boolean same = j.target.isFile() && j.target.length() == sSize
                        && Files.mismatch(j.source.toPath(), j.target.toPath()) < 0;
                j.nsCompare += System.nanoTime() - t0;
                j.compared = true;
                if (same) {
                    j.rec = sb.binaryRec(j.target, sSize, sSize, sMtime);
                    return;
                }
            }
            t0 = System.nanoTime();
            Files.createDirectories(j.target.getParentFile().toPath());
            Files.copy(j.source.toPath(), j.target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            j.nsWriteIo += System.nanoTime() - t0;
            j.written = true;
            j.rec = sb.binaryRec(j.target, sSize, sSize, sMtime);
        } catch (IOException e) {
            j.error = (j.bytes != null ? "写盘失败: " + j.target : "预设复制失败: " + j.target + "（来源 " + j.source + "）")
                    + " - " + e.getMessage();
        }
    }

    /** 按 Job 顺序合并：计数 / 依赖记录 / 错误全部确定（与串行版一致），无共享写 */
    private void merge(List<Job> jobs) {
        for (Job j : jobs) {
            sb.stats.nsSha += j.nsSha;
            sb.stats.nsStat += j.nsStat;
            sb.stats.nsCompare += j.nsCompare;
            sb.stats.nsWriteIo += j.nsWriteIo;
            sb.stats.shaCalls += j.shaCalls;
            sb.stats.statCalls += j.statCalls;
            if (j.compared) {
                sb.stats.slowCompares++;
                sb.stats.compareCalls++;
            }
            if (j.written) sb.stats.writeCalls++;
            if (j.error != null) {
                sb.errors.add(j.error);
                continue;
            }
            if (j.written) sb.stats.filesWritten++;
            else {
                sb.stats.filesSkipped++;
                if (j.fast) sb.stats.fastSkips++;
            }
            if (j.rec != null) sb.manifest.putOutput(j.rel, j.rec);
        }
    }

    /** 目录连带复制 → 展平成 Job（**排序**：遍历顺序不能随文件系统变；同名目标沿用去重规则） */
    private void addDirJobs(File src, File dst, Map<String, Job> byRel) {
        File[] fs = src.listFiles();
        if (fs == null) return;
        Arrays.sort(fs, Comparator.comparing(File::getName));
        for (File f : fs) {
            if (f.isDirectory()) {
                addDirJobs(f, new File(dst, f.getName()), byRel);
                continue;
            }
            File out = new File(dst, f.getName());
            String rel = sb.relOf(out);
            sb.written.add(rel);
            byRel.put(rel, Job.binary(f, out, rel));
        }
    }

    private String replacePreAssets(String c, int depth, File target) {
        String out = c;
        int idx;
        while ((idx = out.indexOf("pre-assets/")) >= 0) {
            int start = idx + "pre-assets/".length();
            int end = start;
            while (end < out.length() && isPathChar(out.charAt(end))) end++;
            String suffix = out.substring(start, end);
            if (suffix.isEmpty() || suffix.contains("..")) {
                sb.errors.add("pre-assets/ 引用非法: \"" + suffix + "\"（" + target.getName() + "）");
                break;
            }
            // ← 热路径上最容易被忽略的 stat：每个 pre-assets 引用检查一次存在性（实测是写盘相的大头之一）
            long t0 = System.nanoTime();
            boolean exists = new File(sb.presetDir, suffix).isFile();
            sb.stats.nsStat += System.nanoTime() - t0;
            sb.stats.statCalls++;
            sb.stats.presetCheckCalls++;
            if (!exists) {
                sb.errors.add("预设文件不存在: src/assets/" + suffix);
                break;
            }
            sb.presetCopies.add(suffix);
            out = out.substring(0, idx) + SiteBuilder.depthPrefix(depth) + "assets/pre/" + out.substring(start);
        }
        return out;
    }

    private String replacePageRefs(String c, int depth) {
        String out = c;
        int idx;
        while ((idx = out.indexOf("@page/")) >= 0) {
            int start = idx + "@page/".length();
            int end = start;
            while (end < out.length() && isPathChar(out.charAt(end))) end++;
            String path = out.substring(start, end);
            if (path.equals("INDEX")) {   // INDEX 特判页输出在站点根（index.html），非 pages/INDEX
                out = out.substring(0, idx) + SiteBuilder.depthPrefix(depth) + out.substring(end);
                continue;
            }
            if (path.isEmpty() || !sb.pageOutputs.contains("pages/" + path)) {
                sb.errors.add("站内互链目标不存在: @page/" + path);
                break;
            }
            out = out.substring(0, idx) + SiteBuilder.depthPrefix(depth) + "pages/" + path + "/" + out.substring(end);
        }
        return out;
    }

    /** 引用路径允许的字符（字母数字、点、下划线、斜线、连字符） */
    private static boolean isPathChar(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '.' || ch == '_' || ch == '/' || ch == '-';
    }

    /** E：正文里的 `assets/pre/X` / `assets/outer/Y` 就是"这一页引用了它们"（写出后由 byRel 再过滤一次） */
    private void collectRefs(Map<String, Set<String>> dst, String owner, String text) {
        if (owner == null) return;
        for (String prefix : new String[]{"assets/pre/", "assets/outer/"}) {
            int i = text.indexOf(prefix);
            while (i >= 0) {
                int s = i + prefix.length(), e = s;
                while (e < text.length() && isPathChar(text.charAt(e))) e++;
                if (e > s) dst.computeIfAbsent(owner, k -> new LinkedHashSet<>()).add(prefix + text.substring(s, e));
                i = text.indexOf(prefix, Math.max(e, i + 1));
            }
        }
    }

    private String replaceBuckets(String c, int depth) {
        String out = c;
        List<String> names = new ArrayList<>(sb.buckets.keySet());
        names.sort(Comparator.comparingInt(String::length).reversed());
        int pos = 0;   // 单趟扫描：替换后的文本不再重扫（替换结果含 n + "/" 会导致自我重复替换死循环）
        while (true) {
            int bestIdx = -1;
            String bestName = null;
            for (String n : names) {
                int i = out.indexOf(n + "/", pos);
                if (i >= 0 && (bestIdx < 0 || i < bestIdx)) { bestIdx = i; bestName = n; }
            }
            if (bestIdx < 0) break;
            String n = bestName;
            int start = bestIdx + n.length() + 1;
            int end = start;
            while (end < out.length() && isPathChar(out.charAt(end))) end++;
            String path = out.substring(start, end);
            File hit = sb.outerFiles.get(n + "/" + path);
            String ins;
            if (sb.offline) {
                // 离线模式：命中 outer 镜像 → 本地复制并替换；未命中 → 报错
                if (path.isEmpty() || hit == null) {
                    sb.errors.add("离线模式下 bucket 资源未在 outer 镜像中: " + n + "/" + path);
                    break;
                }
                sb.queue.add(new SiteBuilder.Queued(new File(sb.outputDir, "assets/outer/" + n + "/" + path), hit));
                ins = SiteBuilder.depthPrefix(depth) + "assets/outer/" + n + "/" + path;
            } else {
                // 线上模式：输出线上 URL；outer 存在时对未命中的引用发对照警告（A 功能）
                if (hit == null && sb.outerDirExists && !path.isEmpty()) {
                    sb.warn("bucket 资源未在 outer 镜像中（线上可能 404）: " + n + "/" + path);
                }
                ins = sb.buckets.get(n) + "/" + path;
            }
            out = out.substring(0, bestIdx) + ins + out.substring(end);
            pos = bestIdx + ins.length();
        }
        return out;
    }
}
