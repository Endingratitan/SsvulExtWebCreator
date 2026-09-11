/*
 * list 官方预设 ssvul:s3 的客户端测试（node 运行；零第三方依赖：手写极简 DOM 桩 + fetch/存储桩）
 * 运行： node src/test/js/bucketlist.test.js
 * 覆盖：请求 URL 拼接（query/prefix/分页 token）| XML 提取与实体解码 | 键→条目映射 | 契约规范化排序
 *       | pattern glob | 目录占位跳过 | 分页与 pages 上限 | 会话缓存命中/过期/关闭 | 失败降级与失败可见
 */
'use strict';
const fs = require('fs');
const path = require('path');

let failed = 0;
function ok(cond, msg) {
  if (cond) { console.log('  ok   ' + msg); }
  else { failed++; console.log('  FAIL ' + msg); }
}

/* ---------- 极简 DOM 桩（与 list.test.js 同款，保持各测试文件可独立运行） ---------- */
function El(tag) {
  const el = {
    tagName: tag, className: '', children: [], attrs: {}, textContent: '', _q: {}, _ev: [],
    setAttribute(k, v) { this.attrs[k] = String(v); },
    getAttribute(k) { return Object.prototype.hasOwnProperty.call(this.attrs, k) ? this.attrs[k] : null; },
    appendChild(c) { this.children.push(c); return c; },
    insertAdjacentHTML(pos, html) { this.children.push({ raw: html }); },
    querySelector(sel) { return this._q[sel] || null; },
    querySelectorAll() { return []; },
    dispatchEvent(e) { this._ev.push(e); return true; },
  };
  Object.defineProperty(el, 'attributes', {
    get() { return Object.keys(this.attrs).map(k => ({ name: k, value: this.attrs[k] })); }
  });
  Object.defineProperty(el, 'childNodes', { get() { return this.children; } });
  Object.defineProperty(el, 'innerHTML', { get() { return this.children.map(serialize).join(''); } });
  return el;
}
function serialize(n) {
  if (n.raw !== undefined) return n.raw;
  let attrs = '';
  for (const k of Object.keys(n.attrs || {})) attrs += ' ' + k + '="' + n.attrs[k] + '"';
  const cls = n.className ? ' class="' + n.className + '"' : '';
  return '<' + n.tagName + cls + attrs + '>' + n.textContent + n.children.map(serialize).join('') + '</' + n.tagName + '>';
}
const documentStub = { createElement: El, readyState: 'loading', addEventListener() {} };
const repoRoot = path.resolve(__dirname, '../../..');
/* 预设里写的是 global.console.warn（浏览器里 window.console 存在）→ 桩窗口也要有 console，
   并把 warn 收集到一个模块级数组，供各用例断言（用例前清空） */
const WARNED = [];
console.warn = (...a) => { WARNED.push(a.join(' ')); };

function storageStub() {
  const m = new Map();
  return {
    getItem: k => (m.has(k) ? m.get(k) : null),
    setItem: (k, v) => m.set(k, String(v)),
    removeItem: k => m.delete(k),
    _keys: () => Array.from(m.keys()),
    _get: k => m.get(k),
    _set: (k, v) => m.set(k, v),
  };
}
/* fetch 桩：按调用次序依次返回 pages 里的 XML；元素为 Error 时 reject */
function fetchStub(pages) {
  const calls = [];
  const fn = url => {
    calls.push(url);
    const next = pages[Math.min(calls.length - 1, pages.length - 1)];
    if (next instanceof Error) return Promise.reject(next);
    return Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve(next) });
  };
  fn.calls = calls;
  return fn;
}
function makeWindow(fetchImpl, storageImpl) {
  const w = { sessionStorage: storageImpl || storageStub(), console: console };
  const files = ['src/assets/runtime/ssvul-div.js', 'src/assets/divs/list/list.js', 'src/assets/div-libs/list/s3.js'];
  for (const rel of files) {
    const code = fs.readFileSync(path.join(repoRoot, rel), 'utf8');
    new Function('window', 'document', 'fetch', 'CustomEvent', code)(
      w, documentStub, fetchImpl || (() => Promise.reject(new Error('stub: no fetch'))),
      function CustomEvent(type, init) { return { type, detail: (init || {}).detail }; }
    );
  }
  return w;
}
function rootEl(attrs) {
  const r = El('div');
  Object.keys(attrs || {}).forEach(k => r.setAttribute(k, attrs[k]));
  const box = El('ul'); box.className = 'list-items';
  r._q['.list-items'] = box;
  return r;
}
function texts(items) { return items.map(i => (i.children || []).map(c => c.textContent).join('|')); }

const HREF = 'https://cdn.example.com';
const EP = 'https://s3.example.com/bk-demo';
const ENTRY = { 'bk-endpoint': EP, 'bk-href': HREF, 'bk-name': 'bk', 'bk-prefix': 'site/blog', 'bk-ttl': '600' };

function xml(contents, extra) {
  return '<?xml version="1.0" encoding="UTF-8"?>\n<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">\n'
    + '  <Name>bk-demo</Name><Prefix>site/blog/</Prefix><MaxKeys>1000</MaxKeys><Delimiter>/</Delimiter>\n'
    + (extra || '  <IsTruncated>false</IsTruncated>\n')
    + contents.map(c => '  <Contents><Key>' + c[0] + '</Key><LastModified>' + c[1] + '</LastModified>'
      + '<ETag>&quot;e&quot;</ETag><Size>12</Size><StorageClass>STANDARD</StorageClass></Contents>\n').join('')
    + '</ListBucketResult>';
}
const PAGE1 = xml([
  ['site/blog/b.html', '2026-09-10T08:00:00.000Z'],
  ['site/blog/a.html', '2026-09-12T23:59:59.000Z'],
  ['site/blog/sub/', '2026-09-01T00:00:00.000Z'],                 // 目录占位 → 跳过
  ['site/blog/my-note_v2 &amp; more.md', '2026-09-11T00:00:00.000Z'],
]);

(async function () {
  /* 1) 请求拼接 + 映射 + 排序 + pattern */
  console.log('请求与映射');
  const f1 = fetchStub([PAGE1]);
  const w1 = makeWindow(f1);
  const res1 = await w1.SsvulListPresets.s3(rootEl({ 'data-depth': '1' }), Object.assign({ pattern: '*.html', fields: 'date,title' }, ENTRY));
  ok(f1.calls.length === 1, '一次请求取完（未截断）');
  const u = f1.calls[0];
  ok(u.indexOf('list-type=2') > 0 && u.indexOf('max-keys=1000') > 0 && u.indexOf('delimiter=%2F') > 0, 'query 含 list-type/max-keys/delimiter');
  ok(u.indexOf('prefix=site%2Fblog%2F') > 0, '存储前缀按目录补斜线并编码');
  ok(u.indexOf('continuation-token') < 0, '首页不带 continuation-token');
  ok(res1.items.length === 2, 'pattern=*.html 过滤掉 md 与目录占位');
  const s1 = serialize(res1.items[0]);
  ok(s1.indexOf('href="' + HREF + '/a.html"') > 0, 'link = href + 去掉存储前缀的键');
  ok(s1.indexOf('>a<') > 0 && s1.indexOf('2026-09-12') > 0, 'title = 文件名去扩展名；date = LastModified 的 UTC 日期');
  ok(serialize(res1.items[1]).indexOf('2026-09-10') > 0, 'date 倒序（09-12 在 09-10 前）');
  const deep = await makeWindow(fetchStub([PAGE1])).SsvulListPresets.s3(rootEl({ 'data-depth': '2' }), Object.assign({ pattern: '*.html' }, ENTRY));
  ok(serialize(deep.items[0]).indexOf('href="../') < 0, '绝对 link 不叠加 data-depth 前缀（与相对 link 的预设不同）');

  const f2 = fetchStub([PAGE1]);
  const w2 = makeWindow(f2);
  const res2 = await w2.SsvulListPresets.s3(rootEl({}), Object.assign({ pattern: '*.md' }, ENTRY));
  ok(res2.items.length === 1, 'pattern=*.md 命中一个键');
  const s2 = serialize(res2.items[0]);
  ok(s2.indexOf(HREF + '/my-note_v2%20%26%20more.md') > 0, '键里的空格与 & 解码后逐段 URL 编码');
  ok(s2.indexOf('>my note v2 &amp; more<') > 0 || s2.indexOf('>my note v2 & more<') > 0, 'title 的 -/_ 变空格、XML 实体已解码');

  /* 2) 契约规范化排序（与构建期同规则；禁用 localeCompare） */
  console.log('契约规范化排序');
  const w3 = makeWindow();
  const sorted = w3.SsvulList.sort([
    { title: 'b', date: '2026-01-02' }, { title: 'a', date: '2026-01-02' },
    { title: 'z', date: '' }, { title: 'Z', date: '2026-01-03' },
  ]);
  ok(sorted[0].title === 'Z' && sorted[1].title === 'a' && sorted[2].title === 'b' && sorted[3].title === 'z',
    'date 倒序 → 同日 title 码元序（Z 在 a 前，非拼音序）→ 无 date 最后');
  ok(w3.SsvulList.sort([{ title: 'b' }, { title: 'a' }]).map(e => e.title).join('') === 'ab', '同 date 时按 title 升序');
  const arr = [{ title: 'b' }, { title: 'a' }];
  w3.SsvulList.sort(arr);
  ok(arr[0].title === 'b', 'sort 不改动调用方数组（返回副本）');

  /* 3) 分页与 pages 上限 */
  console.log('分页');
  const P2 = xml([['site/blog/c.html', '2026-09-09T00:00:00.000Z']],
    '  <IsTruncated>true</IsTruncated>\n  <NextContinuationToken>tok+en/1=</NextContinuationToken>\n');
  const f4 = fetchStub([P2, PAGE1]);
  const w4 = makeWindow(f4);
  const res4 = await w4.SsvulListPresets.s3(rootEl({}), Object.assign({ pattern: '*.html' }, ENTRY));
  ok(f4.calls.length === 2, 'IsTruncated=true → 跟 token 取下一页');
  ok(f4.calls[1].indexOf('continuation-token=tok%2Ben%2F1%3D') > 0, 'token 被 URL 编码');
  ok(res4.items.length === 3, '两页条目合并（c + a + b）');

  const f5 = fetchStub([P2]);
  const w5 = makeWindow(f5);
  WARNED.length = 0;
  const res5 = await w5.SsvulListPresets.s3(rootEl({}), Object.assign({ pattern: '*.html', pages: '2' }, ENTRY));
  ok(f5.calls.length === 2 && res5.items.length === 2, 'pages 上限生效（2 页后停止）');
  ok(WARNED.some(m => m.indexOf('pages 上限') > 0), '到顶还有更多 → console.warn 提示');

  /* 4) 会话缓存：命中 / 过期 / 关闭 */
  console.log('会话缓存');
  const st6 = storageStub();
  const f6 = fetchStub([PAGE1]);
  const w6 = makeWindow(f6, st6);
  const p6 = { pattern: '*.html' };
  const a6 = await w6.SsvulListPresets.s3(rootEl({}), Object.assign({}, p6, ENTRY));
  ok(f6.calls.length === 1 && !a6.cached, '首次请求写缓存且未标记 cached');
  ok(st6._keys().length === 1 && st6._keys()[0].indexOf('ssvul:list:s3|') === 0, '缓存键落在 sessionStorage（前缀 ssvul:list:s3|）');
  const b6 = await w6.SsvulListPresets.s3(rootEl({}), Object.assign({}, p6, ENTRY));
  ok(f6.calls.length === 1 && b6.cached === true, '未过期 → 零请求命中缓存并标记 cached');

  const rec = JSON.parse(st6._get(st6._keys()[0]));
  rec.t = Date.now() - 10 * 24 * 3600 * 1000;                       // 手动做旧
  st6._set(st6._keys()[0], JSON.stringify(rec));
  const c6 = await w6.SsvulListPresets.s3(rootEl({}), Object.assign({}, p6, ENTRY));
  ok(f6.calls.length === 2 && !c6.cached, '过期 → 重新请求（不标记 cached）');

  const f7 = fetchStub([PAGE1]);
  const st7 = storageStub();
  const w7 = makeWindow(f7, st7);
  const p7 = { pattern: '*.html', 'bk-ttl': '0' };
  await w7.SsvulListPresets.s3(rootEl({}), Object.assign({}, ENTRY, p7));
  await w7.SsvulListPresets.s3(rootEl({}), Object.assign({}, ENTRY, p7));
  ok(f7.calls.length === 2 && st7._keys().length === 0, 'cache/bk-ttl=0 → 既不读也不写缓存');

  /* 5) 失败可见 + 过期缓存降级 */
  console.log('失败与降级');
  const f8 = fetchStub([new Error('boom')]);
  const w8 = makeWindow(f8);
  let threw = false;
  try { await w8.SsvulListPresets.s3(rootEl({}), Object.assign({ pattern: '*.html' }, ENTRY)); }
  catch (e) { threw = true; ok(/CORS/.test(e.message), '失败信息可操作（提示 endpoint/网络/CORS）'); }
  ok(threw, '无缓存时失败 → 抛错（薄壳转 .list-error）');

  const st9 = storageStub();
  const f9a = fetchStub([PAGE1]);
  const w9 = makeWindow(f9a, st9);
  const p9 = { pattern: '*.html' };
  await w9.SsvulListPresets.s3(rootEl({}), Object.assign({}, p9, ENTRY));
  const r9 = JSON.parse(st9._get(st9._keys()[0]));
  r9.t = 0;                                                          // 极旧
  st9._set(st9._keys()[0], JSON.stringify(r9));
  f9a.calls.length = 0;
  const f9b = fetchStub([new Error('down')]);
  const w9b = makeWindow(f9b, st9);                                  // 同一 storage、新 fetch
  WARNED.length = 0;
  const res9 = await w9b.SsvulListPresets.s3(rootEl({}), Object.assign({}, p9, ENTRY));
  ok(res9.items.length === 2 && res9.cached === true, '失败 + 过期缓存 → 降级显示旧数据并标记 cached');
  ok(WARNED.some(m => m.indexOf('过期缓存') > 0), '降级时 console.warn 说明原因');

  /* 6) 参数与空态 */
  console.log('参数与空态');
  const f10 = fetchStub([xml([])]);
  const w10 = makeWindow(f10);
  const res10 = await w10.SsvulListPresets.s3(rootEl({}), Object.assign({ pattern: '*.html', empty: '桶里还没有文件' }, ENTRY));
  ok(res10.items.length === 1 && serialize(res10.items[0]).indexOf('list-empty') > 0, 'empty 约定：0 条时输出提示条目');
  let threwNoEp = false;
  try { await w10.SsvulListPresets.s3(rootEl({}), { 'bk-href': HREF }); } catch (e) { threwNoEp = true; }
  ok(threwNoEp, '缺 data-bk-endpoint（未声明 endpoint 的桶）→ 明确抛错');

  const f11 = fetchStub([PAGE1]);
  const w11 = makeWindow(f11);
  await w11.SsvulListPresets.s3(rootEl({}), Object.assign({}, ENTRY, { 'bk-prefix': '' }));
  ok(f11.calls[0].indexOf('prefix=') < 0, '空前缀 → 不发送 prefix 参数（列桶根）');

  /* 7) link 模板：让桶列表条目指向 reader 页（配合 md-csr），而不是直链原始文件 */
  console.log('link 模板');
  const f12 = fetchStub([PAGE1, PAGE1]);
  const w12 = makeWindow(f12);
  const res12 = await w12.SsvulListPresets.s3(rootEl({}), Object.assign({ pattern: '*.md', link: 'reader/?p={key}' }, ENTRY));
  const href12 = serialize(res12.items[0]).match(/href="([^"]*)"/)[1];
  ok(href12 === 'reader/?p=site%2Fblog%2Fmy-note_v2%20%26%20more.md', 'link 模板 {key} = 桶内键（按查询值编码）: ' + href12);
  const res12b = await w12.SsvulListPresets.s3(rootEl({}), Object.assign({ pattern: '*.md', link: '../../reader/?p={path}' }, ENTRY));
  const href12b = serialize(res12b.items[0]).match(/href="([^"]*)"/)[1];
  ok(href12b === '../../reader/?p=my-note_v2%20%26%20more.md', 'link 模板 {path} = 去掉存储前缀的相对路径: ' + href12b);
  ok(f12.calls.length === 2, 'link 模板不同 → 缓存键不同（不会复用上一次的条目 link）');
  const res12c = await w12.SsvulListPresets.s3(rootEl({}), Object.assign({ pattern: '*.md', link: '../../reader/?p={path}' }, ENTRY));
  ok(f12.calls.length === 2 && res12c.cached === true, '同一 link 模板第二次 → 命中缓存（零请求）');

  console.log(failed === 0 ? '\n全部通过' : '\n失败 ' + failed + ' 项'); process.exit(failed === 0 ? 0 : 1);
})();
