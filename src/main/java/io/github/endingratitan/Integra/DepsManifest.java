/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Integra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 依赖/状态记录（包内私有）：**项目根** `.ssvul/deps-<站点号>.json`。
 *
 * 定位：**构建产物（本地）**——只由构建生成，用户不必也不该手写（手改会被下次构建覆盖）；
 * 缺失、损坏、版本不识别一律当作"无记录"（全量构建，绝不阻断）。
 * **绝不放进 output/**：那个目录是要整包部署的，不能被本地元数据污染。
 *
 * 文件名里的**站点号** = `sha256(sets 绝对路径 + output 绝对路径)` 前 8 位：同一父目录下构建多个站点
 * （`--sets a --output out/a` 与 `--sets b --output out/b`）因此各存一份记录，**不会互相顶掉**
 * （曾实测：共用一份记录时交替构建会让对方退化成"慢比较 + 全量重写"）。文件内另存 `site` 块，便于人眼核对。
 *
 * 用途：① 让"跳过未变更写盘"走 **O(1) 快路径**（比对记录的内容哈希 + 磁盘 size/mtime，不读文件）；
 * ② 记录源文件的 size/mtime（v5 增量重建的输入）；③ `outputs` 的键集合 = 上次写集
 * （v4 孤儿清理的差集依据）。只记**相对路径**（`site` 块除外），换机器/移动目录都安全。
 */
final class DepsManifest {

    static final int VERSION = 2;
    /** 记录里存**哈希前缀**的十六进制位数 = 16（64 位）：省一半字节；对万文件量级的碰撞概率 ~5e-12，
     *  与"size+mtime 相等即视为未变"属同一量级的可接受假设。 */
    static final int SHA_HEX = 16;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * **页记录（E 增量跳过，v2）**：以"页身份"（源文件在 sets 下的相对路径，如 `sets:pages/about.json`）为键。
     *
     * <ul>
     *   <li>{@code deps}：本页渲染时**实际读过**的源键（`readFile` 埋点；命中缓存也记）——
     *       跳过判据就是"这些键一个都没变"；</li>
     *   <li>{@code outs}：本页产出的**产物 rel**（含它引用的 `assets/pre/*`、`assets/outer/*`）——
     *       跳过时用来"继承写集"（防孤儿误报）与"重放预设引用"（`assets/pre/X` → `refPreset(X)`）与产物校验；</li>
     *   <li>{@code search}/{@code listDirs}：页级的**全局产物标志**（search-index / list 分片由全体页面 OR 出来，
     *       跳过页不复算时要把自己那份贡献恢复出来，否则全局产物会凭空消失）。</li>
     * </ul>
     */
    static final class PageRec {
        List<String> deps = new ArrayList<>();
        List<String> outs = new ArrayList<>();
        boolean search;
        /** 本页把全站索引烧进了 HTML（`build:page-index` / `ssvul:inline`）→ 别的页变了它也要重渲染 */
        boolean usesIndex;
        List<String> listDirs = new ArrayList<>();
    }

    /** 记录用哈希：`sha256` 的 64 位前缀（**判等一律走这里**，别拿完整哈希直接比记录） */
    static String recSha(byte[] b) {
        String h = sha256(b);
        return h.length() > SHA_HEX ? h.substring(0, SHA_HEX) : h;
    }

    /** 单条记录：文本用 size+mtime+sha；二进制副本另记来源的 size/mtime（不读文件算哈希，字体等大文件代价太高） */
    static final class Rec {
        long size, mtime;
        String sha;              // null = 二进制副本
        long srcSize, srcMtime;  // 仅二进制副本使用
    }

    // 0.4.0：`readFile` 跑在**渲染线程**里 → sources 必须并发；落盘时按 key 排序，保证文件字节可复现
    final Map<String, Rec> sources = new java.util.concurrent.ConcurrentHashMap<>();   // "sets:pages/a.md" → 记录
    final Map<String, Rec> outputs = new LinkedHashMap<>();   // 相对 output 的路径 → 上次写出的记录

    /** E v2：页记录（渲染过的页每次重写；跳过的页保留旧记录） */
    final Map<String, PageRec> pages = new LinkedHashMap<>();
    /** E v2：**没有页归属**的源键（扫描相读取：`.extends`/`.contract`/data/global/Environment.config…）——
     *  它们有任何变化就**全量渲染**（这些读取发生在页面之外，无法精确归因；保守锤只此一处） */
    List<String> shared = new ArrayList<>();
    /** E v2：配置指纹（生成器版本 + `sets/Environment.config` 原文）——它影响产物但不进任何页的依赖集 */
    String config = "";

    String siteSets = "";   // 站点身份（绝对路径；仅用于核对，不参与快路径）
    String siteOutput = "";
    long gcAt;        // 上次整理 sets/.git 的时刻（0 = 从未；机器状态，不放 .env）
    long gcBytes;     // 上次整理后的 .git 体积（松散 + 打包）

    /** 站点号：8 位十六进制 → manifest 文件名（同父目录多站点各存一份） */
    static String siteId(File setsDir, File outputDir) {
        String key = setsDir.getAbsoluteFile().getPath() + "\n" + outputDir.getAbsoluteFile().getPath();
        return sha256(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0, 8);
    }

    Rec output(String rel) { return outputs.get(rel); }

    void putOutput(String rel, Rec r) { outputs.put(rel, r); }

    void putSource(String key, Rec r) { sources.put(key, r); }

    static String sha256(byte[] b) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
            StringBuilder s = new StringBuilder(64);
            for (byte x : d) s.append(String.format("%02x", x));
            return s.toString();
        } catch (Exception e) {
            return "";   // 理论上不会发生；空串只会让快路径失效（退回比较），不影响正确性
        }
    }

    /**
     * 容错读取：文件缺失/JSON 坏/版本不符 → 空记录；**站点标识不符也当空记录**（并把原因放进 warnings）。
     * 站点标识以文件名为准（每个站点一个文件），这里再核对文件内的 `site` 块，能抓住手工改名/拷贝造成的错配。
     */
    static DepsManifest load(File f, String setsPath, String outputPath, List<String> warnings) {
        DepsManifest m = new DepsManifest();
        if (f == null || !f.isFile()) return m;
        try {
            JsonNode root = MAPPER.readTree(Files.readString(f.toPath(), StandardCharsets.UTF_8));
            if (root.path("version").asInt(-1) != VERSION) return new DepsManifest();
            String sSets = root.path("site").path("sets").asText("");
            String sOut = root.path("site").path("output").asText("");
            if (!sSets.isEmpty() && (!sSets.equals(setsPath) || !sOut.equals(outputPath))) {
                warnings.add("依赖记录属于另一个站点（sets=" + sSets + "）→ 本次按无记录全量构建: " + f.getName());
                return new DepsManifest();
            }
            m.siteSets = sSets;
            m.siteOutput = sOut;
            m.config = root.path("config").asText("");
            readInto(root.path("sources"), m.sources);
            readInto(root.path("outputs"), m.outputs);
            JsonNode pg = root.path("pages");
            if (pg.isObject()) {
                pg.fields().forEachRemaining(e -> {
                    JsonNode v = e.getValue();
                    PageRec r = new PageRec();
                    if (v.path("deps").isArray()) for (JsonNode x : v.path("deps")) r.deps.add(x.asText());
                    if (v.path("outs").isArray()) for (JsonNode x : v.path("outs")) r.outs.add(x.asText());
                    r.search = v.path("search").asBoolean(false);
                    r.usesIndex = v.path("usesIndex").asBoolean(false);
                    if (v.path("listDirs").isArray()) for (JsonNode x : v.path("listDirs")) r.listDirs.add(x.asText());
                    m.pages.put(e.getKey(), r);
                });
            }
            if (root.path("shared").isArray()) for (JsonNode x : root.path("shared")) m.shared.add(x.asText());
            JsonNode git = root.path("git");
            m.gcAt = git.path("gcAt").asLong();
            m.gcBytes = git.path("gcBytes").asLong();
        } catch (Exception e) {
            return new DepsManifest();   // 坏文件当无记录
        }
        return m;
    }

    private static void readInto(JsonNode node, Map<String, Rec> dst) {
        if (node == null || !node.isObject()) return;
        node.fields().forEachRemaining(e -> {
            JsonNode v = e.getValue();
            Rec r = new Rec();
            r.size = v.path("size").asLong();
            r.mtime = v.path("mtime").asLong();
            r.sha = v.hasNonNull("sha") ? v.get("sha").asText() : null;
            if (r.sha != null && r.sha.length() > SHA_HEX) r.sha = r.sha.substring(0, SHA_HEX);   // 老记录（全哈希）兼容
            r.srcSize = v.path("srcSize").asLong();
            r.srcMtime = v.path("srcMtime").asLong();
            dst.put(e.getKey(), r);
        });
    }

    /**
     * 原子写（临时文件 + ATOMIC_MOVE）；**内容未变则不重写**；失败不阻断。
     *
     * 为什么正文里**不存构建时刻/耗时**：它们每次都变，会让"内容未变就跳过写盘"永远失效（每构建白写一次盘）；
     * "上次构建是什么时候"看文件自身的 mtime 就够，"耗时多少"看控制台那一行。
     */
    void save(File f, int files) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("version", VERSION);
            ObjectNode s = root.putObject("site");
            s.put("sets", siteSets);
            s.put("output", siteOutput);
            root.putObject("build").put("files", files);
            writeMap(root.putObject("sources"), new java.util.TreeMap<>(sources));   // 排序 → 与写入顺序无关（可复现）
            writeMap(root.putObject("outputs"), outputs);
            // E v2：页记录按 key 排序写（可复现）；shared 排一次序（它是集合语义）
            root.put("config", config);
            java.util.TreeSet<String> sh = new java.util.TreeSet<>(shared);
            var shArr = root.putArray("shared");
            for (String k : sh) shArr.add(k);
            ObjectNode pgs = root.putObject("pages");
            for (Map.Entry<String, PageRec> e : new java.util.TreeMap<>(pages).entrySet()) {
                PageRec r = e.getValue();
                ObjectNode o = pgs.putObject(e.getKey());
                var d = o.putArray("deps");
                for (String k : new java.util.TreeSet<>(r.deps)) d.add(k);
                var u = o.putArray("outs");
                for (String k : new java.util.TreeSet<>(r.outs)) u.add(k);
                if (r.search) o.put("search", true);
                if (r.usesIndex) o.put("usesIndex", true);
                if (!r.listDirs.isEmpty()) {
                    var l = o.putArray("listDirs");
                    for (String k : new java.util.TreeSet<>(r.listDirs)) l.add(k);
                }
            }
            if (gcAt > 0) {
                ObjectNode g = root.putObject("git");
                g.put("gcAt", gcAt);
                g.put("gcBytes", gcBytes);
            }
            byte[] bytes = MAPPER.writeValueAsBytes(root);      // 紧凑 JSON：不 pretty（体积/写盘都省一半以上）
            if (f.isFile() && f.length() == bytes.length
                    && java.util.Arrays.equals(Files.readAllBytes(f.toPath()), bytes)) {
                return;                                          // 与上次完全一致 → 不写
            }
            Files.createDirectories(f.getParentFile().toPath());
            Path tmp = f.toPath().resolveSibling(f.getName() + ".tmp");
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicFailed) {
                Files.move(tmp, f.toPath(), StandardCopyOption.REPLACE_EXISTING);   // 少数文件系统不支持原子移动
            }
        } catch (IOException ignored) {
            // 只影响下一次的加速，绝不影响本次构建
        }
    }

    private static void writeMap(ObjectNode node, Map<String, Rec> src) {
        for (Map.Entry<String, Rec> e : src.entrySet()) {
            Rec r = e.getValue();
            ObjectNode o = node.putObject(e.getKey());
            o.put("size", r.size);
            o.put("mtime", r.mtime);
            if (r.sha != null) o.put("sha", r.sha);
            // 来源字段只对**二进制副本**有意义；源记录上写 "srcSize":0,"srcMtime":0 是纯废字节（实测占记录 ~34B）
            else if (r.srcSize != 0 || r.srcMtime != 0) {
                o.put("srcSize", r.srcSize);
                o.put("srcMtime", r.srcMtime);
            }
        }
    }
}
