/* SsvulExtWebCreator · https://github.com/Endingratitan/SsvulExtWebCreator | MPL-2.0: redistributed with this notice and LICENSE/NOTICE | Copyright (c) 2026 Endingratitan */
window.SsvulDiv.register('backtotop', {
  init: function (doc, root) {
    var threshold = parseInt(root.getAttribute('data-threshold') || '200', 10);
    var btn = root.querySelector('.backtotop');
    if (!btn) return;
    var onScroll = function () {
      var y = doc.defaultView ? doc.defaultView.scrollY : 0;
      btn.classList.toggle('show', y > threshold);
    };
    doc.addEventListener('scroll', onScroll, { passive: true });
    onScroll();
    btn.addEventListener('click', function () {
      if (doc.defaultView) doc.defaultView.scrollTo({ top: 0, behavior: 'smooth' });
    });
  }
});
