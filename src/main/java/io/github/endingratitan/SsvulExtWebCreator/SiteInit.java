/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.SsvulExtWebCreator;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 站点骨架生成（init 子命令）：从 jar 资源 templates/site/ 写出标准站点目录。
 * 目标目录已含 Environment.config 时拒绝覆盖；占位符 {{VERSION}} 替换为构建版本。
 */
public final class SiteInit {

    private static final String[] FILES = {
            "Environment.config", ".gitignore", "README.md",
            "divs/navbar/template.html", "divs/navbar/nav.js", "divs/navbar/nav.css",
            "divs/card/template.html", "divs/card/card.js", "divs/card/card.css",
            "pages/INDEX.json",
            "data/lib/.gitkeep", "favicon/.gitkeep", "outer/.gitkeep"
    };

    private SiteInit() {}

    public static void init(String dirName) {
        File root = new File(dirName);
        if (new File(root, "Environment.config").isFile())
            throw new RuntimeException("目标目录已存在站点内容，拒绝覆盖: " + dirName);
        for (String f : FILES) {
            try (InputStream in = SiteInit.class.getResourceAsStream("/templates/site/" + f)) {
                if (in == null) throw new RuntimeException("模板资源缺失: " + f);
                File t = new File(root, f);
                Files.createDirectories(t.getParentFile().toPath());
                String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                Files.writeString(t.toPath(), content.replace("{{VERSION}}", version()));
            } catch (IOException e) {
                throw new RuntimeException("init 写盘失败: " + f + " - " + e.getMessage(), e);
            }
        }
        IO.println("site 骨架已生成: " + dirName + "（版本 " + version() + "）");
    }

    /** 构建版本：gradle 构建时由 processResources 写入 ssvul-version.txt；非 gradle 环境回退 dev */
    public static String version() {
        try (InputStream in = SiteInit.class.getResourceAsStream("/ssvul-version.txt")) {
            if (in == null) return "dev";
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "dev";
        }
    }
}
