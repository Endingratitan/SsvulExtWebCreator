/* SsvulExtWebCreator 主题管理预设：localStorage 持久化 + data-theme 切换 + themechange 事件 */
(function (global) {
  var KEY = 'ssvul-theme';
  function set(t) {
    localStorage.setItem(KEY, t);
    document.documentElement.setAttribute('data-theme', t);
    global.dispatchEvent(new CustomEvent('themechange', { detail: { theme: t } }));
  }
  global.SsvulTheme = {
    get: function () { return document.documentElement.getAttribute('data-theme') || 'light'; },
    set: set,
    toggle: function () { set(this.get() === 'dark' ? 'light' : 'dark'); }
  };
})(window);
