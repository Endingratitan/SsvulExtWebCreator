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
import java.nio.file.Files;
import java.util.*;

public class AssetsConfigReader {
    private final WebType Objective;
    private final File ConfigFile;

    private JsonNode jsonRoot;                                                // JSON 解析结果
    private final Map<String, List<String>> configMap = new LinkedHashMap<>(); // config 解析结果
    private final LinkedHashMap<String, String> bucketMap = new LinkedHashMap<>(); // 调用名 -> href（含协议、无末尾斜线）
    private final LinkedHashMap<String, List<String>> bucketFields = new LinkedHashMap<>(); // 调用名 -> 全部字段 [调用名,href,属性...]
    private final LinkedHashMap<String, Map<String, String>> bucketAttrs = new LinkedHashMap<>(); // 调用名 -> 属性（endpoint/prefix/ref）

    /** config 允许的键（未知键报错） */
    private static final Set<String> CONFIG_KEYS =
            Set.of("cname", "server", "bucket", "categories", "readme", "local-favicon", "offline",
                    "engine-words", "minify", "minifier", "session-ttl");

    /** bucket 第 3 段起允许的属性键（键=值；未知键报错） */
    private static final Set<String> BUCKET_ATTR_KEYS = Set.of("endpoint", "prefix", "ref");

    // ---- 页面 schema：进程级共享与缓存（P1）----

    /** 进程级共享（只读用法，线程安全）：带"重复键即报错"的 mapper + 校验器工厂 */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(JsonParser.Feature.STRICT_DUPLICATE_DETECTION, true);
    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);

    /** schema 缓存：键 = **schema 文件内容的 SHA-256**（不是 mtime——同秒同长的改写也能失效）；每进程编译一次 */
    private static volatile JsonSchema schemaCache;

    /**
     * R11：schema 定位 —— **classpath `/page.schema.json` 优先**（jar 内 / build/classes-res），
     * 工作目录文件兜底（手工编译、仓库根直跑）。两边都没有 → 教学式报错。
     */
    private static byte[] loadSchemaBytes() {
        try (InputStream in = AssetsConfigReader.class.getResourceAsStream("/page.schema.json")) {
            if (in != null) return in.readAllBytes();
        } catch (IOException ignored) {
            // 落到工作目录兜底
        }
        File f = new File("page.schema.json");
        if (f.isFile()) {
            try {
                return Files.readAllBytes(f.toPath());
            } catch (IOException e) {
                throw new RuntimeException("读取 page.schema.json 失败: " + e.getMessage(), e);
            }
        }
        throw new RuntimeException("找不到 page.schema.json：既不在程序资源 /page.schema.json，也不在工作目录。"
                + "仓库根有一份真源，构建时由 gradle 复制进 jar（手工编译见 tech.md §12 的 cp 一行）");
    }

    /** 取（必要时编译）共享 schema；stats 非空时只在**真正编译**时计数
     *
     *  **热路径纪律（0.4.0 修）**：这里**每页**都会被调用，所以快路径只能是"volatile 读 + 返回" ——
     *  绝不读文件、不算哈希、不进 synchronized。曾经每页都 `loadSchemaBytes()` + `sha256(bytes)`，
     *  实测 300 页白付 **1647ms**（`build/ParRender.java` 对照：校验本身 0ms），还把并行度锁在 1.6×。
     *  代价：改 `page.schema.json` 需要重启构建/预览（每条构建命令本来就是新 JVM；预览重启一次即可）。 */
    private static JsonSchema schema(BuildStats stats) {
        JsonSchema s = schemaCache;                 // volatile 读：无锁、无 IO、无哈希
        if (s != null) return s;
        synchronized (AssetsConfigReader.class) {
            if (schemaCache != null) return schemaCache;
            byte[] bytes = loadSchemaBytes();
            try {
                schemaCache = FACTORY.getSchema(MAPPER.readTree(bytes));
            } catch (IOException e) {
                throw new RuntimeException("page.schema.json 解析失败: " + e.getMessage(), e);
            }
            if (stats != null) stats.schemaCompiles.incrementAndGet();
            return schemaCache;
        }
    }

    /** 可选的构建统计（M1 起用于性能门禁）；测试与独立使用传 null */
    private final BuildStats stats;

    /** 预读内容（可为 null）：调用方已读过同一文件时直接给文本，避免重复读盘（P3） */
    private final String preloaded;

    /** 共享的 JSON mapper（strict 重复键检测）——索引/渲染等处复用，别再 new（P1/P3 同源） */
    static ObjectMapper jsonMapper() { return MAPPER; }

    public AssetsConfigReader(File configFile) { this(configFile, null, null); }

    public AssetsConfigReader(File configFile, BuildStats stats) { this(configFile, stats, null); }

    public AssetsConfigReader(File configFile, BuildStats stats, String preloaded) {
        this.ConfigFile = configFile;
        this.stats = stats;
        this.preloaded = preloaded;
        WebType type = WebType.NULL;
        if (ConfigFile.exists() && ConfigFile.isFile()) {
            type = WebType.checkName(ConfigFile.getName());
        }
        if (type != WebType.JSON && type != WebType.CONFIG) {
            throw new RuntimeException("Create ACR Failed!");
        }
        this.Objective = type;
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
     * schema 走进程级缓存（P1）：每进程只编译一次，与页面数无关。
     */
    private boolean ReadJSON(){
        if (stats != null) stats.jsonParses.incrementAndGet();
        JsonNode root;
        try {
            root = preloaded != null ? MAPPER.readTree(preloaded) : MAPPER.readTree(ConfigFile);
        } catch (IOException e) {
            throw new RuntimeException("JSON 解析失败: " + ConfigFile.getName() + " - " + e.getMessage(), e);
        }
        Set<ValidationMessage> errors = schema(stats).validate(root);
        if (!errors.isEmpty()) {
            throw new RuntimeException("JSON 校验失败: " + ConfigFile.getName() + " - " + errors);
        }
        this.jsonRoot = root;
        return true;
    }

    /**
     * 解析 Environment.config：
     * 无注释；按第一个 '=' 切分；键值 trim；空值视同不设置=默认；
     * bucket 允许多次输入，值格式 <code>[调用名,href,属性...]</code>，如
     * <code>[bk,https://bucket.example.com,endpoint=https://s3.example.com/my-bucket,prefix=site/blog]</code>；
     * 其余键最后一次输入为准；未知键报错；cname 带协议报错。
     */
    private boolean ReadConfig(){
        configMap.clear();
        bucketMap.clear();
        bucketFields.clear();
        bucketAttrs.clear();
        try (BufferedReader reader = new BufferedReader(preloaded != null
                ? new StringReader(preloaded) : new FileReader(ConfigFile, StandardCharsets.UTF_8))) {
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

    /** 解析 bucket=[调用名,href,键=值...]（方括号、逗号分隔；可多次输入即多个 bucket）：
     *  第 1 段调用名（非空、不含 / 与空格）；第 2 段 href＝**公开访问基址**（必须带协议；替换/镜像/拼条目 link 都用它）；
     *  第 3 段起为**键=值属性**（白名单 endpoint/prefix/ref，供列目录型预设使用；未知键报错）。
     *  **兼容**：第 3 段不带 '=' 时按旧约定当 EndPoint（等价 endpoint=值）。
     *  字段内不能含逗号。旧写法 `(调用名,URL)` 与裸 `调用名,URL` 不再接受（见 history.md）。 */
    private void addBucket(String raw, int lineNo){
        String v = raw.trim();
        if (!v.startsWith("[") || !v.endsWith("]")) {
            throw new RuntimeException("bucket 值需为 [调用名,href,键=值...] 形式（第" + lineNo + "行）: " + raw);
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
        // 产物路径以 assets/ 开头（assets/pre、assets/index…），调用名同名会把产物路径误当成桶引用替换掉
        if (name.equals("assets") || name.equals("pre-assets")) {
            throw new RuntimeException("bucket 调用名不能是 assets / pre-assets（与产物目录同名会把产物路径误当桶引用替换）"
                    + "（第" + lineNo + "行）: " + name);
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new RuntimeException("bucket href 必须带协议（第" + lineNo + "行）: " + url);
        }
        url = stripSlash(url);   // 规范化，替换时补 '/'
        fields.set(1, url);
        LinkedHashMap<String, String> attrs = new LinkedHashMap<>();
        for (int i = 2; i < fields.size(); i++) {
            String f = fields.get(i);
            if (f.isEmpty()) {
                throw new RuntimeException("bucket 第 " + (i + 1) + " 段为空（第" + lineNo + "行）: " + raw);
            }
            int eq = f.indexOf('=');
            String k, val;
            if (eq < 0) {
                if (i > 2) {
                    throw new RuntimeException("bucket 第 " + (i + 1) + " 段需为 键=值 形式（可用: endpoint, prefix, ref）"
                            + "（第" + lineNo + "行）: " + raw);
                }
                k = "endpoint"; val = f;                 // 旧约定：第 3 段裸值 = EndPoint
            } else {
                k = f.substring(0, eq).trim();
                val = f.substring(eq + 1).trim();
            }
            if (!BUCKET_ATTR_KEYS.contains(k)) {
                throw new RuntimeException("bucket 未知属性（第" + lineNo + "行）: " + k + "（可用: endpoint, prefix, ref）");
            }
            if (attrs.containsKey(k)) {
                throw new RuntimeException("bucket 属性重复（第" + lineNo + "行）: " + k);
            }
            if (val.isEmpty()) {
                throw new RuntimeException("bucket 属性 " + k + " 的值不能为空（第" + lineNo + "行）: " + raw);
            }
            attrs.put(k, switch (k) {
                case "endpoint" -> checkEndpoint(val, lineNo);
                case "prefix" -> checkPrefix(val, lineNo);
                default -> checkRef(val, lineNo);
            });
        }
        if (bucketMap.containsKey(name)) {
            throw new RuntimeException("bucket 调用名重复（第" + lineNo + "行）: " + name);
        }
        bucketMap.put(name, url);
        bucketFields.put(name, List.copyOf(fields));
        bucketAttrs.put(name, attrs);
        configMap.computeIfAbsent("bucket", k -> new ArrayList<>()).add("[" + String.join(",", fields) + "]");
    }

    /** endpoint：列目录 API 基址（不含 query；S3 的 bucket 写在 path 或 host 均可） */
    private static String checkEndpoint(String v, int lineNo){
        if (!v.startsWith("http://") && !v.startsWith("https://")) {
            throw new RuntimeException("bucket endpoint 必须带协议（第" + lineNo + "行）: " + v);
        }
        if (v.contains("?")) {
            throw new RuntimeException("bucket endpoint 不能含 query（第" + lineNo + "行）: " + v);
        }
        return stripSlash(v);
    }

    /** prefix：存储侧根前缀（对象在 site/blog/… 而链接空间是 blog/… 时用；不带前导/末尾斜线） */
    private static String checkPrefix(String v, int lineNo){
        String s = v;
        while (s.startsWith("/")) s = s.substring(1);
        s = stripSlash(s);
        if (s.isEmpty()) {
            throw new RuntimeException("bucket prefix 不能为空（第" + lineNo + "行）: " + v);
        }
        for (String seg : s.split("/", -1)) {
            if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) {
                throw new RuntimeException("bucket prefix 不能含空段、. 或 ..（第" + lineNo + "行）: " + v);
            }
        }
        return s;
    }

    /** ref：GitHub 分支/标签/commit（阶段 2 用；当前只做形态校验） */
    private static String checkRef(String v, int lineNo){
        if (v.contains(" ") || v.contains("..")) {
            throw new RuntimeException("bucket ref 不能含空格或 ..（第" + lineNo + "行）: " + v);
        }
        return v;
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
    /** 调用名 -> 全部字段（[调用名, href, 属性...]；原始分段，href 已规范化） */
    public Map<String, List<String>> getBucketFields(){ return bucketFields; }
    /** 调用名 -> 属性（endpoint/prefix/ref；未声明则无该键）——列目录型预设在构建期解析用 */
    public Map<String, Map<String, String>> getBucketAttrs(){ return bucketAttrs; }
    /** 调用名 -> endpoint（未声明返回空串）——`ssvul:s3` 的列目录 API 基址 */
    public String getBucketEndpoint(String name){ return bucketAttr(name, "endpoint"); }
    /** 调用名 -> prefix（未声明返回空串）——存储侧根前缀 */
    public String getBucketPrefix(String name){ return bucketAttr(name, "prefix"); }
    /** 调用名 -> ref（未声明返回空串）——GitHub 阶段 2 用 */
    public String getBucketRef(String name){ return bucketAttr(name, "ref"); }

    private String bucketAttr(String name, String key){
        Map<String, String> a = bucketAttrs.get(name);
        return a == null ? "" : a.getOrDefault(key, "");
    }
}
