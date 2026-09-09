/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Integra;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * 输出写盘与替换趟（包内私有）：文本逐文件做 pre-assets/@data/@favicon/@page/bucket 替换（按深度）→
 * UTF-8 写盘；二进制直拷；预设文件与连带目录（KaTeX fonts）按需复制。
 * owner 为 SiteBuilder（共享队列/错误/桶配置）。
 */
class SiteWrite {

    private final SiteBuilder sb;

    SiteWrite(SiteBuilder sb) { this.sb = sb; }

    void writeAll() {
        for (int qi = 0; qi < sb.queue.size(); qi++) {   // 索引循环：替换趟可能追加离线复制项
            SiteBuilder.Queued q = sb.queue.get(qi);
            try {
                if (q.binary) {
                    Files.createDirectories(q.target.getParentFile().toPath());
                    Files.copy(q.source.toPath(), q.target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    continue;
                }
                String c = q.content;
                c = replacePreAssets(c, q.depth, q.target);
                c = c.replace("@data/", SiteBuilder.depthPrefix(q.depth) + "assets/data/");
                c = c.replace("@favicon/", SiteBuilder.depthPrefix(q.depth) + "favicon/");
                c = replacePageRefs(c, q.depth);
                if (!q.target.getName().endsWith(".js")) c = replaceBuckets(c, q.depth);   // JS 不做 bk/ 替换（误伤风险）
                // minify：生成物 js（含站点 CODEUI.js）；vendor lib/ 与 raw 资产不碰
                if (sb.compressOn() && q.target.getName().endsWith(".js")
                        && !q.target.getPath().replace('\\', '/').contains("/lib/")) {
                    c = sb.minifyJs(c);
                }
                Files.createDirectories(q.target.getParentFile().toPath());
                Files.writeString(q.target.toPath(), c, StandardCharsets.UTF_8);
            } catch (IOException e) {
                sb.errors.add("写盘失败: " + q.target + " - " + e.getMessage());
            }
        }
        for (String rel : sb.presetCopies) {
            File src = new File(sb.presetDir, rel);
            File dst = new File(sb.outputDir, "assets/pre/" + rel);
            try {
                Files.createDirectories(dst.getParentFile().toPath());
                // 官方预设 js（md/js 等）在输出副本压缩（保许可头）；vendor lib/ 与 css 原样
                if (sb.compressOn() && rel.endsWith(".js") && !rel.startsWith("lib/")) {
                    Files.writeString(dst.toPath(), sb.minifyJs(sb.readFile(src)), StandardCharsets.UTF_8);
                } else {
                    Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                sb.errors.add("预设复制失败: " + rel + " - " + e.getMessage());
            }
        }
        for (String rel : sb.presetDirCopies) {
            try {
                sb.copyDir(new File(sb.presetDir, rel), new File(sb.outputDir, "assets/pre/" + rel));
            } catch (IOException e) {
                sb.errors.add("预设目录复制失败: " + rel + " - " + e.getMessage());
            }
        }
        if (!sb.errors.isEmpty()) throw new RuntimeException(sb.joinErrors());
    }

    private String replacePreAssets(String c, int depth, File target) {
        String out = c;
        int idx;
        while ((idx = out.indexOf("pre-assets/")) >= 0) {
            int start = idx + "pre-assets/".length();
            int end = start;
            while (end < out.length() && isPathChar(out.charAt(end))) end++;
            String suffix = out.substring(start, end);
            if (suffix.isEmpty() || suffix.contains("..")) {
                sb.errors.add("pre-assets/ 引用非法: \"" + suffix + "\"（" + target.getName() + "）");
                break;
            }
            if (!new File(sb.presetDir, suffix).isFile()) {
                sb.errors.add("预设文件不存在: src/assets/" + suffix);
                break;
            }
            sb.presetCopies.add(suffix);
            out = out.substring(0, idx) + SiteBuilder.depthPrefix(depth) + "assets/pre/" + out.substring(start);
        }
        return out;
    }

    private String replacePageRefs(String c, int depth) {
        String out = c;
        int idx;
        while ((idx = out.indexOf("@page/")) >= 0) {
            int start = idx + "@page/".length();
            int end = start;
            while (end < out.length() && isPathChar(out.charAt(end))) end++;
            String path = out.substring(start, end);
            if (path.equals("INDEX")) {   // INDEX 特判页输出在站点根（index.html），非 pages/INDEX
                out = out.substring(0, idx) + SiteBuilder.depthPrefix(depth) + out.substring(end);
                continue;
            }
            if (path.isEmpty() || !sb.pageOutputs.contains("pages/" + path)) {
                sb.errors.add("站内互链目标不存在: @page/" + path);
                break;
            }
            out = out.substring(0, idx) + SiteBuilder.depthPrefix(depth) + "pages/" + path + "/" + out.substring(end);
        }
        return out;
    }

    /** 引用路径允许的字符（字母数字、点、下划线、斜线、连字符） */
    private static boolean isPathChar(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '.' || ch == '_' || ch == '/' || ch == '-';
    }

    private String replaceBuckets(String c, int depth) {
        String out = c;
        List<String> names = new ArrayList<>(sb.buckets.keySet());
        names.sort(Comparator.comparingInt(String::length).reversed());
        int pos = 0;   // 单趟扫描：替换后的文本不再重扫（替换结果含 n + "/" 会导致自我重复替换死循环）
        while (true) {
            int bestIdx = -1;
            String bestName = null;
            for (String n : names) {
                int i = out.indexOf(n + "/", pos);
                if (i >= 0 && (bestIdx < 0 || i < bestIdx)) { bestIdx = i; bestName = n; }
            }
            if (bestIdx < 0) break;
            String n = bestName;
            int start = bestIdx + n.length() + 1;
            int end = start;
            while (end < out.length() && isPathChar(out.charAt(end))) end++;
            String path = out.substring(start, end);
            File hit = sb.outerFiles.get(n + "/" + path);
            String ins;
            if (sb.offline) {
                // 离线模式：命中 outer 镜像 → 本地复制并替换；未命中 → 报错
                if (path.isEmpty() || hit == null) {
                    sb.errors.add("离线模式下 bucket 资源未在 outer 镜像中: " + n + "/" + path);
                    break;
                }
                sb.queue.add(new SiteBuilder.Queued(new File(sb.outputDir, "assets/outer/" + n + "/" + path), hit));
                ins = SiteBuilder.depthPrefix(depth) + "assets/outer/" + n + "/" + path;
            } else {
                // 线上模式：输出线上 URL；outer 存在时对未命中的引用发对照警告（A 功能）
                if (hit == null && sb.outerDirExists && !path.isEmpty()) {
                    sb.warn("bucket 资源未在 outer 镜像中（线上可能 404）: " + n + "/" + path);
                }
                ins = sb.buckets.get(n) + "/" + path;
            }
            out = out.substring(0, bestIdx) + ins + out.substring(end);
            pos = bestIdx + ins.length();
        }
        return out;
    }
}
