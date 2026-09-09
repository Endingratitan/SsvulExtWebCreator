/* SsvulExtWebCreator · https://github.com/Endingratitan/SsvulExtWebCreator | MPL-2.0: redistributed with this notice and LICENSE/NOTICE | Copyright (c) 2026 Endingratitan */
/* SsvulDiv 运行时（div 继承生命周期）：约定 div js "只注册不执行"。
   契约：window.SsvulDiv.register(name, hooks)（同名 Object.assign 合并覆写；null=清除）
         window.SsvulDiv.super(name, hook)（取最近一次合并前的父钩子——子调父模式）
         window.SsvulDiv.initAll(root)（DOMContentLoaded 后统一执行各 init，幂等）
   脚本**前置**于任何聚合产物（div js 顶层即调 register，运行时必须先定义），多次注入无害（幂等标志）。 */
(function (global) {
  if (global.__ssvulDivInit) return;   // 幂等：多副本注入只生效一次
  global.__ssvulDivInit = true;
  var reg = {}, parent = {}, initDone = false;
  global.SsvulDiv = {
    register: function (name, hooks) {
      if (hooks == null) { delete reg[name]; return; }
      parent[name] = reg[name] || {};
      var merged = {};
      for (var k in parent[name]) merged[k] = parent[name][k];
      for (var k in hooks) merged[k] = hooks[k];
      reg[name] = merged;
    },
    'super': function (name, hook) {
      var p = parent[name];
      return p ? p[hook] : undefined;
    },
    initAll: function (root) {
      if (initDone) return;
      initDone = true;
      var scope = root || document;
      for (var n in reg) {
        var h = reg[n];
        if (typeof h.init !== 'function') continue;
        var els = scope.querySelectorAll ? scope.querySelectorAll('[data-family]') : [];
        var matched = false;
        for (var i = 0; i < els.length; i++) {
          var fams = (els[i].getAttribute('data-family') || '').split(',');
          if (fams.indexOf(n) < 0) continue;
          matched = true;
          runInit(n, h.init, els[i]);
        }
        if (!matched) runInit(n, h.init, scope);   // 兜底：无标记元素时以 scope 调用
      }
    }
  };
  function runInit(name, fn, el) {
    try {
      var r = fn(document, el);   // root = 本家族 div 实例的根元素
      global.dispatchEvent(new CustomEvent('ssvuldiv', { detail: { name: name, result: r } }));
    } catch (e) {
      if (global.console && console.warn) console.warn('SsvulDiv init failed: ' + name, e);
    }
  }
  function go() { global.SsvulDiv.initAll(document.body); }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', go);
  else go();
})(window);
