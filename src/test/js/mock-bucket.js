/*
 * mock 桶 + 静态服务（node 内建 http，零依赖）——用于**没有真实对象存储时**验证 ssvul:s3 的端到端链路：
 * 真实 fetch → 真实 CORS → 真实 XML 解析 → 真实 DOM 渲染。
 *
 * 用法：
 *   node src/test/js/mock-bucket.js <bucketRoot> [staticRoot] [bucketPort] [staticPort]
 *     bucketRoot  桶根目录（其下所有文件会被列出来；key = 相对路径）
 *     staticRoot  可选：站点输出目录（如 build/base-1），挂在另一个端口上 → 顺带验证跨域 CORS
 *     bucketPort  默认 8791；staticPort 默认 8792
 * 例：
 *   node src/test/js/mock-bucket.js build/mock-bucket build/s3-1
 *
 * 然后 Environment.config 里把桶指向 mock（注意是两个不同源，正好验 CORS）：
 *   bucket=[bk,http://127.0.0.1:8791,endpoint=http://127.0.0.1:8791]
 * 页面访问： http://127.0.0.1:8792/pages/blog-s3/index.html
 */
'use strict';
const http = require('http');
const fs = require('fs');
const path = require('path');

const bucketRoot = path.resolve(process.argv[2] || 'build/mock-bucket');
const staticRoot = process.argv[3] ? path.resolve(process.argv[3]) : '';
const bucketPort = parseInt(process.argv[4] || '8791', 10);
const staticPort = parseInt(process.argv[5] || '8792', 10);

function walk(dir, base, out) {
  for (const name of fs.readdirSync(dir)) {
    const full = path.join(dir, name);
    const rel = base ? base + '/' + name : name;
    const st = fs.statSync(full);
    if (st.isDirectory()) walk(full, rel, out);
    else out.push({ key: rel, size: st.size, mtime: st.mtime.toISOString() });
  }
  return out;
}
const esc = s => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

/* 最小 ListObjectsV2：支持 prefix / delimiter / max-keys / continuation-token（token 就是起始下标） */
function listXml(url) {
  const q = url.searchParams;
  const prefix = q.get('prefix') || '';
  const max = parseInt(q.get('max-keys') || '1000', 10);
  const from = parseInt(q.get('continuation-token') || '0', 10);
  const delim = q.get('delimiter') || '';
  const all = fs.existsSync(bucketRoot) ? walk(bucketRoot, '', []) : [];
  const hit = all.filter(o => o.key.startsWith(prefix));
  /* delimiter=/ 时，前缀之后再出现 '/' 的键要折进 CommonPrefixes（真实 S3 行为：当前层、不递归） */
  const prefixes = [];
  const flat = [];
  for (const o of hit) {
    const rest = o.key.slice(prefix.length);
    const slash = delim ? rest.indexOf(delim) : -1;
    if (slash >= 0) {
      const cp = prefix + rest.slice(0, slash + 1);
      if (!prefixes.includes(cp)) prefixes.push(cp);
    } else flat.push(o);
  }
  const page = flat.slice(from, from + max);
  const truncated = from + max < flat.length;
  return '<?xml version="1.0" encoding="UTF-8"?>\n<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">'
    + '<Name>mock-bucket</Name><Prefix>' + esc(prefix) + '</Prefix><KeyCount>' + page.length + '</KeyCount>'
    + '<MaxKeys>' + max + '</MaxKeys><Delimiter>' + esc(delim) + '</Delimiter>'
    + '<IsTruncated>' + truncated + '</IsTruncated>'
    + (truncated ? '<NextContinuationToken>' + (from + max) + '</NextContinuationToken>' : '')
    + page.map(o => '<Contents><Key>' + esc(o.key) + '</Key><LastModified>' + o.mtime + '</LastModified>'
      + '<ETag>&quot;mock&quot;</ETag><Size>' + o.size + '</Size><StorageClass>STANDARD</StorageClass></Contents>').join('')
    + prefixes.map(p => '<CommonPrefixes><Prefix>' + esc(p) + '</Prefix></CommonPrefixes>').join('')
    + '</ListBucketResult>';
}

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, HEAD, OPTIONS',
  'Access-Control-Allow-Headers': '*',
  'Access-Control-Max-Age': '600',
};

/* 桶端口：任何路径都当 list API；带 key 查询时按对象取文件 */
http.createServer((req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1');
  if (req.method === 'OPTIONS') { res.writeHead(204, CORS); return res.end(); }
  if (url.searchParams.get('list-type') === '2') {
    res.writeHead(200, Object.assign({ 'Content-Type': 'application/xml; charset=utf-8' }, CORS));
    return res.end(listXml(url));
  }
  const key = decodeURIComponent(url.pathname.replace(/^\/+/, ''));
  const file = path.join(bucketRoot, key);
  if (key && file.startsWith(bucketRoot) && fs.existsSync(file) && fs.statSync(file).isFile()) {
    res.writeHead(200, Object.assign({ 'Content-Type': 'text/html; charset=utf-8' }, CORS));
    return res.end(fs.readFileSync(file));
  }
  res.writeHead(404, Object.assign({ 'Content-Type': 'application/xml' }, CORS));
  res.end('<Error><Code>NoSuchKey</Code><Message>' + esc(key) + '</Message></Error>');
}).listen(bucketPort, '127.0.0.1', () => {
  console.log('mock bucket : http://127.0.0.1:' + bucketPort + '  (root ' + bucketRoot + ')');
  console.log('  list API  : http://127.0.0.1:' + bucketPort + '?list-type=2&prefix=&delimiter=%2F');
  console.log('  config    : bucket=[bk,http://127.0.0.1:' + bucketPort + '/,endpoint=http://127.0.0.1:' + bucketPort + ']');
});

if (staticRoot) {
  const types = { '.html': 'text/html; charset=utf-8', '.css': 'text/css', '.js': 'text/javascript', '.json': 'application/json', '.png': 'image/png', '.ico': 'image/x-icon' };
  http.createServer((req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    let p = path.join(staticRoot, decodeURIComponent(url.pathname));
    if (fs.existsSync(p) && fs.statSync(p).isDirectory()) p = path.join(p, 'index.html');
    if (p.startsWith(staticRoot) && fs.existsSync(p) && fs.statSync(p).isFile()) {
      res.writeHead(200, Object.assign({ 'Content-Type': types[path.extname(p)] || 'application/octet-stream' }, CORS));
      return res.end(fs.readFileSync(p));
    }
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('404 ' + url.pathname);
  }).listen(staticPort, '127.0.0.1', () => {
    console.log('static site : http://127.0.0.1:' + staticPort + '  (root ' + staticRoot + ')');
  });
}
