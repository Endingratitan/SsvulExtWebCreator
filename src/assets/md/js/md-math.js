/* md 数学预设：KaTeX auto-render 只扫描 .md-math / .md-math-block 锚点。
   依赖：deps 引入 lib/katex/katex.min.css 与 katex.min.js（先于本文件执行）。 */
(function () {
  function run() {
    if (!window.renderMathInElement) return;
    var inline = document.querySelectorAll('.md-math');
    for (var i = 0; i < inline.length; i++) {
      try { renderMathInElement(inline[i], { delimiters: [{ left: '$', right: '$', display: false }] }); } catch (e) {}
    }
    var blocks = document.querySelectorAll('.md-math-block');
    for (var j = 0; j < blocks.length; j++) {
      try { renderMathInElement(blocks[j], { delimiters: [{ left: '$$', right: '$$', display: true }] }); } catch (e) {}
    }
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', run);
  else run();
})();
