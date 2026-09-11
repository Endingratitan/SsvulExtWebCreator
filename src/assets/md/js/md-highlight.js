/* SsvulExtWebCreator · https://github.com/Endingratitan/SsvulExtWebCreator | MPL-2.0: redistributed with this notice and LICENSE/NOTICE | Copyright (c) 2026 Endingratitan */
/* md 高亮预设：客户端 hljs 上色 + token-map 映射 canonical 类（tk-*）。
   依赖：deps 引入 lib/hljs/hljs.min.js（先于本文件执行）；
   自定义语言注册脚本请排在 md-js 本文件之前；token 扩展见 token-map.js。
   脚本位于 body 末尾、DOM 已就绪，立即执行一次；**同时暴露 window.SsvulMdHighlight**
   供 CSR（md-csr 之类的运行时渲染）在注入新内容后重跑（已上色的块用 data-highlighted 跳过）。 */
(function (global) {
  function mapTokens() {
    var map = Object.assign({}, global.__ssvulTokenMap || {}, global.__ssvulTokenMapExtra || {});
    var nodes = document.querySelectorAll('.md-code [class*="hljs-"]');
    for (var i = 0; i < nodes.length; i++) {
      var cls = (nodes[i].className || '').split(/\s+/);
      for (var k = 0; k < cls.length; k++) {
        var id = map[cls[k]];
        if (id) nodes[i].classList.add('tk-' + id);
      }
    }
  }

  function run() {
    var codes = document.querySelectorAll('.md-code[class*="language-"]:not([data-highlighted])');
    for (var i = 0; i < codes.length; i++) {
      if (global.hljs) hljs.highlightElement(codes[i]);
    }
    mapTokens();
    document.dispatchEvent(new CustomEvent('ssvulhighlight'));
  }

  global.SsvulMdHighlight = run;
  run();
})(window);
