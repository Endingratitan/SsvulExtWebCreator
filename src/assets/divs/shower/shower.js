/* SsvulExtWebCreator · https://github.com/Endingratitan/SsvulExtWebCreator | MPL-2.0: redistributed with this notice and LICENSE/NOTICE | Copyright (c) 2026 Endingratitan */
/* shower：数据驱动列表。
   内联模式（默认）：读取构建期内联 data-json（.shower-data；零请求、file:// 可用、离线可用）。
   共享模式（params shared:true）：fetch 按目录分片的共享索引 assets/data/shower/<dir>.json（dir 空 → index.json），
   按 data-pattern/count/order/seed 客户端过滤/排序/截断——同目录多 shower 共用一份字节，且只拉本目录数据。
   ⚠ 共享模式依赖 fetch：file:// 直开预览不可用（与 search 相同限制），仅 http(s) 生效。
   自由接管：window.SsvulShower = { sort(list, params), render(entry, root, prefix), after(root) }；
   data-manual="true" 内置渲染完全让位（共享模式下过滤结果挂在 root.__ssvulShowerData = {entries, total}）。 */
(function (global) {
  global.SsvulShower = {
    sort: function (list) { return list; },
    render: function (entry, root, prefix) {
      var li = document.createElement('li');
      var a = document.createElement('a');
      a.href = prefix + (entry.link || '');
      a.textContent = entry.title || entry.link;
      li.appendChild(a);
      if (entry.date) {
        var d = document.createElement('span');
        d.className = 'shower-date';
        d.textContent = ' ' + entry.date;
        li.appendChild(d);
      }
      if (entry.excerpt) {
        var p = document.createElement('p');
        p.className = 'shower-excerpt';
        p.textContent = entry.excerpt;
        li.appendChild(p);
      }
      return li;
    },
    after: function () {}
  };

  function prefixOf(depth) {
    var p = '';
    for (var i = 0; i < depth; i++) p += '../';
    return p;
  }

  /* 确定性伪随机（seed 可复现 random 顺序；无 seed 用时间） */
  function mulberry32(seed) {
    var a = seed >>> 0;
    return function () {
      a |= 0; a = (a + 0x6D2B79F5) | 0;
      var t = Math.imul(a ^ (a >>> 15), 1 | a);
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }

  /* 与构建期内联数据同语义：dir 前缀过滤 → pattern 类型过滤 → order 排序（date 降序/name 升序/random seed）→ count 截断 */
  function filterSort(entries, params) {
    var pfx = params.dir ? params.dir + '/' : '';
    var out = [];
    for (var k = 0; k < entries.length; k++) {
      var e = entries[k];
      var link = e.link || '';
      if (pfx && link !== pfx && link.indexOf(pfx) !== 0) continue;
      if (params.pattern === '*.md' && e.type !== 'md') continue;
      if (params.pattern === '*.json' && e.type !== 'json') continue;
      out.push(e);
    }
    var total = out.length;
    if (params.order === 'name') {
      // 码点序（与构建期 Java String 比较一致；localeCompare 在 zh 环境按拼音排序会与构建期不一致）
      out.sort(function (a, b) {
        var x = a.title || '', y = b.title || '';
        return x < y ? -1 : (x > y ? 1 : 0);
      });
    } else if (params.order === 'random') {
      var rnd = mulberry32(params.seed >= 0 ? params.seed : Date.now() & 0xffffffff);
      for (var i = out.length - 1; i > 0; i--) {
        var j = Math.floor(rnd() * (i + 1));
        var t = out[i]; out[i] = out[j]; out[j] = t;
      }
    } else {
      // date 降序（yyyy-mm-dd 为 ASCII，码点比较即字典序；与构建期 reverseOrder 一致）
      out.sort(function (a, b) {
        var x = a.date || '', y = b.date || '';
        return x > y ? -1 : (x < y ? 1 : 0);
      });
    }
    if (out.length > params.count) out = out.slice(0, params.count);
    return { entries: out, total: total };
  }

  function renderEntries(doc, root, list, entries, prefix) {
    for (var k = 0; k < entries.length; k++) {
      list.appendChild(window.SsvulShower.render(entries[k], root, prefix));
    }
    window.SsvulShower.after(root);
  }

  window.SsvulDiv.register('shower', {
    init: function (doc, root) {
      var depth = parseInt(root.getAttribute('data-depth') || '0', 10);
      var prefix = prefixOf(depth);
      var params = {
        dir: root.getAttribute('data-dir') || '',
        pattern: root.getAttribute('data-pattern') || '*.md',
        count: parseInt(root.getAttribute('data-count') || '5', 10),
        order: root.getAttribute('data-order') || 'date',
        seed: parseInt(root.getAttribute('data-seed') || '-1', 10),
        fields: root.getAttribute('data-fields') || 'title,date,excerpt'
      };
      if (root.getAttribute('data-shared') === 'true') {
        // 按目录分片：dir="pages/blog" → assets/data/shower/pages/blog.json；dir 空 → index.json（全站）
        var cfg = params.dir ? params.dir + '.json' : 'index.json';
        fetch(prefix + 'assets/data/shower/' + cfg)
          .then(function (r) { if (!r.ok) throw new Error('http ' + r.status); return r.json(); })
          .then(function (entries) {
            var res = filterSort(entries, params);
            res.entries = window.SsvulShower.sort(res.entries, params);
            root.__ssvulShowerData = res;   // manual 模式经此取数据
            if (root.getAttribute('data-manual') === 'true') return;
            var list = root.querySelector('.shower-list');
            if (!list) return;
            renderEntries(doc, root, list, res.entries, prefix);
          })
          .catch(function (e) {
            if (global.console && console.warn)
              console.warn('SsvulShower 共享索引加载失败（file:// 下 fetch 不可用）: ' + e);
          });
        return;
      }
      var node = root.querySelector('.shower-data');
      if (!node) return;
      var data;
      try { data = JSON.parse(node.textContent); } catch (e) { return; }
      if (root.getAttribute('data-manual') === 'true') return;   // 自由接管
      var list = root.querySelector('.shower-list');
      if (!list) return;
      var entries = window.SsvulShower.sort(data.entries || [], data.params || {});
      renderEntries(doc, root, list, entries, prefix);
    }
  });
})(window);
