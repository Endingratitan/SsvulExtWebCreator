/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Integra;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 一次**变更检测**的结果（公共 API）：「哪些源变了 + 谁判定的 + 什么时候」。
 *
 * 一个概念、三个消费者（这是它存在的意义）：
 * ① 预览 watch：决定"要不要重建"（空集 = 不重建）；
 * ② SSE payload：告诉客户端"这次是哪些文件引起的"；
 * ③ **v5 增量重建**：`files` 就是"要重算的输入"的起点（配合 `deps-*.json` 的源→产物图）。
 *
 * 不变性：`files` 是**保序不可变快照**（LinkedHashSet 包装）——它会跨线程传给 SSE 客户端，
 * 也可能进产物/日志，所以不能是活引用；顺序保留是为了输出与测试可复现。
 *
 * @param detector 判定它的检测器：`git` / `stat` / `off`
 * @param files    相对 `sets/` 的路径（`/` 分隔）
 * @param at       检测时刻（epoch ms；0 = 未检测）
 */
public record ChangeSet(String detector, Set<String> files, long at) {

    public static final ChangeSet NONE = new ChangeSet("off", Set.of(), 0L);

    public ChangeSet {
        files = Collections.unmodifiableSet(new LinkedHashSet<>(files));
    }

    public static ChangeSet of(String detector, Set<String> files) {
        return new ChangeSet(detector, files, System.currentTimeMillis());
    }

    public boolean isEmpty() { return files.isEmpty(); }

    public int size() { return files.size(); }
}
