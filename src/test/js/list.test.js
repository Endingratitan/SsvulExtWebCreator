/*
 * list 组件客户端测试（node 运行；零第三方依赖：手写极简 DOM 桩）
 * 运行： node src/test/js/list.test.js      （node 不存在时可跳过，见 tech.md 手工循环说明）
 * 覆盖：条目助手与构建期同一套标记 / fields 子集 / ssvul:inline 预设 / 空态约定 / 薄壳的跳过与失败提示
 * 说明：每个用例用**全新的 window**（运行时注册表是"每窗口一次"，复用会因幂等标志而失效）。
 */
'use strict';
const fs = require('fs');
const path = require('path');

let failed = 0;
function ok(cond, msg) {
  if (cond) { console.log('  ok   ' + msg); }
  else { failed++; console.log('  FAIL ' + msg); }
}

/* ---------- 极简 DOM 桩 ---------- */
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
/* readyState=loading + 空 addEventListener：让运行时的自动启动**挂起**，
   否则它会拿裸 document 先跑一次 init（幂等标志被吃掉），后面的手动 initAll 就再也不执行了 */
const documentStub = { createElement: El, readyState: 'loading', addEventListener() {} };
const repoRoot = path.resolve(__dirname, '../../..');

/* 全新 window + 与浏览器相同的加载顺序（runtime 必须先于组件）；fetchImpl 供 ssvul:json 用例注入 */
function makeWindow(fetchImpl) {
  const w = {};
  const files = ['src/assets/runtime/ssvul-div.js', 'src/assets/divs/list/list.js',
    'src/assets/list/inline.js', 'src/assets/list/json.js'];
  for (const rel of files) {
    const code = fs.readFileSync(path.join(repoRoot, rel), 'utf8');
    new Function('window', 'document', 'fetch', 'CustomEvent', code)(
      w, documentStub, fetchImpl || (() => Promise.reject(new Error('stub: no fetch'))),
      function CustomEvent(type, init) { return { type, detail: (init || {}).detail }; }
    );
  }
  w.__warned = [];
  return w;
}
function rootEl(attrs) {
  const r = El('div');
  Object.keys(attrs || {}).forEach(k => r.setAttribute(k, attrs[k]));
  const box = El('ul'); box.className = 'list-items';
  r._q['.list-items'] = box;
  return r;
}

const entry = { link: 'pages/blog/a/', title: '甲文', date: '2026-09-11', excerpt: '摘要', tags: 'cs,web' };

(async function () {
  /* 1) 条目助手：与构建期同一套类名与结构（不变量） */
  console.log('条目助手（构建期与客户端标记不变量）');
  const w1 = makeWindow();
  const expect = '<li class="list-item"><span class="list-date">2026-09-11</span>'
    + '<a class="list-title" href="../../pages/blog/a/">甲文</a>'
    + '<span class="list-tags"><span class="list-tag">cs</span><span class="list-tag">web</span></span>'
    + '<p class="list-excerpt">摘要</p></li>';
  ok(serialize(w1.SsvulList.item(entry, 'date,title,excerpt,tags', '../../')) === expect, '完整字段标记与构建期一致');
  ok(serialize(w1.SsvulList.item(entry, 'date,title', '')).indexOf('list-excerpt') < 0, 'fields 子集生效（无 excerpt）');

  /* 2) 薄壳：构建期来源跳过 / 未知函数可见提示 / 正常来源装载并派发事件 */
  console.log('薄壳行为');
  const w2 = makeWindow();
  const build = rootEl({ 'data-src': 'build:page-index' });
  w2.SsvulDiv.initAll(build);
  await new Promise(r => setTimeout(r, 0));
  ok(build._q['.list-items'].children.length === 0 && build._ev.length === 0, 'build: 来源直接跳过（构建期已渲染）');

  const w3 = makeWindow();
  const bad = rootEl({ 'data-src': '不存在的函数' });
  w3.SsvulDiv.initAll(bad);
  const badBox = bad._q['.list-items'];
  ok(badBox.children.length === 1 && badBox.children[0].className === 'list-error', '未知函数 → 容器里可见的失败提示');

  const w4 = makeWindow();
  const okRoot = rootEl({ 'data-src': 'ssvul:inline', 'data-depth': '1', 'data-fields': 'date,title' });
  const data4 = El('script'); data4.className = 'list-data';
  data4.textContent = JSON.stringify([entry]);
  okRoot._q['.list-data'] = data4;
  w4.SsvulDiv.initAll(okRoot);
  await new Promise(r => setTimeout(r, 0));
  const box4 = okRoot._q['.list-items'];
  ok(box4.children.length === 1 && serialize(box4.children[0]).indexOf('../pages/blog/a/') >= 0, '预设装载条目并派发事件');
  ok(okRoot._ev.length === 1 && okRoot._ev[0].type === 'ssvullist', '装载后派发 ssvullist 事件');

  /* 3) ssvul:inline 预设：深度前缀 / fields 透传 / empty 约定 */
  console.log('ssvul:inline 预设');
  const w5 = makeWindow();
  const r5 = rootEl({ 'data-depth': '2' });
  const data5 = El('script'); data5.className = 'list-data';
  data5.textContent = JSON.stringify([entry, { link: 'pages/blog/b/', title: '乙文', date: '2026-09-12' }]);
  r5._q['.list-data'] = data5;
  const res5 = await w5.SsvulListPresets.inline(r5, { fields: 'date,title' });
  ok(res5.items.length === 2, '内联条目全部产出');
  ok(serialize(res5.items[0]).indexOf('href="../../pages/blog/a/"') >= 0, '深度前缀按 data-depth 计算');
  ok(serialize(res5.items[0]).indexOf('list-excerpt') < 0, 'fields 透传给预设');

  const r6 = rootEl({ 'data-depth': '0' });
  const data6 = El('script'); data6.className = 'list-data'; data6.textContent = '[]';
  r6._q['.list-data'] = data6;
  const res6 = await w5.SsvulListPresets.inline(r6, { empty: '还没有内容', fields: 'title' });
  const s6 = serialize(res6.items[0]);
  ok(s6.indexOf('list-empty') >= 0 && s6.indexOf('还没有内容') >= 0, 'empty 约定：0 条时输出提示条目');

  /* 4) ssvul:json 预设：纯 CSR（数据来自作者维护的 JSON 文件，构建期不参与） */
  console.log('ssvul:json 预设（纯 CSR）');
  const w7 = makeWindow(() => Promise.resolve({
    ok: true,
    json: () => Promise.resolve({ entries: [entry, { link: 'pages/blog/b/', title: '乙文', date: '2026-09-12' }] })
  }));
  const r7 = rootEl({ 'data-depth': '1' });
  const res7 = await w7.SsvulListPresets.json(r7, { file: 'assets/data/blog/index.json', fields: 'date,title' });
  ok(res7.items.length === 2, '{"entries":[…]} 形状可读');
  ok(serialize(res7.items[0]).indexOf('href="../pages/blog/a/"') >= 0, '相对路径按 data-depth 加前缀');
  ok(serialize(res7.items[0]).indexOf('list-excerpt') < 0, 'fields 透传');

  const w8 = makeWindow(() => Promise.resolve({ ok: true, json: () => Promise.resolve([entry]) }));
  const res8 = await w8.SsvulListPresets.json(rootEl({}), { file: '/abs/index.json' });
  ok(res8.items.length === 1, '裸数组形状可读 + / 开头路径不加前缀');

  const w9 = makeWindow(() => Promise.resolve({ ok: false, status: 404 }));
  let threw404 = false;
  try { await w9.SsvulListPresets.json(rootEl({}), { file: 'x.json' }); } catch (e) { threw404 = true; }
  ok(threw404, '404 → 抛错（薄壳转成可见提示）');
  let threwNoFile = false;
  try { await w9.SsvulListPresets.json(rootEl({}), {}); } catch (e) { threwNoFile = true; }
  ok(threwNoFile, '缺 file 参数 → 抛错');

  const w10 = makeWindow(() => Promise.resolve({ ok: true, json: () => Promise.resolve([]) }));
  const res10 = await w10.SsvulListPresets.json(rootEl({}), { file: 'x.json', empty: '还没有文章' });
  ok(serialize(res10.items[0]).indexOf('list-empty') >= 0, 'empty 约定同样生效');

  console.log(failed === 0 ? '\n全部通过' : '\n失败 ' + failed + ' 项');  process.exit(failed === 0 ? 0 : 1);
})();
