/* SsvulExtWebCreator · https://github.com/Endingratitan/SsvulExtWebCreator | MPL-2.0: redistributed with this notice and LICENSE/NOTICE | Copyright (c) 2026 Endingratitan */
/* list：列表外壳（薄）。
   职责只有四件：找函数（src）→ 调用 fn(root, params) → 把返回的 {items:[…]} 塞进 .list-items → 派发 ssvullist 事件。
   数据处理（过滤/排序/截断）与渲染都在对接函数里：构建期来源由构建期算好（build:page-index 直接出 HTML），
   客户端来源由预设函数或你自己的函数负责——本组件不做任何排序/过滤。
   失败不阻断：往容器里放一条 .list-error 并 console.warn。 */
(function (global) {
  var DEFAULT_FIELDS = 'date,title,excerpt';

  /* 条目构造助手：与构建期渲染**同一套类名与结构**（不变量；标签间空白不要求一致） */
  function item(entry, fields, prefix) {
    entry = entry || {};
    var on = ',' + (fields || DEFAULT_FIELDS) + ',';
    var li = document.createElement('li');
    li.className = 'list-item';
    if (on.indexOf(',date,') >= 0 && entry.date) {
      var d = document.createElement('span');
      d.className = 'list-date';
      d.textContent = entry.date;
      li.appendChild(d);
    }
    if (on.indexOf(',title,') >= 0) {
      var a = document.createElement('a');
      a.className = 'list-title';
      a.setAttribute('href', (prefix || '') + (entry.link || ''));
      a.textContent = entry.title || entry.link || '';
      li.appendChild(a);
    }
    if (on.indexOf(',tags,') >= 0 && entry.tags) {
      var wrap = document.createElement('span');
      wrap.className = 'list-tags';
      var list = String(entry.tags).split(',');
      for (var i = 0; i < list.length; i++) {
        var t = list[i].replace(/^\s+|\s+$/g, '');
        if (!t) continue;
        var s = document.createElement('span');
        s.className = 'list-tag';
        s.textContent = t;
        wrap.appendChild(s);
      }
      if (wrap.childNodes.length) li.appendChild(wrap);
    }
    if (on.indexOf(',excerpt,') >= 0 && entry.excerpt) {
      var p = document.createElement('p');
      p.className = 'list-excerpt';
      p.textContent = entry.excerpt;
      li.appendChild(p);
    }
    return li;
  }

  function prefixOf(root) {
    var depth = parseInt(root.getAttribute('data-depth') || '0', 10), p = '';
    for (var i = 0; i < depth; i++) p += '../';
    return p;
  }

  /* data-* → params（原样透传给对接函数；data-depth / data-family 是框架属性，不传） */
  function paramsOf(root) {
    var out = {}, attrs = root.attributes;
    for (var i = 0; i < attrs.length; i++) {
      var n = attrs[i].name;
      if (n.indexOf('data-') !== 0) continue;
      if (n === 'data-depth' || n === 'data-family') continue;
      out[n.slice(5)] = attrs[i].value;
    }
    return out;
  }

  /* ssvul:<预设名> 查官方预设表；裸名查 window */
  function lookup(src) {
    if (src.indexOf('ssvul:') === 0) {
      var table = global.SsvulListPresets;
      return table ? table[src.slice('ssvul:'.length)] : undefined;
    }
    return global[src];
  }

  function fail(root, msg) {
    if (global.console && console.warn) console.warn('SsvulList: ' + msg);
    var box = root.querySelector('.list-items');
    if (!box) return;
    var li = document.createElement('li');
    li.className = 'list-error';
    li.textContent = '加载失败';
    box.appendChild(li);
  }

  global.SsvulList = { item: item, prefixOf: prefixOf, defaultFields: DEFAULT_FIELDS };

  global.SsvulDiv.register('list', {
    init: function (doc, root) {
      var src = root.getAttribute('data-src') || '';
      if (!src || src.indexOf('build:') === 0) return;   // 构建期已渲染（这类页面也不注入本文件）
      var fn = lookup(src);
      if (typeof fn !== 'function') { fail(root, '未找到函数: ' + src); return; }
      var params = paramsOf(root);
      Promise.resolve(fn(root, params)).then(function (res) {
        var items = (res && res.items) || [];
        var box = root.querySelector('.list-items');
        if (box) for (var i = 0; i < items.length; i++) {
          var it = items[i];
          if (typeof it === 'string') box.insertAdjacentHTML('beforeend', it);   // 字符串按 HTML 插入（作者自负转义）
          else if (it) box.appendChild(it);
        }
        root.dispatchEvent(new CustomEvent('ssvullist', { detail: { params: params, result: res } }));
      }).catch(function (e) { fail(root, (e && e.message) ? e.message : e); });
    }
  });
})(window);
