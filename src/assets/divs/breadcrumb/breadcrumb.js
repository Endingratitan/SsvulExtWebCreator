/* SsvulExtWebCreator · https://github.com/Endingratitan/SsvulExtWebCreator | MPL-2.0: redistributed with this notice and LICENSE/NOTICE | Copyright (c) 2026 Endingratitan */
/* breadcrumb：location.pathname 逐段生成逐级链接（末段为当前页文本）。 */
window.SsvulDiv.register('breadcrumb', {
  init: function (doc, root) {
    var depth = parseInt(root.getAttribute('data-depth') || '0', 10);
    var prefix = '';
    for (var i = 0; i < depth; i++) prefix += '../';
    var sep = root.getAttribute('data-separator') || '/';
    var label = root.getAttribute('data-root-label') || 'Home';
    var path = doc.location.pathname.replace(/\/index\.html?$/, '');
    var segs = path.split('/').filter(Boolean);
    var acc = '';
    var home = doc.createElement('a');
    home.href = prefix;
    home.textContent = label;
    root.appendChild(home);
    for (var k = 0; k < segs.length; k++) {
      var s = doc.createElement('span');
      s.className = 'crumb-sep';
      s.textContent = ' ' + sep + ' ';
      root.appendChild(s);
      if (k === segs.length - 1) {
        var cur = doc.createElement('span');
        cur.className = 'crumb-current';
        cur.textContent = decodeURIComponent(segs[k]);
        root.appendChild(cur);
      } else {
        acc += '../';
        var a = doc.createElement('a');
        a.href = prefix + acc;
        a.textContent = decodeURIComponent(segs[k]);
        root.appendChild(a);
      }
    }
  }
});
