/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Preview;

import io.github.endingratitan.Integra.BuildReport;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.function.Supplier;

/**
 * 产物静态服务（包内私有）：`GET/HEAD` + 目录回落 `index.html` + MIME + **绝不缓存**。
 *
 * 安全与正确性要点：
 * ① **读写锁**：请求持读锁、构建持写锁 → 不会读到"写了一半"的产物（torn read）；
 * ② **路径穿越防护**：先 URL 解码再逐段判定，遇 `..`、反斜杠、盘符冒号一律 403（不做目录列表）；
 * ③ 只支持 GET/HEAD（其余 405）；产物缺失 → 404；**最近一次构建失败 → 500 + 错误页**（首建失败也要能看见原因）；
 * ④ `Cache-Control: no-store`：预览期间浏览器不许缓存，否则"改了看不到"。
 */
final class StaticHandler implements HttpHandler {

    private static final Map<String, String> MIME = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"), Map.entry("htm", "text/html; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"), Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"), Map.entry("json", "application/json; charset=utf-8"),
            Map.entry("map", "application/json; charset=utf-8"), Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("md", "text/plain; charset=utf-8"), Map.entry("xml", "application/xml; charset=utf-8"),
            Map.entry("svg", "image/svg+xml"), Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"), Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"),
            Map.entry("ico", "image/x-icon"), Map.entry("pdf", "application/pdf"),
            Map.entry("woff", "font/woff"), Map.entry("woff2", "font/woff2"), Map.entry("ttf", "font/ttf"),
            Map.entry("otf", "font/otf"), Map.entry("wasm", "application/wasm"),
            Map.entry("mp4", "video/mp4"), Map.entry("webm", "video/webm"));

    private final File root;
    private final ReadWriteLock lock;
    private final Supplier<BuildReport> lastBuild;
    private final boolean inject;

    StaticHandler(File root, ReadWriteLock lock, Supplier<BuildReport> lastBuild, boolean inject) {
        this.root = root;
        this.lock = lock;
        this.lastBuild = lastBuild;
        this.inject = inject;
    }

    /** Host 校验：只放行 `127.0.0.1`/`localhost`/回环 IPv6（防 DNS rebinding：恶意页面把域名解析到本机再读预览） */
    static boolean hostAllowed(HttpExchange ex) {
        String host = ex.getRequestHeaders().getFirst("Host");
        if (host == null || host.isEmpty()) return true;          // HTTP/1.0 无 Host：放行（本机工具常见）
        String h = host;
        int colon = h.lastIndexOf(':');
        if (colon > 0 && h.indexOf(']') < 0) h = h.substring(0, colon);   // 去端口（IPv6 字面量除外）
        h = h.toLowerCase(Locale.ROOT);
        return h.equals("127.0.0.1") || h.equals("localhost") || h.equals("[::1]") || h.equals("::1");
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (!hostAllowed(ex)) {
            send(ex, 403, "text/plain; charset=utf-8",
                    "403 Host 不合法（预览只服务 127.0.0.1/localhost）".getBytes(StandardCharsets.UTF_8), false);
            return;
        }
        String method = ex.getRequestMethod();
        boolean head = method.equals("HEAD");
        if (!method.equals("GET") && !head) {
            send(ex, 405, "text/plain; charset=utf-8", "405 只支持 GET/HEAD".getBytes(StandardCharsets.UTF_8), head);
            return;
        }
        lock.readLock().lock();                 // 构建（写锁）期间不读产物
        try {
            String path = decode(ex.getRequestURI().getRawPath());
            File target = resolve(path);
            if (target == null) {
                send(ex, 403, "text/plain; charset=utf-8",
                        "403 路径不合法（预览只服务产物目录内的文件）".getBytes(StandardCharsets.UTF_8), head);
                return;
            }
            if (target.isDirectory()) target = new File(target, "index.html");
            if (!target.isFile()) {
                notFound(ex, path, head);
                return;
            }
            byte[] body = Files.readAllBytes(target.toPath());
            if (inject && isHtml(target.getName())) body = injectClient(body);
            send(ex, 200, mimeOf(target.getName()), body, head);
        } finally {
            lock.readLock().unlock();
        }
    }

    private static boolean isHtml(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".html") || n.endsWith(".htm");
    }

    /**
     * 给 HTML 响应注入预览客户端脚本（**只改 HTTP 响应，磁盘产物零字节变化** → 双参照 diff 必须仍 0 行）。
     * 两个保守点：① 已经注入过就不重复；② 不是合法 UTF-8（解码出现替换字符）就**放弃注入**，绝不冒险改写字节。
     */
    private static byte[] injectClient(byte[] body) {
        String tag = "<script src=\"/__ssvul/client.js\" defer></script>";
        String html = new String(body, StandardCharsets.UTF_8);
        if (html.indexOf('\uFFFD') >= 0 || html.contains(tag)) return body;
        int idx = lastIndexOfIgnoreCase(html, "</body>");
        String out = idx < 0 ? html + tag : html.substring(0, idx) + tag + html.substring(idx);
        return out.getBytes(StandardCharsets.UTF_8);
    }

    private static int lastIndexOfIgnoreCase(String s, String needle) {
        return s.toLowerCase(Locale.ROOT).lastIndexOf(needle);
    }

    /** URL 解码（容错：坏编码按原样处理）；再逐段判定，拒绝任何越界路径 */
    private static String decode(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) return "/";
        try {
            return URLDecoder.decode(rawPath, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return rawPath;
        }
    }

    private File resolve(String path) {
        if (path.indexOf('\0') >= 0) return null;
        String rel = path;
        while (rel.startsWith("/")) rel = rel.substring(1);
        for (String seg : rel.split("/")) {
            if (seg.equals("..") || seg.indexOf('\\') >= 0 || seg.indexOf(':') >= 0) return null;
        }
        return rel.isEmpty() ? root : new File(root, rel);
    }

    private void notFound(HttpExchange ex, String path, boolean head) throws IOException {
        BuildReport r = lastBuild.get();
        if (r != null && !r.ok()) {
            String html = page("构建失败", "<p>最近一次构建失败，产物可能是旧的或不完整：</p><pre>"
                    + esc(String.join("\n", r.errors())) + "</pre>");
            send(ex, 500, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8), head);
            return;
        }
        String html = page("404", "<p>产物里没有这个路径：<code>" + esc(path) + "</code></p>");
        send(ex, 404, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8), head);
    }

    private static String page(String title, String body) {
        return "<!doctype html><meta charset=\"utf-8\"><title>" + esc(title) + " · ssvul preview</title>"
                + "<style>body{font:14px/1.6 system-ui,sans-serif;margin:3rem auto;max-width:44rem;padding:0 1rem}"
                + "pre{background:#f5f5f5;padding:.8rem;overflow:auto;white-space:pre-wrap}</style>"
                + "<h1>" + esc(title) + "</h1>" + body
                + "<hr><p style=\"color:#888\">SsvulExtWebCreator 预览（本机静态服务；产物目录外的路径一律不服务）</p>";
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String mimeOf(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return MIME.getOrDefault(ext, "application/octet-stream");
    }

    static void send(HttpExchange ex, int code, String type, byte[] body, boolean head) throws IOException {
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", type);
        h.set("Cache-Control", "no-store");             // 预览绝不缓存
        h.set("X-Content-Type-Options", "nosniff");
        if (head) {
            ex.sendResponseHeaders(code, -1);           // HEAD：只回头，不发体
            ex.close();
            return;
        }
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    /** 供测试与排错：允许的扩展名清单（断言 MIME 覆盖用） */
    static List<String> knownExtensions() {
        return List.copyOf(MIME.keySet());
    }
}
