/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Preview;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * 浏览器启动器（包内私有）：`preview` 起来后自动打开页面。
 *
 * 为什么要"探测"而不是写死一条命令：三种运行环境各不相同 ——
 * **纯 Windows**（用户在 Windows + IntelliJ 里直接跑）用 `cmd.exe /c start`；
 * **WSL**（本项目的开发环境）浏览器在宿主侧，得走互操作（`cmd.exe`/`powershell.exe`/`explorer.exe`）
 * 或 `wslu` 的 `wslview`；**macOS / Linux 桌面**用 `open` / `xdg-open`。
 * 探测不到就只打印 URL（绝不因为打不开浏览器而报错）。
 *
 * 纪律：① 启动器进程**分离**（`redirectOutput/Error(DISCARD)`，不等它、不管退出码）；
 * ② 只在 CLI 层调用（库/测试不会无缘无故弹浏览器）；③ 纯逻辑 `detect(os, wsl, onPath)` 可单测。
 */
public final class Browser {

    /** 一个可用的启动器：名字（打印用）+ 命令模板（`{url}` 占位） */
    record Launch(String name, List<String> command) {
        List<String> forUrl(String url) {
            return command.stream().map(s -> s.equals("{url}") ? url : s).toList();
        }
    }

    private Browser() {}

    /** 真实环境探测 */
    static Launch detect() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return detect(os, isWsl(), Browser::onPath);
    }

    /**
     * 纯逻辑（可单测）：按环境挑第一个可用的启动器，没有则 null。
     *
     * 分层顺序：
     * - **纯 Windows**：`cmd.exe /c start "" <url>` → `rundll32 url.dll,FileProtocolHandler` → `powershell.exe`；
     * - **WSL**（浏览器在宿主侧，靠互操作）：`wslview` → `cmd.exe` → `powershell.exe` → `explorer.exe`；
     * - **macOS**：`open`；其它 *nix：`xdg-open`。
     */
    static Launch detect(String osName, boolean wsl, Predicate<String> onPath) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {                           // 纯 Windows（非 WSL）：本身就是宿主，直接 cmd start
            if (onPath.test("cmd.exe")) return new Launch("cmd.exe", List.of("cmd.exe", "/c", "start", "", "{url}"));
            if (onPath.test("rundll32.exe")) return new Launch("rundll32.exe", List.of("rundll32.exe", "url.dll,FileProtocolHandler", "{url}"));
            if (onPath.test("powershell.exe")) {
                return new Launch("powershell.exe", List.of("powershell.exe", "-NoProfile", "-Command", "Start-Process", "{url}"));
            }
            return null;
        }
        if (wsl) {
            if (onPath.test("wslview")) return new Launch("wslview", List.of("wslview", "{url}"));
            if (onPath.test("cmd.exe")) return new Launch("cmd.exe", List.of("cmd.exe", "/c", "start", "", "{url}"));
            if (onPath.test("powershell.exe")) {
                return new Launch("powershell.exe", List.of("powershell.exe", "-NoProfile", "-Command", "Start-Process", "{url}"));
            }
            if (onPath.test("explorer.exe")) return new Launch("explorer.exe", List.of("explorer.exe", "{url}"));
            return null;
        }
        if (os.contains("mac")) {
            return onPath.test("open") ? new Launch("open", List.of("open", "{url}")) : null;
        }
        return onPath.test("xdg-open") ? new Launch("xdg-open", List.of("xdg-open", "{url}")) : null;
    }

    /** 打开 URL；返回实际用的启动器名，没得用则 null（调用方打印 URL 即可，不算错误） */
    public static String open(String url) {
        Launch l = detect();
        if (l == null) return null;
        try {
            new ProcessBuilder(l.forUrl(url))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();                      // 分离：不等它、不管退出码（浏览器会一直活着）
            return l.name();
        } catch (IOException e) {
            return null;
        }
    }

    /** 是否 WSL（两路判据：环境变量 + `/proc/version` 里的 microsoft 标记） */
    static boolean isWsl() {
        if (System.getenv("WSL_DISTRO_NAME") != null || System.getenv("WSL_INTEROP") != null) return true;
        try {
            String v = java.nio.file.Files.readString(java.nio.file.Path.of("/proc/version")).toLowerCase(Locale.ROOT);
            return v.contains("microsoft") || v.contains("wsl");
        } catch (IOException e) {
            return false;
        }
    }

    /** PATH 里找可执行文件（扫一遍目录，避免依赖 `which`） */
    static boolean onPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isEmpty()) continue;
            File f = new File(dir, exe);
            if (f.isFile() && f.canExecute()) return true;
        }
        return false;
    }
}
