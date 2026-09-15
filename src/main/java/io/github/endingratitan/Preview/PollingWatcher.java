/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Preview;

import io.github.endingratitan.Integra.ChangeSet;
import io.github.endingratitan.Integra.SiteBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 轮询式源监听（公共 API）：本机 `/mnt/d` 上 inotify **完全无事件**，可靠的变更检测只能轮询。
 *
 * 四条纪律（每条都对应一个实测教训）：
 * ① **按需唤醒**：闸门说"没人在看"就跳过这一轮（`watch=auto`）——没人开页面时**零开销**；
 * ② **自适应退避**：间隔 = `max(poll, 4×上次检测耗时)`，连续无变更再逐步翻倍（上限 2s），
 *    一旦有变更立刻回到 `poll`。300 页站点一次 `git status` ~340ms，固定 700ms 轮询会占掉半个核；
 * ③ **去抖**：检出变更后等静默（默认 250ms）再复检，最多 4 轮 —— 编辑器保存是"临时文件 + rename"、
 *    格式化会连改多文件，别为中间态构建；
 * ④ **single-flight**：闸门说正在构建 → 本轮不检测；连写只保留"最后一次"（去抖那步天然合并）。
 *
 * 只依赖 `SiteBuilder.detectChanges`（与构建**同一套**判据：git 两段解析 + 快筛 + 哈希终判），
 * 所以不存在"watch 说变了、构建说没变"这种两套标准。
 */
public final class PollingWatcher implements SourceWatcher {

    private final File setsDir, outputDir, presetDir;
    private final String detectorPref;
    private final int pollMs;
    private final long debounceMs;
    private final int idleCeilMs;
    private final Consumer<String> log;

    private volatile boolean running;
    private Thread thread;
    private Consumer<ChangeSet> onChange;
    private WatchGate gate = WatchGate.open();

    /** 轮询耗时统计（诊断用；由 {@link #pollCostMs()} 暴露，测试与日志都看它） */
    private volatile long pollCostMs;
    private volatile int polls;

    public PollingWatcher(File setsDir, File outputDir, File presetDir, String detectorPref, int pollMs) {
        this(setsDir, outputDir, presetDir, detectorPref, pollMs, 250, 2000, s -> { });
    }

    public PollingWatcher(File setsDir, File outputDir, File presetDir, String detectorPref, int pollMs,
                          long debounceMs, int idleCeilMs, Consumer<String> log) {
        this.setsDir = setsDir;
        this.outputDir = outputDir;
        this.presetDir = presetDir;
        this.detectorPref = detectorPref;
        this.pollMs = Math.max(50, pollMs);
        this.debounceMs = Math.max(0, debounceMs);
        this.idleCeilMs = Math.max(this.pollMs, idleCeilMs);
        this.log = log != null ? log : s -> { };
    }

    /** 最近一次检测耗时（ms）：退避与日志都用它 */
    public long pollCostMs() { return pollCostMs; }

    public int pollCount() { return polls; }

    @Override
    public void start(Consumer<ChangeSet> onChange, WatchGate gate) {
        if (running) return;
        this.onChange = onChange;
        this.gate = gate == null ? WatchGate.open() : gate;
        this.running = true;
        this.thread = new Thread(this::loop, "ssvul-watch");
        this.thread.setDaemon(true);          // 守护线程：绝不让预览因为监听线程而无法退出
        this.thread.start();
    }

    @Override
    public void close() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    private void loop() {
        long interval = pollMs;
        int idle = 0;
        while (running) {
            try {
                Thread.sleep(interval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!running) return;
            try {
                if (!gate.viewersPresent()) {     // ① 按需唤醒：没人在看 → 不检测（省 WSL 的资源）
                    interval = pollMs;
                    idle = 0;
                    continue;
                }
                if (gate.buildInFlight()) {       // ④ 构建中不抢 I/O
                    continue;
                }
                long t0 = System.nanoTime();
                List<String> warn = new ArrayList<>();
                ChangeSet cs = SiteBuilder.detectChanges(setsDir, outputDir, presetDir, detectorPref, warn);
                pollCostMs = (System.nanoTime() - t0) / 1_000_000;
                polls++;
                for (String w : warn) log.accept(w);
                if (cs.isEmpty()) {               // ② 空闲退避
                    idle++;
                    long backoff = Math.max(pollMs, pollCostMs * 4);      // 别比检测本身还快
                    interval = Math.min(idleCeilMs, idle > 3 ? Math.max(backoff, interval * 2) : backoff);
                    continue;
                }
                ChangeSet merged = debounce(cs);  // ③ 等静默再复检
                idle = 0;
                interval = pollMs;
                if (merged != null && !merged.isEmpty()) onChange.accept(merged);
            } catch (RuntimeException e) {
                log.accept("watch 检测异常（已忽略，下一轮重试）: " + e.getMessage());
                interval = Math.min(idleCeilMs, Math.max(pollMs, pollCostMs * 4));
            }
        }
    }

    /** 去抖：等一段静默后再复检，把中间态合并成一次（最多 4 轮，避免一直等下去） */
    private ChangeSet debounce(ChangeSet first) {
        ChangeSet latest = first;
        for (int round = 0; round < 4 && running; round++) {
            try {
                Thread.sleep(debounceMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            if (gate.buildInFlight()) return null;      // 已经有人在建了 → 丢弃本次（下一轮会重新检出）
            List<String> warn = new ArrayList<>();
            ChangeSet again = SiteBuilder.detectChanges(setsDir, outputDir, presetDir, detectorPref, warn);
            if (again.isEmpty()) return latest;         // 静默了 → 用这一批
            latest = again;
        }
        return latest;
    }
}
