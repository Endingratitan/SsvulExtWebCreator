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
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** init 骨架：生成结构完整、{{VERSION}} 替换、已存在站点拒绝覆盖 */
public class SiteInitTest {

    @TempDir
    Path tmp;

    @Test
    void initGeneratesSkeleton() {
        File site = tmp.resolve("site").toFile();
        SiteInit.init(site.getPath());
        assertTrue(new File(site, "Environment.config").isFile());
        assertTrue(new File(site, ".gitignore").isFile());
        assertTrue(new File(site, "pages/INDEX.json").isFile());
        assertTrue(new File(site, "divs/navbar/template.html").isFile());
        assertTrue(new File(site, "divs/card/card.css").isFile());
        assertTrue(new File(site, "outer/.gitkeep").isFile());
        // 骨架不被 .gitignore 误伤（站点内容必须可提交）
        assertFalse(read(site, ".gitignore").contains("pages/"));
        // {{VERSION}} 已替换
        String json = read(site, "pages/INDEX.json");
        assertFalse(json.contains("{{VERSION}}"), json);
    }

    @Test
    void initRefusesOverwrite() {
        File site = tmp.resolve("site").toFile();
        SiteInit.init(site.getPath());
        RuntimeException e = assertThrows(RuntimeException.class, () -> SiteInit.init(site.getPath()));
        assertTrue(e.getMessage().contains("拒绝覆盖"));
    }

    @Test
    void initSiteIsBuildable() {
        File site = tmp.resolve("site").toFile();
        SiteInit.init(site.getPath());
        // 用内部 --sets 等价路径构建骨架站（默认 assets 用 src/assets）
        io.github.endingratitan.Integra.SiteBuilder.build(site, new File(site.getParentFile(), "out"), new File("src/assets"));
        assertTrue(new File(site.getParentFile(), "out/index.html").isFile());
    }

    private static String read(File root, String rel) {
        try {
            return Files.readString(new File(root, rel).toPath());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
