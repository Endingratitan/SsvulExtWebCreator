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
import io.github.endingratitan.Preview.Browser;
import io.github.endingratitan.Preview.PollingWatcher;
import io.github.endingratitan.Preview.PreviewOptions;
import io.github.endingratitan.Preview.PreviewServer;
import io.github.endingratitan.Preview.ReloadNotifier;
import io.github.endingratitan.Preview.SourceWatcher;
import io.github.endingratitan.Preview.WatchOptions;
import io.github.endingratitan.Settings.DotEnv;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 入口：ssvul [build|-b]（默认）/ init|-i / preview|-p / version|-v。
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
                String detector = cli.opt("detector", null);
                if (cli.flag("git")) detector = "git";          // --git 强制用 git（无仓库则回退 stat 并警告）
                if (cli.flag("no-git")) detector = "stat";      // --no-git 强制 stat+哈希
                // 标准布局（sets/）才自动建基线；--sets 指向别处 = 内部/测试用法，绝不往里塞仓库
                Boolean gitInit = cli.has("git-init") ? cli.flag("git-init") : sets.getName().equals("sets");
                Integer threads = null;
                if (cli.has("threads")) {
                    String tv = cli.opt("threads", "");
                    if (tv.equalsIgnoreCase("auto")) threads = -1;
                    else {
                        try {
                            threads = Integer.parseInt(tv);
                        } catch (NumberFormatException e) {
                            IO.println("--threads 值须为 auto 或 0–256: " + tv);
                            return 2;
                        }
                        if (threads < 0 || threads > 256) {
                            IO.println("--threads 值须为 auto 或 0–256: " + tv);
                            return 2;
                        }
                    }
                }
                SiteBuilder.build(sets, output, assets, new SiteBuilder.BuildOptions(
                        cli.flag("rebuild"), cli.flag("detect"), detector, gitInit, threads));
                IO.println("Build Done!");
            }
            case "init" -> SiteInit.init(cli.opt("dir", "site"));
            case "preview" -> {
                File sets = new File(cli.opt("sets", "sets"));
                File output = new File(cli.opt("output", "output"));
                File assets = new File(cli.opt("assets", "src/assets"));
                File root = output.getAbsoluteFile().getParentFile();
                List<String> pw = new ArrayList<>();
                DotEnv env = DotEnv.load(root == null ? output : root, pw);   // ② CLI 没写的部分查 .env
                for (String w : pw) IO.println("[预览警告] " + w);
                int port = env.previewPort;                                     // ① .env 的 preview-port
                if (cli.has("port")) {                                          // ③ --port 覆盖（0 = 内核分配）
                    try {
                        port = Integer.parseInt(cli.opt("port", ""));
                    } catch (NumberFormatException e) {
                        IO.println("--port 值须为整数: " + cli.opt("port", ""));
                        return 2;
                    }
                    if (port < 0 || port > 65535) {
                        IO.println("--port 值须在 0–65535: " + port);
                        return 2;
                    }
                }
                String watchMode = env.watch;
                if (cli.has("watch")) {
                    watchMode = cli.opt("watch", "");
                    if (!watchMode.equals("0") && !watchMode.equals("1") && !watchMode.equalsIgnoreCase("auto")) {
                        IO.println("--watch 值须为 auto|1|0: " + watchMode);
                        return 2;
                    }
                    watchMode = watchMode.toLowerCase(java.util.Locale.ROOT);
                }
                int poll = env.poll;
                if (cli.has("poll")) {
                    try {
                        poll = Integer.parseInt(cli.opt("poll", ""));
                    } catch (NumberFormatException e) {
                        IO.println("--poll 值须为整数（毫秒）: " + cli.opt("poll", ""));
                        return 2;
                    }
                    if (poll < 50 || poll > 60_000) {
                        IO.println("--poll 值须在 50–60000 毫秒: " + poll);
                        return 2;
                    }
                }
                int sseMax = env.sseMax;
                if (cli.has("sse-max")) {
                    try {
                        sseMax = Integer.parseInt(cli.opt("sse-max", ""));
                    } catch (NumberFormatException e) {
                        IO.println("--sse-max 值须为整数: " + cli.opt("sse-max", ""));
                        return 2;
                    }
                    if (sseMax < 1 || sseMax > 256) {
                        IO.println("--sse-max 值须在 1–256: " + sseMax);
                        return 2;
                    }
                }
                boolean inject = cli.has("inject") ? cli.flag("inject") : env.inject == 1;
                boolean openBrowser = cli.has("open") ? cli.flag("open") : env.open == 1;   // 默认开（.env 可关）
                boolean detect = cli.flag("detect");
                WatchOptions watch = new WatchOptions(watchMode, poll);
                SiteBuilder.BuildOptions bopts = new SiteBuilder.BuildOptions(
                        cli.flag("rebuild"), detect, null, null);
                PreviewOptions po = new PreviewOptions(sets, output, assets, port,
                        PreviewOptions.DEF_THREADS, PreviewOptions.DEF_QUEUE,
                        detect, null, cli.flag("rebuild"), inject, sseMax, watch);
                SourceWatcher watcher = watch.enabled()
                        ? new PollingWatcher(sets, output, assets, env.detector, poll, 250, 2000, IO::println)
                        : SourceWatcher.noop();
                try (PreviewServer s = PreviewServer.start(po, watcher, ReloadNotifier.noop())) {
                    Runtime.getRuntime().addShutdownHook(new Thread(s::close));   // Ctrl+C → 优雅停机
                    if (openBrowser) {
                        String url = "http://127.0.0.1:" + s.port() + "/";
                        String used = Browser.open(url);
                        IO.println(used == null
                                ? "[预览] 没找到可用的浏览器启动器（WSL 下需要 cmd.exe/wslview；可用 --open 0 关掉本行为），请手动打开 " + url
                                : "[预览] 已用 " + used + " 打开 " + url);
                    }
                    s.awaitTermination();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IOException e) {
                    IO.println("预览启动失败: " + e.getMessage());
                    return 1;
                }
            }
            case "version" -> IO.println(SiteInit.version());
        }
        return 0;
    }
}
