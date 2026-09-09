/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.WebMinify.js;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A 层声明扫描：提取单文件顶层 let/const/class 声明名（深度 0）。
 * 串联后同作用域重名 = SyntaxError，聚合前逐文件比对冲突。
 */
public final class JsDeclScan {

    private JsDeclScan() {}

    public static Set<String> topLevelBlockScoped(String js) {
        Set<String> names = new LinkedHashSet<>();
        List<Lex.Tok> toks = Lex.tokens(js);
        int depth = 0;
        for (int k = 0; k < toks.size(); k++) {
            Lex.Tok t = toks.get(k);
            if (t.type() == Lex.PUNCT) {
                if (t.text().equals("{")) depth++;
                else if (t.text().equals("}")) depth = Math.max(0, depth - 1);
                continue;
            }
            if (depth > 0) continue;   // 仅顶层声明（嵌套作用域重名合法）
            if (t.type() == Lex.KEYWORD
                    && (t.text().equals("let") || t.text().equals("const") || t.text().equals("class"))) {
                int p = k + 1;
                while (p < toks.size() && toks.get(p).type() == Lex.NL) p++;
                if (p < toks.size() && toks.get(p).type() == Lex.IDENT) names.add(toks.get(p).text());
            }
        }
        return names;
    }
}
