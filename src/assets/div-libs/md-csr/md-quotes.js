/* SsvulExtWebCreator · https://github.com/Endingratitan/SsvulExtWebCreator | MPL-2.0: redistributed with this notice and LICENSE/NOTICE | Copyright (c) 2026 Endingratitan */
/* 对应 Java: Integra/MarkdownIntergra/MdQuotes.java（引用块 + callout）
   空行后仍是 `>` 通常续同一引用，但下一段以 callout 标记开头时断成两块（否则第二个 callout 会被吞成字面文本）。
   深度上限 Md.NEST_LIMIT：simple 压平继续渲染（警告一次），strict 报错并丢该行。 */
(function (global) {
  var Md = global.SsvulMd = global.SsvulMd || {};

  var CALLOUT = /^\[!([A-Za-z][A-Za-z0-9-]*)\]\s*(.*)$/;

  Md.quotes = {
    /** 引用块（含 callout）：返回消费到的下一行下标 */
    renderQuote: function (ctx, out, lines, start, end, listDepth, quoteDepth, base) {
      var LIMIT = Md.NEST_LIMIT;
      var flatten = quoteDepth >= LIMIT;
      if (flatten) {
        if (ctx.isStrict()) {
          ctx.error(base + start + 1, '引用嵌套超过 ' + LIMIT + ' 层', '请压缩层级', lines[start].trim());
          return start + 1;
        }
        if (!ctx.quoteFlattenWarned) {
          ctx.warn(base + start + 1, '引用嵌套超过 ' + LIMIT + ' 层（simple：继续渲染，仅提示一次）');
          ctx.quoteFlattenWarned = true;
        }
      }
      var sub = [], i = start;
      while (i < end) {
        var l = lines[i];
        if (l.trim() === '') {
          var j = i;
          while (j < end && lines[j].trim() === '') j++;
          if (j < end && Md.stripIndent(lines[j]).charAt(0) === '>'
              && !Md.quotes.startsCallout(Md.stripIndent(lines[j]))) { sub.push(''); i = j; continue; }
          break;
        }
        var t = Md.stripIndent(l);
        if (t.charAt(0) !== '>') {
          if (ctx.isStrict()) ctx.error(base + i + 1, '引用块内每行需以 > 开头', '请补 > 或以空行结束引用块', t);
          else ctx.warn(base + i + 1, '引用块内缺 > 行（simple：按段落继续）');
          break;
        }
        t = t.slice(1);
        if (t.charAt(0) === ' ') t = t.slice(1);
        sub.push(t);
        i++;
      }
      // callout：只看引用首个非空行（`[!TYPE] 可选标题`；转义 \[!TYPE\] 首字符是 \ 天然不匹配）
      var isCallout = false, calloutType = null, calloutTitle = null;
      var markerLine = base + start + 1;
      if (sub.length) {
        var m = CALLOUT.exec(sub[0]);
        if (m) {
          var t2 = m[1].toLowerCase();
          if (Md.callouts.kebab(t2)) {
            isCallout = true;
            calloutType = t2;
            var rest = m[2].trim();
            calloutTitle = rest === '' ? null : rest;
            sub.shift();   // 标记行不进正文
            if (!Md.callouts.known(t2) && !ctx.calloutWarned[t2]) {
              ctx.calloutWarned[t2] = true;
              ctx.warn(markerLine, '非内置 callout 类型 ' + t2
                + '（已按扩展类型渲染；可在 sets/global/callout/CALLOUT.css 提供样式）');
            }
            if (!sub.length) ctx.warn(markerLine, 'callout 无内容: [' + t2 + ']');
          }
        }
      }
      if (isCallout) {
        ctx.hadCallout = true;
        out.push('<blockquote class="md-callout md-callout-' + calloutType + '" data-callout="' + calloutType + '">\n');
        var text = calloutTitle !== null ? calloutTitle
          : (ctx.calloutTitleOn ? Md.callouts.label(calloutType) : null);
        if (text !== null && text !== '') {
          var isDefault = calloutTitle === null;
          out.push('<p class="md-callout-title' + (isDefault ? ' md-callout-title-default' : '') + '">');
          if (isDefault) {
            out.push('<span class="md-callout-label">' + Md.esc(text) + '</span>');
          } else {
            out.push(Md.inline.inline(ctx, text, markerLine, 0));
          }
          out.push('</p>\n');
        }
      } else {
        out.push('<blockquote>\n');
      }
      var subOut = [];
      Md.blocks.renderBlocks(ctx, subOut, sub, listDepth, flatten ? quoteDepth : quoteDepth + 1, base + start);
      out.push(subOut.join(''));
      out.push('</blockquote>\n');
      return i;
    },

    /** 引用行是否以 callout 标记开头——用于空行断块判定 */
    startsCallout: function (rawLine) {
      if (rawLine.charAt(0) !== '>') return false;
      var t = rawLine.slice(1);
      if (t.charAt(0) === ' ') t = t.slice(1);
      var m = CALLOUT.exec(t);
      return !!m && Md.callouts.kebab(m[1].toLowerCase());
    }
  };
})(window);
