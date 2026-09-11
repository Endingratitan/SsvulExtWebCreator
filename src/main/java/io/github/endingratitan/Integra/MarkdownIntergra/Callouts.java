/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Integra.MarkdownIntergra;

import java.util.*;

/**
 * callout 类型表（包内私有，**无状态**）：内置 16 类型的默认中文标签 + 图标名。
 *
 * 非内置类型不是错误——那是扩展逃生舱（sets/global/callout/CALLOUT[.&lt;lang&gt;].css 提供视觉）：
 * 标题回落为"类型名首字母大写"（release → Release），配色回落 CSS 里的中性兜底值。
 * 无状态是刻意的：静态注册表会跨构建残留上一次的词表（EngineRegistry 早期踩过的坑）。
 */
final class Callouts {

    /** 默认标签（中文真实文本）+ 图标名（对应 CSS 变量 --md-callout-icon-&lt;name&gt;） */
    record Def(String label, String icon) {}

    private static final Map<String, Def> BUILTIN = new LinkedHashMap<>();

    static {
        BUILTIN.put("note",      new Def("注意", "info"));
        BUILTIN.put("tip",       new Def("提示", "bulb"));
        BUILTIN.put("important", new Def("重要", "star"));
        BUILTIN.put("warning",   new Def("警告", "warning"));
        BUILTIN.put("caution",   new Def("小心", "caution"));
        BUILTIN.put("info",      new Def("信息", "info"));
        BUILTIN.put("success",   new Def("成功", "check"));
        BUILTIN.put("question",  new Def("疑问", "question"));
        BUILTIN.put("example",   new Def("示例", "example"));
        BUILTIN.put("quote",     new Def("引用", "quote"));
        BUILTIN.put("abstract",  new Def("摘要", "list"));
        BUILTIN.put("todo",      new Def("待办", "check"));
        BUILTIN.put("danger",    new Def("危险", "caution"));
        BUILTIN.put("failure",   new Def("失败", "cross"));
        BUILTIN.put("bug",       new Def("缺陷", "bug"));
        BUILTIN.put("debug",     new Def("调试", "code"));
    }

    private Callouts() {}

    /** 类型名是否合法（kebab-case；调用方已转小写） */
    static boolean kebab(String type) {
        return type != null && type.matches("[a-z0-9]+(-[a-z0-9]+)*");
    }

    static boolean known(String type) { return BUILTIN.containsKey(type); }

    /** 标题：内置取中文标签；扩展类型回落"类型名首字母大写"（release-note → Release Note） */
    static String label(String type) {
        Def d = BUILTIN.get(type);
        return d != null ? d.label() : humanize(type);
    }

    static String humanize(String type) {
        StringBuilder sb = new StringBuilder();
        for (String w : type.split("-")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    /** 内置类型名（文档/测试用，顺序=表序） */
    static Set<String> names() { return Collections.unmodifiableSet(BUILTIN.keySet()); }
}
