package io.github.endingratitan.Integra;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

public class IntegraMachiner {
    StringBuilder builder;
    WebType webType;

    public IntegraMachiner(WebType webType) {
        builder = new StringBuilder();
        this.webType = webType;
        switch (webType) {
            case HTML:
                builder.append("<!DOCTYPE html>\n");
                break;
            case CSS:
            case JS:
                break;
        }
    }

    /** 追加原生内容（链式） */
    public IntegraMachiner append(String s){ builder.append(s); return this; }

    /** 当前生成结果长度 */
    int CollectIndi(){ return builder.length(); }

    /** 返回生成结果 */
    public String build(){ return builder.toString(); }

    /** 以 UTF-8（无 BOM）写盘 */
    public void writeTo(File target){
        try {
            Files.writeString(target.toPath(), build(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("写入失败: " + target, e);
        }
    }

    /**
     * 页面组装骨架（v1）。
     * 已实现：div-ID 按数值升序组装、默认包裹层（id/class/attrs/params data-*）、
     *         嵌套子 div（id 按 div-2-1 拼接）。
     * TODO 待定后补：外壳模板 src/assets/page/base.html；sets/pages/INDEX.json → output/index.html；
     *        输出路径深度替换；bucket 替换接入输出阶段；favicon 键；md→html（转换库未定）；
     *        template.html 加载与 {{key}}/{{content}}/{{children}} 注入（#13/#15 待定）；
     *        .global/.adds 三态互斥与 web_global 拼装。
     */
    public String buildPage(JsonNode pageConfig){
        JsonNode page = pageConfig.path("page");   // schema 保证必填
        return renderGroup("", page);
    }

    /** 渲染一个 div-ID 组合：键 "div-N" 按 N 数值升序 */
    private String renderGroup(String parentId, JsonNode group){
        List<Map.Entry<String, JsonNode>> divs = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> it = group.fields();
        while (it.hasNext()) divs.add(it.next());
        divs.sort(Comparator.comparingInt(e -> Integer.parseInt(e.getKey().substring("div-".length()))));
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, JsonNode> e : divs)
            out.append(renderDiv(parentId, e.getKey(), e.getValue()));
        return out.toString();
    }

    /** 渲染单个 div：默认包裹层（template.html 注入待实现） */
    private String renderDiv(String parentId, String divKey, JsonNode div){
        String id = parentId.isEmpty() ? divKey : parentId + "-" + divKey.substring("div-".length());
        String type = div.path("type").asText();
        JsonNode attrs = div.get("attrs");
        String cls = "ssvul-" + type.replace('/', '-');
        if (attrs != null && attrs.isObject() && attrs.has("class"))
            cls = cls + " " + attrs.get("class").asText();   // attrs.class 并入默认类，避免重复 class 属性
        StringBuilder sb = new StringBuilder();
        sb.append("<div id=\"").append(id).append("\" class=\"").append(cls).append('"');
        appendAttrs(sb, attrs);
        appendDataAttrs(sb, div.get("params"));
        sb.append(">\n");
        // TODO: 加载模板（sets/divs/<type>/template.html，未命中回落 src/assets/divs/<type>/），
        //       {{key}}←params、{{content}}←markdown/raw、{{children}}←子 divs
        JsonNode children = div.get("divs");
        if (children != null && children.isObject())
            sb.append(renderGroup(id, children));
        sb.append("</div>\n");
        return sb.toString();
    }

    /** attrs 直接注入（schema 已禁止 id；class 已并入默认类，跳过） */
    private static void appendAttrs(StringBuilder sb, JsonNode attrs){
        if (attrs == null || !attrs.isObject()) return;
        Iterator<Map.Entry<String, JsonNode>> it = attrs.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> a = it.next();
            if (a.getKey().equals("class")) continue;
            sb.append(' ').append(a.getKey()).append("=\"").append(a.getValue().asText()).append('"');
        }
    }

    /** params → data-* 属性 */
    private static void appendDataAttrs(StringBuilder sb, JsonNode params){
        if (params == null || !params.isObject()) return;
        Iterator<Map.Entry<String, JsonNode>> it = params.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> p = it.next();
            sb.append(" data-").append(p.getKey()).append("=\"").append(p.getValue().asText()).append('"');
        }
    }

    /**
     * bucket 引用替换：bk/assets/... → https://bucket.example.com/assets/...
     * 调用名长的先替换，避免短名前缀吞掉长名（bk 与 bkx 并存时）。
     * TODO: 接入输出阶段（与 pre-assets/、路径深度替换同一趟处理）。
     */
    public static String replaceBucketRefs(String text, Map<String, String> buckets){
        String out = text;
        List<String> names = new ArrayList<>(buckets.keySet());
        names.sort(Comparator.comparingInt(String::length).reversed());
        for (String name : names)
            out = out.replace(name + "/", buckets.get(name) + "/");
        return out;
    }
}
