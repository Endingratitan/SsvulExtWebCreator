/* SsvulExtWebCreator · https://github.com/Endingratitan/SsvulExtWebCreator | MPL-2.0: redistributed with this notice and LICENSE/NOTICE | Copyright (c) 2026 Endingratitan */
/* 对应 Java: Integra/MarkdownIntergra/MdLists.java（列表：有序/无序/任务、续行子视图、围栏与表格"逃逸"）
   深度上限 Md.NEST_LIMIT：strict 超限报错并降级为普通段落（不丢内容），simple 超限继续渲染并只提示一次。 */
(function (global) {
  var Md = global.SsvulMd = global.SsvulMd || {};

  Md.lists = {
    /** 列表：同层同类型（有序/无序 + 内容列一致）的项归为同一列表 */
    renderList: function (ctx, out, lines, i, end, listDepth, quoteDepth, base) {
      var LIMIT = Md.NEST_LIMIT;
      var levels = listDepth + 1;
      if (levels > LIMIT) {
        if (ctx.isStrict()) {
          ctx.error(base + i + 1, '列表嵌套超过 ' + LIMIT + ' 层', '请压缩列表层级', Md.stripIndent(lines[i]).trim());
          return Md.blocks.renderFallbackLine(ctx, out, lines, i, end, base);   // 降级为普通段落
        }
        if (!ctx.listFlattenWarned) {
          ctx.warn(base + i + 1, '列表嵌套超过 ' + LIMIT + ' 层（simple：继续渲染，仅提示一次）');
          ctx.listFlattenWarned = true;
        }
      }
      var t = Md.stripIndent(lines[i]);
      var ordered = /\d/.test(t.charAt(0));
      var col = Md.scan.listContentCol(t);
      out.push(ordered ? '<ol>\n' : '<ul>\n');
      while (i < end) {
        while (i < end && lines[i].trim() === '') i++;
        if (i >= end) break;
        var cur = Md.stripIndent(lines[i]);
        if (!Md.scan.isListStart(cur)) break;
        if ((/\d/.test(cur.charAt(0)) !== ordered) || Md.scan.listContentCol(cur) !== col) break;
        i = Md.lists.renderListItem(ctx, out, lines, i, end, col, listDepth, quoteDepth, base);
      }
      out.push(ordered ? '</ol>\n' : '</ul>\n');
      return i;
    },

    /** 单个列表项（含任务框与续行子视图） */
    renderListItem: function (ctx, out, lines, i, end, col, listDepth, quoteDepth, base) {
      var t = Md.stripIndent(lines[i]);
      var rawContent = t.slice(Math.min(col, t.length));
      var content = rawContent.trim();
      var task = false, checked = false;
      if (/^\[[ xX]\]([ \t].*)?$/.test(content)) {
        task = true;
        checked = content.charAt(1) === 'x' || content.charAt(1) === 'X';
      }
      var firstRaw = task ? rawContent.replace(/^\[[ xX]\][ \t]*/, '') : rawContent;
      out.push('<li' + (task ? ' class="md-task"' : '') + '>');
      if (task) out.push('<input type="checkbox" disabled' + (checked ? ' checked' : '') + '> ');
      var firstIdx = i;
      i++;
      var sub = [firstRaw];
      while (i < end) {
        var l = lines[i];
        if (l.trim() === '') {
          var j = i;
          while (j < end && lines[j].trim() === '') j++;
          if (j < end && Md.leadingSpaces(lines[j]) >= col) {
            for (var k = i; k < j; k++) sub.push('');   // 逐行加入保行号
            i = j;
            continue;
          }
          break;
        }
        if (Md.leadingSpaces(l) < col) break;
        var stripped = l.slice(Math.min(col, l.length));
        var inner = Md.stripIndent(stripped);
        if (Md.scan.isFence(inner) || Md.tables.isTableAhead(lines, i, end)) break;   // 逃逸：交给主循环
        sub.push(stripped);   // 保留相对缩进，嵌套层级判定不被抹平
        i++;
      }
      if (sub.length) {
        out.push('\n');
        var subOut = [];
        Md.blocks.renderBlocks(ctx, subOut, sub, listDepth + 1, quoteDepth, base + firstIdx);
        out.push(subOut.join(''));
      }
      out.push('</li>\n');
      return i;
    }
  };
})(window);
