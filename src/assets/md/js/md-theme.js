/* SsvulExtWebCreator · https://github.com/Endingratitan/SsvulExtWebCreator | MPL-2.0: redistributed with this notice and LICENSE/NOTICE | Copyright (c) 2026 Endingratitan */
/* SsvulExtWebCreator 主题管理预设：localStorage 持久化 + data-theme 切换 + themechange 事件 +
   访客调色板（ssvul-palette：CSS 变量覆盖表，优先于主题 css 文件）+ palettechange 事件。
   契约见 README「接口契约」。 */
(function (global) {
  var KEY = 'ssvul-theme';
  var PALETTE_KEY = 'ssvul-palette';
  function applyPalette(p) {
    for (var k in p) document.documentElement.style.setProperty(k, p[k]);
  }
  function set(t) {
    localStorage.setItem(KEY, t);
    document.documentElement.setAttribute('data-theme', t);
    global.dispatchEvent(new CustomEvent('themechange', { detail: { theme: t } }));
  }
  global.SsvulTheme = {
    get: function () { return document.documentElement.getAttribute('data-theme') || 'light'; },
    set: set,
    toggle: function () { set(this.get() === 'dark' ? 'light' : 'dark'); },
    setPalette: function (obj) {
      localStorage.setItem(PALETTE_KEY, JSON.stringify(obj));
      applyPalette(obj);
      global.dispatchEvent(new CustomEvent('palettechange', { detail: { palette: obj } }));
    },
    clearPalette: function () {
      var raw = localStorage.getItem(PALETTE_KEY);
      if (raw) {
        try {
          var p = JSON.parse(raw), out = {};
          for (var k in p) out[k] = '';   // 置空 = 回落到主题 css 文件值
          applyPalette(out);
        } catch (e) {}
      }
      localStorage.removeItem(PALETTE_KEY);
      global.dispatchEvent(new CustomEvent('palettechange', { detail: { palette: null } }));
    }
  };
})(window);
