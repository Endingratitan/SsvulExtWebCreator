/* SsvulExtWebCreator · https://github.com/Endingratitan/SsvulExtWebCreator | MPL-2.0: redistributed with this notice and LICENSE/NOTICE | Copyright (c) 2026 Endingratitan */
/* 对应 Java: Integra/MarkdownIntergra/Callouts.java（callout 类型表，无状态）
   16 个内置类型的默认中文标签；非内置类型不是错误——是扩展逃生舱：
   标题回落"类型名首字母大写"（release-note → Release Note），配色由站点 CALLOUT.css 提供。
   本文件必须与 Java 侧逐条一致（两侧标签差异会被 md-parity 的金标抓住）。 */
(function (global) {
  var Md = global.SsvulMd = global.SsvulMd || {};

  var BUILTIN = {
    note: '注意', tip: '提示', important: '重要', warning: '警告',
    caution: '小心', info: '信息', success: '成功', question: '疑问',
    example: '示例', quote: '引用', abstract: '摘要', todo: '待办',
    danger: '危险', failure: '失败', bug: '缺陷', debug: '调试'
  };

  Md.callouts = {
    BUILTIN: BUILTIN,
    /** 类型名是否合法（kebab-case；调用方已转小写） */
    kebab: function (type) { return !!type && /^[a-z0-9]+(-[a-z0-9]+)*$/.test(type); },
    known: function (type) { return Object.prototype.hasOwnProperty.call(BUILTIN, type); },
    /** 标题：内置取中文标签；扩展类型回落 humanize */
    label: function (type) {
      return Md.callouts.known(type) ? BUILTIN[type] : Md.callouts.humanize(type);
    },
    humanize: function (type) {
      var parts = String(type).split('-'), out = [];
      for (var i = 0; i < parts.length; i++) {
        if (!parts[i]) continue;
        out.push(parts[i].charAt(0).toUpperCase() + parts[i].slice(1));
      }
      return out.join(' ');
    }
  };
})(window);
