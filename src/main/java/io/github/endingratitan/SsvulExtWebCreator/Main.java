/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * 项目来源: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.SsvulExtWebCreator;


import io.github.endingratitan.Integra.IntegraMachiner;
import io.github.endingratitan.Integra.SiteBuilder;
import io.github.endingratitan.Integra.WebType;
import io.github.endingratitan.SsvulExtWebCreator.Tools;

import java.io.File;



//TIP 要<b>运行</b>代码，请按 <shortcut actionId="Run"/> 或
// 点击装订区域中的 <icon src="AllIcons.Actions.Execute"/> 图标。
public class Main {
    static void main() {
        initializingPrj();
        SiteBuilder.build(new File("sets"), new File("output"), new File("src/assets"));
        IO.println("Build Done!");
    }



    static void initializingPrj() {
        if(Tools.detectedAndCreate("output","Project is Initializing")){
            Tools.deleteD(new File("./output"));

        }
        Tools.initOpt();

        Tools.detectedAndCreate("sets","");
    }

}
