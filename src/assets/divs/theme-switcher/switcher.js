/* theme-switcher 预设组件：按钮经 SsvulTheme.set 切换主题并持久化到 localStorage。
   params：themes="light,dark"（逗号分隔，缺省 light,dark）；labels="亮,暗"（可选，缺省用主题名）。
   依赖：页面开启 theme 键（自动引用 md-theme.js），或 md-js 手动引入 md/js/md-theme.js。 */
(function () {
  var roots = document.querySelectorAll('.ssvul-theme-switcher');
  for (var i = 0; i < roots.length; i++) (function (root) {
    var themes = (root.getAttribute('data-themes') || 'light,dark').split(',');
    var labels = (root.getAttribute('data-labels') || '').split(',');
    for (var k = 0; k < themes.length; k++) {
      var t = themes[k].trim();
      if (!t) continue;
      var btn = document.createElement('button');
      btn.type = 'button';
      btn.textContent = (labels[k] || '').trim() || t;
      btn.setAttribute('data-theme-btn', t);
      root.appendChild(btn);
    }
    root.addEventListener('click', function (e) {
      var t = e.target && e.target.getAttribute && e.target.getAttribute('data-theme-btn');
      if (t && window.SsvulTheme) SsvulTheme.set(t);
    });
  })(roots[i]);
})();
