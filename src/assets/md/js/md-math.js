/* SsvulExtWebCreator · https://github.com/Endingratitan/SsvulExtWebCreator | MPL-2.0: redistributed with this notice and LICENSE/NOTICE | Copyright (c) 2026 Endingratitan */
/* md 数学预设：直接用 katex.render 渲染 .md-math / .md-math-block 锚点（不依赖 auto-render）。
   支持 $…$ / $$…$$ 与 LaTeX 原生 \(…\) / \[…\]（非默认定界符经 data-delim 标注）。
   依赖：deps 引入 lib/katex/katex.min.css 与 katex.min.js（先于本文件执行）。
   脚本位于 body 末尾、DOM 已就绪，立即执行。 */
(function () {
  function delims(el, inline) {
    var d = el.getAttribute('data-delim');
    if (inline) return d === 'latex' ? ['\\(', '\\)'] : ['$', '$'];
    return d === 'latex-block' ? ['\\[', '\\]'] : ['$$', '$$'];
  }
  function strip(el, open, close, displayMode) {
    var t = el.textContent;
    if (t.slice(0, open.length) !== open || t.slice(-close.length) !== close) return;
    var tex = t.slice(open.length, t.length - close.length);
    try { katex.render(tex, el, { displayMode: displayMode, throwOnError: false }); } catch (e) {}
  }
  var inline = document.querySelectorAll('.md-math');
  for (var i = 0; i < inline.length; i++) {
    var di = delims(inline[i], true);
    strip(inline[i], di[0], di[1], false);
  }
  var blocks = document.querySelectorAll('.md-math-block');
  for (var j = 0; j < blocks.length; j++) {
    var db = delims(blocks[j], false);
    strip(blocks[j], db[0], db[1], true);
  }
})();
