/*
 * 客户端 md 渲染器（divs/md-csr）的**差分测试台**：Java 是唯一参照。
 *
 * 语料分层（src/test/md-corpus/）：
 *   basic/    *.md（+ 可选 .opts）→ *.expected.html 金标：Java 渲染成功，JS 必须**逐字节相等**
 *   js-only/  *.md（+ .assert）  ：Java 会报错（如相对链接不在构建期白名单），JS 按放宽策略渲染 → 断言
 *   advanced/ 同上金标机制（脚注/TOC/code-ui 等进阶语法，批次 C）
 *
 * 固定对齐配置：mode=simple、PassThrough(hljs) 语义、raw-html=on（JS 默认是安全转义，见指南）。
 *
 * 运行： node src/test/js/md-parity.test.js            # 全部层级
 *        node src/test/js/md-parity.test.js basic      # 只跑某一层
 *        node src/test/js/md-parity.test.js basic b07  # 只跑名字含 b07 的
 *        node src/test/js/md-parity.test.js all '' build/stage3-1/assets/pre/div-libs/md-csr
 *          ↑ 第 3 参数 = **产物里的库目录**（压缩后的 .js）→ 产物级 parity：一次覆盖
 *            "压缩器没压坏 / 去重没删错 / 多文件串联后命名空间仍在"
 */
'use strict';
const fs = require('fs');
const path = require('path');

const repoRoot = path.resolve(__dirname, '../../..');
const libDir = process.argv[4]
  ? path.resolve(repoRoot, process.argv[4])          // 产物级：指向 output/assets/pre/div-libs/md-csr
  : path.join(repoRoot, 'src/assets/div-libs/md-csr');
const corpusRoot = path.join(repoRoot, 'src/test/md-corpus');

let pass = 0, fail = 0;
const failures = [];

function ok(cond, msg) {
  if (cond) { pass++; }
  else { fail++; failures.push(msg); }
}

/* ---------- 加载库（模拟浏览器：只给 window；md 库必须是 DOM-free 的纯函数） ---------- */
function loadLib() {
  const w = { console: console };
  const files = fs.readdirSync(libDir).filter(f => f.endsWith('.js')).sort();
  if (!files.length) throw new Error('库目录为空: ' + libDir);
  for (const f of files) {
    const code = fs.readFileSync(path.join(libDir, f), 'utf8');
    try {
      new Function('window', code)(w);
    } catch (e) {
      throw new Error('加载 ' + f + ' 失败: ' + e.message);
    }
  }
  if (!w.SsvulMd || typeof w.SsvulMd.render !== 'function') {
    throw new Error('库未导出 window.SsvulMd.render（已加载: ' + files.join(', ') + '）');
  }
  return { w: w, files: files };
}

/* ---------- 语料读取 ---------- */
function optsOf(file) {
  const f = file.replace(/\.md$/, '.opts');
  const m = { mode: 'simple', 'raw-html': 'on' };     // 对齐配置（两侧共用；raw-html 仅 JS 侧认识）
  if (fs.existsSync(f)) {
    for (const line of fs.readFileSync(f, 'utf8').split('\n')) {
      const s = line.trim();
      if (!s || s.startsWith('#')) continue;
      const eq = s.indexOf('=');
      if (eq > 0) m[s.slice(0, eq).trim()] = s.slice(eq + 1).trim();
    }
  }
  return m;
}
function mdFiles(dir) {
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir).filter(f => f.endsWith('.md')).sort().map(f => path.join(dir, f));
}
function firstDiff(a, b) {
  const n = Math.min(a.length, b.length);
  for (let i = 0; i < n; i++) if (a[i] !== b[i]) return i;
  return a.length === b.length ? -1 : n;
}
function ctx(s, i) {
  const from = Math.max(0, i - 40), to = Math.min(s.length, i + 60);
  return JSON.stringify(s.slice(from, to)) + ' …(偏移 ' + i + ')';
}

/* ---------- 主流程 ---------- */
const tier = process.argv[2] || '';
const filter = process.argv[3] || '';
const tiers = ['basic', 'advanced', 'js-only'].filter(t => !tier || tier === 'all' || t === tier);
if (!tiers.length) {   // 静默空跑比失败更危险：层级名写错必须立刻报错
  console.log('  FAIL 未知层级: ' + tier + '（可用: basic / advanced / js-only / all）');
  process.exit(1);
}

let lib;
try {
  lib = loadLib();
  console.log('库文件（按加载顺序）: ' + lib.files.join(' '));
} catch (e) {
  console.log('  FAIL ' + e.message);
  process.exit(1);
}

for (const t of tiers) {
  const dir = path.join(corpusRoot, t);
  const files = mdFiles(dir).filter(f => !filter || path.basename(f).includes(filter));
  if (!files.length) { console.log('\n[' + t + '] 无语料（跳过）'); continue; }
  console.log('\n[' + t + '] ' + files.length + ' 条语料');

  for (const mdFile of files) {
    const name = path.basename(mdFile, '.md');
    const md = fs.readFileSync(mdFile, 'utf8');
    const opts = optsOf(mdFile);
    let res;
    try {
      res = lib.w.SsvulMd.render(md, opts);
    } catch (e) {
      ok(false, t + '/' + name + ' → JS 抛异常: ' + e.message);
      continue;
    }
    const html = typeof res === 'string' ? res : res.html;
    if (typeof html !== 'string') { ok(false, t + '/' + name + ' → render 未返回字符串'); continue; }

    if (t === 'js-only') {
      const af = mdFile.replace(/\.md$/, '.assert');
      if (!fs.existsSync(af)) { ok(false, t + '/' + name + ' → 缺 .assert'); continue; }
      for (const line of fs.readFileSync(af, 'utf8').split('\n')) {
        const s = line.trim();
        if (!s || s.startsWith('#')) continue;
        const c = s.indexOf(':');
        const kind = s.slice(0, c), arg = s.slice(c + 1);
        if (kind === 'contains') ok(html.includes(arg), t + '/' + name + ' → 应包含 ' + JSON.stringify(arg));
        else if (kind === 'notcontains') ok(!html.includes(arg), t + '/' + name + ' → 不应包含 ' + JSON.stringify(arg));
        else ok(false, t + '/' + name + ' → 未知断言类型: ' + kind);
      }
      continue;
    }

    const expFile = mdFile.replace(/\.md$/, '.expected.html');
    if (!fs.existsSync(expFile)) { ok(false, t + '/' + name + ' → 缺金标（跑 MdDump gen 生成）'); continue; }
    const exp = fs.readFileSync(expFile, 'utf8');
    const at = firstDiff(html, exp);
    if (at < 0) { pass++; continue; }
    const which = at >= exp.length ? '（JS 多出内容）' : at >= html.length ? '（JS 少内容）' : '';
    failures.push(t + '/' + name + ' → 第 ' + at + ' 字节起不同 ' + which
      + '\n      Java: ' + ctx(exp, at) + '\n      JS  : ' + ctx(html, at));
    fail++;
  }
}

console.log('\n断言 ' + (pass + fail) + ' 项：通过 ' + pass + '，失败 ' + fail);
if (pass + fail === 0) {   // 空跑视为失败（语料目录写错/库目录为空都不会再悄悄通过）
  console.log('  FAIL 一条断言都没跑（检查语料目录与库目录）');
  process.exit(1);
}
if (fail) {
  console.log('\n--- 失败明细 ---');
  for (const f of failures) console.log('  ' + f);
}
process.exit(fail === 0 ? 0 : 1);
