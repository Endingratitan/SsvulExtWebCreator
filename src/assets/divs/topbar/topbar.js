/* SsvulExtWebCreator · https://github.com/Endingratitan/SsvulExtWebCreator | MPL-2.0: redistributed with this notice and LICENSE/NOTICE | Copyright (c) 2026 Endingratitan */
/* topbar：置顶导航 = navbar + sticky + 滚动阴影。家族 init 覆写（super 子调父）。 */
window.SsvulDiv.register('bar', {
  init: function (doc, root) {
    var parent = window.SsvulDiv.super('bar', 'init');
    if (parent) parent(doc, root);
    var onScroll = function () {
      var y = doc.defaultView ? doc.defaultView.scrollY : 0;
      root.classList.toggle('bar-scrolled', y > 4);
    };
    doc.addEventListener('scroll', onScroll, { passive: true });
    onScroll();
  }
});
