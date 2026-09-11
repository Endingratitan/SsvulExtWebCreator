/* SsvulExtWebCreator · https://github.com/Endingratitan/SsvulExtWebCreator | MPL-2.0: redistributed with this notice and LICENSE/NOTICE | Copyright (c) 2026 Endingratitan */
/* 对应 Java: Integra/MarkdownIntergra/MdCodeBlocks.java（围栏代码 + code-ui 外壳 + 块数学）
   **差异（有意）**：客户端没有构建期引擎链，代码一律按 **PassThrough（hljs）语义**输出——
   只做 HTML 转义 + `class="md-code language-x"`，上色交给浏览器 hljs（md-highlight.js）。
   因此站点若在构建期用 simple 引擎，两者代码块的配色会不同（指南里有差异表）。 */
(function (global) {
  var Md = global.SsvulMd = global.SsvulMd || {};

  Md.code = {
    /** 块数学：$$…$$ 或 \[…\]（单行或多行）；未闭合报错（错误只收集，不抛） */
    renderMathBlock: function (ctx, out, lines, i, end, base) {
      var first = Md.stripIndent(lines[i]);
      var bracket = first.indexOf('\\[') === 0;
      var close = bracket ? '\\]' : '$$';
      var sb = first;
      var j = i + 1;
      var closed = first.trim().length >= 4 && first.trim().slice(-close.length) === close;
      while (!closed && j < end) {
        var l = lines[j];
        sb += '\n' + l;
        j++;
        if (l.trim().slice(-close.length) === close) { closed = true; break; }
      }
      if (!closed) ctx.error(base + i + 1, '块数学未闭合', '请在末尾补 ' + close, first);
      out.push('<div class="md-math-block"' + (bracket ? ' data-delim="latex-block"' : '') + '>'
        + Md.esc(sb) + '</div>\n');
      return j;
    },

    /** 围栏代码块：返回消费到的下一行下标 */
    renderFenced: function (ctx, out, lines, i, end, base) {
      var t = Md.stripIndent(lines[i]);
      var fc = t.charAt(0);
      var len = 0;
      while (len < t.length && t.charAt(len) === fc) len++;
      var lang = '';
      var info = t.slice(len).trim();
      if (info) {
        var firstWord = info.split(/\s+/)[0];
        if (/^[A-Za-z0-9_+-]+$/.test(firstWord)) lang = firstWord;
      }
      var j = i + 1, content = [], closed = false;
      while (j < end) {
        var lt = Md.stripIndent(lines[j]);
        if (lt && lt.charAt(0) === fc) {
          var k = 0;
          while (k < lt.length && lt.charAt(k) === fc) k++;
          if (k >= len && lt.slice(k).trim() === '') { closed = true; j++; break; }
        }
        content.push(lines[j]);
        j++;
      }
      if (!closed && ctx.isStrict()) ctx.error(base + i + 1, '未闭合的代码块', '请在末尾补上 ' + fc, t);
      var rendered = Md.esc(content.join('\n'));      // PassThrough 语义（hljs 客户端上色）
      ctx.hadCode = true;
      var ui = ctx.codeUiItems.length > 0 || ctx.codeUiBg !== null || ctx.codeUiRounded;
      var pre = '<pre class="md-pre"><code class="md-code' + (lang ? ' language-' + lang : '') + '">'
        + rendered + '</code></pre>\n';
      if (!ui) { out.push(pre); return j; }

      var hasLang = ctx.codeUiItems.indexOf('lang-label') >= 0;
      var hasDots = ctx.codeUiItems.indexOf('mac-dots') >= 0;
      var hasCopy = ctx.codeUiItems.indexOf('copy-btn') >= 0;
      var b = '<div class="md-code-block';
      if (hasLang) b += ' codeui-lang';
      if (hasDots) b += ' codeui-dots';
      if (hasCopy) b += ' codeui-copy';
      if (ctx.codeUiBg !== null) b += ' codeui-bg';
      if (ctx.codeUiRounded) b += ' codeui-rounded';
      if (hasCopy && ctx.codeUiLabelPos === 'tr') b += ' has-copy';
      b += '" data-lang="' + Md.esc(lang) + '" data-items="' + Md.esc(ctx.codeUiItems.join(',')) + '"';
      if (ctx.codeUiBg !== null) b += ' style="background-image:url(' + Md.esc(ctx.codeUiBg) + ');"';
      b += '>\n';
      for (var x = 0; x < ctx.codeUiItems.length; x++) {
        var it = ctx.codeUiItems[x];
        if (it === 'lang-label') {
          if (lang) b += '<span class="md-code-lang pos-' + ctx.codeUiLabelPos + '">' + Md.esc(lang) + '</span>\n';
        } else if (it === 'mac-dots') {
          b += '<span class="md-code-dots" aria-hidden="true"><i></i><i></i><i></i></span>\n';
        } else if (it === 'copy-btn') {
          b += '<button type="button" class="md-code-copy">复制</button>\n';
        } else if (!ctx.codeUiWarnedUnknown) {
          ctx.warn(base + i + 1, 'code-ui 未知 item（已跳过，可由 CODEUI.js 按 data-items 实现）: ' + it);
          ctx.codeUiWarnedUnknown = true;
        }
      }
      b += pre + '</div>\n';
      out.push(b);
      return j;
    }
  };
})(window);
