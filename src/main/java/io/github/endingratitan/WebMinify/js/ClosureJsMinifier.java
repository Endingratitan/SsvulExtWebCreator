/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.WebMinify.js;

import java.util.ArrayList;
import java.util.List;

/**
 * **Google Closure Compiler 桥**（可选、反射、零编译期依赖）。
 *
 * 定位：`JsMinifier` 契约的第三方实现（`name()="closure"`）。**不打包** Closure —— 用反射调用，
 * 类不在 classpath 时 {@link #available()} 返回 false，注册表就不注册它，`minifier=closure` 会自动回退 simple。
 * 这样"零依赖自研可控"的铁律（tech.md 铁律 3）不被破坏，愿意用的人自己把
 * `closure-compiler-v<日期>.jar` 放进 classpath（或 fat jar 同目录的 `lib/`）。
 *
 * 参数口径（都是刻意选的，别改）：
 * <ul>
 *   <li><b>只 SIMPLE_OPTIMIZATIONS（或 WHITESPACE_ONLY）</b>：ADVANCED 会重命名属性、打断 div/global 的
 *       **字符串契约**（`SsvulDiv.register('x', …)`、`window.SsvulList`），要用得配 externs，属 v4+。</li>
 *   <li><b>`language_in = language_out = ECMASCRIPT_NEXT`</b>：不降级转译 —— 默认可能降到 ES5，
 *       那会把 `const`/箭头/模板串改写，直接违反"可证等价"铁律。</li>
 *   <li><b>许可头先摘后贴</b>：Closure 只保留 `@license`/`@preserve` 注释，而本项目的 MPL 头没有该标记，
 *       直接交给它会丢许可头（MPL-2.0 合规问题）。</li>
 *   <li><b>任何异常/`success=false` → 回退 simple</b>（铁律 5：失败不阻断），并把原因记进 notes（会变成构建警告）。</li>
 * </ul>
 */
public final class ClosureJsMinifier implements JsMinifier {

    /** 默认档（A/B 之后如果 WHITESPACE_ONLY 更划算再改这里） */
    public static final String DEFAULT_LEVEL = "SIMPLE_OPTIMIZATIONS";

    private final String levelName;

    public ClosureJsMinifier() { this(DEFAULT_LEVEL); }

    /** @param levelName Closure 的 `CompilationLevel` 枚举名（`WHITESPACE_ONLY` / `SIMPLE_OPTIMIZATIONS`） */
    public ClosureJsMinifier(String levelName) { this.levelName = levelName; }

    @Override public String name() { return "closure"; }

    /** 本实例用的档位（探针/A-B 用；注册表只按 `name()` 取） */
    public String level() { return levelName; }

    /** Closure 在不在 classpath（不在就别注册；调用方据此给教学提示） */
    public static boolean available() {
        try {
            Class.forName("com.google.javascript.jscomp.Compiler");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 版本串 —— **必须进 config 指纹**：换了 jar 但配置没变时，E 不能让产物停在旧压缩结果。
     *  优先取 jar 路径里的 `vYYYYMMDD`；取不到就用 jar 文件名（仍能区分不同 jar）。 */
    public static String version() {
        try {
            Class<?> c = Class.forName("com.google.javascript.jscomp.Compiler");
            java.security.CodeSource cs = c.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                String path = cs.getLocation().getPath();
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("v\\d{6,8}").matcher(path);
                if (m.find()) return m.group();
                int i = path.lastIndexOf('/');
                if (i >= 0 && i + 1 < path.length()) return path.substring(i + 1);
            }
        } catch (Throwable ignored) {
            // 落到下面
        }
        return "unknown";
    }

    @Override public Result minify(String js) {
        List<String> notes = new ArrayList<>();
        if (js == null || js.isEmpty()) return new Result(js == null ? "" : js, notes);
        String header = extractHeader(js);
        try {
            String out = compile(js);
            if (out == null || out.isEmpty()) {
                notes.add("Closure（" + levelName + "）未产出结果 → 本次回退 simple");
                return new SimpleJsMinifier().minify(js);
            }
            return new Result(header.isEmpty() ? out : header + out, notes);
        } catch (Throwable t) {
            notes.add("Closure（" + levelName + "）不可用/压缩失败 → 本次回退 simple：" + t);
            JsMinifier.Result r = new SimpleJsMinifier().minify(js);
            notes.addAll(r.notes());
            return new Result(r.code(), notes);
        }
    }

    /**
     * 反射调用 Closure（`Compiler` + `CompilerOptions` + `SourceFile` + `Result`）。
     * 用反射的代价是"API 跨版本可能变"——所以任何 `NoSuchMethod` 都走回退路径，只留一条警告。
     */
    private String compile(String js) throws Exception {
        Class<?> cCompiler = Class.forName("com.google.javascript.jscomp.Compiler");
        Class<?> cOpts = Class.forName("com.google.javascript.jscomp.CompilerOptions");
        Class<?> cLevel = Class.forName("com.google.javascript.jscomp.CompilationLevel");
        Class<?> cLang = Class.forName("com.google.javascript.jscomp.CompilerOptions$LanguageMode");
        Class<?> cSource = Class.forName("com.google.javascript.jscomp.SourceFile");
        Class<?> cResult = Class.forName("com.google.javascript.jscomp.Result");

        Object compiler = cCompiler.getDeclaredConstructor().newInstance();
        // 静音 Closure 自己的 java.util.logging 输出（否则每个含 Annex B `<!-- -->` 头的文件都会往 stderr 刷一条
        // JSC_PARSE_ERROR 警告）；我们只认 `Result.success`，其余信息走 notes。
        try {
            cCompiler.getMethod("setLoggingLevel", java.util.logging.Level.class)
                    .invoke(compiler, java.util.logging.Level.OFF);
        } catch (Throwable ignored) {
            // 老/新版本没有这个方法就算了（只影响日志噪音）
        }
        Object opts = cOpts.getDeclaredConstructor().newInstance();

        @SuppressWarnings({"unchecked", "rawtypes"})
        Object level = Enum.valueOf((Class<? extends Enum>) cLevel.asSubclass(Enum.class), levelName);
        cLevel.getMethod("setOptionsForCompilationLevel", cOpts).invoke(level, opts);

        @SuppressWarnings({"unchecked", "rawtypes"})
        Object next = Enum.valueOf((Class<? extends Enum>) cLang.asSubclass(Enum.class), "ECMASCRIPT_NEXT");
        cOpts.getMethod("setLanguageIn", cLang).invoke(opts, next);
        cOpts.getMethod("setLanguageOut", cLang).invoke(opts, next);

        Object src = cSource.getMethod("fromCode", String.class, String.class).invoke(null, "input.js", js);
        Object externs = cSource.getMethod("fromCode", String.class, String.class).invoke(null, "externs.js", "");

        Object result;
        try {
            result = cCompiler.getMethod("compile", cSource, cSource, cOpts).invoke(compiler, externs, src, opts);
        } catch (NoSuchMethodException e) {
            // 老/新版本可能是 List 形态：compile(List<SourceFile>, List<SourceFile>, CompilerOptions)
            Class<?> cList = List.class;
            result = cCompiler.getMethod("compile", cList, cList, cOpts)
                    .invoke(compiler, List.of(externs), List.of(src), opts);
        }
        if (!(Boolean) cResult.getField("success").get(result)) {
            throw new IllegalStateException("Closure success=false");
        }
        return (String) cCompiler.getMethod("toSource").invoke(compiler);
    }

    /**
     * 摘出"首个真实 token 之前的连续注释/空白"（= 许可头区）。含行首 `<!-- … -->`（Annex B）与 BOM。
     * 与 `SimpleJsMinifier` 的 headerZone 同口径，但这里是独立小实现（那边是私有词法状态）。
     */
    static String extractHeader(String js) {
        int i = 0, n = js.length();
        if (n > 0 && js.charAt(0) == '\uFEFF') i = 1;
        int end = i;
        while (i < n) {
            char c = js.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '/' && i + 1 < n && js.charAt(i + 1) == '/') {              // 行注释
                int nl = js.indexOf('\n', i);
                i = nl < 0 ? n : nl + 1;
                end = i;
                continue;
            }
            if (c == '/' && i + 1 < n && js.charAt(i + 1) == '*') {              // 块注释
                int close = js.indexOf("*/", i + 2);
                i = close < 0 ? n : close + 2;
                end = i;
                continue;
            }
            if (c == '<' && js.startsWith("<!--", i)) {                         // Annex B 行首 HTML 注释
                int nl = js.indexOf('\n', i);
                i = nl < 0 ? n : nl + 1;
                end = i;
                continue;
            }
            break;                                                              // 首个真实 token → 头区结束
        }
        return js.substring(0, end);
    }
}
