package dev.hotpath;

import java.util.ArrayList;

/**
 * Lightweight text parser for C++ structs and classes.
 * Preserves raw declarations, inline and preceding comments for refactoring.
 * Correctly accounts for template brackets <...> and parentheses when finding statement ends.
 */
public class CppStructParser {

    private CppStructParser() {
    }

    public static class Field {
        public String name = "";
        public String type = "";
        public String decl = "";
        public int size;
        public int align;
        public int offset;
        public String thread;
        public boolean atomic;
        public int bits = -1;
        public int explicitAlign;
        public int start;
        public int end;
        public int arrayCount = 1;

        public String leadingComment = "";
        public String trailingComment = "";

        public boolean known() {
            return align > 0 && size > 0;
        }

        public String toFullCode(String indent) {
            StringBuilder sb = new StringBuilder();
            if (leadingComment != null && !leadingComment.isEmpty()) {
                sb.append(indent).append(leadingComment).append("\n");
            }
            sb.append(indent).append(decl).append(";");
            if (trailingComment != null && !trailingComment.isEmpty()) {
                sb.append(" ").append(trailingComment);
            }
            return sb.toString();
        }
    }

    public static class StructInfo {
        public String name = "";
        public boolean union;
        public boolean hasVptr;
        public ArrayList<Field> fields = new ArrayList<>();
        public ArrayList<String[]> typedefs = new ArrayList<>();
        public ArrayList<String> notes = new ArrayList<>();
    }

    public static StructInfo parse(String regionText) {
        if (regionText == null) return null;
        String cleared = clearComments(regionText);
        int open = cleared.indexOf('{');
        if (open < 0) {
            return null;
        }
        String header = cleared.substring(0, open).trim();
        if (!header.contains("struct") && !header.contains("class") && !header.contains("union")) {
            return null;
        }
        StructInfo info = new StructInfo();
        info.union = headerWord(header, "union");
        info.name = extractName(header);

        if (cleared.contains("packed") || cleared.contains("pack(1)") || cleared.contains("pack(push, 1)")
                || cleared.contains("pack(push,1)")) {
            info.notes.add("pack(1)");
        }

        int bodyStart = open + 1;
        int bodyEnd = matchingClose(cleared, open);
        if (bodyEnd < 0) {
            bodyEnd = cleared.length();
        }
        int depth = 0;
        int angleDepth = 0;
        int stmtStart = bodyStart;
        StringBuilder stmt = new StringBuilder();
        int prevFieldEnd = bodyStart;

        for (int i = bodyStart; i < bodyEnd; i++) {
            char c = cleared.charAt(i);
            if (c == '{') {
                int close = matchingClose(cleared, i);
                if (close > 0 && looksLikeBody(cleared, stmtStart, i)) {
                    i = close;
                    stmt.setLength(0);
                    stmtStart = i + 1;
                    prevFieldEnd = stmtStart;
                    continue;
                }
                depth++;
            } else if (c == '}') {
                depth--;
            } else if (c == '(' || c == '[') {
                depth++;
            } else if (c == ')' || c == ']') {
                depth--;
            } else if (c == '<') {
                angleDepth++;
            } else if (c == '>') {
                if (angleDepth > 0) angleDepth--;
            }

            if (depth < 0) depth = 0;
            stmt.append(c);

            // Точка с запятой является концом декларации только вне скобок и шаблонов
            if (depth == 0 && angleDepth == 0 && c == ';') {
                String rawStmt = regionText.substring(stmtStart, Math.min(i + 1, regionText.length()));
                Field f = addStatement(info, stmt.toString(), rawStmt, stmtStart, i, regionText, prevFieldEnd);
                if (f != null) {
                    int lineEnd = regionText.indexOf('\n', i);
                    prevFieldEnd = (lineEnd != -1) ? lineEnd + 1 : i + 1;
                }
                stmt.setLength(0);
                stmtStart = i + 1;
            }
        }
        return info;
    }

    static Field addStatement(StructInfo info, String text, String raw, int start, int endPos, String fullRegion, int prevFieldEnd) {
        String s = text.trim();
        if (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        if (s.isEmpty() || (s.endsWith(":") && !s.contains("("))) {
            return null;
        }
        if (s.startsWith("static ") || startsWithWord(s, "static") || s.startsWith("friend ") || s.startsWith("template ")) {
            return null;
        }
        if (s.startsWith("typedef ")) {
            recordTypedef(info, s.substring("typedef ".length()));
            return null;
        }
        if (s.startsWith("using ")) {
            recordUsing(info, s.substring("using ".length()));
            return null;
        }
        int firstParen = s.indexOf('(');
        if (firstParen >= 0) {
            boolean funcPointer = firstParen + 1 < s.length() && s.charAt(firstParen + 1) == '*';
            if (!funcPointer) {
                if (s.contains("virtual ")) info.hasVptr = true;
                return null;
            }
        }
        if (s.startsWith("enum ")) {
            if (!s.contains("{") && countTopLevelWords(s) <= 1 && !s.contains("class ")) {
                Field f = parseField(s);
                if (f != null) {
                    finishField(info, f, raw, start, endPos, fullRegion, prevFieldEnd);
                    return f;
                }
            }
            return null;
        }
        if ((containsWord(s, "struct") || containsWord(s, "class")) && s.contains("{")) {
            String after = afterBrace(s);
            if (after.isEmpty()) return null;
            s = stripBraceBlock(s);
        }
        Field f = parseField(s);
        if (f != null) {
            finishField(info, f, raw, start, endPos, fullRegion, prevFieldEnd);
            return f;
        }
        return null;
    }

    static void finishField(StructInfo info, Field f, String raw, int start, int endPos, String fullRegion, int prevFieldEnd) {
        f.start = start;
        f.end = endPos;
        f.thread = findThreadTag(raw);
        if (f.thread == null) {
            f.thread = findThreadsTag(raw);
        }

        if (fullRegion != null && endPos >= 0 && endPos < fullRegion.length()) {
            int lineEnd = fullRegion.indexOf('\n', endPos);
            if (lineEnd == -1) lineEnd = fullRegion.length();
            if (lineEnd > endPos + 1) {
                String trailing = fullRegion.substring(endPos + 1, lineEnd).trim();
                if (trailing.startsWith("//") || trailing.startsWith("/*")) {
                    f.trailingComment = trailing;
                }
            }

            if (start > prevFieldEnd && prevFieldEnd >= 0 && start <= fullRegion.length()) {
                String between = fullRegion.substring(prevFieldEnd, start).trim();
                if (between.startsWith("//") || between.startsWith("/*")) {
                    f.leadingComment = between;
                }
            }
        }

        info.fields.add(f);
    }

    static Field parseField(String s) {
        Field f = new Field();
        String work = s.trim();

        int colon = topLevelIndexOf(work, ':');
        if (colon > 0) {
            String tail = work.substring(colon + 1).trim();
            if (tail.matches("\\d+")) {
                f.bits = Integer.parseInt(tail);
                work = work.substring(0, colon).trim();
            }
        }
        int eq = topLevelIndexOf(work, '=');
        if (eq >= 0) work = work.substring(0, eq).trim();
        int br = work.indexOf('{');
        if (br >= 0 && colon < 0) work = work.substring(0, br).trim();

        int al = work.indexOf("alignas(");
        if (al >= 0) {
            int close = work.indexOf(')', al);
            if (close > al) {
                try {
                    f.explicitAlign = Integer.parseInt(work.substring(al + 8, close).trim());
                } catch (Exception e) {
                    f.explicitAlign = 0;
                }
                work = (work.substring(0, al) + " " + work.substring(close + 1)).trim();
            }
        }

        int total = 1;
        int bracket = work.indexOf('[');
        while (bracket >= 0) {
            int close = work.indexOf(']', bracket);
            if (close < 0) break;
            String num = work.substring(bracket + 1, close).trim();
            try {
                total *= Integer.parseInt(num);
            } catch (Exception e) {
                total = -1;
            }
            work = (work.substring(0, bracket) + " " + work.substring(close + 1)).trim();
            bracket = work.indexOf('[');
        }
        f.arrayCount = total > 0 ? total : 1;
        f.decl = s;
        f.atomic = work.contains("atomic<") || work.contains("atomic_");
        int comma = topLevelIndexOf(work, ',');
        if (comma > 0) work = work.substring(0, comma).trim();

        f.name = lastIdentifier(work);
        f.type = typePart(work);
        if (f.name.isEmpty() || f.type.isEmpty()) {
            return null;
        }

        int[] sa = CppTypeResolver.sizeAndAlign(f.type);
        f.size = sa[0];
        f.align = sa[1];
        if (sa.length > 2 && sa[2] == 1) {
            f.atomic = true;
        }

        if (f.bits >= 0) {
            if (f.align > 0) f.size = Math.min(f.align, Math.max(1, (f.bits + 7) / 8));
        } else if (total == -1) {
            f.size = 0;
            f.align = 0;
        } else if (total > 1 && f.align > 0) {
            f.size = f.size * total;
        }
        return f;
    }

    static String findThreadTag(String raw) {
        int i = raw.indexOf("@thread ");
        if (i < 0) return null;
        if (raw.indexOf("@threads ") == i + 1) return null;
        return cleanTag(raw.substring(i + 8));
    }

    static String findThreadsTag(String raw) {
        int i = raw.indexOf("@threads ");
        if (i < 0) return null;
        String list = raw.substring(i + 9);
        return cleanTag(list.split(",")[0]);
    }

    static String cleanTag(String tag) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tag.length(); i++) {
            char c = tag.charAt(i);
            if (c == '\n' || c == '\r') break;
            if (c == '*' && i + 1 < tag.length() && tag.charAt(i + 1) == '/') break;
            sb.append(c);
        }
        String t = sb.toString().trim();
        return t.isEmpty() ? null : t;
    }

    public static String clearComments(String text) {
        char[] out = text.toCharArray();
        int state = 0;
        for (int i = 0; i < out.length; i++) {
            char c = out[i];
            char n = i + 1 < out.length ? out[i + 1] : 0;
            if (state == 0) {
                if (c == '/' && n == '/') {
                    state = 1;
                    out[i] = ' ';
                    out[i + 1] = ' ';
                    i++;
                } else if (c == '/' && n == '*') {
                    state = 2;
                    out[i] = ' ';
                    out[i + 1] = ' ';
                    i++;
                } else if (c == '"') {
                    state = 3;
                } else if (c == '\'') {
                    state = 4;
                }
            } else if (state == 1) {
                if (c == '\n') state = 0; else out[i] = ' ';
            } else if (state == 2) {
                out[i] = ' ';
                if (c == '*' && n == '/') {
                    out[i + 1] = ' ';
                    state = 0;
                    i++;
                }
            } else if (state == 3) {
                if (c == '\\') i++; else if (c == '"') state = 0;
            } else if (state == 4) {
                if (c == '\\') i++; else if (c == '\'') state = 0;
            }
        }
        return new String(out);
    }

    static void recordTypedef(StructInfo info, String rest) {
        String r = rest.trim();
        String alias = lastIdentifier(r);
        String target = typePart(r);
        if (!alias.isEmpty() && !target.isEmpty()) {
            info.typedefs.add(new String[]{alias, target});
        }
    }

    static void recordUsing(StructInfo info, String rest) {
        String r = rest.trim();
        int eq = r.indexOf('=');
        if (eq > 0) {
            info.typedefs.add(new String[]{r.substring(0, eq).trim(), r.substring(eq + 1).trim()});
        }
    }

    static int matchingClose(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return text.length();
    }

    static boolean looksLikeBody(String text, int from, int pos) {
        String pre = text.substring(Math.max(0, from), pos).trim();
        if (pre.isEmpty() || pre.endsWith("=")) return false;
        return pre.contains(")");
    }

    static String lastIdentifier(String s) {
        return lastIdentifierSimple(s);
    }

    static String typePart(String s) {
        String name = lastIdentifierSimple(s);
        if (name.isEmpty()) return s.trim();
        int idx = s.lastIndexOf(name);
        if (idx < 0) return s.trim();
        String t = s.substring(0, idx).trim();
        String tail = s.substring(idx + name.length()).trim();
        return (t + " " + tail).replaceAll("\\s+", " ").trim();
    }

    static boolean isIdChar(char c) {
        return Character.isJavaIdentifierPart(c);
    }

    static String lastIdentifierSimple(String s) {
        int angle = 0;
        StringBuilder flat = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') angle++;
            if (c == '>' && angle > 0) angle--;
            if (angle == 0) flat.append(c);
        }
        String t = flat.toString();
        int end = t.length();
        while (end > 0 && !isIdChar(t.charAt(end - 1))) end--;
        int begin = end;
        while (begin > 0 && isIdChar(t.charAt(begin - 1))) begin--;
        return t.substring(begin, end);
    }

    static int topLevelIndexOf(String s, char c) {
        int depth = 0;
        int angle = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '(' || ch == '[' || ch == '{') depth++;
            else if (ch == ')' || ch == ']' || ch == '}') depth--;
            else if (ch == '<') angle++;
            else if (ch == '>' && angle > 0) angle--;
            if (depth == 0 && angle == 0 && ch == c) return i;
        }
        return -1;
    }

    static String afterBrace(String s) {
        int open = s.indexOf('{');
        if (open < 0) return "";
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            if (s.charAt(i) == '{') depth++;
            else if (s.charAt(i) == '}') {
                depth--;
                if (depth == 0) return s.substring(i + 1).replace(",", "").trim();
            }
        }
        return "";
    }

    static String stripBraceBlock(String s) {
        int open = s.indexOf('{');
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            if (s.charAt(i) == '{') depth++;
            else if (s.charAt(i) == '}') {
                depth--;
                if (depth == 0) return (s.substring(0, open) + " " + s.substring(i + 1)).trim();
            }
        }
        return s.substring(0, open).trim();
    }

    static boolean containsWord(String s, String word) {
        return (" " + s + " ").contains(" " + word + " ");
    }

    static boolean headerWord(String header, String word) {
        return containsWord(header, word);
    }

    static boolean startsWithWord(String s, String word) {
        return s.equals(word) || s.startsWith(word + " ");
    }

    static int countTopLevelWords(String s) {
        return s.replaceAll("<[^>]*>", " X ").trim().split("\\s+").length;
    }

    static String extractName(String header) {
        String h = header.replaceAll("alignas\\s*\\([^)]*\\)", " ")
                .replaceAll("\\[\\[[^\\]]*\\]\\]", " ")
                .replaceAll("\\([^)]*\\)", " ");
        int colon = h.indexOf(':');
        if (colon >= 0) h = h.substring(0, colon);
        String[] parts = h.trim().split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals("struct") || parts[i].equals("class") || parts[i].equals("union")) {
                for (int j = i + 1; j < parts.length; j++) {
                    String cand = parts[j];
                    if (cand.equals("final") || cand.equals("alignas")) {
                        continue;
                    }
                    if (cand.matches("[A-Za-z_][A-Za-z0-9_:]*")) {
                        int scope = cand.lastIndexOf("::");
                        return scope >= 0 ? cand.substring(scope + 2) : cand;
                    }
                }
                return "";
            }
        }
        return "";
    }
}