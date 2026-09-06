/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * 项目来源: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.JSMinifier;

import java.util.List;

/**
 * JS 压缩引擎接口（构建期压缩通道，与 Integra 的 CodeEngine 同构的"引擎缝"）。
 *
 * 内置 {@link SimpleJsMinifier}（零依赖、稳定优先）；v3 计划接入 Google Closure Compiler
 * 时实现本接口并注册进 {@link JsMinifierRegistry}（Environment.config 的 minifier 键切换）。
 *
 * 契约：输出必须是合法等价 JS；**必须保留文件首块注释**（MPL-2.0 分发须保留许可声明）；
 * 压缩失败不得抛出破坏构建的异常（内部回退原文）。
 */
public interface JsMinifier {

    /** 引擎注册名（registry 键），如 "simple"、"closure" */
    String name();

    /** 压缩结果：code=压缩产物，notes=降级说明（调用方按警告输出） */
    final class Result {
        private final String code;
        private final List<String> notes;
        public Result(String code, List<String> notes) { this.code = code; this.notes = notes; }
        public String code() { return code; }
        public List<String> notes() { return notes; }
    }

    /** 压缩（含降级说明；绝不抛异常——内部兜底返回原文） */
    Result minify(String js);
}
