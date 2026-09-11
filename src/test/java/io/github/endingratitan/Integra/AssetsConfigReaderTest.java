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
                "cname=example.com\nbucket=[bk,https://bucket.example.com]\n"
                + "bucket=[img,https://img.example.com/,https://s3.example.com]\nreadme=1\n");
        AssetsConfigReader acr = new AssetsConfigReader(f);
        assertTrue(acr.Read());
        assertEquals("example.com", acr.getConfig().get("cname").get(0));
        assertEquals(2, acr.getConfig().get("bucket").size());                  // 多个 bucket 仍然支持
        assertEquals("https://bucket.example.com", acr.getBuckets().get("bk"));
        assertEquals("https://img.example.com", acr.getBuckets().get("img"));   // href 末尾斜线被规范化
        assertEquals("", acr.getBucketEndpoint("bk"));                          // 未声明第 3 段
        assertEquals("https://s3.example.com", acr.getBucketEndpoint("img"));   // EndPoint = 第 3 段
        assertEquals(3, acr.getBucketFields().get("img").size());
    }

    @Test
    void configStrictness() throws Exception {
        AssetsConfigReader unknown = new AssetsConfigReader(write("a.config", "unknown=1\n"));
        assertThrows(RuntimeException.class, unknown::Read);                  // 未知键

        AssetsConfigReader proto = new AssetsConfigReader(write("b.config", "cname=https://x.com\n"));
        assertThrows(RuntimeException.class, proto::Read);                    // cname 带协议

        // 0.3.1：bucket 改为 [调用名,href,EndPoint,...]；旧语法明确报错（见 history.md）
        AssetsConfigReader parenSyntax = new AssetsConfigReader(write("c.config", "bucket=(bk,https://a.com)\n"));
        assertThrows(RuntimeException.class, parenSyntax::Read);              // 旧圆括号写法
        AssetsConfigReader bareSyntax = new AssetsConfigReader(write("e.config", "bucket=bk,https://a.com\n"));
        assertThrows(RuntimeException.class, bareSyntax::Read);               // 缺方括号
        AssetsConfigReader noProto = new AssetsConfigReader(write("f.config", "bucket=[bk,nourl]\n"));
        assertThrows(RuntimeException.class, noProto::Read);                  // href 缺协议
        AssetsConfigReader oneField = new AssetsConfigReader(write("g.config", "bucket=[bk]\n"));
        assertThrows(RuntimeException.class, oneField::Read);                 // 段数不足

        AssetsConfigReader dup = new AssetsConfigReader(write("d.config",
                "bucket=[bk,https://a.com]\nbucket=[bk,https://b.com]\n"));
        assertThrows(RuntimeException.class, dup::Read);                      // 调用名重复
    }

    @Test
    void bucketAttrsAndSessionTtl() throws Exception {
        File f = write("Environment.config",
                "cname=example.com\n"
                + "bucket=[a,https://cdn.example.com/,endpoint=https://s3.example.com/bk,prefix=site/blog,ref=main]\n"
                + "bucket=[b,https://b.example.com,https://s3.example.com/legacy]\n"
                + "session-ttl=600\n");
        AssetsConfigReader acr = new AssetsConfigReader(f);
        assertTrue(acr.Read());
        assertEquals("https://cdn.example.com", acr.getBuckets().get("a"));            // href 末尾斜线仍规范化
        assertEquals("https://s3.example.com/bk", acr.getBucketEndpoint("a"));
        assertEquals("site/blog", acr.getBucketPrefix("a"));
        assertEquals("main", acr.getBucketRef("a"));
        assertEquals("https://s3.example.com/legacy", acr.getBucketEndpoint("b"));    // 第 3 段裸值 = 旧约定 endpoint
        assertEquals("", acr.getBucketPrefix("b"));                                   // 未声明 prefix
        assertEquals("", acr.getBucketRef("b"));
        assertEquals("600", acr.getConfig().get("session-ttl").get(0));               // 站点级新键在白名单内
    }

    @Test
    void bucketAttrStrictness() throws Exception {
        String[] bad = {
                "bucket=[a,https://a.com,kind=s3]\n",                                       // 未知属性（kind 明确不做）
                "bucket=[a,https://a.com,endpoint=https://x.com,endpoint=https://y.com]\n",  // 属性重复
                "bucket=[a,https://a.com,endpoint=s3.example.com]\n",                        // endpoint 缺协议
                "bucket=[a,https://a.com,endpoint=https://x.com?list-type=2]\n",             // endpoint 带 query
                "bucket=[a,https://a.com,prefix=site/../etc]\n",                             // prefix 含 ..
                "bucket=[a,https://a.com,prefix=/site//blog]\n",                             // prefix 空段/前导斜线
                "bucket=[a,https://a.com,endpoint=]\n",                                      // 属性值为空
                "bucket=[a,https://a.com,endpoint=https://x.com,https://y.com]\n",           // 第 4 段位置裸值
                "bucket=[a,https://a.com,,prefix=site]\n",                                   // 空段
                "bucket=[a,https://a.com,ref=with space]\n",                                 // ref 含空格
        };
        for (String line : bad) {
            AssetsConfigReader acr = new AssetsConfigReader(write("bad.config", "cname=example.com\n" + line));
            assertThrows(RuntimeException.class, acr::Read, line);
        }
    }

    @Test
    void bucketCallNameReserved() throws Exception {
        // R17：调用名与产物目录同名 → replaceBuckets 会把 assets/… 产物路径误当桶引用替换（实测 14 处）
        for (String line : new String[]{"bucket=[assets,https://a.com]\n", "bucket=[pre-assets,https://a.com]\n"}) {
            AssetsConfigReader acr = new AssetsConfigReader(write("resv.config", "cname=example.com\n" + line));
            RuntimeException e = assertThrows(RuntimeException.class, acr::Read, line);
            assertTrue(e.getMessage().contains("不能是 assets / pre-assets"), e.getMessage());
        }
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
