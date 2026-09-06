/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * 项目来源: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.SsvulExtWebCreator;

import java.io.File;

public class Tools {

    static boolean detectedAndCreate(String arrow,String say) {

        File p = new File("./" + arrow);
        if (!p.exists()) {
            if(p.mkdir()) IO.println(say);else throw new RuntimeException("Creating Failed!");
            return false;
        }else {IO.println(arrow + " dir detected");
            return true;}

    }

    static boolean initOpt() {
        File p = new File("./output");
        p.mkdir();
        p= new File("./output/assets");
        p.mkdir();
        p= new File("./output/pages");
        p.mkdir();
        p= new File("./output/favicon");
        p.mkdir();
        p= new File("./output/assets/data");
        p.mkdir();
        p= new File("./output/assets/js");
        p.mkdir();
        p= new File("./output/assets/css");
        p.mkdir();
        return true;
    };

    static boolean deleteD(File arrowDir) {

        if (!arrowDir.isDirectory()) {return false;}
        File[] files = arrowDir.listFiles();
        if (files == null) {return false;}
        for (File thefile : files) {
            if (thefile.isFile()) {
                thefile.delete();
            }else if (thefile.isDirectory()) {
                deleteD(thefile);
            }
        }
        return arrowDir.delete();
    }
}
