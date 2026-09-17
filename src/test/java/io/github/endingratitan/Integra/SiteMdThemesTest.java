/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Integra;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link SiteMdThemes}：spec 归一化 / 类名派生（稳定性与可复现）/ 注册与护栏 / 作用域化缓存 */
class SiteMdThemesTest {

    @Test
    void equivalentSpecsNormalizeToSameValue() {
        String base = SiteMdThemes.normalize("pre-assets/md/css/md.css");
        assertEquals("pre-assets/md/css/md.css", base);
        assertEquals(base, SiteMdThemes.normalize("  pre-assets/md/css/md.css  "));
        assertEquals(base, SiteMdThemes.normalize("./pre-assets/md/css/md.css"));
        assertEquals(base, SiteMdThemes.normalize("pre-assets//md/css/md.css"));
        assertEquals(base, SiteMdThemes.normalize("pre-assets\\md\\css\\md.css"));
        assertEquals(base, SiteMdThemes.normalize("default"), "`default` 必须解析成真实 spec");
    }

    @Test
    void urlSpecsLowercaseSchemeAndHostButKeepPathCase() {
        assertEquals(SiteMdThemes.normalize("https://cdn.example.com/md/Theme.css"),
                SiteMdThemes.normalize("https://CDN.Example.com/md/Theme.css"));
        assertNotEquals(SiteMdThemes.normalize("https://cdn.example.com/md/Theme.css"),
                SiteMdThemes.normalize("https://cdn.example.com/md/theme.css"));
    }

    @Test
    void absolutePathsAndEmptyAndNoneAreRejected() {
        assertNull(SiteMdThemes.normalize("/mnt/d/GitHub/x/md.css"), "绝对 WSL 路径必须拒绝");
        assertNull(SiteMdThemes.normalize("D:/GitHub/x/md.css"), "绝对 Windows 路径必须拒绝");
        assertNull(SiteMdThemes.normalize("d:\\GitHub\\x\\md.css"), "反斜杠绝对路径必须拒绝");
        assertNull(SiteMdThemes.normalize("   "));
        assertNull(SiteMdThemes.normalize("none"));
        assertTrue(SiteMdThemes.isNone("NONE"));
        assertTrue(SiteMdThemes.isNone("0"));
    }

    @Test
    void classNameIsStableAndSpecDerived() {
        String a = SiteMdThemes.classNameOf(SiteMdThemes.normalize("pre-assets/md/css/md.css"));
        String b = SiteMdThemes.classNameOf(SiteMdThemes.normalize("  ./default  "));
        assertEquals(a, b, "等价 spec 应得到同类名（跨机器/目录可复现）");
        assertTrue(a.startsWith(SiteMdThemes.PREFIX));
        assertEquals(SiteMdThemes.PREFIX.length() + DepsManifest.SHA_HEX, a.length(), "类名应为 PREFIX + 16 hex");
        assertNotEquals(a, SiteMdThemes.classNameOf(SiteMdThemes.normalize("@data/md/print.css")));
    }

    @Test
    void registerReturnsClassAndIsIdempotent() {
        SiteMdThemes t = new SiteMdThemes();
        SiteMdThemes.Reg r1 = t.register("default");
        SiteMdThemes.Reg r2 = t.register("pre-assets/md/css/md.css");
        assertNull(r1.error());
        assertNotNull(r1.cls());
        assertEquals(r1.cls(), r2.cls(), "等价 spec 注册两次应得同类名且不报错");
        assertNull(r2.error());
        assertEquals(r1.cls(), t.classOf("./default"));
        SiteMdThemes.Reg none = t.register("none");
        assertNull(none.cls());
        assertNull(none.error(), "显式 none 不是错误");
    }

    @Test
    void rejectsAbsolutePathWithError() {
        SiteMdThemes.Reg r = new SiteMdThemes().register("/mnt/d/x/md.css");
        assertNull(r.cls());
        assertNotNull(r.error());
        assertTrue(r.error().contains("绝对路径"), r.error());
    }

    @Test
    void classCollisionIsReportedOnce() {
        SiteMdThemes t = new SiteMdThemes(n -> SiteMdThemes.PREFIX + "ffffffffffffffff");   // 强制碰撞
        assertNull(t.register("pre-assets/md/css/md.css").error());
        SiteMdThemes.Reg clash = t.register("@data/md/print.css");
        assertNotNull(clash.error(), "同 hash 不同 spec 必须报错");
        assertTrue(clash.error().contains("冲突"), clash.error());
        SiteMdThemes t2 = new SiteMdThemes(n -> SiteMdThemes.PREFIX + "ffffffffffffffff");
        t2.register("pre-assets/md/css/md.css");
        assertNotNull(t2.register("@data/md/print.css").error());
        assertNull(t2.register("@data/other.css").error(), "同一类名的冲突只报一次（并行下不刷屏）");
    }

    @Test
    void scopedIsCachedPerSpec() {
        SiteMdThemes t = new SiteMdThemes();
        String css = ".md-body h2{a:1}";
        var s1 = t.scoped("default", css);
        var s2 = t.scoped("./default", css);
        assertNotNull(s1);
        assertSame(s1, s2, "同一 spec 只变换一次");
        assertEquals(1, s1.rules());
        assertTrue(s1.balanced());
        String cls = t.register("default").cls();
        assertTrue(s1.css().contains("." + cls + " h2{a:1}"), s1.css());
        assertNull(t.scoped("none", css), "none 不应产生作用域化结果");
    }
}
