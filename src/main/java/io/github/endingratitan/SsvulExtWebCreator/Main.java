/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.SsvulExtWebCreator;

import io.github.endingratitan.Integra.SiteBuilder;

import java.io.File;

/**
 * 入口：ssvul [build|-b]（默认）/ init|-i / preview|-p（v3-④）/ version|-v。
 * 构建默认读取 sets/ 输出 output/ 使用 src/assets 预设（--sets/--output/--assets 仅供内部测试）。
 *
 * 退出码：0=正常；**2=命令/参数解析错误**（CI 里命令写错不能再被当成成功）；构建/init 抛出的异常照旧上抛 → JVM 退出码 1。
 */
public class Main {

    public static void main(String[] args) {
        int code = run(args);
        if (code != 0) System.exit(code);
    }

    /** 可测入口（不退出 JVM）：解析错误 → 2，其余 → 0 */
    static int run(String[] args) {
        Cli cli;
        try {
            cli = Cli.parse(args);
        } catch (RuntimeException e) {
            IO.println(e.getMessage());
            return 2;
        }
        switch (cli.command()) {
            case "build" -> {
                File sets = new File(cli.opt("sets", "sets"));
                File output = new File(cli.opt("output", "output"));
                File assets = new File(cli.opt("assets", "src/assets"));
                SiteBuilder.build(sets, output, assets);
                IO.println("Build Done!");
            }
            case "init" -> SiteInit.init(cli.opt("dir", "site"));
            case "preview" -> IO.println("preview 将在 v3-④ 提供（本地静态服务 + 文件监听重建）");
            case "version" -> IO.println(SiteInit.version());
        }
        return 0;
    }
}
