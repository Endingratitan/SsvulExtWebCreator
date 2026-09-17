/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.WebMinify.js;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * **Closure 桥的门禁**：classpath 里没有 Closure 时**整个类跳过**（与 `GitProbe.available()` 同款的假设跳过），
 * 所以"零依赖"环境与 CI 默认仍然全绿；把 `closure-compiler-v<日期>.jar` 放进 classpath 后这些用例自动生效。
 *
 * 钉住四条口径（都是实测得出的）：① 许可头必须活下来（Closure 只认 `@license`，我们的 MPL 头没有该标记 → 先摘后贴）；
 * ② 未知名 minifier 回落 simple；③ **WHITESPACE_ONLY 不优于我们的 simple**（实测：它没有局部改名）；
 * ④ 版本串可解析（要进 `config` 指纹，否则换 jar 后 E 会跳过、产物停在旧压缩结果）。
 */
public class ClosureBridgeTest {

    @Test
    void licenseHeaderSurvivesAndOutputIsValidJs() throws Exception {
        assumeTrue(ClosureJsMinifier.available(), "classpath 无 Closure，跳过");
        JsMinifier m = JsMinifierRegistry.get("closure");
        assertEquals("closure", m.name(), "注册表应解析到 closure");
        // 输入要足够长：许可头是**逐字保留**的"地板"，小输入下产物可能反而更大
        String src = "/*\n * MPL-2.0 header without @license tag\n */\n"
                + "function unusedHelper(alpha, beta) { var gamma = alpha * 2 + beta; return gamma - 1; }\n"
                + "function addOne(a) { var result = a + 1; return result; }\n"
                + "function twice(a) { var t = addOne(a); return addOne(t); }\n"
                + "window.ADD = addOne;\nwindow.TWICE = twice;\n";
        JsMinifier.Result r = m.minify(src);
        assertTrue(r.code().contains("MPL-2.0 header"), "许可头必须保留（先摘后贴）: " + r.code());
        assertTrue(r.code().length() < src.length(), "应当变小: " + r.code().length() + " vs " + src.length());

        File dir = new File("build/etest");
        assertTrue(dir.isDirectory() || dir.mkdirs(), "临时目录应可用");
        File f = File.createTempFile("closure", ".js", dir);
        Files.writeString(f.toPath(), r.code());
        try {
            Process p = new ProcessBuilder("node", "--check", f.getPath()).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            assertEquals(0, p.waitFor(), "node --check 应通过");
        } catch (java.io.IOException noNode) {
            // 没有 node 就只跳过语法校验
        }
        assertTrue(f.delete());
    }

    @Test
    void unknownMinifierFallsBackToSimple() {
        assertEquals("simple", JsMinifierRegistry.get("definitely-not-a-minifier").name(),
                "未知名必须回落 simple（永不返回 null）");
    }

    @Test
    void whitespaceOnlyIsNotBetterThanOurSimple() {
        assumeTrue(ClosureJsMinifier.available(), "classpath 无 Closure，跳过");
        String src = "function alpha(beta) { var gamma = beta + 1; return gamma; }\nwindow.A = alpha;\n";
        int ours = new SimpleJsMinifier().minify(src).code().length();
        int white = new ClosureJsMinifier("WHITESPACE_ONLY").minify(src).code().length();
        assertTrue(white >= ours, "实测结论：Closure WHITESPACE_ONLY 不优于我们的 simple（" + white + " vs " + ours + "）");
    }

    @Test
    void versionIsReportedForFingerprint() {
        assumeTrue(ClosureJsMinifier.available(), "classpath 无 Closure，跳过");
        String v = ClosureJsMinifier.version();
        assertFalse(v.isBlank(), "版本串不能为空（要进 config 指纹）");
        assertNotEquals("unknown", v, "应从 jar 路径解析出版本: " + v);
    }

    @Test
    void failureFallsBackToSimpleNotToNothing() {
        assumeTrue(ClosureJsMinifier.available(), "classpath 无 Closure，跳过");
        // 故意喂一段 Closure 会报 success=false 的输入 → 必须回退 simple（有产出、有 notes），而不是丢原文或抛异常
        String broken = "function ( { ] }\n";
        JsMinifier.Result r = new ClosureJsMinifier().minify(broken);
        assertNotNull(r.code(), "回退后仍要有产出");
        assertFalse(r.notes().isEmpty(), "回退必须留下 notes（会变成构建警告）: " + r.notes());
    }
}
