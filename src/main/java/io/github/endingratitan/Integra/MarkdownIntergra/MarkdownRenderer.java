/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Integra.MarkdownIntergra;

import io.github.endingratitan.Integra.CodeEngine;
import io.github.endingratitan.Integra.PassThroughCodeEngine;

import java.util.*;

/**
 * Markdown 渲染器（公共门面）。
 *
 * 结构：本类负责公共 API、错误/警告收集与共享工具（包内私有状态由各协作类共享）；
 * 块级派发见 {@link MdBlocks}，判定辅助见 {@link MdBlockScan}，代码围栏与块数学见 {@link MdCodeBlocks}，
 * 引用与 callout 见 {@link MdQuotes}，列表见 {@link MdLists}，表格见 {@link MdTables}，
 * 行内解析见 {@link MdInline}，脚注子系统见 {@link MdFootnotes}，callout 类型表见 {@link Callouts}。
 *
 * 语法与模式见 README：md 语法（v1 子集）+ md-options（mode=simple|strict、
 * footnote-display=end|inline，均有默认值）。收集式报错（上限 20 条）一次性抛出。
 */
public class MarkdownRenderer {

    private static final int MAX_ERRORS = 20;

    /** 本次渲染使用的代码引擎（**实例级**：不再有可变静态字段 → 后台重建/并行构建都不会串页）。
     *  默认纯转义；页面级引擎链由调用方经 {@link #renderParts(String, String, Map, String, CodeEngine)} 传入。 */
    CodeEngine engine = new PassThroughCodeEngine();

    /** HTML 转义（供引擎实现等复用；char 重载避免热循环里为单个字符分配 String） */
    public static String escapeHtml(String s) { return esc(s); }
    public static String escapeHtml(char c) { return esc(c); }

    private final String source;
    final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private boolean overflow;
    private String mode = "simple";            // simple=GitHub 兼容（默认）| strict=严格报错
    List<String> srcLines = List.of();         // 错误上下文（三行显示）
    int headingSeq;                            // 标题锚点序号（文档内 s1/s2/...）
    String anchorPrefix = "";                  // 锚点/脚注 id 前缀（div 级个性化：div-1-s1；裸 md 为空）
    boolean inFootDef;                         // 正在渲染脚注定义内容（内部引用按字面）
    boolean tocOn;                             // md-options.toc：构建期生成目录
    boolean quoteFlattenWarned;                // 引用压平警告只报一次（simple 模式）
    boolean listFlattenWarned;                 // 列表压平警告只报一次（simple 模式）
    boolean hadCode;                           // 本次渲染是否出现围栏代码块（code-ui 注入门控）
    boolean hadCallout;                        // 本次渲染是否出现 callout（md-callout.css / CALLOUT css 注入门控）
    boolean calloutTitleOn = true;             // md-options.callout-title：default|none（none=不注入默认标签）
    final Set<String> calloutWarned = new HashSet<>();   // 非内置 callout 类型警告只报一次
    List<String> codeUiItems = List.of();      // code-ui.items：块内元素（顺序=DOM 顺序）
    String codeUiBg;                           // code-ui.bg：块背景图（null=无）
    boolean codeUiRounded;                     // code-ui.rounded：圆角类
    String codeUiLabelPos = "tr";              // code-ui.label-pos：角标位置
    boolean codeUiWarnedUnknown;               // 未知 item 警告只报一次
    final List<String[]> tocItems = new ArrayList<>();   // {level, 原文, id}（h2 起收集）

    private MdInline inl;

    private MarkdownRenderer(String source) { this.source = source; }

    boolean isStrict() { return "strict".equals(mode); }

    /** 行内解析委托（块级/脚注子系统经此调用） */
    String inline(String s, int lineNo, int depth) { return inl.inline(s, lineNo, depth); }

    public static String render(String md) { return render(md, "内联 md"); }

    public static String render(String md, String source) {
        return render(md, source, Collections.emptyMap());
    }

    /** options：mode=simple|strict（默认 simple）；footnote-display=end|inline（默认 end） */
    public static String render(String md, String source, Map<String, String> options) {
        return joinResult(renderParts(md, source, options));
    }

    /** 分离返回 mdBody 与脚注区（供 {{footnotes}} 占位符注入） */
    public static MdResult renderParts(String md, String source, Map<String, String> options) {
        return renderParts(md, source, options, "");
    }

    /** 便捷重载（1~4 参）：引擎取默认纯转义（单页渲染与测试用；站点构建请用 5 参版传引擎链） */
    public static MdResult renderParts(String md, String source, Map<String, String> options, String anchorPrefix) {
        return renderParts(md, source, options, anchorPrefix, new PassThroughCodeEngine());
    }

    /** anchorPrefix：标题锚点 id 前缀（如 "div-1-"），避免多 md div 页面锚点重复；TOC 链接同步。
     *  engine：本次渲染的代码引擎（**沿参数传入**，不再改静态字段）。 */
    public static MdResult renderParts(String md, String source, Map<String, String> options,
                                      String anchorPrefix, CodeEngine engine) {
        MarkdownRenderer r = new MarkdownRenderer(source);
        r.engine = Objects.requireNonNull(engine);
        r.mode = "strict".equalsIgnoreCase(options.getOrDefault("mode", "simple")) ? "strict" : "simple";
        r.anchorPrefix = anchorPrefix == null ? "" : anchorPrefix;
        // code-ui 渲染开关（全部默认关/缺省）
        String items = options.get("codeui.items");
        r.codeUiItems = items == null || items.isEmpty() ? List.of()
                : Arrays.asList(items.split(","));
        r.codeUiBg = options.get("codeui.bg");
        r.codeUiRounded = "true".equalsIgnoreCase(options.getOrDefault("codeui.rounded", "false"));
        r.codeUiLabelPos = options.getOrDefault("codeui.label-pos", "tr");
        r.calloutTitleOn = !"none".equalsIgnoreCase(options.getOrDefault("callout-title", "default"));
        MdResult res = r.runParts(md == null ? "" : md, options);
        if (!r.errors.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("md 渲染错误（").append(source).append("）：\n");
            for (String e : r.errors) sb.append("  ").append(e).append('\n');
            if (r.overflow) sb.append("  ……（其余错误已省略，收集上限 ").append(MAX_ERRORS).append(" 条）\n");
            throw new RuntimeException(sb.toString());
        }
        return res;
    }

    /** 渲染结果：mdBody 不含脚注区（脚注区单独返回，供 {{footnotes}} 占位符或文末注入）；
     *  hasCode=是否出现围栏代码块；hasCallout=是否出现 callout（各自决定对应 CSS 的按需注入） */
    public record MdResult(String mdBody, String footnotes, boolean hasCode, boolean hasCallout) {
        public MdResult(String mdBody, String footnotes) { this(mdBody, footnotes, false, false); }
        public MdResult(String mdBody, String footnotes, boolean hasCode) { this(mdBody, footnotes, hasCode, false); }
    }

    /** 把脚注区插回 md-body 末尾（无脚注时原样返回） */
    public static String joinResult(MdResult r) {
        if (r.footnotes.isEmpty()) return r.mdBody;
        int pos = r.mdBody.lastIndexOf("</div>");
        return r.mdBody.substring(0, pos) + r.footnotes + r.mdBody.substring(pos);
    }

    // ==================== 入口与规范化 ====================

    private MdResult runParts(String md, Map<String, String> options) {
        if (md.startsWith("\uFEFF")) md = md.substring(1);              // BOM
        md = md.replace("\r\n", "\n").replace('\r', '\n');              // CRLF
        List<String> lines = new ArrayList<>(Arrays.asList(md.split("\n", -1)));
        srcLines = lines;

        // 掩码保留字符：源文出现裸 NUL 会让脚注占位符 \u0000FN<seq>\u0000 可被伪造（曾崩 IndexOutOfBounds）→ 收集式报错
        int nulAt = md.indexOf('\u0000');
        if (nulAt >= 0) {
            int lineNo = 1;
            for (int k = 0; k < nulAt; k++) if (md.charAt(k) == '\n') lineNo++;
            error(lineNo, "源文含非法控制字符 U+0000（md 脚注掩码保留字符）", "删除该字符后重试", lines.get(lineNo - 1));
            return new MdResult("", "");
        }

        MdFootnotes fn = new MdFootnotes(this);
        fn.inlineFn = "inline".equals(options.getOrDefault("footnote-display", "end"));
        this.inl = new MdInline(this, fn);
        MdBlocks blocks = new MdBlocks(this, fn);

        tocOn = "true".equalsIgnoreCase(options.getOrDefault("toc", "false"));
        fn.parseFootnotes(lines);
        StringBuilder content = new StringBuilder();
        blocks.renderBlocks(content, lines, 0, 0, 0);
        String footnotes = fn.finalizeFootnotes(content);               // 编号分配 + 占位符替换 + 脚注区生成
        if (tocOn && !tocItems.isEmpty()) content.insert(0, buildToc());   // 目录置于 md-body 顶部
        for (String w : warnings) IO.println("[md 警告] " + w);
        String mdBody = "<div class=\"md-body\">\n" + content + "</div>\n";
        return new MdResult(mdBody, footnotes, hadCode, hadCallout);
    }

    /** 块级解析器回调：记录入目录的标题（h2 起） */
    void recordHeading(int level, String text, int seq) {
        tocItems.add(new String[]{String.valueOf(level), text, anchorId(seq)});
    }

    /** 锚点 id（含 div 前缀，如 div-1-s1） */
    String anchorId(int seq) { return anchorPrefix + "s" + seq; }

    private String buildToc() {
        StringBuilder sb = new StringBuilder("<nav class=\"md-toc\">\n<ul>\n");
        for (String[] it : tocItems) {
            String txt = it[1].replaceAll("\\*{1,3}|_{1,2}|~~|`", "").trim();   // 粗剪行内标记
            sb.append("<li class=\"toc-h").append(it[0]).append("\"><a href=\"#").append(it[2]).append("\">")
              .append(esc(txt)).append("</a></li>\n");
        }
        sb.append("</ul>\n</nav>\n");
        return sb.toString();
    }

    // ==================== 共享工具（包内可见） ====================

    static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int k = 0; k < s.length(); k++) sb.append(esc(s.charAt(k)));
        return sb.toString();
    }

    static String esc(char c) {
        return switch (c) {
            case '&' -> "&amp;";
            case '<' -> "&lt;";
            case '>' -> "&gt;";
            case '"' -> "&quot;";
            default -> String.valueOf(c);
        };
    }

    static String snippet(String s) {
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    static int leadingSpaces(String line) {
        int k = 0;
        while (k < line.length() && line.charAt(k) == ' ') k++;
        return k;
    }

    /** 剥掉行首 0~3 个空格（**字符扫描**：逐行调用的热路径，不再每次编译正则） */
    static String stripIndent(String line) {
        int k = 0, n = line.length();
        while (k < 3 && k < n && line.charAt(k) == ' ') k++;
        return k == 0 ? line : line.substring(k);
    }

    static boolean isWS(char c) { return Character.isWhitespace(c); }
    static boolean isAlnum(char c) { return Character.isLetterOrDigit(c); }

    // ==================== 错误与警告 ====================

    void error(int lineNo, String msg, String suggest, String original) {
        if (errors.size() >= MAX_ERRORS) { overflow = true; return; }
        StringBuilder sb = new StringBuilder("第 ").append(lineNo).append(" 行: ").append(msg);
        if (original != null && !original.isEmpty()) sb.append("，原文: \"").append(snippet(original)).append('"');
        if (suggest != null) sb.append("；建议: ").append(suggest);
        sb.append(contextOf(lineNo));
        errors.add(sb.toString());
    }

    /** 警告（不阻断构建，渲染完成后经 IO.println 输出到控制台） */
    void warn(int lineNo, String msg) {
        StringBuilder sb = new StringBuilder("第 ").append(lineNo).append(" 行: ").append(msg);
        sb.append(contextOf(lineNo));
        warnings.add(sb.toString());
    }

    /** 错误上下文三行显示：· 上一行 / ▸ 本行 / · 下一行 */
    private String contextOf(int lineNo) {
        if (srcLines.isEmpty() || lineNo < 1 || lineNo > srcLines.size()) return "";
        StringBuilder sb = new StringBuilder();
        if (lineNo > 1) sb.append("\n    · 上一行: \"").append(snippet(srcLines.get(lineNo - 2))).append('"');
        sb.append("\n    ▸ 本行: \"").append(snippet(srcLines.get(lineNo - 1))).append('"');
        if (lineNo < srcLines.size()) sb.append("\n    · 下一行: \"").append(snippet(srcLines.get(lineNo))).append('"');
        return sb.toString();
    }
}
