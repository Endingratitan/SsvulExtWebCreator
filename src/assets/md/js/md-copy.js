/* SsvulExtWebCreator · https://github.com/Endingratitan/SsvulExtWebCreator | MPL-2.0: redistributed with this notice and LICENSE/NOTICE | Copyright (c) 2026 Endingratitan */
/* md 复制按钮预设（copy-btn item 的行为实现）：clipboard API + execCommand 回退（file:// 等非安全上下文），
   成功改文案 2 秒恢复，并派发 ssvulcopy 事件（detail: {ok, lang}）。
   依赖：构建期生成的 .md-code-copy 按钮（code-ui.items 含 copy-btn）；脚本位于 body 末尾、DOM 已就绪。 */
(function () {
  function copyText(t) {
    if (navigator.clipboard && window.isSecureContext) return navigator.clipboard.writeText(t);
    return new Promise(function (resolve, reject) {
      var ta = document.createElement('textarea');
      ta.value = t;
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      var ok = false;
      try { ok = document.execCommand('copy'); } catch (e) {}
      document.body.removeChild(ta);
      ok ? resolve() : reject(new Error('copy failed'));
    });
  }
  var btns = document.querySelectorAll('.md-code-copy');
  for (var i = 0; i < btns.length; i++) (function (btn) {
    btn.addEventListener('click', function () {
      var block = btn.closest('.md-code-block');
      var code = block && block.querySelector('code');
      if (!code) return;
      copyText(code.textContent).then(function () {
        btn.textContent = '已复制';
        window.dispatchEvent(new CustomEvent('ssvulcopy', {
          detail: { ok: true, lang: (block.getAttribute('data-lang') || '') }
        }));
        setTimeout(function () { btn.textContent = '复制'; }, 2000);
      }, function () {
        window.dispatchEvent(new CustomEvent('ssvulcopy', { detail: { ok: false } }));
      });
    });
  })(btns[i]);
})();
