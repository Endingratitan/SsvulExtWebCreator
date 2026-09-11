/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Integra;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class AssetsConfigReader {
    private final WebType Objective;
    private final File ConfigFile;

    private JsonNode jsonRoot;                                                // JSON 解析结果
    private final Map<String, List<String>> configMap = new LinkedHashMap<>(); // config 解析结果
    private final LinkedHashMap<String, String> bucketMap = new LinkedHashMap<>(); // 调用名 -> href（含协议、无末尾斜线）
    private final LinkedHashMap<String, List<String>> bucketFields = new LinkedHashMap<>(); // 调用名 -> 全部字段 [调用名,href,EndPoint,...]

    /** config 允许的键（未知键报错） */
    private static final Set<String> CONFIG_KEYS =
            Set.of("cname", "server", "bucket", "categories", "readme", "local-favicon", "offline",
                    "engine-words", "minify", "minifier");

    public AssetsConfigReader(File configFile) {
        this.ConfigFile = configFile;
        WebType type = WebType.NULL;
        if (ConfigFile.exists() && ConfigFile.isFile()) {
            type = WebType.checkName(ConfigFile.getName());
        }
        if (type != WebType.JSON && type != WebType.CONFIG) {
            throw new RuntimeException("Create ACR Failed!");
        }
        this.Objective = type;
        IO.println("Creat ACR Success!");
    }

    public boolean Read(){
        switch (Objective){
            case JSON: return ReadJSON();
            case CONFIG: return ReadConfig();
            case null, default: return false;
        }
    }

    /**
     * 解析并校验页面 json。
     * 重复键必须在解析层报错（JSON Schema 校验发生在解析之后，看不到被覆盖的重复键）。
     * TODO: page.schema.json 路径目前写死为工作目录下，后续改为随程序定位。
     */
    private boolean ReadJSON(){
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(JsonParser.Feature.STRICT_DUPLICATE_DETECTION, true);
        JsonNode root;
        try {
            root = mapper.readTree(ConfigFile);
        } catch (IOException e) {
            throw new RuntimeException("JSON 解析失败: " + ConfigFile.getName() + " - " + e.getMessage(), e);
        }
        try {
            JsonNode schemaNode = mapper.readTree(new File("page.schema.json"));
            JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(schemaNode);
            Set<ValidationMessage> errors = schema.validate(root);
            if (!errors.isEmpty()) {
                throw new RuntimeException("JSON 校验失败: " + ConfigFile.getName() + " - " + errors);
            }
        } catch (IOException e) {
            throw new RuntimeException("读取 page.schema.json 失败: " + e.getMessage(), e);
        }
        this.jsonRoot = root;
        return true;
    }

    /**
     * 解析 Environment.config：
     * 无注释；按第一个 '=' 切分；键值 trim；空值视同不设置=默认；
     * bucket 允许多次输入，值格式 <code>[调用名,href,EndPoint,...]</code>，如 <code>[bk,https://bucket.example.com]</code>；
     * 其余键最后一次输入为准；未知键报错；cname 带协议报错。
     */
    private boolean ReadConfig(){
        configMap.clear();
        bucketMap.clear();
        bucketFields.clear();
        try (BufferedReader reader = new BufferedReader(new FileReader(ConfigFile, StandardCharsets.UTF_8))) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                String s = line.trim();
                if (s.isEmpty()) continue;
                int eq = s.indexOf('=');
                if (eq <= 0) throw new RuntimeException("config 语法错误（第" + lineNo + "行，缺'='）: " + s);
                String key = s.substring(0, eq).trim();
                String value = s.substring(eq + 1).trim();
                if (!CONFIG_KEYS.contains(key)) {
                    throw new RuntimeException("config 未知键（第" + lineNo + "行）: " + key);
                }
                if (value.isEmpty()) continue;    // 空值 = 不设置 = 默认
                if (key.equals("cname") && (value.startsWith("http://") || value.startsWith("https://"))) {
                    throw new RuntimeException("cname 不能带协议（第" + lineNo + "行）: " + value);
                }
                if (key.equals("bucket")) {
                    addBucket(value, lineNo);
                } else if (key.equals("engine-words")) {
                    configMap.computeIfAbsent("engine-words", k -> new ArrayList<>()).add(value);   // 允许多条
                } else {
                    configMap.put(key, new ArrayList<>(List.of(value)));   // 非 bucket 键：最后一次为准
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("读取 config 失败: " + ConfigFile.getName(), e);
        }
        return true;
    }

    /** 解析 bucket=[调用名,href,EndPoint,...]（方括号、逗号分隔；可多次输入即多个 bucket）：
     *  第 1 段调用名（非空、不含 / 与空格）、第 2 段 href（必须带协议，替换时补 '/'）、
     *  第 3 段起为**预留字段**（第 3 段约定为 EndPoint，供将来"按目录列文件"的来源使用；更多段原样保留给后续版本）。
     *  字段内不能含逗号。旧写法 `(调用名,URL)` 与裸 `调用名,URL` 不再接受（见 history.md）。 */
    private void addBucket(String raw, int lineNo){
        String v = raw.trim();
        if (!v.startsWith("[") || !v.endsWith("]")) {
            throw new RuntimeException("bucket 值需为 [调用名,href,EndPoint,...] 形式（第" + lineNo + "行）: " + raw);
        }
        List<String> fields = new ArrayList<>();
        for (String f : v.substring(1, v.length() - 1).split(",", -1)) fields.add(f.trim());
        if (fields.size() < 2) {
            throw new RuntimeException("bucket 至少需要 [调用名,href] 两段（第" + lineNo + "行）: " + raw);
        }
        String name = fields.get(0);
        String url  = fields.get(1);
        if (name.isEmpty() || name.contains("/") || name.contains(" ")) {
            throw new RuntimeException("bucket 调用名不能为空、不能含 / 或空格（第" + lineNo + "行）: " + raw);
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new RuntimeException("bucket href 必须带协议（第" + lineNo + "行）: " + url);
        }
        url = stripSlash(url);   // 规范化，替换时补 '/'
        fields.set(1, url);
        if (fields.size() >= 3) {
            String ep = stripSlash(fields.get(2));
            if (!ep.isEmpty() && !ep.startsWith("http://") && !ep.startsWith("https://")) {
                throw new RuntimeException("bucket EndPoint 必须带协议（第" + lineNo + "行）: " + fields.get(2));
            }
            fields.set(2, ep);
        }
        if (bucketMap.containsKey(name)) {
            throw new RuntimeException("bucket 调用名重复（第" + lineNo + "行）: " + name);
        }
        bucketMap.put(name, url);
        bucketFields.put(name, List.copyOf(fields));
        configMap.computeIfAbsent("bucket", k -> new ArrayList<>()).add("[" + String.join(",", fields) + "]");
    }

    private static String stripSlash(String u) {
        String s = u;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    public JsonNode getJson(){ return jsonRoot; }
    public Map<String, List<String>> getConfig(){ return configMap; }
    /** 调用名 -> href（含协议、无末尾斜线）——替换趟与 favicon/code-ui 引用都用它 */
    public Map<String, String> getBuckets(){ return bucketMap; }
    /** 调用名 -> 全部字段（[调用名, href, EndPoint, ...]；第 3 段起为预留，未声明则不存在该下标） */
    public Map<String, List<String>> getBucketFields(){ return bucketFields; }
    /** 调用名 -> EndPoint（第 3 段；未声明返回空串）——供将来"按目录列文件"的来源使用 */
    public String getBucketEndpoint(String name){
        List<String> f = bucketFields.get(name);
        return f != null && f.size() >= 3 ? f.get(2) : "";
    }
}
