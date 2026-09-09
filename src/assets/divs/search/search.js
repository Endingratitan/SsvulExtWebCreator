/* SsvulExtWebCreator · https://github.com/Endingratitan/SsvulExtWebCreator | MPL-2.0: redistributed with this notice and LICENSE/NOTICE | Copyright (c) 2026 Endingratitan */
/* search：读取构建期搜索索引（fetch assets/data/search-index.json，路径由 div 根的 data-depth 决定）。 */
window.SsvulDiv.register('search', {
  init: function (doc, root) {
    var depth = parseInt(root.getAttribute('data-depth') || '0', 10);
    var prefix = '';
    for (var i = 0; i < depth; i++) prefix += '../';
    var limit = parseInt(root.getAttribute('data-limit') || '10', 10);
    var input = root.querySelector('.search-input');
    var list = root.querySelector('.search-results');
    var index = null;
    fetch(prefix + 'assets/data/search-index.json').then(function (r) {
      return r.json();
    }).then(function (data) {
      index = data;
    }).catch(function () {});
    function doSearch(q) {
      list.innerHTML = '';
      if (!index || !q) { list.hidden = true; return; }
      var ql = q.toLowerCase();
      var hits = [];
      for (var i = 0; i < index.length; i++) {
        var e = index[i];
        if ((e.title || '').toLowerCase().indexOf(ql) >= 0 || (e.text || '').toLowerCase().indexOf(ql) >= 0) hits.push(e);
        if (hits.length >= limit) break;
      }
      if (!hits.length) { list.hidden = true; return; }
      list.hidden = false;
      for (var k = 0; k < hits.length; k++) {
        var li = doc.createElement('li');
        var a = doc.createElement('a');
        a.href = prefix + (hits[k].link || '');
        a.textContent = hits[k].title || hits[k].link;
        li.appendChild(a);
        list.appendChild(li);
      }
    }
    input.addEventListener('input', function () { doSearch(input.value.trim()); });
  }
});
