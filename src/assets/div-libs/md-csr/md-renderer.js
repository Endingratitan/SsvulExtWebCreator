/* SsvulExtWebCreator · https://github.com/Endingratitan/SsvulExtWebCreator | MPL-2.0: redistributed with this notice and LICENSE/NOTICE | Copyright (c) 2026 Endingratitan */
/* 对应 Java: Integra/MarkdownIntergra/MarkdownRenderer.java（门面 + 状态 + 共享工具）
 *
 * 这是客户端 md 渲染器的**唯一公开入口**（DOM-free：只吃字符串、吐字符串，因此能在 Node 里跑 parity）：
 *   SsvulMd.render(md, options) -> { html, errors, warnings, hasCode, hasCallout }
 *
 * 与构建期的对齐目标：mode=simple、代码引擎 = PassThrough（hljs 语义）。
 * **有意的差异**（见 docs/UserWrite/md-csr-guide.md 的差异表）：
 *   · options['raw-html'] 默认 off：原生 HTML 一律转义（CSR 的 md 常来自"别人上传的文件"，直出=存储型 XSS）；
 *     置 on 时与构建期 simple 模式逐字节一致（parity 测试即用 on）。
 *   · options['link-policy'] 默认 relaxed：放行相对路径（../、./、/），仍拦截 javascript:/data:/vbscript:/file:。
 *   · 错误不抛异常（页面必须渲染），收集到 errors/warnings 里交给调用方展示。 */
(function (global) {
  var Md = global.SsvulMd = global.SsvulMd || {};
  Md.version = '0.1.0';
  Md.NEST_LIMIT = 8;                     // 对齐 MdBlocks.NEST_LIMIT（引用/callout 与列表共用）

  /* ---------- 共享工具（对应 MarkdownRenderer 的 static 工具） ---------- */

  var ALNUM_RE = null;                   // 动态构造：老的引擎不支持 \p{...} 时不至于整脚本解析失败
  try { ALNUM_RE = new RegExp('[\\p{L}\\p{N}]', 'u'); } catch (e) { ALNUM_RE = /[0-9A-Za-z]/; }

  Md.escChar = function (c) {
    return c === '&' ? '&amp;' : c === '<' ? '&lt;' : c === '>' ? '&gt;' : c === '"' ? '&quot;' : c;
  };
  Md.esc = function (s) {
    return String(s == null ? '' : s).replace(/[&<>"]/g, Md.escChar);
  };
  /* Java Character.isWhitespace 的近似（刻意不含 NBSP：Java 里 NBSP 不是空白） */
  Md.isWS = function (c) {
    if (c === ' ' || c === '\t' || c === '\n' || c === '\r' || c === '\f' || c === '\u000B') return true;
    if (c >= '\u001C' && c <= '\u001F') return true;
    return c === '\u00A0' ? false : /^\s$/.test(c) && c !== '\uFEFF';
  };
  Md.isAlnum = function (c) { return ALNUM_RE.test(c); };
  Md.stripIndent = function (line) { return line.replace(/^ {0,3}/, ''); };
  Md.leadingSpaces = function (line) {
    var k = 0;
    while (k < line.length && line.charAt(k) === ' ') k++;
    return k;
  };
  Md.snippet = function (s) {
    s = String(s == null ? '' : s);
    return s.length > 40 ? s.slice(0, 40) + '…' : s;
  };

  /* ---------- 渲染状态（对应 MarkdownRenderer 的实例字段） ---------- */

  Md.newCtx = function (options) {
    options = options || {};
    var rawHtml = String(options['raw-html'] || 'off').toLowerCase() === 'on';
    var items = options['codeui.items'];
    var ctx = {
      mode: String(options.mode || 'simple').toLowerCase() === 'strict' ? 'strict' : 'simple',
      rawHtml: rawHtml,
      linkRelaxed: String(options['link-policy'] || 'relaxed').toLowerCase() !== 'strict',
      source: options.source || '内联 md',
      srcLines: [],
      headingSeq: 0,
      anchorPrefix: options['anchor-prefix'] || '',
      inFootDef: false,
      tocOn: String(options.toc || 'false').toLowerCase() === 'true',
      quoteFlattenWarned: false,
      listFlattenWarned: false,
      hadCode: false,
      hadCallout: false,
      calloutTitleOn: String(options['callout-title'] || 'default').toLowerCase() !== 'none',
      calloutWarned: {},
      codeUiItems: items ? String(items).split(',') : [],
      codeUiBg: options['codeui.bg'] || null,
      codeUiRounded: String(options['codeui.rounded'] || 'false').toLowerCase() === 'true',
      codeUiLabelPos: options['codeui.label-pos'] || 'tr',
      codeUiWarnedUnknown: false,
      tocItems: [],
      errors: [],
      warnings: [],
      overflow: false,
      defLineIdx: {},                    // 脚注定义行（批次 C 填充）
      inlineFn: String(options['footnote-display'] || 'end').toLowerCase() === 'inline',
      footnotes: ''                      // 脚注区 HTML（批次 C 填充）
    };
    ctx.isStrict = function () { return ctx.mode === 'strict'; };
    ctx.maxErrors = 20;
    ctx.error = function (lineNo, msg, suggest, original) {
      if (ctx.errors.length >= ctx.maxErrors) { ctx.overflow = true; return; }
      var s = '第 ' + lineNo + ' 行: ' + msg;
      if (original) s += '，原文: "' + Md.snippet(original) + '"';
      if (suggest) s += '；建议: ' + suggest;
      ctx.errors.push(s + ctx.contextOf(lineNo));
    };
    ctx.warn = function (lineNo, msg) { ctx.warnings.push('第 ' + lineNo + ' 行: ' + msg + ctx.contextOf(lineNo)); };
    ctx.contextOf = function (lineNo) {
      var L = ctx.srcLines;
      if (!L.length || lineNo < 1 || lineNo > L.length) return '';
      var s = '';
      if (lineNo > 1) s += '\n    · 上一行: "' + Md.snippet(L[lineNo - 2]) + '"';
      s += '\n    ▸ 本行: "' + Md.snippet(L[lineNo - 1]) + '"';
      if (lineNo < L.length) s += '\n    · 下一行: "' + Md.snippet(L[lineNo]) + '"';
      return s;
    };
    ctx.anchorId = function (seq) { return ctx.anchorPrefix + 's' + seq; };
    ctx.recordHeading = function (level, text, seq) {
      ctx.tocItems.push([String(level), text, ctx.anchorId(seq)]);
    };
    return ctx;
  };

  /* ---------- 门面（对应 MarkdownRenderer.runParts + render） ---------- */

  Md.render = function (md, options) {
    var ctx = Md.newCtx(options || {});
    if (typeof md !== 'string') md = '';
    if (md.charAt(0) === '\uFEFF') md = md.slice(1);                 // BOM
    md = md.replace(/\r\n/g, '\n').replace(/\r/g, '\n');             // CRLF
    var lines = md.split('\n');
    ctx.srcLines = lines;

    var nulAt = md.indexOf('\u0000');                                // Java 侧同样拒绝裸 NUL（脚注掩码保留字符）
    if (nulAt >= 0) {
      var lineNo = 1;
      for (var k = 0; k < nulAt; k++) if (md.charAt(k) === '\n') lineNo++;
      ctx.error(lineNo, '源文含非法控制字符 U+0000（md 脚注掩码保留字符）', '删除该字符后重试', lines[lineNo - 1]);
      return finishCtx(ctx, '');
    }

    if (Md.footnotes && Md.footnotes.parseFootnotes) Md.footnotes.parseFootnotes(ctx, lines);
    var out = [];
    Md.blocks.renderBlocks(ctx, out, lines, 0, 0, 0);
    var content = out.join('');
    if (Md.footnotes && Md.footnotes.finalizeFootnotes) content = Md.footnotes.finalizeFootnotes(ctx, content);
    if (ctx.tocOn && ctx.tocItems.length) content = Md.buildToc(ctx) + content;
    return finishCtx(ctx, '<div class="md-body">\n' + content + '</div>\n');
  };

  function finishCtx(ctx, mdBody) {
    var html = joinResult(mdBody, ctx.footnotes);
    return { html: html, errors: ctx.errors, warnings: ctx.warnings, hasCode: ctx.hadCode, hasCallout: ctx.hadCallout };
  }

  /** 与 Java joinResult 同构：脚注区插到 md-body 末尾的 </div> 之前 */
  function joinResult(mdBody, footnotes) {
    if (!footnotes) return mdBody;
    var pos = mdBody.lastIndexOf('</div>');
    return mdBody.slice(0, pos) + footnotes + mdBody.slice(pos);
  }

  /* TOC（对应 MarkdownRenderer.buildToc；h2 起入目录） */
  Md.buildToc = function (ctx) {
    var s = '<nav class="md-toc">\n<ul>\n';
    for (var i = 0; i < ctx.tocItems.length; i++) {
      var it = ctx.tocItems[i];
      var txt = it[1].replace(/\*{1,3}|_{1,2}|~~|`/g, '').trim();
      s += '<li class="toc-h' + it[0] + '"><a href="#' + it[2] + '">' + Md.esc(txt) + '</a></li>\n';
    }
    return s + '</ul>\n</nav>\n';
  };
})(window);
