/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Preview;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * `/__ssvul/` **保留命名空间**（包内私有）：预览自用的控制端点，只服务两件事 ——
 * `GET /__ssvul/events`（SSE）与 `GET /__ssvul/client.js`（注入用脚本）；**其余一律 403**。
 *
 * 为什么要独立命名空间：产物目录是作者的，`__ssvul/` 是开发期设施——显式占名可以给出清晰报错，
 * 也避免将来和作者的文件路径打架（`HttpServer` 按最长前缀路由，注册 `/__ssvul/` 必然优先于 `/`）。
 * 同时做 **Host 校验**（见 {@link StaticHandler#hostAllowed}）：本地预览必须防 DNS rebinding。
 */
final class ControlHandler implements HttpHandler {

    static final String PREFIX = "/__ssvul/";

    private final SseHub hub;

    ControlHandler(SseHub hub) {
        this.hub = hub;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (!StaticHandler.hostAllowed(ex)) {
            StaticHandler.send(ex, 403, "text/plain; charset=utf-8",
                    "403 Host 不合法（预览只服务 127.0.0.1/localhost）".getBytes(StandardCharsets.UTF_8), false);
            return;
        }
        String path = ex.getRequestURI().getPath();
        boolean get = "GET".equals(ex.getRequestMethod()) || "HEAD".equals(ex.getRequestMethod());
        if (!get) {
            StaticHandler.send(ex, 405, "text/plain; charset=utf-8",
                    "405 只支持 GET/HEAD".getBytes(StandardCharsets.UTF_8), false);
            return;
        }
        if (path.equals(SseHub.CLIENT_PATH)) {
            String lastId = ex.getRequestHeaders().getFirst("Last-Event-ID");
            if (!hub.add(ex, lastId)) {
                ex.getResponseHeaders().set("Retry-After", "2");
                StaticHandler.send(ex, 503, "text/plain; charset=utf-8",
                        ("503 SSE 连接数已达上限（" + hub.maxClients() + "）；稍后自动重试").getBytes(StandardCharsets.UTF_8),
                        "HEAD".equals(ex.getRequestMethod()));
            }
            return;
        }
        if (path.equals(PREFIX + "client.js")) {
            StaticHandler.send(ex, 200, "text/javascript; charset=utf-8",
                    ClientScript.JS.getBytes(StandardCharsets.UTF_8), "HEAD".equals(ex.getRequestMethod()));
            return;
        }
        StaticHandler.send(ex, 403, "text/plain; charset=utf-8",
                ("403 /__ssvul/ 是预览保留路径，只有 " + SseHub.CLIENT_PATH + " 与 " + PREFIX + "client.js")
                        .getBytes(StandardCharsets.UTF_8), false);
    }
}
