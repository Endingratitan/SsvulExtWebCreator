/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Integra;

/**
 * 构建统计（包内私有）：**行为计数优先，墙钟为辅**。
 *
 * 为什么以计数为主：性能门禁若断言时间，会随机器快慢抖动；断言"schema 每进程只编译一次""二次构建零写盘"
 * 这类**结构计数**才是稳的（也是"优化被回退"时最先变红的信号）。分相耗时只用于人工决策与排错。
 *
 * 不参与产物（不写盘、不进页面），只在构建末尾打印一行报告。
 */
public final class BuildStats {

    // ---- 行为计数（结构性门禁用这些）----
    public int schemaCompiles;      // page.schema.json 编译次数（P1 后应为 1/进程）
    public int jsonParses;          // 页面 json 解析次数
    public int diskReads;           // 真实读盘次数（P3 缓存后应≈唯一文件数）
    public int cacheHits;           // 内容缓存命中
    public int cacheMisses;         // 内容缓存未命中
    public int minifyCalls;         // JS 压缩调用次数（P4 后应≈唯一聚合数）
    public long minifyBytes;        // 进入压缩的总字节
    public int filesWritten;        // 真正写盘的文件数（P5 后二次构建应为 0）
    public int filesSkipped;        // 跳过写盘的文件数（内容未变）
    public int fastSkips;           // 其中走 manifest 快路径（不读文件）
    public int slowCompares;        // 退回"读+逐字节比较"的次数
    public int cacheStores;         // 内容缓存实际存入的条目数（二次命中才存）
    public int minifyHits;          // JS 压缩结果缓存命中次数
    public int cssMinifyCalls;      // CSS 去重+压缩真实执行次数
    public int cssMinifyHits;       // CSS 结果缓存命中次数
    public int detectCalls;         // 变更检测执行次数（0 = 本次未采集变更清单）
    public int hashVerifiedSkips;   // 快筛候选经**内容哈希复核**判为"其实没变"的次数（典型：纯 touch）

    // ---- 写盘相分项（纳秒累加；并行后改为墙钟，见 tech.md）----
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

    /** 一行报告（构建末尾打印，便于 Bench/CI 抓取） */
    public String report() {
        return "[构建统计] schema编译=" + schemaCompiles + " json解析=" + jsonParses
                + " 读盘=" + diskReads + " 缓存(命中=" + cacheHits + " 存入=" + cacheStores + ")"
                + " 压缩=" + minifyCalls + "次(命中" + minifyHits + ")/" + (minifyBytes / 1024) + "KB"
                + " css=" + cssMinifyCalls + "次(命中" + cssMinifyHits + ")"
                + " 写盘=" + filesWritten + " 跳过=" + filesSkipped + "(快路径" + fastSkips + "/慢比较" + slowCompares + ")"
                + (detectCalls > 0 ? " 变更检测=" + tDetect + "ms" : "")
                + " | 扫描=" + tScan + "ms 页面=" + tPages + "ms 全局=" + tGlobals + "ms 写盘=" + tWrite + "ms"
                + " 合计=" + tTotal + "ms"
                + (tWrite > 0 ? "\n[写盘细分] 替换=" + ms(nsReplace) + " 压缩=" + ms(nsMinify) + " sha=" + ms(nsSha)
                        + " stat=" + ms(nsStat) + "(" + statCalls + "次，其中预设检查" + presetCheckCalls + ")"
                        + " 比较=" + ms(nsCompare) + "(" + compareCalls + "次)"
                        + " 落盘=" + ms(nsWriteIo) + "(" + writeCalls + "次)" : "")
                + (tScan > 0 ? "\n[扫描细分] divs=" + tDivs + " 页面=" + tScanPages + " data=" + tScanData
                        + " favicon/outer/global=" + tScanMisc + " 索引=" + tIndex + "（合计=" + tScan + "）" : "");
    }

    private static long ms(long ns) { return ns / 1_000_000; }
}
