/* SsvulExtWebCreator · https://github.com/Endingratitan/SsvulExtWebCreator | MPL-2.0: redistributed with this notice and LICENSE/NOTICE | Copyright (c) 2026 Endingratitan */
/* md-csr：**运行时渲染 markdown**（薄壳）。库在 src/assets/md-csr/（预设路径，构建期按需注入 assets/pre/div-libs/md-csr/）。
 *
 * 取数三选一（都不写 = 读 URL 查询参数，默认 p）：
 *   key + bucket  桶内路径（如 key="site/blog/note.md"）→ href 由构建期注入 data-bk-href
 *   key + href    直接给公开读基址（不走 bucket 配置）
 *   file          相对站点根 / / 开头 / http(s)://（与 ssvul:json 同风格）
 *   （默认）?p=<路径>  同一个 reader 页渲染任意 md —— 配合 list 的 link 模板使用
 * 渲染选项（透传，与构建期 md-options 同名）：raw-html（默认 off）、link-policy（默认 relaxed）、
 *   toc、footnote-display、callout-title、codeui.items/bg/rounded/label-pos
 * 行为：成功 → 注入 .md-csr-slot 并派发 **ssvulmd** 事件（含 errors/warnings/hasCode/hasCallout）；
 *       随后重跑 hljs/KaTeX 钩子（SsvulMdHighlight / SsvulMdMath，若页面已加载）；
 *       失败 → 在槽位放一条 .md-csr-error（页面不白屏），详情进 console.warn。 */
(function (global) {
  if (!global.SsvulDiv) return;

  function paramsOf(root) {
    var out = {}, attrs = root.attributes;
    for (var i = 0; i < attrs.length; i++) {
      var n = attrs[i].name;
      if (n.indexOf('data-') !== 0 || n === 'data-depth' || n === 'data-family') continue;
      out[n.slice(5)] = attrs[i].value;
    }
    return out;
  }

  /* 取数解析：返回 {url, site}——site=true 表示"站点相对路径"（调用方按 data-depth 补前缀）。
     key + bucket/href → 桶内键（拼 href）；file → 原样；都没有 → URL 查询参数（默认 p）：
     **有桶配置时查询值同样按桶内键解释**（配合 list 的 link 模板 reader/?p={key}），否则按站点相对路径。 */
  function resolveUrl(params) {
    if (params.file) return { url: params.file, site: true };
    var base = String(params.href || params['bk-href'] || '').replace(/\/+$/, '');
    var key = params.key || '';
    if (!key) {
      var q = (global.location && global.location.search) || '';
      var name = params.query || 'p';
      var m = new RegExp('[?&]' + name + '=([^&]*)').exec(q);
      if (!m) throw new Error('md-csr 没有数据源：请给 key/file，或用 ?' + name + '=<md 路径>');
      key = decodeURIComponent(m[1]);
    }
    if (key.indexOf('..') >= 0 || /^[a-z]+:/i.test(key)) throw new Error('md-csr 路径不合法: ' + key);
    if (base) {
      return {
        url: base + '/' + key.replace(/^\/+/, '').split('/').map(encodeURIComponent).join('/'),
        site: false
      };
    }
    if (params.key) throw new Error('md-csr 用了 key 但缺少 href/data-bk-href（请写 bucket= 由构建期注入，或直接给 href）');
    return { url: key, site: true };
  }

  function prefixOf(root) {
    var depth = parseInt(root.getAttribute('data-depth') || '0', 10), p = '';
    for (var i = 0; i < depth; i++) p += '../';
    return p;
  }

  function renderOptions(params) {
    var o = { mode: 'simple', 'raw-html': params['raw-html'] || 'off' };
    ['link-policy', 'toc', 'footnote-display', 'callout-title', 'codeui.items', 'codeui.bg',
      'codeui.rounded', 'codeui.label-pos'].forEach(function (k) { if (params[k] !== undefined) o[k] = params[k]; });
    return o;
  }

  function fail(root, msg) {
    if (global.console && console.warn) console.warn('SsvulMdCsr: ' + msg);
    var slot = root.querySelector('.md-csr-slot');
    if (!slot) return;
    var box = document.createElement('div');
    box.className = 'md-csr-error';
    box.textContent = '内容加载失败';
    slot.appendChild(box);
  }

  global.SsvulDiv.register('md-csr', {
    init: function (doc, root) {
      var params = paramsOf(root), slot = root.querySelector('.md-csr-slot');
      if (!slot) return;
      var got;
      try { got = resolveUrl(params); }
      catch (e) { fail(root, e.message); return; }
      var url = got.url;
      if (got.site && !/^(https?:)?\/\//.test(url)) url = prefixOf(root) + url.replace(/^\/+/, '');
      fetch(url, { mode: 'cors', credentials: 'omit' }).then(function (r) {
        if (!r.ok) throw new Error(url + ' → http ' + r.status);
        return r.text();
      }).then(function (md) {
        if (!global.SsvulMd) throw new Error('缺少 md-csr 库（SsvulMd）——检查构建期是否注入了 assets/pre/div-libs/md-csr/');
        var res = global.SsvulMd.render(md, renderOptions(params));
        slot.innerHTML = res.html;
        if (global.SsvulMdHighlight) global.SsvulMdHighlight();            // 代码块上色（重跑，幂等）
        if (global.SsvulMdMath) global.SsvulMdMath();                      // 公式排版（重跑，幂等）
        if (res.warnings && res.warnings.length && global.console && console.warn) {
          for (var i = 0; i < res.warnings.length; i++) console.warn('SsvulMdCsr[md 警告] ' + res.warnings[i]);
        }
        root.dispatchEvent(new CustomEvent('ssvulmd', {
          detail: { url: url, errors: res.errors, warnings: res.warnings, hasCode: res.hasCode, hasCallout: res.hasCallout }
        }));
      }).catch(function (e) { fail(root, (e && e.message) ? e.message : e); });
    }
  });
})(window);
