/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * 项目来源: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Integra;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 引擎注册表（包内私有，v3 再开放用户注册 SPI）。
 *
 * 内置：hljs（客户端链路，默认）、simple（构建期词法验证器）。
 * resolve 把页面 engine 配置（有序名表）解析成 {@link EngineChain}：
 * 未知名 → 警告并剔除；全部剔除 → 回退默认 hljs。
 */
final class EngineRegistry {

    private static final Map<String, CodeEngine> ENGINES = new LinkedHashMap<>();

    static {
        ENGINES.put("hljs", new PassThroughCodeEngine());
        ENGINES.put("simple", new LexerCodeEngine());
    }

    private EngineRegistry() {}

    /** 注册/覆盖引擎（构建期 U1 词表合并后重注册 simple 也走这里） */
    static void register(String name, CodeEngine engine) {
        ENGINES.put(name, java.util.Objects.requireNonNull(engine));
    }

    /** 解析页面 engine 配置 → 引擎链（永不返回 null；空表回退 hljs） */
    static EngineChain resolve(List<String> names, Consumer<String> warn) {
        List<CodeEngine> list = new ArrayList<>();
        for (String n : names) {
            CodeEngine e = ENGINES.get(n);
            if (e == null) {
                warn.accept("未知代码引擎名（已忽略）: " + n + "；可用: " + ENGINES.keySet());
                continue;
            }
            list.add(e);
        }
        if (list.isEmpty()) list.add(ENGINES.get("hljs"));
        return new EngineChain(list);
    }
}
