/* SsvulExtWebCreator · https://github.com/Endingratitan/SsvulExtWebCreator | MPL-2.0: redistributed with this notice and LICENSE/NOTICE | Copyright (c) 2026 Endingratitan */
/* 对应 Java: Integra/MarkdownIntergra/MdFootnotes.java（脚注子系统）
   流程与 Java 逐字同序：① 扫描顶层定义行（掩码记录，不删行以保行号）→ ② 引用登记（正文埋 \u0000FN<seq>\u0000）
   → ③ 编号分配（显式配对 → 懒惰/空位池按顺序配 → 未定义引用报错）→ ④ 占位符替换 → ⑤ end 模式生成文末脚注区。
   与 Java 的两处实现差异（行为等价）：
     · 用**数组**保存 explicitDefs / allDefs（JS 对象的整数键会按升序迭代，会破坏 LinkedHashMap 的插入序 →
       会影响未配对定义的配对顺序，故必须用数组 + 查找函数）；
     · finalizeFootnotes 返回**新的正文串**并把脚注区写入 ctx.footnotes（JS 字符串不可变，替代 Java 的 StringBuilder 原地改）。 */
(function (global) {
  var Md = global.SsvulMd = global.SsvulMd || {};

  var FN_MARK = '\u0000FN';      // 引用占位符（正文内）
  var FND_MARK = '\u0000FXD';    // 定义占位符（inline 模式；不得以 FN_MARK 为前缀）

  function has(obj, k) { return Object.prototype.hasOwnProperty.call(obj, k); }

  function findExplicit(fn, num) {
    for (var i = 0; i < fn.explicitDefs.length; i++) if (fn.explicitDefs[i].num === num) return fn.explicitDefs[i];
    return null;
  }

  function nextFree(reserved) {
    var n = 1;
    while (has(reserved, n)) n++;
    return n;
  }

  Md.footnotes = {
    /** Phase 0：扫描顶层定义行；重复定义/空内容/编号 0/超长编号报错（编号 >6 位曾直接 parseInt 裸崩） */
    parseFootnotes: function (ctx, lines) {
      var fn = ctx.fn = {
        explicitDefs: [],      // [{num, lineNo, content, dispNum}]（保插入序）
        lazyDefs: [],
        allDefs: [],
        refs: []
      };
      var exp = /^\[\^(\d+)\]:\s*(.*)$/;
      var lazy = /^\[\.\^\]:\s*(.*)$/;
      for (var idx = 0; idx < lines.length; idx++) {
        var t = Md.stripIndent(lines[idx]);
        var m = exp.exec(t), content = null, d = null;
        if (m) {
          if (m[1].length > 6) {
            ctx.error(idx + 1, '脚注编号过大（最多 6 位）: [^' + m[1] + ']', '改用 1~999999 的编号', t);
            continue;
          }
          var num = parseInt(m[1], 10);
          content = m[2].trim();
          if (num === 0) { ctx.error(idx + 1, '脚注编号必须为正整数: [^0]', null, t); continue; }
          if (!content) { ctx.error(idx + 1, '脚注定义内容为空', null, t); continue; }
          if (findExplicit(fn, num)) { ctx.error(idx + 1, '脚注编号重复定义: [^' + num + ']', null, t); continue; }
          d = { num: num, lineNo: idx + 1, content: content, dispNum: null };
          fn.explicitDefs.push(d);
          fn.allDefs.push(d);
          ctx.defLineIdx[idx] = fn.allDefs.length - 1;
        } else {
          var m2 = lazy.exec(t);
          if (m2) {
            content = m2[1].trim();
            if (!content) { ctx.error(idx + 1, '脚注定义内容为空', null, t); continue; }
            d = { num: null, lineNo: idx + 1, content: content, dispNum: null };
            fn.lazyDefs.push(d);
            fn.allDefs.push(d);
            ctx.defLineIdx[idx] = fn.allDefs.length - 1;
          }
        }
      }
    },

    /** 行内解析器回调：登记引用并埋占位符（超长编号按字面输出 + 报错） */
    registerRef: function (ctx, label, lineNo, out) {
      if (label !== '.' && label.length > 6) {
        ctx.error(lineNo, '脚注编号过大（最多 6 位）: [^' + label + ']', '改用 1~999999 的编号', label);
        out.push('[^' + label + ']');
        return;
      }
      var r = { label: label, lineNo: lineNo, seq: ctx.fn.refs.length, num: -1, def: null };
      ctx.fn.refs.push(r);
      out.push(FN_MARK + r.seq + '\u0000');
    },

    /** inline 模式：在定义行原位置埋占位符，随后替换为脚注块 */
    emitInlineDefMarker: function (ctx, out, defIdx) { out.push(FND_MARK + defIdx + '\u0000'); },

    /** Phase 3：编号分配 → 替换正文占位符 → 写 ctx.footnotes（end 模式）→ 返回处理后的正文 */
    finalizeFootnotes: function (ctx, content) {
      var fn = ctx.fn;
      if (!fn) { ctx.footnotes = ''; return content; }
      var refs = fn.refs, i, j;

      // 1) 显式配对（未配对显式引用保留用户编号；[^.] 自动补空位）
      var reserved = {};
      for (i = 0; i < refs.length; i++) if (refs[i].label !== '.') reserved[parseInt(refs[i].label, 10)] = true;
      var unmatchedRefs = [];
      for (i = 0; i < refs.length; i++) {
        var r = refs[i];
        if (r.label !== '.') {
          var n = parseInt(r.label, 10);
          var d = findExplicit(fn, n);
          if (d) { r.num = n; r.def = d; d.dispNum = n; }   // 回填编号（inline 模式就地渲染需要）
          else unmatchedRefs.push(r);
        } else {
          unmatchedRefs.push(r);
        }
      }
      var referencedNums = {};
      for (i = 0; i < refs.length; i++) if (refs[i].def) referencedNums[refs[i].num] = true;
      var unmatchedDefs = [];
      for (i = 0; i < fn.explicitDefs.length; i++) {
        if (!has(referencedNums, fn.explicitDefs[i].num)) unmatchedDefs.push(fn.explicitDefs[i]);
      }
      for (i = 0; i < fn.lazyDefs.length; i++) unmatchedDefs.push(fn.lazyDefs[i]);

      // 2) 懒惰/空位池按顺序配对
      var k = 0;
      for (i = 0; i < unmatchedRefs.length; i++) {
        var ur = unmatchedRefs[i];
        if (k < unmatchedDefs.length) {
          var nn = ur.label === '.' ? nextFree(reserved) : parseInt(ur.label, 10);
          reserved[nn] = true;
          ur.num = nn;
          ur.def = unmatchedDefs[k];
          unmatchedDefs[k].dispNum = nn;
          k++;
        } else {
          ctx.error(ur.lineNo, '脚注引用未定义: [^' + ur.label + ']',
            '补写定义行（如 [^' + ur.label + ']: 内容）或移除引用', null);
          ur.num = -1;
        }
      }
      for (; k < unmatchedDefs.length; k++) {
        var dd = unmatchedDefs[k];
        var msg = '脚注定义未被引用（多余第 ' + (k - unmatchedRefs.length + 1) + ' 个）: ' + dd.content;
        if (ctx.isStrict()) ctx.error(dd.lineNo, msg, '请删除或补引用', dd.content);
        else ctx.warn(dd.lineNo, msg);
        dd.dispNum = -1;
      }

      // 3) 替换引用占位符（同编号多次引用 → fnref-N-2、fnref-N-3…）
      var occ = {}, idx;
      while ((idx = content.indexOf(FN_MARK)) >= 0) {
        var end = content.indexOf('\u0000', idx + FN_MARK.length);
        var seq = parseInt(content.slice(idx + FN_MARK.length, end), 10);
        var rr = refs[seq], html;
        if (rr.num < 0) {
          html = '[^' + rr.label + ']';            // 未定义 → 原样字面
        } else {
          occ[rr.num] = (occ[rr.num] || 0) + 1;
          var c = occ[rr.num];
          var p = ctx.anchorPrefix;                // div 前缀：多 md div 页面脚注 id 不重复
          var id = c === 1 ? p + 'fnref-' + rr.num : p + 'fnref-' + rr.num + '-' + c;
          html = '<sup id="' + id + '"><a href="#' + p + 'fn-' + rr.num + '">' + rr.num + '</a></sup>';
        }
        content = content.slice(0, idx) + html + content.slice(end + 1);
      }

      // 4) 替换定义占位符（inline 模式）
      while ((idx = content.indexOf(FND_MARK)) >= 0) {
        var end2 = content.indexOf('\u0000', idx + FND_MARK.length);
        var di = parseInt(content.slice(idx + FND_MARK.length, end2), 10);
        var dfn = fn.allDefs[di], html2;
        if (dfn.dispNum !== null && dfn.dispNum > 0) {
          var p2 = ctx.anchorPrefix;
          html2 = '<div class="md-footnote" id="' + p2 + 'fn-' + dfn.dispNum + '">' + renderDefContent(ctx, dfn)
            + ' <a href="#' + p2 + 'fnref-' + dfn.dispNum + '" class="md-fn-back">↩</a></div>';
        } else {
          html2 = '<div class="md-footnote">' + renderDefContent(ctx, dfn) + '</div>';
        }
        content = content.slice(0, idx) + html2 + content.slice(end2 + 1);
      }

      // 5) end 模式脚注区（按编号升序；同编号只出现一次）
      if (ctx.inlineFn) { ctx.footnotes = ''; return content; }
      var byNum = [], seen = {};
      for (i = 0; i < refs.length; i++) {
        var rf = refs[i];
        if (rf.num > 0 && rf.def && !has(seen, rf.num)) { seen[rf.num] = true; byNum.push({ num: rf.num, def: rf.def }); }
      }
      if (!byNum.length) { ctx.footnotes = ''; return content; }
      byNum.sort(function (a, b) { return a.num - b.num; });
      var fnHtml = '<div class="md-footnotes">\n<hr>\n<ol>\n';
      for (j = 0; j < byNum.length; j++) {
        var e = byNum[j], pp = ctx.anchorPrefix;
        fnHtml += '<li id="' + pp + 'fn-' + e.num + '">' + renderDefContent(ctx, e.def)
          + ' <a href="#' + pp + 'fnref-' + e.num + '" class="md-fn-back">↩</a></li>\n';
      }
      fnHtml += '</ol>\n</div>\n';
      ctx.footnotes = fnHtml;
      return content;
    }
  };

  /** 定义内容按行内规则渲染（渲染期间 inFootDef=true → 定义内的 [^x] 按字面） */
  function renderDefContent(ctx, d) {
    ctx.inFootDef = true;
    try {
      return Md.inline.inline(ctx, d.content, d.lineNo, 0);
    } finally {
      ctx.inFootDef = false;
    }
  }
})(window);
