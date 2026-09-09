/* SsvulExtWebCreator · https://github.com/Endingratitan/SsvulExtWebCreator | MPL-2.0: redistributed with this notice and LICENSE/NOTICE | Copyright (c) 2026 Endingratitan */
/* pager：按 params 渲染 上一页/页码/下一页（base + n/ 链接形态）。 */
window.SsvulDiv.register('pager', {
  init: function (doc, root) {
    var current = parseInt(root.getAttribute('data-current') || '1', 10);
    var total = parseInt(root.getAttribute('data-total') || '1', 10);
    var base = root.getAttribute('data-base') || '';
    var mk = function (text, page, cls, disabled) {
      var a = doc.createElement('a');
      a.textContent = text;
      a.className = cls || '';
      if (disabled) { a.setAttribute('aria-disabled', 'true'); a.classList.add('disabled'); }
      else a.href = base + page + '/';
      return a;
    };
    root.appendChild(mk('‹', current - 1, 'pager-prev', current <= 1));
    for (var n = 1; n <= total; n++) {
      var a = mk(String(n), n, n === current ? 'pager-cur' : '');
      root.appendChild(a);
    }
    root.appendChild(mk('›', current + 1, 'pager-next', current >= total));
  }
});
