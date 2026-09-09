/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Integra;


public enum WebType {
    HTML,
    CSS,
    JS,
    JSON,
    MD,
    CONFIG,
    GLOBAL,
    ADDS,
    CONTRACT,
    NULL;

    public static WebType checkName(String FileName){
        String[] parts = FileName.split("\\.");
        String extension = parts[parts.length-1];
        return switch (extension) {
            case "html" -> HTML;
            case "css" -> CSS;
            case "json" -> JSON;
            case "js" -> JS;
            case "md" -> MD;
            case "config" -> CONFIG;
            case "global" -> GLOBAL;
            case "adds" -> ADDS;
            case "contract" -> CONTRACT;
            default -> NULL;
        };
    }

}
