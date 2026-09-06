/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * 项目来源: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Integra;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class AssetsConfigReaderTest {

    @TempDir
    Path tmp;

    private File write(String name, String content) throws Exception {
        File f = tmp.resolve(name).toFile();
        Files.createDirectories(f.getParentFile().toPath());
        Files.writeString(f.toPath(), content);
        return f;
    }

    @Test
    void configParsing() throws Exception {
        File f = write("Environment.config",
                "cname=example.com\nbucket=(bk,https://bucket.example.com)\nbucket=(img,https://img.example.com)\nreadme=1\n");
        AssetsConfigReader acr = new AssetsConfigReader(f);
        assertTrue(acr.Read());
        assertEquals("example.com", acr.getConfig().get("cname").get(0));
        assertEquals(2, acr.getConfig().get("bucket").size());
        assertEquals("https://bucket.example.com", acr.getBuckets().get("bk"));
        assertEquals("https://img.example.com", acr.getBuckets().get("img"));
    }

    @Test
    void configStrictness() throws Exception {
        AssetsConfigReader unknown = new AssetsConfigReader(write("a.config", "unknown=1\n"));
        assertThrows(RuntimeException.class, unknown::Read);                  // 未知键

        AssetsConfigReader proto = new AssetsConfigReader(write("b.config", "cname=https://x.com\n"));
        assertThrows(RuntimeException.class, proto::Read);                    // cname 带协议

        AssetsConfigReader noProto = new AssetsConfigReader(write("c.config", "bucket=bk,nourl\n"));
        assertThrows(RuntimeException.class, noProto::Read);                  // bucket 缺协议

        AssetsConfigReader dup = new AssetsConfigReader(write("d.config",
                "bucket=(bk,https://a.com)\nbucket=(bk,https://b.com)\n"));
        assertThrows(RuntimeException.class, dup::Read);                      // 调用名重复
    }

    @Test
    void jsonValidation() throws Exception {
        AssetsConfigReader unknownKey = new AssetsConfigReader(write("page.json",
                "{\"name\":\"x\",\"unknown\":1,\"page\":{\"div-1\":{\"type\":\"t\"}}}"));
        assertThrows(RuntimeException.class, unknownKey::Read);               // 未知键

        AssetsConfigReader dupKey = new AssetsConfigReader(write("dup.json",
                "{\"page\":{\"div-1\":{\"type\":\"a\"},\"div-1\":{\"type\":\"b\"}}}"));
        assertThrows(RuntimeException.class, dupKey::Read);                   // 重复键（解析层）

        AssetsConfigReader reservedParam = new AssetsConfigReader(write("resv.json",
                "{\"page\":{\"div-1\":{\"type\":\"t\",\"params\":{\"content\":\"x\"}}}}"));
        assertThrows(RuntimeException.class, reservedParam::Read);            // params 保留字

        AssetsConfigReader rawAndMd = new AssetsConfigReader(write("both.json",
                "{\"page\":{\"div-1\":{\"type\":\"t\",\"raw\":\"<b>x</b>\",\"markdown\":\"# y\"}}}"));
        assertThrows(RuntimeException.class, rawAndMd::Read);                 // raw/markdown 互斥

        AssetsConfigReader valid = new AssetsConfigReader(write("ok.json",
                "{\"name\":\"about\",\"page\":{\"div-1\":{\"type\":\"navbar\",\"params\":{\"brand\":\"S\"}}}}"));
        assertTrue(valid.Read());
    }
}
