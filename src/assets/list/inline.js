/* SsvulExtWebCreator · https://github.com/Endingratitan/SsvulExtWebCreator | MPL-2.0: redistributed with this notice and LICENSE/NOTICE | Copyright (c) 2026 Endingratitan */
/* list 官方预设函数：ssvul:inline —— 读构建期内联的条目（.list-data）。
   构建期已经把条目过滤/排序好了，这里只负责产出条目节点（不排序、不过滤）。
   由构建期按名自动复制并注入（src: "ssvul:inline" 时），作者无需手写 <script>。 */
(function (global) {
  global.SsvulListPresets = global.SsvulListPresets || {};

  global.SsvulListPresets.inline = function (root, params) {
    var node = root.querySelector('.list-data');
    var entries = [];
    if (node) {
      try { entries = JSON.parse(node.textContent) || []; } catch (e) { entries = []; }
    }
    var prefix = global.SsvulList.prefixOf(root);
    var fields = (params && params.fields) || global.SsvulList.defaultFields;
    if (!entries.length && params && params.empty) return { items: [emptyItem(params.empty)] };
    return {
      items: entries.map(function (e) { return global.SsvulList.item(e, fields, prefix); })
    };
  };

  /* empty 是内置预设自己的约定参数（0 条时显示），不是 list 组件的参数 */
  function emptyItem(text) {
    var li = document.createElement('li');
    li.className = 'list-empty';
    li.textContent = text;
    return li;
  }
})(window);
