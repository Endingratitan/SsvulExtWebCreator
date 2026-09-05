package io.github.endingratitan.Integra;

/**
 * v1 默认引擎：不构建期上色，仅做 HTML 转义。
 * 语法高亮由客户端 hljs（md-js 预设）完成后，token-map.js 再映射到 canonical 类。
 */
public class PassThroughCodeEngine implements CodeEngine {
    @Override
    public String renderCode(String code, String language) {
        return MarkdownRenderer.escapeHtml(code);
    }
}
