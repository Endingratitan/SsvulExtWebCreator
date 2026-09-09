/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.SsvulExtWebCreator;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 极简命令行解析（零依赖）：首参子命令（支持长名与单字母缩略），其后 --键 值 对；
 * init 允许一个位置参数（目标目录）。未知命令/参数 → 抛异常（Main 打印 usage）。
 */
public final class Cli {

    private final String command;
    private final Map<String, String> opts;

    private Cli(String command, Map<String, String> opts) { this.command = command; this.opts = opts; }

    public String command() { return command; }

    public String opt(String key, String def) { return opts.getOrDefault(key, def); }

    public static Cli parse(String[] args) {
        if (args == null || args.length == 0) return new Cli("build", Map.of());   // 默认 build（兼容无参运行）
        String cmd = switch (args[0]) {
            case "build", "-b" -> "build";
            case "init", "-i" -> "init";
            case "preview", "-p" -> "preview";
            case "version", "-v" -> "version";
            default -> throw new RuntimeException("未知命令: " + args[0] + usage());
        };
        Map<String, String> opts = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--")) {
                if (i + 1 >= args.length) throw new RuntimeException("缺参数值: " + a + usage());
                opts.put(a.substring(2), args[++i]);
                continue;
            }
            if (cmd.equals("init") && !opts.containsKey("dir")) { opts.put("dir", a); continue; }   // init 位置参数
            throw new RuntimeException("参数须为 --键 值 形式: " + a + usage());
        }
        return new Cli(cmd, opts);
    }

    public static String usage() {
        return "\n用法:\n" +
                "  ssvul [build|-b] [--sets d] [--output d] [--assets d]   构建（默认；参数仅供内部测试）\n" +
                "  ssvul [init|-i] [dir]                                 生成站点骨架（默认 site/）\n" +
                "  ssvul [preview|-p] [--port n]                         本地预览（v3-④ 提供）\n" +
                "  ssvul [version|-v]                                    打印版本";
    }
}
