/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Preview;

/**
 * 预览客户端脚本（包内私有，**内嵌字符串而非资源文件**）。
 *
 * 为什么不放 `src/main/resources/`：本项目有一条老坑——改了资源必须手动刷新 `build/classes-res`，
 * 否则测试/运行读到的是旧副本。这 30 行脚本跟着代码走，没有副本、没有陈旧风险。
 *
 * 行为：连 `/__ssvul/events` → `building` 显示角标 → `built` 且成功则整页刷新 → `build-error` 显示
 * 覆盖层（**不刷新**，页面保持上一次成功的结果）；网络层断线由浏览器自带重连处理。
 * 事件名刻意避开 `error`（那是 `EventSource` 的传输错误事件名，会撞车）。
 */
final class ClientScript {

    static final String JS = """
            /* SsvulExtWebCreator 预览客户端（仅预览期注入到 HTTP 响应，不写进产物） */
            (function () {
              if (!window.EventSource || !document.body) return;
              var es = new EventSource('/__ssvul/events');
              var badge = null, box = null;

              function showBadge(text) {
                if (!badge) {
                  badge = document.createElement('div');
                  badge.setAttribute('data-ssvul-badge', '');
                  badge.style.cssText = 'position:fixed;right:12px;bottom:12px;z-index:2147483647;'
                    + 'font:12px/1.6 system-ui,-apple-system,sans-serif;padding:4px 10px;border-radius:999px;'
                    + 'background:rgba(17,17,17,.88);color:#fff;pointer-events:none';
                  document.body.appendChild(badge);
                }
                badge.textContent = text;
              }
              function hideBadge() { if (badge) { badge.remove(); badge = null; } }

              function parse(e) { try { return JSON.parse(e.data || '{}') || {}; } catch (_) { return {}; } }

              es.addEventListener('ready', function () { hideBadge(); });

              es.addEventListener('building', function () { showBadge('\\u91cd\\u5efa\\u4e2d\\u2026'); });

              es.addEventListener('built', function (e) {
                var d = parse(e);
                if (d.ok === false) return;                  /* 失败走 build-error，不刷新 */
                showBadge('\\u5df2\\u91cd\\u5efa\\uff0c\\u6b63\\u5728\\u5237\\u65b0\\u2026');
                location.reload();
              });

              es.addEventListener('build-error', function (e) {
                var d = parse(e);
                if (!d.errors) return;
                if (!box) {
                  box = document.createElement('div');
                  box.setAttribute('data-ssvul-error', '');
                  box.style.cssText = 'position:fixed;left:0;right:0;bottom:0;z-index:2147483647;max-height:45vh;'
                    + 'overflow:auto;background:#7f1d1d;color:#fff;font:12px/1.7 ui-monospace,Menlo,monospace;'
                    + 'padding:10px 14px;white-space:pre-wrap;box-shadow:0 -2px 12px rgba(0,0,0,.4)';
                  document.body.appendChild(box);
                }
                box.textContent = '\\u6784\\u5efa\\u5931\\u8d25\\uff08\\u9875\\u9762\\u4fdd\\u6301\\u4e0a\\u4e00\\u6b21\\u6210\\u529f\\u7684\\u7ed3\\u679c\\uff09\\n'
                  + (d.errors || []).join('\\n');
                hideBadge();
              });

              /* 连接断开（服务重启/网络）：浏览器会自动重连，这里只提示，不清错误层 */
              es.addEventListener('error', function (e) {
                if (e && e.data) return;                     /* 有 data = 我们自己的事件名冲突，忽略 */
                showBadge('\\u9884\\u89c8\\u8fde\\u63a5\\u4e2d\\u65ad\\uff0c\\u6b63\\u5728\\u91cd\\u8fde\\u2026');
              });
            })();
            """;

    private ClientScript() {}
}
