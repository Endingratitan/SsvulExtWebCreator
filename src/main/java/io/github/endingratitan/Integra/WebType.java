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
            default -> NULL;
        };
    }

}
