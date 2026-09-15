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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 生成器**本机设置**（`.env`）：与 `.ssvul/` 同级（标准布局 = 项目根 = 输出目录的父级）。
 *
 * 分层（为什么在 `Settings` 而不是 `Integra`）：**产物相关 → Integra；机器相关 → 这里**。
 * `sets/Environment.config` 是**网站契约**（决定产物字节），所以它留在 Integra 的配置链里；
 * `.env` 只管"这台机器/这个人怎么跑生成器"（git 用不用、检测器、缓存上限、预览端口），
 * 一个字节都不进产物，而它的消费者横跨 Integra（构建）、Preview（端口）、CLI（开关）三家。
 *
 * 铁律（0.3.3 定稿）：
 * ① **只在文件缺失时创建一次**，此后只读、**绝不覆盖**（用户写的注释与键序都保留）；
 * ② 缺键用内置默认，**不回写**（改默认值不需要动用户的文件）；
 * ③ 未知键**忽略并警告**（教学式：列出本版可用键）；
 * ④ 目录只读/创建失败 → 警告后降级，**绝不阻断构建**；
 * ⑤ 优先级 **CLI 参数 > .env > 内置默认**。
 */
public final class DotEnv {

    public static final int VERSION = 1;
    public static final String[] KEYS = {"env-version", "git", "detector", "git-gc", "cache-limit", "preview-port",
            "threads", "watch", "poll", "inject", "sse-max", "open"};

    public static final int DEF_GIT = 0;                 // 能力位由首轮探测写入；缺省视为"不用 git"
    public static final String DEF_DETECTOR = "auto";    // auto | git | stat
    public static final int DEF_GIT_GC = 2;              // 0=关 | 1=git gc --quiet | 2=只留本次+上次两代快照（用户定）
    public static final int DEF_CACHE_LIMIT = 8;         // MiB；0 = 关内容缓存
    public static final int DEF_PREVIEW_PORT = 23143;    // >20000 的质数，避开常见端口
    public static final int DEF_THREADS = -1;            // -1=auto（核数-1，封顶 8，再按工作量收敛）｜0=串行｜N=指定
    public static final String THREADS_AUTO = "auto";
    public static final String DEF_WATCH = "auto";       // auto=有 SSE 客户端才轮询（省资源）｜1=常开｜0=关
    public static final int DEF_POLL = 700;              // 轮询间隔 ms（自适应退避只增不减，这是下限）
    public static final int DEF_INJECT = 1;              // 是否给 HTML 响应注入预览客户端
    public static final int DEF_SSE_MAX = 16;            // 同时在线的 SSE 客户端上限
    public static final int DEF_OPEN = 1;                // preview 启动后自动打开浏览器（找不到启动器就只打印 URL）

    public File file;
    public boolean exists;                 // 文件存在（含"存在但读不了"——那种情况更要禁止覆盖）
    public int envVersion = VERSION;
    public int git = DEF_GIT;
    public String detector = DEF_DETECTOR;
    public int gitGc = DEF_GIT_GC;
    public int cacheLimit = DEF_CACHE_LIMIT;
    public int previewPort = DEF_PREVIEW_PORT;
    public int threads = DEF_THREADS;       // 写盘相 I/O 并发度（-1=auto）
    public String watch = DEF_WATCH;        // auto | 1 | 0
    public int poll = DEF_POLL;             // 轮询间隔 ms
    public int inject = DEF_INJECT;         // 0/1
    public int sseMax = DEF_SSE_MAX;        // SSE 客户端上限
    public int open = DEF_OPEN;             // preview 是否自动开浏览器（0/1）

    public int cacheLimitBytes() { return cacheLimit <= 0 ? 0 : cacheLimit << 20; }

    public static File fileOf(File root) { return new File(root, ".env"); }

    /** 读取：文件缺失 → 全默认（{@code exists=false}，调用方探测能力后再创建） */
    public static DotEnv load(File root, List<String> warnings) {
        DotEnv e = new DotEnv();
        File f = fileOf(root);
        e.file = f;
        if (!f.isFile()) return e;
        e.exists = true;                       // 标记在读之前：读失败也绝不覆盖用户的文件
        List<String> lines;
        try {
            lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            warnings.add(".env 读取失败（按内置默认继续，文件保持原样）: " + ex.getMessage());
            return e;
        }
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            if (i == 0 && raw.startsWith("\uFEFF")) raw = raw.substring(1);   // 记事本 BOM
            String s = raw.trim();
            if (s.isEmpty() || s.startsWith("#") || s.startsWith(";")) continue;
            int eq = s.indexOf('=');
            if (eq <= 0) {
                warnings.add(".env 第 " + (i + 1) + " 行不是 键=值 形式（已忽略）: " + s);
                continue;
            }
            String key = s.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String val = stripInlineComment(s.substring(eq + 1).trim());
            switch (key) {
                case "env-version" -> {
                    Integer v = intOf(val);
                    if (v == null) bad(warnings, key, val, "整数");
                    else if (v != VERSION) warnings.add(".env env-version=" + v + " 不是本版识别的版本（按 " + VERSION + " 处理）");
                    else e.envVersion = v;
                }
                case "git" -> {
                    Integer v = intOf(val);
                    if (v == null || (v != 0 && v != 1)) bad(warnings, key, val, "0 或 1");
                    else e.git = v;
                }
                case "detector" -> {
                    String v = val.toLowerCase(Locale.ROOT);
                    if (!v.equals("auto") && !v.equals("git") && !v.equals("stat")) bad(warnings, key, val, "auto / git / stat");
                    else e.detector = v;
                }
                case "git-gc" -> {
                    Integer v = intOf(val);
                    if (v == null || v < 0 || v > 2) bad(warnings, key, val, "0 / 1 / 2");
                    else e.gitGc = v;
                }
                case "cache-limit" -> {
                    Integer v = intOf(val);
                    if (v == null || v < 0) bad(warnings, key, val, "非负整数（MiB；0 = 关）");
                    else e.cacheLimit = v;
                }
                case "preview-port" -> {
                    Integer v = intOf(val);
                    if (v == null || v < 0 || v > 65535) bad(warnings, key, val, "0–65535（0 = 随机端口）");
                    else e.previewPort = v;
                }
                case "threads" -> {
                    if (val.equalsIgnoreCase(THREADS_AUTO)) {
                        e.threads = DEF_THREADS;
                        break;
                    }
                    Integer v = intOf(val);
                    if (v == null || v < 0 || v > 256) bad(warnings, key, val, "auto 或 0–256（0 = 串行）");
                    else e.threads = v;
                }
                case "watch" -> {
                    String v = val.toLowerCase(Locale.ROOT);
                    if (!v.equals("auto") && !v.equals("0") && !v.equals("1")) bad(warnings, key, val, "auto / 1 / 0");
                    else e.watch = v;
                }
                case "poll" -> {
                    Integer v = intOf(val);
                    if (v == null || v < 50 || v > 60_000) bad(warnings, key, val, "50–60000（毫秒）");
                    else e.poll = v;
                }
                case "inject" -> {
                    Integer v = intOf(val);
                    if (v == null || (v != 0 && v != 1)) bad(warnings, key, val, "0 或 1");
                    else e.inject = v;
                }
                case "sse-max" -> {
                    Integer v = intOf(val);
                    if (v == null || v < 1 || v > 256) bad(warnings, key, val, "1–256");
                    else e.sseMax = v;
                }
                case "open" -> {
                    Integer v = intOf(val);
                    if (v == null || (v != 0 && v != 1)) bad(warnings, key, val, "0 或 1");
                    else e.open = v;
                }
                default -> warnings.add(".env 未知键（已忽略）: " + key + "（本版可用键: " + String.join(" / ", KEYS) + "）");
            }
        }
        return e;
    }

    /** 我们生成的 `.env` 的标记行（只有带它的文件才会被"补齐缺失键"碰） */
    private static final String MARK = "# SsvulExtWebCreator 本机设置";

    /** 首次创建（仅在文件缺失时调用；**绝不覆盖已存在的文件**）；失败只警告 */
    public static void create(File root, int gitCapability, List<String> warnings) {
        File f = fileOf(root);
        if (f.exists()) return;
        String body = MARK + "（生成器侧）\n"
                + "# 网站契约在 sets/Environment.config；本文件只管\"这台机器怎么跑生成器\"。\n"
                + "# 只在缺失时创建一次，此后**只读**：生成器不会改你写过的行；删掉本文件即恢复内置默认。\n"
                + "# 优先级：CLI 参数 > 本文件 > 内置默认。\n"
                + "# git    = 本机 git 能力位（0/1），由首轮探测写入\n"
                + "# detector = 变更检测器：auto（有仓库用 git，否则 stat+哈希）/ git / stat\n"
                + "# git-gc = sets/.git 整理：0=关 / 1=git gc --quiet / 2=只保留本次+上次两代快照（默认）\n"
                + "# cache-limit = 单次构建内容缓存上限（MiB，0=关）\n"
                + "# preview-port = 预览端口（0 = 随机）\n"
                + "# threads = 写盘相 I/O 并发度：auto（默认，min(8, 核数-1)）/ 0 = 串行 / N = 指定\n"
                + "# watch = 预览是否自动重建：auto（有浏览器连着才轮询）/ 1 = 常开 / 0 = 关\n"
                + "# poll = 轮询间隔（毫秒，下限；实际会按单次检测耗时自适应退避）\n"
                + "# inject = 是否给 HTML 响应注入预览客户端（只改响应，不动产物）\n"
                + "# sse-max = 同时在线的 SSE 客户端上限（超限 503）\n"
                + "# open = preview 启动后自动打开浏览器（0=不打开；WSL 下走 cmd.exe/wslview 等宿主通道）\n"
                + defaults(gitCapability);
        try {
            Files.createDirectories(root.toPath());
            Files.writeString(f.toPath(), body, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            warnings.add(".env 创建失败（目录只读？按内置默认继续）: " + f + " - " + ex.getMessage());
        }
    }

    /**
     * **补齐缺失键**（0.3.4 新增）：升级后老 `.env` 里没有新键时，在**文件末尾追加**它们的默认值。
     *
     * 三条边界（不动用户的东西）：① 只处理**我们自己生成的**文件（首行带 {@link #MARK}）；用户手写的 `.env` 一碰不碰；
     * ② **只追加**，已有行一个字节都不改（注释、顺序、你改过的值原样保留）；③ 键齐了**根本不写**（每次构建零开销）。
     * 写失败（目录只读等）只警告 —— 缺键本来就用默认值，不补齐不影响任何行为。
     */
    public static void backfill(File root, int gitCapability, List<String> warnings) {
        File f = fileOf(root);
        if (!f.isFile()) return;
        String text;
        try {
            text = Files.readString(f.toPath(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return;                                     // 读不了就当默认值用（调用方已按默认继续）
        }
        if (!text.contains(MARK)) return;               // 用户手写的文件：绝不触碰
        List<String> missing = new ArrayList<>();
        for (String line : defaults(gitCapability).split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq);
            if (!hasKey(text, key)) missing.add(line);
        }
        if (missing.isEmpty()) return;                  // 已经齐全 → 不写
        StringBuilder sb = new StringBuilder(text);
        if (!text.endsWith("\n")) sb.append('\n');
        // 标题行只在**首次**补齐时写：否则跨版本加键会攒出好几块"本版新增键"（0.4.0 实测的观感问题）
        String hdr = "# ↓ 本版新增键的默认值（生成器补全；可自由修改/删除，删掉即回默认）";
        if (!text.contains(hdr)) sb.append(hdr).append('\n');
        for (String m : missing) sb.append(m).append('\n');
        try {
            Files.writeString(f.toPath(), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            warnings.add(".env 补齐新键失败（按内置默认继续）: " + ex.getMessage());
        }
    }

    /** 11 个键的 `键=值` 默认行（创建与补齐共用同一份真源） */
    private static String defaults(int gitCapability) {
        return "env-version=" + VERSION + "\n"
                + "git=" + (gitCapability == 1 ? 1 : 0) + "\n"
                + "detector=" + DEF_DETECTOR + "\n"
                + "git-gc=" + DEF_GIT_GC + "\n"
                + "cache-limit=" + DEF_CACHE_LIMIT + "\n"
                + "preview-port=" + DEF_PREVIEW_PORT + "\n"
                + "threads=" + THREADS_AUTO + "\n"
                + "watch=" + DEF_WATCH + "\n"
                + "poll=" + DEF_POLL + "\n"
                + "inject=" + DEF_INJECT + "\n"
                + "sse-max=" + DEF_SSE_MAX + "\n"
                + "open=" + DEF_OPEN + "\n";
    }

    /** 文本里是否已有该键（按行、忽略大小写与首尾空白） */
    private static boolean hasKey(String text, String key) {
        for (String line : text.split("\n")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            int eq = s.indexOf('=');
            if (eq > 0 && s.substring(0, eq).trim().equalsIgnoreCase(key)) return true;
        }
        return false;
    }

    private static String stripInlineComment(String v) {
        int hash = v.indexOf(" #");
        return hash < 0 ? v : v.substring(0, hash).trim();
    }

    private static Integer intOf(String v) {
        try {
            return Integer.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void bad(List<String> warnings, String key, String val, String expect) {
        warnings.add(".env " + key + " 值不合法（须为 " + expect + "，已用默认）: " + val);
    }
}
