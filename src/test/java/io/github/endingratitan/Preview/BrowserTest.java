/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Preview;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 浏览器启动器探测的纯逻辑门禁（**不真的启动浏览器**：`open()` 是集成路径，测试只验"选哪条命令"）。
 */
public class BrowserTest {

    private static java.util.function.Predicate<String> path(String... exes) {
        Set<String> s = Set.of(exes);
        return s::contains;
    }

    @Test
    void wslPrefersWslviewThenWindowsInterop() {
        Browser.Launch l = Browser.detect("linux", true, path("wslview", "cmd.exe", "powershell.exe", "explorer.exe"));
        assertEquals("wslview", l.name());
        assertEquals(List.of("wslview", "http://x/"), l.forUrl("http://x/"));

        l = Browser.detect("linux", true, path("cmd.exe", "powershell.exe"));
        assertEquals("cmd.exe", l.name(), "没有 wslview 时用互操作");
        assertEquals(List.of("cmd.exe", "/c", "start", "", "http://x/"), l.forUrl("http://x/"),
                "start 后面必须跟一个空标题参数，否则 URL 会被当成窗口标题");

        l = Browser.detect("linux", true, path("powershell.exe", "explorer.exe"));
        assertEquals("powershell.exe", l.name());
        l = Browser.detect("linux", true, path("explorer.exe"));
        assertEquals("explorer.exe", l.name());
    }

    @Test
    void linuxAndMacFallbacks() {
        Browser.Launch l = Browser.detect("linux", false, path("xdg-open"));
        assertEquals("xdg-open", l.name());
        assertEquals(List.of("xdg-open", "http://x/"), l.forUrl("http://x/"));

        l = Browser.detect("Mac OS X", false, path("open"));
        assertEquals("open", l.name());

        assertNull(Browser.detect("linux", true, path()), "WSL 里一个通道都没有 → null（只打印 URL）");
        assertNull(Browser.detect("linux", false, path()), "普通 Linux 没有 xdg-open → null");
        assertNull(Browser.detect("Mac OS X", false, path("xdg-open")), "macOS 不认 xdg-open");
    }

    @Test
    void pureWindowsUsesCmdStart() {
        Browser.Launch l = Browser.detect("Windows 11", false, path("cmd.exe", "rundll32.exe", "powershell.exe"));
        assertEquals("cmd.exe", l.name());
        assertEquals(List.of("cmd.exe", "/c", "start", "", "http://127.0.0.1:23143/"),
                l.forUrl("http://127.0.0.1:23143/"), "start 后必须跟空标题参数");

        l = Browser.detect("Windows 10", false, path("rundll32.exe", "powershell.exe"));
        assertEquals("rundll32.exe", l.name());
        assertEquals(List.of("rundll32.exe", "url.dll,FileProtocolHandler", "http://x/"), l.forUrl("http://x/"));

        l = Browser.detect("Windows 10", false, path("powershell.exe"));
        assertEquals("powershell.exe", l.name());
        assertNull(Browser.detect("Windows 10", false, path()), "纯 Windows 三个通道都没有 → null");
    }

    @Test
    void wslAndWindowsOrderingDiffer() {
        // WSL 优先 wslview（wslu 专为此设计）；纯 Windows 没有 wslview 一说，直接 cmd.exe
        assertEquals("wslview", Browser.detect("Linux", true, path("wslview", "cmd.exe")).name());
        assertEquals("cmd.exe", Browser.detect("Windows 11", false, path("cmd.exe")).name());
    }

    @Test
    void wslDetectionAndPathScanWork() {
        // 只验"能跑通、不抛异常"：真实环境的结论随机器变，不适合写死断言
        assertDoesNotThrow(Browser::isWsl);
        assertTrue(Browser.onPath("sh") || Browser.onPath("bash"), "PATH 扫描应能找到 sh/bash 之一");
        assertFalse(Browser.onPath("definitely-not-a-real-binary-xyz"));
    }
}
