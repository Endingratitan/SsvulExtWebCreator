/* SsvulExtWebCreator · https://github.com/Endingratitan/SsvulExtWebCreator | MPL-2.0: redistributed with this notice and LICENSE/NOTICE | Copyright (c) 2026 Endingratitan */
/* navbar：bar 家族普通导航。覆写家族 init（super 子调父 + 追加日志），样式在 navbar.css。 */
window.SsvulDiv.register('bar', {
  init: function (doc, root) {
    var parent = window.SsvulDiv.super('bar', 'init');
    if (parent) parent(doc, root);
    console.log('navbar ready');
  }
});
