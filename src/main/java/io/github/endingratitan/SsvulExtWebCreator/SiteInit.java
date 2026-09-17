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

    /**
     * 资源名与目标文件名的差异：`.gitignore` 命中 Gradle/Ant 的默认排除（打不进 jar），
     * 所以仓库里那份模板存成 `gitignore.txt`，写盘时再改回 `.gitignore`。
     */
    private static final java.util.Map<String, String> RESOURCE_ALIAS =
            java.util.Map.of(".gitignore", "gitignore.txt");

    /** `gitignore.txt` 也缺失时的兜底内容（与 `src/main/resources/templates/site/gitignore.txt` 一致） */
    private static final String DEFAULT_GITIGNORE = "/output/\n/build/\n/.gradle/\n.idea/\n*.iml\n.DS_Store\n";

    private SiteInit() {}

    public static void init(String dirName) {
        File root = new File(dirName);
        if (new File(root, "Environment.config").isFile())
            throw new RuntimeException("目标目录已存在站点内容，拒绝覆盖: " + dirName);
        for (String f : FILES) {
            String content;
            String res = RESOURCE_ALIAS.getOrDefault(f, f);
            try (InputStream in = SiteInit.class.getResourceAsStream("/templates/site/" + res)) {
                if (in == null) {
                    // 点文件被打包工具漏掉时不至于让"jar 里的 init 直接不可用"
                    if (!f.equals(".gitignore")) throw new RuntimeException("模板资源缺失: " + res);
                    content = DEFAULT_GITIGNORE;
                } else {
                    content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                File t = new File(root, f);
                Files.createDirectories(t.getParentFile().toPath());
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
