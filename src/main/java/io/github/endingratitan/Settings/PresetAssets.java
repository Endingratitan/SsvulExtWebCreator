/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Settings;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * **内置预设资产的落地**（本机能力层）：把生成器自带的 `assets/**`（fat jar 里的资源，或开发期的 classes 目录）
 * 解包到 `<项目根>/.ssvul/assets-<版本>/`，让"仓库外 `java -jar` 也能直接构建"。
 *
 * <p>为什么必须解包成**真目录**：`presetDir` 全程按真目录用（`new File(presetDir, suffix)`、`Files.copy`、
 * css 同目录 `fonts/` 连带复制、`sourceKey` 的绝对路径前缀匹配）——解包一次（几十 ms）换来**管线零改动**。
 *
 * <p>目录名带生成器版本 ⇒ 升级后自动用新资产（旧目录留着不碍事，可手删）；带 `.complete` 标记 ⇒ 幂等，
 * 且中断过的解包不会被当成完整可用。用户仍可用 `--assets <目录>` 覆盖（开发期或"多站点共享一份解包目录"）。
 *
 * <p>层级：`Settings` 是**机器相关**层（`.env`/git/本机缓存），生成器资源落到本机正属于此；`Integra` 只读它
 * （`presetDir` 仍是一个普通目录），因此不会反向依赖。
 */
public final class PresetAssets {

    /** 资源里的根前缀（`processResources` 把 `src/assets` 放到 jar 的 `assets/` 下） */
    private static final String PREFIX = "assets/";

    private PresetAssets() {}

    /**
     * 幂等解包（已存在且带 `.complete` 标记 → 直接复用）。
     *
     * @param projectRoot 项目根（= `output/` 的父级；`.env`/`.ssvul/` 都在这里）
     * @param version     生成器版本（空 → `dev`）
     * @return 预设目录；**找不到内置资产或解包失败 → null**（调用方降级：提示用 `--assets` 指定）
     */
    public static File ensure(File projectRoot, String version, List<String> warnings) {
        String v = version == null || version.isBlank() ? "dev" : version.trim();
        File root = projectRoot == null ? new File(".") : projectRoot;
        File dst = new File(new File(root, ".ssvul"), "assets-" + v);
        if (new File(dst, ".complete").isFile()) return dst;
        try {
            File loc = location();
            if (loc == null) {
                warn(warnings, "找不到内置预设资产（classpath 无来源）→ 请用 --assets 指定预设目录");
                return null;
            }
            deleteRec(dst);                                   // 中断过的半成品不要复用
            if (loc.isFile()) extractJar(loc, dst);
            else extractDir(new File(loc, "assets"), dst);
            if (!dst.isDirectory()) {
                warn(warnings, "解包后目录不存在: " + dst + " → 请用 --assets 指定预设目录");
                return null;
            }
            Files.writeString(new File(dst, ".complete").toPath(), v + "\n");
            return dst;
        } catch (Exception e) {
            warn(warnings, "解包内置预设资产失败: " + e.getMessage() + " → 请用 --assets 指定预设目录");
            return null;
        }
    }

    /** 内置版本串（资源 `/ssvul-version.txt`；手工编译的 classes 目录里通常是 `dev`） */
    public static String version() {
        try (InputStream in = PresetAssets.class.getResourceAsStream("/ssvul-version.txt")) {
            return in == null ? "dev" : new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "dev";
        }
    }

    /** **目录分支**（开发期的 classes 目录；也是 JUnit 能直接测的那条路） */
    static void extractDir(File srcAssets, File dst) throws IOException {
        if (!srcAssets.isDirectory()) throw new IOException("资源目录不存在: " + srcAssets);
        Path src = srcAssets.toPath();
        try (java.util.stream.Stream<Path> st = Files.walk(src)) {
            for (Path p : st.toList()) {
                Path to = dst.toPath().resolve(src.relativize(p));
                if (Files.isDirectory(p)) Files.createDirectories(to);
                else {
                    Files.createDirectories(to.getParent());
                    Files.copy(p, to, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** **jar 分支**：只取 `assets/` 前缀的条目，落盘时剥掉该前缀（于是 `dst` 本身就是 presetDir） */
    static void extractJar(File jar, File dst) throws IOException {
        try (JarFile jf = new JarFile(jar)) {
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                String n = e.getName();
                if (!n.startsWith(PREFIX) || n.endsWith("/")) continue;
                File out = new File(dst, n.substring(PREFIX.length()));
                Files.createDirectories(out.getParentFile().toPath());
                try (InputStream in = jf.getInputStream(e)) {
                    Files.copy(in, out.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** 本类所在位置的来源（fat jar → jar 文件；开发期 → classes 目录） */
    private static File location() {
        try {
            java.security.CodeSource cs = PresetAssets.class.getProtectionDomain().getCodeSource();
            return cs == null || cs.getLocation() == null ? null : new File(cs.getLocation().toURI());
        } catch (Exception e) {
            return null;
        }
    }

    private static void deleteRec(File f) {
        if (f == null || !f.exists()) return;
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) deleteRec(c);
        // 删不掉也无所谓：后面的解包是覆盖写
        f.delete();
    }

    private static void warn(List<String> w, String m) {
        if (w != null) w.add(m);
    }
}
