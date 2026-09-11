/* SsvulExtWebCreator · https://github.com/Endingratitan/SsvulExtWebCreator | MPL-2.0: redistributed with this notice and LICENSE/NOTICE | Copyright (c) 2026 Endingratitan */
/* list 官方预设函数：ssvul:shared —— fetch 按目录分片 assets/data/list/<dir>.json（dir 空 → index.json）。
   分片里的条目已由构建期排好序，这里只负责产出条目节点；依赖 fetch，file:// 直开不可用（仅 http(s)）。
   由构建期按名自动复制并注入（src: "ssvul:shared" 时）。 */
(function (global) {
  global.SsvulListPresets = global.SsvulListPresets || {};

  global.SsvulListPresets.shared = function (root, params) {
    var dir = (params && params.dir) || '';
    var cfg = dir ? dir + '.json' : 'index.json';
    var prefix = global.SsvulList.prefixOf(root);
    var fields = (params && params.fields) || global.SsvulList.defaultFields;
    return fetch(prefix + 'assets/data/list/' + cfg).then(function (r) {
      if (!r.ok) throw new Error('http ' + r.status);
      return r.json();
    }).then(function (entries) {
      entries = entries || [];
      if (!entries.length && params && params.empty) {
        var li = document.createElement('li');
        li.className = 'list-empty';
        li.textContent = params.empty;      // empty 是内置预设自己的约定参数，不是 list 组件的参数
        return { items: [li] };
      }
      return {
        items: entries.map(function (e) { return global.SsvulList.item(e, fields, prefix); })
      };
    });
  };
})(window);
