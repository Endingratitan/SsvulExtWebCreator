/* SsvulExtWebCreator · https://github.com/Endingratitan/SsvulExtWebCreator | MPL-2.0: redistributed with this notice and LICENSE/NOTICE | Copyright (c) 2026 Endingratitan */
/* list 官方预设函数：ssvul:s3 —— **纯 CSR 列目录**（S3 兼容协议：R2 / 阿里云 OSS / MinIO / 腾讯 COS / Ceph 通用）。
   "站点构建一次之后，往桶里加/删/改文件即刷新可见，无需重建"——列表由浏览器实时列目录得到。

   桶信息由**构建期**解析并注入 data-bk-*（作者只写 params.bucket / dir）：
     bk-name      调用名（缓存键与报错用）
     bk-endpoint  列目录 API 基址（不带 query；S3 的 bucket 写在 path 或 host 均可）
     bk-href      公开访问基址（拼条目 link 用）
     bk-prefix    生效存储前缀（= bucket 的 prefix + params.dir）
     bk-ttl       会话缓存秒数（= params.cache ＞ 站点 session-ttl ＞ 86400；0 = 不缓存）
   作者可写的 params：bucket（选哪个桶，缺省=站点唯一桶）| dir（存储子目录）| pattern（**键名** glob，默认 *）
     | max（每页条数，默认 1000=S3 上限）| pages（最多请求页数，默认 3）| cache（覆盖 bk-ttl）| fields | empty
     | link（**条目 link 模板**：如 `reader/?p={key}` —— `{key}` = 桶内键（按查询值编码）、
             `{path}` = 去掉存储前缀的相对路径（按路径编码）；不写 = href + 相对路径。
             配合 md-csr 的 reader 页即可"上传 md → 点开由站点渲染"，而不是直链到原始文件）

   行为：GET {endpoint}?list-type=2&prefix={prefix}&delimiter=/&max-keys={max}[&continuation-token=…]
        只取 <Contents>（= 只列当前层，不递归；<CommonPrefixes> 与以 / 结尾的键都跳过）
        → pattern 过滤 → 映射 {link,type,title,date} → SsvulList.sort（与构建期同序）→ SsvulList.item
   缓存：sessionStorage（不跨会话/标签；随标签页存活，故"页面挂机"不会反复请求）
   失败：抛错由薄壳转成 .list-error；若存在**过期缓存**则降级显示旧数据 + console.warn（失败不阻断）。 */
(function (global) {
  global.SsvulListPresets = global.SsvulListPresets || {};

  var DEFAULT_TTL = 86400;     // 与站点默认 session-ttl 一致（构建期注入的 bk-ttl 会覆盖）
  var DEFAULT_MAX = 1000;      // S3 ListObjectsV2 单页上限
  var DEFAULT_PAGES = 3;       // 安全阀：最多请求页数（误连大桶不会拖死页面）
  var TIMEOUT_MS = 10000;      // 固定超时：挂住的请求按失败处理（可降级到过期缓存）

  function intOf(v, dft) { var n = parseInt(v, 10); return isNaN(n) ? dft : n; }

  /* 简单 glob：* = 任意长度，? = 单个字符（其余字符字面量）。作用于**键名**（含扩展名）。 */
  function glob(pattern, name) {
    if (!pattern || pattern === '*') return true;
    var re = String(pattern).replace(/[.+^${}()|[\]\\]/g, '\\$&')
      .replace(/\*/g, '[\\s\\S]*').replace(/\?/g, '[\\s\\S]');
    return new RegExp('^' + re + '$').test(name);
  }

  /* XML 实体解码（S3 的 list XML 只转义这几种；顺带支持数字实体） */
  function unesc(s) {
    return s.replace(/&(amp|lt|gt|quot|apos|#[0-9]+|#x[0-9a-fA-F]+);/g, function (m, e) {
      if (e === 'amp') return '&';
      if (e === 'lt') return '<';
      if (e === 'gt') return '>';
      if (e === 'quot') return '"';
      if (e === 'apos') return "'";
      return String.fromCodePoint(e.charAt(1) === 'x' || e.charAt(1) === 'X'
        ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10));
    });
  }

  /* 取单个标签内容。S3 的 list XML 是机器生成、同层不重复，故正则足够（浏览器与 Node 同一套实现，
     不依赖 DOMParser） */
  function tag(xml, name) {
    var m = new RegExp('<' + name + '>([\\s\\S]*?)</' + name + '>').exec(xml);
    return m ? unesc(m[1]) : '';
  }

  /* <Contents> 块 → [{key,date}]（不含 <CommonPrefixes>，即不递归子目录） */
  function contents(xml) {
    var out = [], re = /<Contents>([\s\S]*?)<\/Contents>/g, m;
    while ((m = re.exec(xml))) out.push({ key: tag(m[1], 'Key'), date: tag(m[1], 'LastModified') });
    return out;
  }

  function encPath(rel) {
    return rel.split('/').map(function (s) { return encodeURIComponent(s); }).join('/');
  }

  /* 键 → 条目：link = href + 去掉存储前缀的键；type = 扩展名；title = 文件名去扩展名、-/_ → 空格；
     date = LastModified 的 UTC 日期。excerpt/tags 留空（要就自写对接函数）。 */
  function mapKey(key, prefix, href, linkTpl) {
    var rel = prefix && key.indexOf(prefix + '/') === 0 ? key.slice(prefix.length + 1) : key;
    var seg = rel.split('/').pop();
    var dot = seg.lastIndexOf('.');
    var link = href + '/' + encPath(rel);
    if (linkTpl) {   // 模板 link：{key}=桶内键（查询值编码）、{path}=相对路径（路径编码）
      link = String(linkTpl).replace(/\{key\}/g, encodeURIComponent(key)).replace(/\{path\}/g, encPath(rel));
    }
    return {
      link: link,
      type: dot > 0 ? seg.slice(dot + 1).toLowerCase() : '',
      title: (dot > 0 ? seg.slice(0, dot) : seg).replace(/[-_]+/g, ' '),
      date: '', excerpt: '', tags: ''
    };
  }

  function emptyItem(text) {
    var li = document.createElement('li');
    li.className = 'list-empty';
    li.textContent = text;
    return li;
  }

  /* fetch + 状态/超时 → 文本；失败信息要**可操作**（权限/CORS 是最常见原因） */
  function fetchText(url) {
    var opt = { mode: 'cors', credentials: 'omit' }, ctl = null, timer = null;
    if (typeof AbortController !== 'undefined') { ctl = new AbortController(); opt.signal = ctl.signal; }
    if (ctl) timer = setTimeout(function () { ctl.abort(); }, TIMEOUT_MS);
    function done() { if (timer) { clearTimeout(timer); timer = null; } }
    return fetch(url, opt).then(function (r) {
      done();
      if (!r.ok) throw new Error('http ' + r.status + '（检查桶的匿名 ListBucket 权限与 CORS 配置）');
      return r.text();
    }, function (e) {
      done();
      throw new Error((e && e.name === 'AbortError' ? '请求超时' : '请求失败') + '（检查 endpoint / 网络 / CORS）');
    });
  }

  /* 分页取数：跟 <NextContinuationToken>，最多 pages 页；到顶还有更多则 warn（DOM 不新增标记） */
  function fetchPages(endpoint, prefix, pattern, href, max, pages, linkTpl) {
    function one(token, left, acc) {
      var url = endpoint + '?list-type=2&max-keys=' + max + '&delimiter=%2F'
        + (prefix ? '&prefix=' + encodeURIComponent(prefix + '/') : '')
        + (token ? '&continuation-token=' + encodeURIComponent(token) : '');
      return fetchText(url).then(function (xml) {
        var list = contents(xml);
        for (var i = 0; i < list.length; i++) {
          var key = list[i].key;
          if (!key || key.charAt(key.length - 1) === '/') continue;          // 目录占位
          if (!glob(pattern, key.split('/').pop())) continue;
          var e = mapKey(key, prefix, href, linkTpl);
          e.date = list[i].date ? list[i].date.slice(0, 10) : '';
          acc.push(e);
        }
        var next = tag(xml, 'NextContinuationToken');
        if (tag(xml, 'IsTruncated') === 'true' && next) {
          if (left > 1) return one(next, left - 1, acc);
          if (global.console && console.warn) {
            console.warn('SsvulList s3: 已达 pages 上限(' + pages + ')，还有更多条目未加载（可调大 params.pages）');
          }
        }
        return acc;
      });
    }
    return one('', pages, []);
  }

  /* ---- sessionStorage 缓存（不可用/超配额时静默降级，绝不影响渲染） ---- */
  function store() {
    try { return global.sessionStorage || null; } catch (e) { return null; }
  }
  function readCache(key) {
    var s = store();
    if (!s) return null;
    try {
      var raw = s.getItem(key);
      if (!raw) return null;
      var o = JSON.parse(raw);
      return (o && o.entries) ? o : null;
    } catch (e) { return null; }
  }
  function writeCache(key, entries) {
    var s = store();
    if (!s || entries.length > 1000) return;      // 大桶不写，防 5MB 配额被撑爆
    try { s.setItem(key, JSON.stringify({ t: Date.now(), entries: entries })); } catch (e) { /* 隐私模式/配额满 */ }
  }

  global.SsvulListPresets.s3 = function (root, params) {
    params = params || {};
    var endpoint = params['bk-endpoint'] || '';
    if (!endpoint) throw new Error('ssvul:s3 缺少构建期注入的 data-bk-endpoint（请在 Environment.config 给该 bucket 声明 endpoint）');
    var href = params['bk-href'] || '';
    var prefix = params['bk-prefix'] || '';
    var pattern = params.pattern || '*';
    var max = Math.min(Math.max(intOf(params.max, DEFAULT_MAX), 1), 1000);
    var pages = Math.min(Math.max(intOf(params.pages, DEFAULT_PAGES), 1), 20);
    var ttl = Math.max(intOf(params['bk-ttl'], DEFAULT_TTL), 0);
    var fields = params.fields || global.SsvulList.defaultFields;
    var linkTpl = params.link || '';                 // 条目 link 模板（{key}/{path}）——也进缓存键，避免不同模板互相污染
    var cacheKey = 'ssvul:list:s3|' + endpoint + '|' + prefix + '|' + pattern + '|' + max + '|' + pages + '|' + linkTpl;

    function finish(entries) {
      entries = global.SsvulList.sort(entries);
      if (!entries.length && params.empty) return { items: [emptyItem(params.empty)] };
      // link 默认是**绝对 URL**（href + 键）→ 不叠加 data-depth 前缀；用 params.link 模板时是站内相对路径，
      // 同样不叠加（模板里自己写 ../ 或绝对路径，作者可控）
      return { items: entries.map(function (e) { return global.SsvulList.item(e, fields, ''); }) };
    }

    var rec = readCache(cacheKey);
    if (ttl > 0 && rec && (Date.now() - (rec.t || 0)) <= ttl * 1000) {
      var hit = finish(rec.entries);
      hit.cached = true;
      return hit;                                  // 命中且未过期：零请求
    }
    return fetchPages(endpoint, prefix, pattern, href, max, pages, linkTpl).then(function (entries) {
      if (ttl > 0) writeCache(cacheKey, entries);   // ttl=0 = 完全不缓存（既不读也不写）
      return finish(entries);
    }, function (e) {
      if (rec && rec.entries.length) {             // 失败降级：过期缓存胜过空白
        if (global.console && console.warn) {
          console.warn('SsvulList s3: ' + ((e && e.message) || e) + '；改用过期缓存显示');
        }
        var old = finish(rec.entries);
        old.cached = true;
        return old;
      }
      throw e;                                     // 交给薄壳 → .list-error
    });
  };
})(window);
