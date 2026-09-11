/* SsvulExtWebCreator · https://github.com/Endingratitan/SsvulExtWebCreator | MPL-2.0: redistributed with this notice and LICENSE/NOTICE | Copyright (c) 2026 Endingratitan */
/* list 官方预设函数：ssvul:json —— **纯 CSR**：客户端 fetch 一个由作者维护的 JSON 文件。
   与 ssvul:inline / ssvul:shared 的区别只有一个：**数据不是构建期生成的**，
   所以你之后只上传/覆盖那个 JSON 文件即可更新列表，**不需要重新构建**。
   参数（本预设自己的约定）：
     file  必填：JSON 文件路径（相对站点根，如 assets/data/blog/index.json；也接受 http(s):// 与 / 开头的绝对路径）
     fields 透传（缺省 date,title,excerpt）
   条目形状与构建期完全一致：{link,type,title,date,excerpt,tags}（数组，或 {"entries":[…]} 两种都收）。
   依赖 fetch：file:// 直开不可用（与 ssvul:shared / search 同限制）；跨域需目标允许 CORS。 */
(function (global) {
  global.SsvulListPresets = global.SsvulListPresets || {};

  global.SsvulListPresets.json = function (root, params) {
    var file = (params && params.file) || '';
    if (!file) throw new Error('ssvul:json 需要在 params 里给 file（JSON 文件路径）');
    var url = /^(https?:)?\/\//.test(file) ? file : global.SsvulList.prefixOf(root) + file;
    var fields = (params && params.fields) || global.SsvulList.defaultFields;
    return fetch(url, { credentials: 'same-origin' }).then(function (r) {
      if (!r.ok) throw new Error(file + ' → http ' + r.status);
      return r.json();
    }).then(function (data) {
      var entries = Array.isArray(data) ? data : ((data && data.entries) || []);
      if (!entries.length && params && params.empty) {
        var li = document.createElement('li');
        li.className = 'list-empty';
        li.textContent = params.empty;      // empty 是内置预设自己的约定参数
        return { items: [li] };
      }
      return {
        items: entries.map(function (e) { return global.SsvulList.item(e, fields, global.SsvulList.prefixOf(root)); })
      };
    });
  };
})(window);
