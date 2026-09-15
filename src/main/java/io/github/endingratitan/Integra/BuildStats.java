/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Integra;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * 构建统计（包内私有）：**行为计数优先，墙钟为辅**。
 *
 * 为什么以计数为主：性能门禁若断言时间，会随机器快慢抖动；断言"schema 每进程只编译一次""二次构建零写盘"
 * 这类**结构计数**才是稳的（也是"优化被回退"时最先变红的信号）。分相耗时只用于人工决策与排错。
 *
 * **0.4.0 起分两类**（并行渲染的前提）：
 * ① 会被**多个渲染线程**写到的计数用原子类型（`AtomicInteger` / `LongAdder`）——写在跨页共享的助手方法里
 *    （`readFile` / `minifyJs` / `aggregateDiv*` / `AssetsConfigReader`）；
 * ② 只在**串行阶段**写的计数保持普通 int/long（写盘相的合并、变更检测、扫描相等），零开销。
 * 计数都是"和"，与顺序无关 → 并行前后数值一致，结构门禁不用改。
 *
 * 不参与产物（不写盘、不进页面），只在构建末尾打印一行报告。
 */
public final class BuildStats {

    // ---- 行为计数：**多线程写**（跨页共享助手）→ 原子 ----
    public final AtomicInteger schemaCompiles = new AtomicInteger();   // page.schema.json 编译次数（P1 后应为 1/进程）
    public final AtomicInteger jsonParses = new AtomicInteger();       // 页面 json 解析次数
    public final AtomicInteger diskReads = new AtomicInteger();        // 真实读盘次数（P3 缓存后应≈唯一文件数）
    public final AtomicInteger cacheHits = new AtomicInteger();        // 内容缓存命中
    public final AtomicInteger cacheMisses = new AtomicInteger();      // 内容缓存未命中
    public final AtomicInteger cacheStores = new AtomicInteger();      // 内容缓存存入条目数
    public final AtomicInteger minifyCalls = new AtomicInteger();      // JS 压缩真实执行次数（P4 后≈唯一聚合数）
    public final LongAdder minifyBytes = new LongAdder();              // 进入压缩的总字节
    public final AtomicInteger minifyHits = new AtomicInteger();       // JS 压缩结果缓存命中
    public final AtomicInteger cssMinifyCalls = new AtomicInteger();   // CSS 去重+压缩真实执行次数
    public final AtomicInteger cssMinifyHits = new AtomicInteger();    // CSS 结果缓存命中
    public final LongAdder nsMd = new LongAdder();                     // 页面相：md 渲染
    public final LongAdder nsAgg = new LongAdder();                    // 页面相：div js/css 聚合
    public final LongAdder nsJson = new LongAdder();                   // 页面相：页面 json 解析
    public final AtomicInteger mdCalls = new AtomicInteger();          // md 渲染调用次数
    public final AtomicInteger aggCalls = new AtomicInteger();         // 聚合调用次数
    public final LongAdder nsIdxParse = new LongAdder();               // 扫描相索引：页面 json 解析（**与渲染相重复**）
    public final LongAdder nsIdxText = new LongAdder();                // 扫描相索引：抽取正文全文（collectMd）
    public final LongAdder nsIdxClean = new LongAdder();               // 扫描相索引：标题/摘要净化（cleanInline）

    // ---- 行为计数：**只在串行阶段写** → 普通类型（零开销）----
    public int filesWritten;        // 真正写盘的文件数（P5 后二次构建应为 0）
    public int filesSkipped;        // 跳过写盘的文件数（内容未变）
    public int fastSkips;           // 其中走 manifest 快路径（不读文件）
    public int slowCompares;        // 退回"读+逐字节比较"的次数
    public int detectCalls;         // 变更检测执行次数（0 = 本次未采集变更清单）
    public int hashVerifiedSkips;   // 快筛候选经**内容哈希复核**判为"其实没变"的次数（典型：纯 touch）

    // ---- 写盘相分项（只在写盘相的串行准备/合并里写）----
    public long nsReplace;          // 替换趟（pre-assets/@data/@favicon/@page/bucket 字符串扫描）
    public long nsMinify;           // 写盘相里的压缩调用（缓存未命中时才真压）
    public long nsSha;              // 内容 sha256
    public long nsStat;             // 产物/来源 stat（快路径校验 + pre-assets 存在性检查）
    public long nsCompare;          // 回退的读 + 逐字节比较
    public long nsWriteIo;          // createDirectories + 真正落盘
    public int statCalls;           // stat 次数
    public int shaCalls;            // sha256 次数
    public int compareCalls;        // 慢比较次数
    public int writeCalls;          // 真写次数（含拷贝）
    public int presetCheckCalls;    // pre-assets 引用存在性检查次数（热路径上最容易被忽略的 stat）

    // ---- 分相耗时（ms，仅参考）----
    public long tScan, tPages, tGlobals, tWrite, tDetect, tTotal;
    // ---- 扫描相分项（归因用：先量后优）----
    public long tDivs, tScanPages, tScanData, tScanMisc, tIndex;
    public int depPages, depKeysMin, depKeysMax, depGroups;   // E 埋点（依赖图规模）
    public long tEnv;              // .env + Environment.config + CNAME 入队（扫描相里没归因的那段）
    public long tFavicon, tOuter, tGlobalScan;   // 把 tScanMisc 拆开

    /** 一行报告（构建末尾打印，便于 Bench/CI 抓取） */
    public String report() {
        long msJson = ms(nsJson.sum()), msMd = ms(nsMd.sum()), msAgg = ms(nsAgg.sum());
        return "[构建统计] schema编译=" + schemaCompiles.get() + " json解析=" + jsonParses.get()
                + " 读盘=" + diskReads.get() + " 缓存(命中=" + cacheHits.get() + " 存入=" + cacheStores.get() + ")"
                + " 压缩=" + minifyCalls.get() + "次(命中" + minifyHits.get() + ")/" + (minifyBytes.sum() / 1024) + "KB"
                + " css=" + cssMinifyCalls.get() + "次(命中" + cssMinifyHits.get() + ")"
                + " 写盘=" + filesWritten + " 跳过=" + filesSkipped + "(快路径" + fastSkips + "/慢比较" + slowCompares + ")"
                + (detectCalls > 0 ? " 变更检测=" + tDetect + "ms" : "")
                + " | 扫描=" + tScan + "ms 页面=" + tPages + "ms 全局=" + tGlobals + "ms 写盘=" + tWrite + "ms"
                + " 合计=" + tTotal + "ms"
                + (tWrite > 0 ? "\n[写盘细分] 替换=" + ms(nsReplace) + " 压缩=" + ms(nsMinify) + " sha=" + ms(nsSha)
                        + " stat=" + ms(nsStat) + "(" + statCalls + "次，其中预设检查" + presetCheckCalls + ")"
                        + " 比较=" + ms(nsCompare) + "(" + compareCalls + "次)"
                        + " 落盘=" + ms(nsWriteIo) + "(" + writeCalls + "次)" : "")
                + (tScan > 0 ? "\n[扫描细分] divs=" + tDivs + " 页面=" + tScanPages + " data=" + tScanData
                        + " favicon=" + tFavicon + " outer=" + tOuter + " global=" + tGlobalScan + " 索引=" + tIndex
                        + "（线程累计：解析含读=" + ms(nsIdxParse.sum()) + " 正文=" + ms(nsIdxText.sum())
                        + " 净化=" + ms(nsIdxClean.sum()) + "）env=" + tEnv + " 合计=" + tScan : "")
                + (tPages > 0 ? "\n[页面细分] json=" + msJson + " md=" + msMd + "(" + mdCalls.get() + "次)"
                        + " 聚合=" + msAgg + "(" + aggCalls.get() + "次)"
                        + " 其余（模板/标签/组装）=" + (msJson + msMd + msAgg <= tPages
                                ? String.valueOf(tPages - msJson - msMd - msAgg) : "n/a（并行：以上为**线程累计**）")
                        + "（墙钟合计=" + tPages + "）" : "");
    }

    private static long ms(long ns) { return ns / 1_000_000; }
}
