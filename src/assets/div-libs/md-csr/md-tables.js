/* SsvulExtWebCreator · https://github.com/Endingratitan/SsvulExtWebCreator | MPL-2.0: redistributed with this notice and LICENSE/NOTICE | Copyright (c) 2026 Endingratitan */
/* 对应 Java: Integra/MarkdownIntergra/MdTables.java（表格：表头 + 分隔行 + 数据行）
   注：out 是**分片数组**（对应 Java 的 StringBuilder），返回值为"消费到的下一行下标"。 */
(function (global) {
  var Md = global.SsvulMd = global.SsvulMd || {};

  Md.tables = {
    /** 表格：返回消费到的下一行下标 */
    renderTable: function (ctx, out, lines, i, end, base) {
      var header = Md.tables.tableCells(lines[i]);
      var delim = Md.tables.tableCells(lines[i + 1]);
      if (delim.length !== header.length && ctx.isStrict()) {
        ctx.error(base + i + 2, '表格分隔行列数与表头不一致', null, lines[i + 1]);
      }
      var align = [], k;
      for (k = 0; k < header.length && k < delim.length; k++) {
        var d = delim[k].trim();
        var l = d.indexOf(':') === 0, r = d.charAt(d.length - 1) === ':';
        align[k] = (l && r) ? 'md-al-c' : l ? 'md-al-l' : r ? 'md-al-r' : 'md-al-l';
      }
      var sb = '<div class="md-table-wrap">\n<table class="md-table">\n<thead>\n<tr>\n';
      for (k = 0; k < header.length; k++) {
        sb += '<th' + Md.tables.alignCls(align[k]) + '>'
            + Md.inline.inline(ctx, header[k].trim(), base + i + 1, 0) + '</th>\n';
      }
      sb += '</tr>\n</thead>\n<tbody>\n';
      var j = i + 2;
      while (j < end && lines[j].trim() !== '' && lines[j].indexOf('|') >= 0) {
        var cells = Md.tables.tableCells(lines[j]);
        if (cells.length !== header.length && ctx.isStrict()) {
          ctx.error(base + j + 1, '表格行列数与表头不一致（' + cells.length + ' vs ' + header.length + '）', null, lines[j]);
        }
        sb += '<tr>\n';
        var m = Math.min(cells.length, header.length);
        for (k = 0; k < m; k++) {
          sb += '<td' + Md.tables.alignCls(align[k]) + '>'
              + Md.inline.inline(ctx, cells[k].trim(), base + j + 1, 0) + '</td>\n';
        }
        sb += '</tr>\n';
        j++;
      }
      sb += '</tbody>\n</table>\n</div>\n';
      out.push(sb);
      return j;
    },

    /* ---- 判定与切分（同时供段落/列表的"块起点"判定复用） ---- */

    isTableAhead: function (lines, i, end) {
      if (i + 1 >= end) return false;
      return lines[i].trim().indexOf('|') >= 0 && Md.tables.isDelimRow(lines[i + 1]);
    },

    isDelimRow: function (line) {
      var t = line.trim();
      if (!t || t.indexOf('|') < 0 || t.indexOf('-') < 0) return false;
      var cells = Md.tables.tableCells(t);
      for (var k = 0; k < cells.length; k++) {
        if (!/^:?-+:?$/.test(cells[k].trim())) return false;
      }
      return true;
    },

    /** 拆分表格行，剥离首尾管道产生的空单元格 */
    tableCells: function (line) {
      var cells = Md.tables.splitCells(line);
      if (line.charAt(0) === '|' && cells.length) cells.shift();
      if (line.charAt(line.length - 1) === '|' && cells.length) cells.pop();
      return cells;
    },

    /** 按未转义的 `|` 切分（`\|` 视为字面量） */
    splitCells: function (line) {
      var cells = [], cur = '', esc = false;
      for (var k = 0; k < line.length; k++) {
        var ch = line.charAt(k);
        if (esc) { cur += ch; esc = false; continue; }
        if (ch === '\\') { esc = true; continue; }
        if (ch === '|') { cells.push(cur); cur = ''; continue; }
        cur += ch;
      }
      cells.push(cur);
      return cells;
    },

    alignCls: function (a) { return a === 'md-al-l' ? '' : ' class="' + a + '"'; }
  };
})(window);
