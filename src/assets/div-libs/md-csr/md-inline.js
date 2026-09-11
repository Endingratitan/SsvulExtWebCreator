/* SsvulExtWebCreator · https://github.com/Endingratitan/SsvulExtWebCreator | MPL-2.0: redistributed with this notice and LICENSE/NOTICE | Copyright (c) 2026 Endingratitan */
/* 对应 Java: Integra/MarkdownIntergra/MdInline.java（行内解析）
   优先级链（与 Java 逐字同序）：\( 数学 → \ 转义 → `code` → $数学$ → 强调 → 链接/图片 → [[kbd]] → 脚注 → `自动链接`/原生 HTML。
   **两处有意的差异**（默认值，见 md-csr 指南）：
     · 原生行内 HTML：仅在 raw-html=on 时直出，否则转义（CSR 默认安全）。
     · 链接策略 relaxed：放行相对路径（../、./、/），仍拦截 javascript:/data:/vbscript:/file:。 */
(function (global) {
  var Md = global.SsvulMd = global.SsvulMd || {};

  var PUNCT = '\\`*_{}[]()#+-.!|~><$';

  Md.inline = {
    inline: function (ctx, s, lineNo, depth) {
      var out = [], i = 0, n = s.length;
      while (i < n) {
        var c = s.charAt(i);

        // LaTeX 原生定界符 \( … \)：置于转义分支之前，避免 \( 被当作转义吃掉
        if (c === '\\' && s.slice(i, i + 2) === '\\(') {
          var lj = s.indexOf('\\)', i + 2);
          if (lj > i + 1) {
            out.push('<span class="md-math" data-delim="latex">' + Md.esc(s.slice(i, lj + 2)) + '</span>');
            i = lj + 2;
            continue;
          }
          if (ctx.isStrict()) {
            ctx.error(lineNo, '行内数学未闭合（strict）', '请补 \\) 或改用 \\[ \\] 块数学', '\\(');
            out.push('\\(');
            i += 2;
            continue;
          }
          ctx.warn(lineNo, '行内数学未闭合（simple：按行尾收口渲染）');
          out.push('<span class="md-math" data-delim="latex">' + Md.esc(s.slice(i)) + '</span>');
          i = n;
          continue;
        }
        if (c === '\\' && i + 1 < n && PUNCT.indexOf(s.charAt(i + 1)) >= 0) {
          out.push(Md.escChar(s.charAt(i + 1)));
          i += 2;
          continue;
        }
        if (c === '`') {
          var cj = s.indexOf('`', i + 1);
          if (cj > i + 1) {
            out.push('<code class="md-code-inline">' + Md.esc(s.slice(i + 1, cj)) + '</code>');
            i = cj + 1;
            continue;
          }
        }
        if (c === '$' && mathOpen(s, i)) {
          var mj = mathClose(s, i);
          if (mj > 0) {
            out.push('<span class="md-math">' + Md.esc(s.slice(i, mj + 1)) + '</span>');
            i = mj + 1;
            continue;
          }
          if (i + 1 < n && /\d/.test(s.charAt(i + 1))) {   // 货币形态（$5）：保持字面
            out.push('$');
            i++;
            continue;
          }
          if (ctx.isStrict()) {
            ctx.error(lineNo, '行内数学未闭合（strict）', '请补 $ 或改用 $$ 块数学', '$');
            out.push('$');
            i++;
            continue;
          }
          ctx.warn(lineNo, '行内数学未闭合（simple：按行尾收口渲染）');
          out.push('<span class="md-math">' + Md.esc(s.slice(i)) + '</span>');
          i = n;
          continue;
        }
        if (depth < 2) {
          var j = -1;
          if (s.slice(i, i + 3) === '***' && i + 3 < n && !Md.isWS(s.charAt(i + 3))
              && (j = findClose(s, i + 3, '***', false)) >= 0) {
            out.push('<strong><em>' + Md.inline.inline(ctx, s.slice(i + 3, j), lineNo, depth + 1) + '</em></strong>');
            i = j + 3;
            continue;
          }
          if (s.slice(i, i + 2) === '**' && i + 2 < n && !Md.isWS(s.charAt(i + 2))
              && (j = findClose(s, i + 2, '**', false)) >= 0) {
            out.push('<strong>' + Md.inline.inline(ctx, s.slice(i + 2, j), lineNo, depth + 1) + '</strong>');
            i = j + 2;
            continue;
          }
          if (s.slice(i, i + 2) === '__' && openUnder(s, i, 2)
              && (j = findClose(s, i + 2, '__', true)) >= 0) {
            out.push('<strong>' + Md.inline.inline(ctx, s.slice(i + 2, j), lineNo, depth + 1) + '</strong>');
            i = j + 2;
            continue;
          }
          if (c === '*' && i + 1 < n && !Md.isWS(s.charAt(i + 1))
              && (j = findClose(s, i + 1, '*', false)) >= 0) {
            out.push('<em>' + Md.inline.inline(ctx, s.slice(i + 1, j), lineNo, depth + 1) + '</em>');
            i = j + 1;
            continue;
          }
          if (c === '_' && openUnder(s, i, 1)
              && (j = findClose(s, i + 1, '_', true)) >= 0) {
            out.push('<em>' + Md.inline.inline(ctx, s.slice(i + 1, j), lineNo, depth + 1) + '</em>');
            i = j + 1;
            continue;
          }
          if (s.slice(i, i + 2) === '~~' && i + 2 < n && !Md.isWS(s.charAt(i + 2))
              && (j = findClose(s, i + 2, '~~', false)) >= 0) {
            out.push('<del>' + Md.inline.inline(ctx, s.slice(i + 2, j), lineNo, depth + 1) + '</del>');
            i = j + 2;
            continue;
          }
        }
        if (s.slice(i, i + 2) === '![') {
          var iclose = s.indexOf('](', i + 2);
          if (iclose >= 0) {
            var ie = s.indexOf(')', iclose + 2);
            if (ie >= 0) {
              var alt = s.slice(i + 2, iclose);
              var iurl = s.slice(iclose + 2, ie).trim();
              if (checkUrl(ctx, iurl, lineNo, '图片')) {
                out.push('<img class="md-img" src="' + Md.esc(iurl) + '" alt="' + Md.esc(alt) + '" loading="lazy">');
                i = ie + 1;
                continue;
              }
            }
          }
        }
        if (c === '[') {
          var lclose = s.indexOf('](', i + 1);
          if (lclose >= 0) {
            var le = s.indexOf(')', lclose + 2);
            if (le >= 0) {
              var lurl = s.slice(lclose + 2, le).trim();
              if (checkUrl(ctx, lurl, lineNo, '链接')) {
                out.push('<a href="' + Md.esc(lurl) + '">'
                  + Md.inline.inline(ctx, s.slice(i + 1, lclose), lineNo, depth) + '</a>');
                i = le + 1;
                continue;
              }
            }
          }
        }
        if (s.slice(i, i + 2) === '[[') {
          var kj = s.indexOf(']]', i + 2);
          if (kj >= 0) {
            out.push('<kbd class="md-kbd">' + Md.esc(s.slice(i + 2, kj)) + '</kbd>');
            i = kj + 2;
            continue;
          }
        }
        if (c === '[' && i + 1 < n && s.charAt(i + 1) === '^') {
          var fclose = s.indexOf(']', i + 2);
          if (fclose > i + 2) {
            var label = s.slice(i + 2, fclose);
            var isRef = label === '.' || (/^\d+$/.test(label) && label.charAt(0) !== '0');
            if (!ctx.inFootDef && isRef && Md.footnotes && Md.footnotes.registerRef) {
              Md.footnotes.registerRef(ctx, label, lineNo, out);
              i = fclose + 1;
              continue;
            }
            if (!ctx.inFootDef && (label === '0' || /^\d+$/.test(label))) {
              ctx.error(lineNo, '脚注编号必须为正整数: [^' + label + ']', null, label);
            }
            // 定义内容内 / 脚注子系统未装载（批次 B）→ 字面，落入普通处理
          }
        }
        if (c === '<') {
          var e = s.indexOf('>', i + 1);
          if (e > i + 1) {
            var inner = s.slice(i + 1, e);
            if (inner.indexOf('http://') === 0 || inner.indexOf('https://') === 0) {
              out.push('<a href="' + Md.esc(inner) + '">' + Md.esc(inner) + '</a>');
              i = e + 1;
              continue;
            }
            if (ctx.rawHtml && !ctx.isStrict() && isRawTag(s, i, e)) {   // simple + raw-html=on：行内原生 HTML 直出
              out.push(s.slice(i, e + 1));
              i = e + 1;
              continue;
            }
          }
        }
        out.push(Md.escChar(c));
        i++;
      }
      return out.join('');
    }
  };

  /* ---- URL 策略（Java 用站点白名单；CSR 放宽相对路径，危险协议一律拦截） ---- */

  function checkUrl(ctx, url, lineNo, kind) {
    if (!url || /[ \t]/.test(url)) {
      ctx.error(lineNo, kind + ' URL 不能为空或含空白', null, url);
      return false;
    }
    if (!validUrl(ctx, url)) {
      ctx.error(lineNo, kind + ' URL 不符合白名单: ' + Md.snippet(url),
        '允许: http(s)://、pre-assets/、@data/、@page/、#锚点、相对路径；拦截 javascript:/data:/vbscript:/file:', url);
      return false;
    }
    return true;
  }

  function validUrl(ctx, u) {
    if (/^(javascript|data|vbscript|file):/i.test(u)) return false;      // 危险协议：两侧都拦
    if (u.indexOf('http://') === 0 || u.indexOf('https://') === 0) return true;
    if (u.indexOf('pre-assets/') === 0 || u.indexOf('@data/') === 0 || u.indexOf('@page/') === 0) return true;
    if (u.charAt(0) === '#') return true;
    if (/^[A-Za-z0-9_-]+\/.*/.test(u)) return true;                      // 预置/桶引用形态
    if (!ctx.linkRelaxed) return false;
    return !/^[A-Za-z][A-Za-z0-9+.-]*:/.test(u);                         // 无协议头 → 相对路径，放行
  }

  /* ---- 行内判定（与 Java 同规则） ---- */

  /** $ 开标记：前后都是边界（行首尾/空白/$）才视为孤立（货币等），否则是数学候选 */
  function mathOpen(s, i) {
    var prevB = i === 0 || Md.isWS(s.charAt(i - 1)) || s.charAt(i - 1) === '$';
    var nextB = i + 1 >= s.length || Md.isWS(s.charAt(i + 1)) || s.charAt(i + 1) === '$';
    return !(prevB && nextB);
  }

  /** 闭 $：前邻紧邻非空白非 $（后邻随意，行尾亦可）；跳过被 \ 转义的 $ */
  function mathClose(s, open) {
    for (var j = open + 2; j < s.length; j++) {
      if (s.charAt(j) !== '$') continue;
      if (j > 0 && s.charAt(j - 1) === '\\') continue;
      var p = s.charAt(j - 1);
      if (!Md.isWS(p) && p !== '$') return j;
    }
    return -1;
  }

  /** '_' 开标记：两侧不能同时是字母数字，且后一字符非空白 */
  function openUnder(s, i, len) {
    var prevAl = i > 0 && Md.isAlnum(s.charAt(i - 1));
    var nextAl = i + len < s.length && Md.isAlnum(s.charAt(i + len));
    var nextNotWs = i + len < s.length && !Md.isWS(s.charAt(i + len));
    return nextNotWs && (!prevAl || !nextAl);
  }

  /** under=true 用 '_' 闭规则，否则用 '*' 规则（前一字符非空白） */
  function findClose(s, from, mark, under) {
    var ml = mark.length;
    for (var j = from; j + ml <= s.length; j++) {
      if (s.slice(j, j + ml) !== mark) continue;
      var prevNotWs = j === 0 || !Md.isWS(s.charAt(j - 1));
      if (!prevNotWs) continue;
      if (under) {
        var prevAl = j > 0 && Md.isAlnum(s.charAt(j - 1));
        var nextAl = j + ml < s.length && Md.isAlnum(s.charAt(j + ml));
        if (prevAl && nextAl) continue;
      }
      return j;
    }
    return -1;
  }

  /** 形如 <tag ...> 的原生 HTML 片段（其间无嵌套 <） */
  function isRawTag(s, i, e) {
    var c = s.charAt(i + 1);
    if (!Md.isAlnum(c) && c !== '/' && c !== '!') return false;
    var next = s.indexOf('<', i + 1);
    return next === -1 || next > e;
  }
})(window);
