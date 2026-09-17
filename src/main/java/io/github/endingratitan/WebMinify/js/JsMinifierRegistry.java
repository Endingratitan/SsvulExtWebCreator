/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.WebMinify.js;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 压缩引擎注册表（包内风格与 Integra.EngineRegistry 同构）。
 *
 * 内置 simple（自编稳定实现）；v3 接入 Closure 时 register("closure", ...)，
 * Environment.config 的 minifier=closure 即可切换，管线零改动。
 */
public final class JsMinifierRegistry {

    private static final Map<String, JsMinifier> ENGINES = new LinkedHashMap<>();

    static {
        register(new SimpleJsMinifier());
        // Closure 是**可选**的（反射桥，零编译期依赖）：类不在 classpath 就不注册 → get("closure") 自动回落 simple。
        // 这样"零依赖自研可控"不被破坏，愿意用的人把 closure-compiler-v<日期>.jar 放进 classpath 即可。
        try {
            if (ClosureJsMinifier.available()) register(new ClosureJsMinifier());
        } catch (Throwable ignored) {
            // 桥自身出错也只当"没有这个引擎"
        }
    }

    private JsMinifierRegistry() {}

    public static void register(JsMinifier engine) {
        ENGINES.put(engine.name(), java.util.Objects.requireNonNull(engine));
    }

    /** 按名取引擎；未知名回退 simple（永不返回 null） */
    public static JsMinifier get(String name) {
        JsMinifier e = ENGINES.get(name == null ? "" : name);
        return e != null ? e : ENGINES.get("simple");
    }

    /** 可用引擎名（警告提示用） */
    public static String names() { return ENGINES.keySet().toString(); }
}
