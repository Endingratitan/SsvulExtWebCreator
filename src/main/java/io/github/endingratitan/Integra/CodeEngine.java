package io.github.endingratitan.Integra;

/**
 * 代码渲染引擎接口（Java 构建期上色通道）。
 *
 * 三层架构中的"引擎层"：MarkdownRenderer 只负责产出 {@code <pre class="md-pre"><code class="md-code language-x">}
 * 外壳，内部内容交由本接口渲染。v1 默认 {@link PassThroughCodeEngine}（纯转义、不上色），
 * 语法高亮由客户端 hljs 完成；未来接入自写词法引擎或 tree-sitter 时实现本接口，
 * token 直接输出 canonical 类（tk-&lt;id&gt;），与 CSS 变量层（--md-code-*）无缝对接，无需再换 MarkdownRenderer。
 */
public interface CodeEngine {

    /**
     * 渲染一段代码为可安全嵌入 {@code <code>} 内的 HTML 片段。
     *
     * @param code     原始源码（未转义、不含围栏行）
     * @param language 围栏信息里的语言名（可能为空字符串）
     * @return 已 HTML 转义的片段；token 建议使用 canonical 类名 tk-&lt;id&gt;
     */
    String renderCode(String code, String language);
}
