package dev.omnilogin.identity;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tiny extractors for the flat JSON the session servers return (no JSON library needed). */
public final class Json {

    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-fA-F]{32})\"");
    private static final Pattern NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]{1,16})\"");

    private Json() {
    }

    public static UUID id(String body) {
        Matcher m = ID.matcher(body);
        if (!m.find()) {
            return null;
        }

        String h = m.group(1);
        return UUID.fromString(h.substring(0, 8) + "-" + h.substring(8, 12) + "-" + h.substring(12, 16)
                + "-" + h.substring(16, 20) + "-" + h.substring(20));
    }

    public static String name(String body) {
        Matcher m = NAME.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    /** The raw "properties" array (skin textures), or "[]". */
    public static String properties(String body) {
        int key = body.indexOf("\"properties\"");
        if (key < 0) {
            return "[]";
        }

        int start = body.indexOf('[', key);
        if (start < 0) {
            return "[]";
        }

        int depth = 0;
        boolean inString = false;
        for (int i = start; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return body.substring(start, i + 1);
                }
            }
        }

        return "[]";
    }
}
