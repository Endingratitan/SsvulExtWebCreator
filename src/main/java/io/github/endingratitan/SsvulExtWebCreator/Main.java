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
