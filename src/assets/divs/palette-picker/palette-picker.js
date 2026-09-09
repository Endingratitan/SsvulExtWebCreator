/* SsvulExtWebCreator · https://github.com/Endingratitan/SsvulExtWebCreator | MPL-2.0: redistributed with this notice and LICENSE/NOTICE | Copyright (c) 2026 Endingratitan */
/* palette-picker：整页主题切换器（SsvulTheme.set，localStorage ssvul-theme 持久化）。
   params：palettes="dark,sepia,green"（主题名；clear=恢复页面初始主题）、labels（可选）。
   依赖：生成器在含本组件的页面链接全部内置主题 css（md-code-<name>.css）。 */
window.SsvulDiv.register('palette-picker', {
  init: function (doc, root) {
    var initial = window.SsvulTheme ? SsvulTheme.get() : 'dark';
    var names = (root.getAttribute('data-palettes') || 'dark,sepia,green').split(',');
    var labels = (root.getAttribute('data-labels') || '').split(',');
    var setActive = function (name) {
      var btns = root.querySelectorAll('.palette-btn');
      var matched = false;
      for (var k = 0; k < btns.length; k++) {
        var on = btns[k].getAttribute('data-palette') === name;
        matched = matched || on;
        if (on) btns[k].classList.add('active');
        else btns[k].classList.remove('active');
        btns[k].setAttribute('aria-pressed', on ? 'true' : 'false');
      }
      // 当前主题不在按钮列表里时，标记 clear（恢复默认）为选中
      if (!matched) {
        for (var j = 0; j < btns.length; j++) {
          if (btns[j].getAttribute('data-palette') === 'clear') {
            btns[j].classList.add('active');
            btns[j].setAttribute('aria-pressed', 'true');
          }
        }
      }
    };
    for (var k = 0; k < names.length; k++) {
      var p = names[k].trim();
      if (!p) continue;
      (function (p) {
        var btn = doc.createElement('button');
        btn.type = 'button';
        btn.className = 'palette-btn';
        btn.setAttribute('data-palette', p);
        btn.textContent = (labels[k] || '').trim() || p;
        btn.addEventListener('click', function () {
          if (!window.SsvulTheme) return;
          var target = p === 'clear' ? initial : p;
          SsvulTheme.set(target);
          setActive(target);
        });
        root.appendChild(btn);
      })(p);
    }
    setActive(initial);
  }
});
