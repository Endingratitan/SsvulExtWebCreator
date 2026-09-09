/* SsvulExtWebCreator · https://github.com/Endingratitan/SsvulExtWebCreator | MPL-2.0: redistributed with this notice and LICENSE/NOTICE | Copyright (c) 2026 Endingratitan */
/* bar 家族基类行为：汉堡菜单开合。后代以同名 register('bar', …) 覆写并可用 SsvulDiv.super('bar','init') 子调父。 */
window.SsvulDiv.register('bar', {
  init: function (doc, root) {
    var btn = root.querySelector('.bar-menu');
    if (!btn) return;
    btn.addEventListener('click', function () {
      var open = root.classList.toggle('bar-open');
      btn.setAttribute('aria-expanded', String(open));
    });
  }
});
