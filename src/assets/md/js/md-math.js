/* md 数学预设：直接用 katex.render 渲染 .md-math / .md-math-block 锚点（不依赖 auto-render）。
   依赖：deps 引入 lib/katex/katex.min.css 与 katex.min.js（先于本文件执行）。
   脚本位于 body 末尾、DOM 已就绪，立即执行。 */
(function () {
  function strip(el, open, close, displayMode) {
    var t = el.textContent;
    if (t.slice(0, open.length) !== open || t.slice(-close.length) !== close) return;
    var tex = t.slice(open.length, t.length - close.length);
    try { katex.render(tex, el, { displayMode: displayMode, throwOnError: false }); } catch (e) {}
  }
  var inline = document.querySelectorAll('.md-math');
  for (var i = 0; i < inline.length; i++) strip(inline[i], '$', '$', false);
  var blocks = document.querySelectorAll('.md-math-block');
  for (var j = 0; j < blocks.length; j++) strip(blocks[j], '$$', '$$', true);
})();
