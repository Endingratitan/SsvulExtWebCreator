/* SsvulExtWebCreator · https://github.com/Endingratitan/SsvulExtWebCreator | MPL-2.0: redistributed with this notice and LICENSE/NOTICE | Copyright (c) 2026 Endingratitan */
/* CODEUI.js 官方模板：复制到 sets/global/codeui/CODEUI.js 后按需修改（存在即注入，仅注入有代码块的页面，
   并在引擎资源与 md-copy.js 之后执行）。
   可用契约：
     - 事件：ssvulhighlight（引擎上色完成）、ssvulcopy（detail.ok/lang，复制完成）、
             themechange（detail.theme）、palettechange（detail.palette）
     - 全局：window.SsvulTheme { get, set, toggle, setPalette, clearPalette }
     - 结构：.md-code-block[data-lang][data-items]（data-items 为页面 code-ui.items 全量列表，
             生成器不认识的 item 名也会保留在这里，由本文件自行实现） */
(function () {
  function init() {
    var blocks = document.querySelectorAll('.md-code-block');
    for (var i = 0; i < blocks.length; i++) (function (block) {
      var items = (block.getAttribute('data-items') || '').split(',').filter(Boolean);
      for (var k = 0; k < items.length; k++) {
        if (items[k] === 'my-badge') renderBadge(block);   // 示例：实现自定义 item
      }
    })(blocks[i]);
  }
  function renderBadge(block) {
    var s = document.createElement('span');
    s.className = 'md-code-badge';
    s.textContent = '自定义';
    block.appendChild(s);
  }
  window.addEventListener('ssvulhighlight', init);
  init();   // 构建期已上色的引擎可能先于本脚本完成，再兜底一次
})();
