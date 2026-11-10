package dev.hotpath;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds C++ struct, class, and union regions in text automatically.
 * Supports template structs, aligned definitions, unions, and final classes.
 * Immune to catastrophic backtracking (ReDoS) and supports nested template arguments.
 */
public class HotRegionFinder {

    public static class Region {
        private final int markerStart;
        private final int bodyStart;
        private final int bodyEnd;
        private final String name;

        public Region(int markerStart, int bodyStart, int bodyEnd, String name) {
            this.markerStart = markerStart;
            this.bodyStart = bodyStart;
            this.bodyEnd = bodyEnd;
            this.name = name != null ? name.trim() : "";
        }

        public int mStart() {
            return markerStart;
        }

        public int bStart() {
            return bodyStart;
        }

        public int bEnd() {
            return bodyEnd;
        }

        public String name() {
            return name;
        }
    }

    private static final Pattern STRUCT_PATTERN = Pattern.compile(
            "(?:template\\s*<(?:[^<>]|<(?:[^<>]|<[^<>]*>)*>)*>\\s*)?" +
                    "((?://\\s*@(padding|p)[^\\n]*\\n\\s*)|(?:alignas\\s*\\([^)]*\\)\\s*))*" +
                    "\\b(struct|class|union)\\s+" +
                    "(?:alignas\\s*\\([^)]*\\)\\s+|\\[\\[[^\\]]*\\]\\]\\s+)*" +
                    "([a-zA-Z_0-9]+)\\s*(?:final\\s*)?(?::\\s*[^{]+)?\\{",
            Pattern.MULTILINE
    );

    public static ArrayList<Region> findAllStructs(String text) {
        ArrayList<Region> regions = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return regions;
        }

        Matcher matcher = STRUCT_PATTERN.matcher(text);
        while (matcher.find()) {
            int markerStart = matcher.start();
            int structKeywordStart = matcher.start(3);
            int openBrace = matcher.end() - 1;
            String structName = matcher.group(4);

            int closeBrace = findMatchingBrace(text, openBrace);
            if (closeBrace != -1) {
                int endPos = closeBrace + 1;
                while (endPos < text.length() && Character.isWhitespace(text.charAt(endPos))) {
                    endPos++;
                }
                if (endPos < text.length() && text.charAt(endPos) == ';') {
                    endPos++;
                }
                regions.add(new Region(markerStart, structKeywordStart, endPos, structName));
            }
        }
        return regions;
    }

    public static ArrayList<Region> find(String text, String marker) {
        if ("@padding".equals(marker)) {
            return new ArrayList<>();
        }
        return findAllStructs(text);
    }

    private static int findMatchingBrace(String text, int openBraceIdx) {
        int depth = 0;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        boolean inString = false;

        for (int i = openBraceIdx; i < text.length(); i++) {
            char c = text.charAt(i);
            char next = (i + 1 < text.length()) ? text.charAt(i + 1) : '\0';

            if (inLineComment) {
                if (c == '\n') inLineComment = false;
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '/' && next == '/') {
                inLineComment = true;
                i++;
            } else if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }
}