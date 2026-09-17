/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Integra;

import io.github.endingratitan.WebMinify.css.CssScoper;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * **md 主题注册表**（包内协作件）：`spec → 类名`、作用域化缓存、碰撞护栏。
 *
 * 关系：与 `SiteIncremental` 同族（`Site*` = 横跨管线的协作件）。**本类不含站点模型** ——
 * 依赖登记、产物入队、给 div 挂类由 `SiteBuilder`/`SitePages`/`SiteTags` 在接线处完成
 * （这样注册表可以脱离站点独立测试；见 `SiteMdThemesTest`）。
 *
 * 设计要点（tech.md「md 主题」节）：
 * <ul>
 *   <li>类名 = `md-theme-<hash16>`，hash 输入是**归一化后的 spec 字符串**（路径 hash，非内容 hash）——
 *       改主题内容**不改变类名与文件名** ⇒ 引用页 HTML 字节不变，改一行主题只重写 1 个 css 文件；
 *       绝不用绝对路径派生（否则同站在两台机器/两个目录构建出的产物字节不同，破坏可复现）</li>
 *   <li>同一份主题只变换一次（`scoped` 缓存，键 = 归一化 spec）</li>
 *   <li>碰撞护栏：同 hash 不同 spec ⇒ 返回一条**去重后**的错误（并行下每线程各报一条会刷屏）</li>
 * </ul>
 *
 * 并发纪律（会被并行的页面渲染线程调用）：
 * ① 本类只做纯计算 + `ConcurrentHashMap`，**不做 IO**（主题文件的 stat/读由调用方每 spec 只做一次）；
 * ② 缓存与"按页归属"分离（归属在调用方按页登记，不进本类缓存）；
 * ③ 入队产物由调用方用 `AtomicBoolean`（或等价物）保证每主题一次，且不在 `computeIfAbsent` 里做。
 */
final class SiteMdThemes {

    static final String PREFIX = "md-theme-";

    /** 别名 → 真实 spec（`default` 必须先解析，否则换默认主题时类名不变、语义漂移） */
    private static final Map<String, String> ALIAS = Map.of("default", "pre-assets/md/css/md.css");

    private final Function<String, String> hasher;
    private final Map<String, String> classBySpec = new ConcurrentHashMap<>();   // 归一化 spec → 类名
    private final Map<String, String> specByClass = new ConcurrentHashMap<>();   // 类名 → 归一化 spec（护栏）
    private final Set<String> reported = ConcurrentHashMap.newKeySet();          // 已报过的冲突类名（去重）
    private final Map<String, CssScoper.Scoped> scopedBySpec = new ConcurrentHashMap<>();

    SiteMdThemes() { this(SiteMdThemes::classNameOf); }

    /** 测试接缝：注入可控哈希，用来验证碰撞护栏（生产用上面的无参构造） */
    SiteMdThemes(Function<String, String> hasher) { this.hasher = hasher; }

    /** 注册结果：`cls` 非空 = 成功；`error` 非空 = 要加进 `sb.errors`（同一冲突只会返回一次） */
    record Reg(String cls, String error) { }

    /** 站点/页面配置里的显式"不要主题" */
    static boolean isNone(String spec) {
        if (spec == null) return false;
        String s = spec.trim();
        return s.equalsIgnoreCase("none") || s.equals("0");
    }

    /**
     * 归一化 spec：trim、反斜杠转正斜杠、折叠重复斜杠、去 `./`、`http(s)` 只小写 scheme+host、去尾斜杠、
     * `default` 先解析成真实 spec。**返回 null = 非法**（空、绝对路径、`none`）。
     * 绝对路径必须拒绝：它跨机器/跨目录不可复现，是"路径 hash"最容易踩的可靠性陷阱。
     */
    static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim().replace('\\', '/');
        if (s.isEmpty() || isNone(s)) return null;
        String lower = s.toLowerCase();
        if (lower.startsWith("/") || lower.matches("^[a-z]:/.*")) return null;      // 绝对路径：拒
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            int i = s.indexOf("//") + 2;
            int j = s.indexOf('/', i);
            String head = (j < 0 ? s : s.substring(0, j)).toLowerCase();
            String tail = j < 0 ? "" : s.substring(j);
            return head + collapse(tail);
        }
        while (s.startsWith("./")) s = s.substring(2);                 // 先去 `./` 再解析别名（否则 "./default" 解析不到）
        String alias = ALIAS.get(s);
        if (alias != null) {
            s = alias;
            while (s.startsWith("./")) s = s.substring(2);
        }
        return collapse(s);
    }

    private static String collapse(String p) {
        String out = p.replaceAll("/{2,}", "/");
        if (out.length() > 1 && out.endsWith("/")) out = out.substring(0, out.length() - 1);
        return out;
    }

    /** 归一化 spec → 类名（复用 `DepsManifest.recSha` 的 16 hex 约定） */
    static String classNameOf(String normalized) {
        return PREFIX + DepsManifest.recSha(normalized.getBytes(StandardCharsets.UTF_8));
    }

    /** 注册一个主题 spec，返回类名（失败时 `cls == null`，`error` 可能非空） */
    Reg register(String rawSpec) {
        String n = normalize(rawSpec);
        if (n == null) {
            if (isNone(rawSpec)) return new Reg(null, null);                       // 显式 none：不是错误
            return new Reg(null, "md 主题 spec 非法（不得为绝对路径或空）: " + rawSpec);
        }
        String cls = hasher.apply(n);
        classBySpec.putIfAbsent(n, cls);
        String other = specByClass.putIfAbsent(cls, n);
        if (other != null && !other.equals(n)) {                                   // 同 hash 不同 spec：护栏
            return reported.add(cls)
                    ? new Reg(null, "md 主题类名冲突（同 hash 不同 spec）: " + other + " 与 " + n)
                    : new Reg(null, null);
        }
        return new Reg(cls, null);
    }

    /** 已注册过的类名（未注册返回 null） */
    String classOf(String rawSpec) {
        String n = normalize(rawSpec);
        return n == null ? null : classBySpec.get(n);
    }

    /**
     * 作用域化（缓存：同一 spec 只变换一次）。`cssText` 由调用方读入（读盘与依赖登记在调用方，每 spec 一次）。
     * 返回 null = spec 非法。
     */
    CssScoper.Scoped scoped(String rawSpec, String cssText) {
        String n = normalize(rawSpec);
        if (n == null) return null;
        return scopedBySpec.computeIfAbsent(n, k -> CssScoper.scope(cssText, hasher.apply(k)));
    }
}
