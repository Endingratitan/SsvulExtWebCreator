/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Integra;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 引擎链（包内私有）：按序问询成员 accepts(lang)，命中即渲染。
 *
 * 回退语义：全员拒接 → renderCode 返回 null（MdBlocks 纯文本兜底 + 警告，构建永不失败）。
 * 资源语义：clientAssetsNeeded 记录本页是否有块落到"带客户端资源的成员"（如 hljs）——
 * 无代码块或无语言块时不注入任何引擎资源（性能优先：省流量、省客户端渲染）。
 */
final class EngineChain implements CodeEngine {

    private final List<CodeEngine> members;
    private boolean clientAssetsNeeded;

    EngineChain(List<CodeEngine> members) { this.members = List.copyOf(members); }

    /** 本页是否真有块需要客户端资源（hljs 等）；决定 autoAssets 是否注入 */
    boolean clientAssetsNeeded() { return clientAssetsNeeded; }

    @Override public String name() { return "chain"; }

    @Override
    public boolean accepts(String language) {
        for (CodeEngine e : members) if (e.accepts(language)) return true;
        return false;
    }

    /** 链式渲染：null = 全员拒接（调用方按纯文本兜底并警告） */
    @Override
    public String renderCode(String code, String language) {
        for (CodeEngine e : members) {
            if (e.accepts(language)) {
                if (!e.autoAssets().isEmpty() && !language.isEmpty()) clientAssetsNeeded = true;
                return e.renderCode(code, language);
            }
        }
        return null;
    }

    /** 链成员资源合并去重（成员内部顺序 × 链顺序） */
    @Override
    public List<String> autoAssets() {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (CodeEngine e : members)
            for (String a : e.autoAssets())
                if (seen.add(a)) out.add(a);
        return out;
    }
}
