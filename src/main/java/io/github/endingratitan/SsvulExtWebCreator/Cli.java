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
import java.util.Set;

/**
 * 极简命令行解析（零依赖）：首参子命令（支持长名与单字母缩略），其后 --键 值 对；
 * init 允许一个位置参数（目标目录）。未知命令/参数 → 抛异常（Main 打印 usage）。
 */
public final class Cli {

    /** 允许**裸写**的布尔开关（`--rebuild` ≡ `--rebuild 1`）；其余键仍必须带值（R5：缺值必须报错） */
    private static final Set<String> BOOL_FLAGS = Set.of("rebuild", "detect", "git", "no-git", "git-init",
            "watch", "inject", "open");

    private final String command;
    private final Map<String, String> opts;

    private Cli(String command, Map<String, String> opts) { this.command = command; this.opts = opts; }

    public String command() { return command; }

    public String opt(String key, String def) { return opts.getOrDefault(key, def); }

    public boolean has(String key) { return opts.containsKey(key); }

    /** 布尔开关：缺省/`0`/`false` = 关；其余（含裸写与 `1`）= 开 */
    public boolean flag(String key) {
        String v = opts.get(key);
        return v != null && !v.equals("0") && !v.equalsIgnoreCase("false");
    }

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
                String key = a.substring(2);
                if (BOOL_FLAGS.contains(key) && (i + 1 >= args.length || args[i + 1].startsWith("--"))) {
                    opts.put(key, "1");     // 裸布尔开关
                    continue;
                }
                if (i + 1 >= args.length) throw new RuntimeException("缺参数值: " + a + usage());
                opts.put(key, args[++i]);
                continue;
            }
            if (cmd.equals("init") && !opts.containsKey("dir")) { opts.put("dir", a); continue; }   // init 位置参数
            throw new RuntimeException("参数须为 --键 值 形式: " + a + usage());
        }
        return new Cli(cmd, opts);
    }

    public static String usage() {
        return "\n用法:\n" +
                "  ssvul [build|-b] [--sets d] [--output d] [--assets d]   构建（默认；sets/output/assets 仅供内部测试）\n" +
                "                   [--rebuild] [--detect] [--git|--no-git] [--git-init 0|1] [--threads auto|0|N] [--verify 0|1]\n" +
                "  ssvul [init|-i] [dir]                                 生成站点骨架（默认 site/）\n" +
                "  ssvul [preview|-p] [--port n] [--watch auto|1|0] [--poll ms] [--inject 0|1] [--open 0|1]\n" +
                "                                 本地预览（只绑 127.0.0.1；watch 自动重建 + SSE 刷新）\n" +
                "  ssvul [version|-v]                                    打印版本\n" +
                "本机设置见项目根的 .env（首轮自动生成，此后只读）：detector / git-gc / cache-limit / preview-port / threads";
    }
}
