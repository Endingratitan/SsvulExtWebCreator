/* SsvulExtWebCreator · https://github.com/Endingratitan/SsvulExtWebCreator | MPL-2.0: redistributed with this notice and LICENSE/NOTICE | Copyright (c) 2026 Endingratitan */
/* 对应 Java: Integra/MarkdownIntergra/MdBlocks.java（块级派发 + 段落合并 + 兜底一行 + 标题）
   out 为**分片数组**（对应 StringBuilder），返回值 = 消费到的下一行下标（与 Java 同构）。
   **一处有意的差异**：原生 HTML 块在 `raw-html=off`（CSR 默认）时按普通段落转义输出，不做直出。 */
(function (global) {
  var Md = global.SsvulMd = global.SsvulMd || {};

  Md.blocks = {
    renderBlocks: function (ctx, out, lines, listDepth, quoteDepth, base) {
      var i = 0, end = lines.length;
      while (i < end) {
        if (lines === ctx.srcLines && ctx.defLineIdx[i] !== undefined) {
          if (ctx.inlineFn && Md.footnotes && Md.footnotes.emitInlineDefMarker) {
            Md.footnotes.emitInlineDefMarker(ctx, out, ctx.defLineIdx[i]);   // inline 模式：定义处占位
          }
          i++;
          continue;
        }
        var t = Md.stripIndent(lines[i]);
        if (!t) { i++; continue; }
        if (Md.scan.isFence(t)) { i = Md.code.renderFenced(ctx, out, lines, i, end, base); continue; }
        if (t.charAt(0) === '>') { i = Md.quotes.renderQuote(ctx, out, lines, i, end, listDepth, quoteDepth, base); continue; }
        if (Md.scan.isListStart(t)) { i = Md.lists.renderList(ctx, out, lines, i, end, listDepth, quoteDepth, base); continue; }
        if (Md.tables.isTableAhead(lines, i, end)) { i = Md.tables.renderTable(ctx, out, lines, i, end, base); continue; }
        if (Md.scan.isMathOpen(t) || Md.scan.isMathOpenBracket(t)) {
          i = Md.code.renderMathBlock(ctx, out, lines, i, end, base);
          continue;
        }
        var h = Md.scan.headingLevel(t);
        if (h > 0) { Md.blocks.renderHeading(ctx, out, t, h, base + i + 1); i++; continue; }
        var hr = Md.scan.hrType(t);
        if (hr !== null) { out.push('<hr class="' + hr + '">\n'); i++; continue; }
        if (Md.scan.isHtmlBlock(t)) {
          if (ctx.isStrict()) {
            ctx.error(base + i + 1, 'strict 模式不支持 HTML', '请改用 div.raw 或模板', t);
            i++;
            continue;
          }
          if (!ctx.rawHtml) {   // CSR 默认：不直出原生 HTML（XSS 面）→ 交给段落按转义文本渲染
            i = Md.blocks.renderParagraph(ctx, out, lines, i, end, base);
            continue;
          }
          out.push(lines[i] + '\n');   // simple + raw-html=on：与构建期一致，原生 HTML 直出
          i++;
          continue;
        }
        i = Md.blocks.renderParagraph(ctx, out, lines, i, end, base);
      }
      return i;
    },

    /** 标题：锚点 id 自动编号（h2 起入目录），空标题仅警告 */
    renderHeading: function (ctx, out, t, level, lineNo) {
      var content = t.slice(level).trim();
      content = content.replace(/[ \t]*#+[ \t]*$/, '');
      if (!content) ctx.warn(lineNo, '标题为空（# 后无内容）');
      ctx.headingSeq++;
      out.push('<h' + level + ' id="' + ctx.anchorId(ctx.headingSeq) + '">'
        + Md.inline.inline(ctx, content, lineNo, 0)
        + '</h' + level + '>\n');
      if (ctx.tocOn && level >= 2) ctx.recordHeading(level, content, ctx.headingSeq);
    },

    /** 兜底：把当前行按普通段落渲染并消费一行（保证索引前进）；列表超限降级也复用它 */
    renderFallbackLine: function (ctx, out, lines, i, end, base) {
      if (i >= end) return i;
      var t = Md.stripIndent(lines[i]);
      out.push('<p>' + Md.inline.inline(ctx, t, base + i + 1, 0) + '</p>\n');
      return i + 1;
    },

    /** 段落：连续非空行合并（行尾两空格 = 硬换行），遇到任何块起点即结束 */
    renderParagraph: function (ctx, out, lines, start, end, base) {
      var sb = '<p>', i = start, first = true, hardPrev = false;
      while (i < end) {
        var l = lines[i];
        if (l.trim() === '') break;
        if (Md.scan.isBlockStartAt(ctx, lines, i, end)) break;
        var line = l.replace(/[ \t]+$/, '');
        if (!first) sb += hardPrev ? '<br>\n' : '\n';
        sb += Md.inline.inline(ctx, line, base + i + 1, 0);
        hardPrev = /[ \t]{2,}$/.test(l);
        first = false;
        i++;
      }
      sb += '</p>\n';
      out.push(sb);
      return i;
    }
  };
})(window);
