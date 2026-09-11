/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.SsvulExtWebCreator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** CLI 解析：默认 build、长名/单字母缩略、init 位置参数、未知命令与缺值报错 */
public class CliTest {

    @Test
    void defaultIsBuild() {
        assertEquals("build", Cli.parse(new String[0]).command());
        assertEquals("sets", Cli.parse(new String[0]).opt("sets", "sets"));
    }

    @Test
    void longAndShortNames() {
        assertEquals("init", Cli.parse(new String[]{"init"}).command());
        assertEquals("init", Cli.parse(new String[]{"-i"}).command());
        assertEquals("preview", Cli.parse(new String[]{"-p", "--port", "9000"}).command());
        assertEquals("version", Cli.parse(new String[]{"-v"}).command());
        assertEquals("build", Cli.parse(new String[]{"-b", "--sets", "x", "--output", "y", "--assets", "z"}).command());
    }

    @Test
    void buildOptions() {
        Cli c = Cli.parse(new String[]{"build", "--sets", "s2", "--output", "o2"});
        assertEquals("s2", c.opt("sets", "sets"));
        assertEquals("o2", c.opt("output", "output"));
        assertEquals("src/assets", c.opt("assets", "src/assets"));
    }

    @Test
    void initPositionalDir() {
        assertEquals("mydir", Cli.parse(new String[]{"init", "mydir"}).opt("dir", "site"));
        assertEquals("site", Cli.parse(new String[]{"init"}).opt("dir", "site"));
        assertEquals("site", Cli.parse(new String[]{"-i"}).opt("dir", "site"));
    }

    @Test
    void unknownCommandErrors() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> Cli.parse(new String[]{"nope"}));
        assertTrue(e.getMessage().contains("未知命令"));
    }

    @Test
    void missingValueErrors() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> Cli.parse(new String[]{"build", "--sets"}));
        assertTrue(e.getMessage().contains("缺参数值"));
    }

    @Test
    void bareArgOutsideInitErrors() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> Cli.parse(new String[]{"build", "x"}));
        assertTrue(e.getMessage().contains("--键 值"));
    }

    @Test
    void parseErrorsExitNonZero() {
        // R5：解析错误曾静默 exit 0（CI 里命令写错会被判成功）→ 现返回 2；正常路径 0
        assertEquals(2, Main.run(new String[]{"nope"}));
        assertEquals(2, Main.run(new String[]{"build", "--sets"}));
        assertEquals(2, Main.run(new String[]{"build", "x"}));
        assertEquals(0, Main.run(new String[]{"version"}));
    }
}
