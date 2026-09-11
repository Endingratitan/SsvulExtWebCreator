/* SsvulExtWebCreator · https://github.com/Endingratitan/SsvulExtWebCreator | MPL-2.0: redistributed with this notice and LICENSE/NOTICE | Copyright (c) 2026 Endingratitan */
/* 对应 Java: Integra/MarkdownIntergra/MdBlockScan.java（块起点判定，不产出 HTML）
   注意：同级文件**不在加载时捕获**彼此的命名空间（文件名排序决定加载顺序），一律在调用时取 Md.xxx。 */
(function (global) {
  var Md = global.SsvulMd = global.SsvulMd || {};

  Md.scan = {
    /** 段落是否在此行结束（= 该行是某个块的起点） */
    isBlockStartAt: function (ctx, lines, i, end) {
      if (lines === ctx.srcLines && ctx.defLineIdx[i] !== undefined) return true;
      var t = Md.stripIndent(lines[i]);
      if (!t) return true;
      return Md.scan.isFence(t) || t.charAt(0) === '>' || Md.scan.isListStart(t)
        || Md.tables.isTableAhead(lines, i, end) || Md.scan.isMathOpen(t) || Md.scan.isMathOpenBracket(t)
        || Md.scan.headingLevel(t) > 0 || Md.scan.hrType(t) !== null || Md.scan.isHtmlBlock(t);
    },

    isFence: function (t) { return /^(`{3,}|~{3,}).*/.test(t); },
    isMathOpen: function (t) { return t.indexOf('$$') === 0; },
    isMathOpenBracket: function (t) { return t.indexOf('\\[') === 0; },

    /** 分割线特性：--- / +++ / ***（与 ___ 同档）各自独立 class；simple 与 strict 都放行 */
    hrType: function (t) {
      if (/^\*{3,}[ \t]*$/.test(t) || /^_{3,}[ \t]*$/.test(t)) return 'md-hr-star';
      if (/^-{3,}[ \t]*$/.test(t)) return 'md-hr-dash';
      if (/^\+{3,}[ \t]*$/.test(t)) return 'md-hr-plus';
      return null;
    },

    isHtmlBlock: function (t) {
      if (t.charAt(0) !== '<' || t.length < 2) return false;
      var c = t.charAt(1);
      return Md.isAlnum(c) || c === '/' || c === '!';
    },

    headingLevel: function (t) {
      if (!t || t.charAt(0) !== '#') return 0;
      var k = 0;
      while (k < t.length && t.charAt(k) === '#') k++;
      if (k > 6) return 0;
      if (k < t.length && t.charAt(k) !== ' ' && t.charAt(k) !== '\t') return 0;   // 标准：须空格
      return k;
    },

    isListStart: function (t) { return /^[-*+]([ \t].*)?$/.test(t) || /^\d+[.)]([ \t].*)?$/.test(t); },

    /** 列表项内容起始列（`- ` = 2，`1. ` = 3，`12) ` = 4）——嵌套层级与续行归属靠它 */
    listContentCol: function (t) {
      var k = 0;
      if (/\d/.test(t.charAt(0))) {
        while (k < t.length && /\d/.test(t.charAt(k))) k++;
        if (k < t.length && (t.charAt(k) === '.' || t.charAt(k) === ')')) k++;
      } else {
        k = 1;
      }
      while (k < t.length && (t.charAt(k) === ' ' || t.charAt(k) === '\t')) k++;
      return k;
    }
  };
})(window);
