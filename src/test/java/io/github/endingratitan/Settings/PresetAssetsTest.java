/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 内置预设资产解包的门禁（`PresetAssets`）。
 *
 * 打包拼图的最后一块：`presetDir` 全程按**真目录**用，所以 jar 用户必须先把 jar 内的 `assets/**` 解包出来。
 * 这里测**目录分支**（也是 `ensure()` 在 classes/资源目录下走的那条）与幂等语义；
 * **jar 分支**在真实 fat jar 上手工端到端验过（见 `tech.md`「打包与分发」：无 `--assets` 跑通、产物与仓库内运行 `diff -rq` 0 行）。
 */
public class PresetAssetsTest {

    @TempDir
    Path tmp;

    private void write(File f, String s) throws Exception {
        Files.createDirectories(f.getParentFile().toPath());
        Files.writeString(f.toPath(), s);
    }

    @Test
    void extractDirCopiesWholeTree() throws Exception {
        File src = tmp.resolve("srcAssets").toFile();
        write(new File(src, "page/BASE.html"), "<html>{{content}}</html>");
        write(new File(src, "divs/t/t.css"), ".t{}");
        File dst = tmp.resolve("out/assets-test").toFile();
        PresetAssets.extractDir(src, dst);
        assertTrue(new File(dst, "page/BASE.html").isFile(), "文件应被复制");
        assertTrue(new File(dst, "divs/t/t.css").isFile(), "子目录应被保留");
        assertEquals("<html>{{content}}</html>", Files.readString(new File(dst, "page/BASE.html").toPath()));
    }

    @Test
    void extractDirRejectsMissingSource() {
        assertThrows(java.io.IOException.class,
                () -> PresetAssets.extractDir(new File(tmp.toFile(), "nope"), new File(tmp.toFile(), "dst")),
                "源目录不存在应当抛错（调用方据此降级并提示 --assets）");
    }

    /** `ensure` 必须"要么给出目录、要么给出警告"，绝不抛异常（失败不阻断铁律） */
    @Test
    void ensureNeverThrowsAndAlwaysExplains() throws Exception {
        File root = tmp.resolve("proj").toFile();
        assertTrue(root.mkdirs());
        List<String> warns = new ArrayList<>();
        File dir = PresetAssets.ensure(root, "test-version", warns);
        // 开发期 classpath 里可能没有 assets/（→ null + 警告）；gradle 的 build/resources/main 里有（→ 目录）
        assertTrue(dir != null || !warns.isEmpty(), "要么给目录、要么给警告");
        if (dir != null) {
            assertTrue(dir.isDirectory(), "返回的应当是目录");
            assertTrue(new File(dir, ".complete").isFile(), "应有 .complete 标记（幂等依据）");
        }
    }

    @Test
    void versionIsNeverBlank() {
        String v = PresetAssets.version();
        assertNotNull(v);
        assertFalse(v.isBlank(), "版本串不能为空（要拼进解包目录名）");
    }
}
