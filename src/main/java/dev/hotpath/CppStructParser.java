package dev.hotpath;

import java.util.ArrayList;

/**
 * Lightweight text parser for C++ structs and classes. No compilation is
 * involved: the parser works on raw editor text only. It finds field
 * declarations, skips method bodies, constructors, access specifiers and
 * static members, and records thread annotations taken from comments like
 * {@code // @thread producer}.
 */
public class CppStructParser {

    /** Closed because all methods are static. */
    private CppStructParser() {
    }

    /** One non-static data member of a struct. */
    public static class Field {
        /** Field name, "?" when it cannot be determined. */
        public String name = "?";
        /** Type as written in the declaration. */
        public String type = " ";
        /** Full declaration text without ';', used by the optimize action. */
        public String decl = " ";
        /** Size in bytes, 0 when the type is unknown. */
        public int size;
        /** Alignment in bytes, 0 when the type is unknown. */
        public int align;
        /** Offset inside the struct, filled by LayoutCalculator. */
        public int offset;
        /** Thread tag from "@thread x" comment, null when absent. */
        public String thread;
        /** True when the type contains std::atomic. */
        public boolean atomic;
        /** Bitfield width from "int flags : 3;", -1 when not a bitfield. */
        public int bits = -1;
        /** Explicit alignas(N) value from the declaration, 0 when absent. */
        public int explicitAlign;
        /** Start of the declaration inside the region text. */
        public int start;
        /** Position of the semicolon inside the region text. */
        public int end;

        /**
         * True when size and alignment were resolved.
         * @return {@code true} if the layout of this field is known
         */
        public boolean known() {
            return align > 0 && size > 0;
        }
    }

    /** Everything the parser learned about one marked struct. */
    public static class StructInfo {
        /** Struct or class name, empty when anonymous. */
        public String name = " ";
        /** True for "union". */
        public boolean union;
        /** True when the body contains a virtual method (vptr expected). */
        public boolean hasVptr;
        /** Fields in declaration order. */
        public ArrayList<Field> fields = new ArrayList<>();
        /** Local typedef/using aliases found in the body. */
        public ArrayList<String[]> typedefs = new ArrayList<>();
        /** Free-form notes explaining partially unknown layout. */
        public ArrayList<String> notes = new ArrayList<>();
    }

    /**
     * Parses the block of one region: text from the line after the marker
     * up to and including the closing brace.
     * @param regionText text of the struct block, comments intact
     * @return parsed info or {@code null} when the header has no struct
     */
    public static StructInfo parse(String regionText) {
        // Cleared text is used for parsing, but it has exactly the same
        // length as the raw text, so statement bounds can be sliced from
        // the raw text as well. Thread tags live inside comments and must
        // be read from the raw slice, not from the cleared one.
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
        int bodyStart = open + 1;
        int bodyEnd = matchingClose(cleared, open);
        if (bodyEnd < 0) {
            bodyEnd = cleared.length();
        }
        int depth = 0;
        int stmtStart = bodyStart;
        StringBuilder stmt = new StringBuilder();
        for (int i = bodyStart; i < bodyEnd; i++) {
            char c = cleared.charAt(i);
            if (c == '{') {
                // A '{' right after ')' opens a function body: skip it whole.
                int close = matchingClose(cleared, i);
                if (close > 0 && looksLikeBody(cleared, stmtStart, i)) {
                    i = close;
                    stmt.setLength(0);
                    stmtStart = i + 1;
                    continue;
                }
                depth++;
            } else if (c == '}' || c == '(' || c == '[') {
                if (c != '}') {
                    depth++;
                } else {
                    depth--;
                }
            } else if (c == ')' || c == ']') {
                depth--;
            }
            if (depth < 0) {
                depth = 0;
            }
            stmt.append(c);
            if (depth == 0 && c == ';') {
                String rawStmt = regionText.substring(stmtStart, Math.min(i + 1, regionText.length()));
                addStatement(info, stmt.toString(), rawStmt, stmtStart, i);
                stmt.setLength(0);
                stmtStart = i + 1;
            }
        }
        return info;
    }

    /**
     * Decides what to do with one top-level statement of the body.
     * @param info   struct being filled
     * @param text   cleared statement text (comments blanked)
     * @param raw    original statement text with comments, for thread tags
     * @param start  statement start in the region text
     * @param endPos position of ';' in the region text
     */
    static void addStatement(StructInfo info, String text, String raw, int start, int endPos) {
        String s = text.trim();
        if (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        if (s.isEmpty()) {
            return;
        }
        // Access specifier ("public:"), labels: no storage.
        if (s.endsWith(":") && !s.contains("(")) {
            return;
        }
        if (s.startsWith("static ") || startsWithWord(s, "static")
                || s.startsWith("friend ") || s.startsWith("template ")) {
            return;
        }
        if (s.startsWith("typedef ")) {
            recordTypedef(info, s.substring("typedef ".length()));
            return;
        }
        if (s.startsWith("using ")) {
            recordUsing(info, s.substring("using ".length()));
            return;
        }
        // Methods, constructors and destructors have parentheses before any
        // initializer. Pointer-to-function fields look like "(*cb)" instead.
        int firstParen = s.indexOf('(');
        if (firstParen >= 0) {
            boolean funcPointer = firstParen + 1 < s.length() && s.charAt(firstParen + 1) == '*';
            if (!funcPointer) {
                if (s.contains("virtual ")) {
                    info.hasVptr = true;
                }
                return;
            }
        }
        if (s.startsWith("enum ")) {
            // "enum Color c;" is a field, "enum {...};" or "enum class X;" is not.
            if (!s.contains("{") && countTopLevelWords(s) <= 1 && !s.contains("class ")) {
                Field f = parseField(s);
                if (f != null) {
                    finishField(info, f, raw, start, endPos);
                    return;
                }
            }
            info.notes.add("nested enum definition '" + shorten(s) + "' skipped");
            return;
        }
        // Nested struct/class definition. Without an instance name it takes
        // no space; with one ("struct { ... } inner;") we keep the instance.
        if ((containsWord(s, "struct") || containsWord(s, "class")) && s.contains("{")) {
            String after = afterBrace(s);
            if (after.isEmpty()) {
                info.notes.add("nested type definition found, its own layout is not merged");
                return;
            }
            s = stripBraceBlock(s);
        }
        Field f = parseField(s);
        if (f != null) {
            finishField(info, f, raw, start, endPos);
        } else {
            info.notes.add("cannot parse '" + shorten(text) + "'");
        }
    }

    /**
     * Stores positions and thread tag into a parsed field and adds it.
     * @param info   struct info
     * @param f      parsed field
     * @param raw    original statement text with comments
     * @param start  statement start in region text
     * @param endPos semicolon position in region text
     */
    static void finishField(StructInfo info, Field f, String raw, int start, int endPos) {
        f.start = start;
        f.end = endPos;
        f.thread = findThreadTag(raw);
        if (f.thread == null) {
            f.thread = findThreadsTag(raw);
        }
        info.fields.add(f);
    }

    /**
     * Builds a field out of a cleaned declarator like
     * "std::atomic<size_t> head", "int arr[10]" or "unsigned x : 3".
     * @param s statement without the trailing semicolon, comments blanked
     * @return the field or {@code null} when it cannot be understood
     */
    static Field parseField(String s) {
        Field f = new Field();
        String work = s.trim();
        // Bitfield: "int flags : 3". Only when ':' is at top level and the
        // part after it is a number.
        int colon = topLevelIndexOf(work, ':');
        if (colon > 0) {
            String tail = work.substring(colon + 1).trim();
            if (tail.matches("\\d+")) {
                f.bits = Integer.parseInt(tail);
                work = work.substring(0, colon).trim();
            }
        }
        // Default member initializer: "= 5", "{10}", "(0)".
        int eq = topLevelIndexOf(work, '=');
        if (eq >= 0) {
            work = work.substring(0, eq).trim();
        }
        int br = work.indexOf('{');
        if (br >= 0 && colon < 0) {
            work = work.substring(0, br).trim();
        }
        // Explicit alignment: "alignas(64) int x;" or "int x alignas(64);".
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
        // Array suffixes: "int buf[16][3]".
        int total = 1;
        int bracket = work.indexOf('[');
        while (bracket >= 0) {
            int close = work.indexOf(']', bracket);
            if (close < 0) {
                break;
            }
            String num = work.substring(bracket + 1, close).trim();
            try {
                total *= Integer.parseInt(num);
            } catch (Exception e) {
                total = -1;
            }
            work = (work.substring(0, bracket) + " " + work.substring(close + 1)).trim();
            bracket = work.indexOf('[');
        }
        f.decl = s;
        f.atomic = work.contains("atomic<");
        // Multiple declarators "int a, b;" - resolve the first one and note
        // that the rest was ignored.
        int comma = topLevelIndexOf(work, ',');
        if (comma > 0) {
            work = work.substring(0, comma).trim();
        }
        f.name = lastIdentifier(work);
        f.type = typePart(work);
        if (f.name.isEmpty() || f.type.isEmpty()) {
            return null;
        }
        int[] sa = CppTypeResolver.sizeAndAlign(f.type);
        f.size = sa[0];
        f.align = sa[1];
        if (sa[2] == 1) {
            f.atomic = true;
        }
        if (f.bits >= 0) {
            // Bitfields: best effort, ceil(bits/8) bytes capped by base size.
            if (f.align > 0) {
                f.size = Math.min(f.align, Math.max(1, (f.bits + 7) / 8));
            }
        } else if (total == -1) {
            f.size = 0;
            f.align = 0;
        } else if (total > 1 && f.align > 0) {
            f.size = f.size * total;
        }
        return f;
    }

    /**
     * Finds the single-thread annotation "@thread name" in raw text.
     * @param raw original text including comments
     * @return tag like "producer" or {@code null}
     */
    static String findThreadTag(String raw) {
        int i = raw.indexOf("@thread ");
        if (i < 0) {
            return null;
        }
        if (raw.indexOf("@threads ") == i + 1) {
            return null;
        }
        String tag = raw.substring(i + 8);
        return cleanTag(tag);
    }

    /**
     * Finds the multi-thread annotation "@threads a,b". Returns the first
     * name; the full list is handled by FalseSharingAnalyzer.
     * @param raw original text including comments
     * @return first tag or {@code null}
     */
    static String findThreadsTag(String raw) {
        int i = raw.indexOf("@threads ");
        if (i < 0) {
            return null;
        }
        String list = raw.substring(i + 9);
        String first = list.split(",")[0];
        return cleanTag(first);
    }

    /**
     * Trims a thread tag at end-of-comment characters.
     * @param tag raw tag text
     * @return cleaned tag or {@code null} when empty
     */
    static String cleanTag(String tag) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tag.length(); i++) {
            char c = tag.charAt(i);
            if (c == '\n' || c == '\r') {
                break;
            }
            if (c == '*' && i + 1 < tag.length() && tag.charAt(i + 1) == '/') {
                break;
            }
            sb.append(c);
        }
        String t = sb.toString().trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * Replaces comment content with spaces so that positions of all other
     * characters stay unchanged. String and char literals are preserved.
     * @param text region text
     * @return text of the same length with comments blanked out
     */
    public static String clearComments(String text) {
        char[] out = text.toCharArray();
        int state = 0; // 0 code, 1 line comment, 2 block comment, 3 string, 4 char
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
                if (c == '\n') {
                    state = 0;
                } else {
                    out[i] = ' ';
                }
            } else if (state == 2) {
                out[i] = ' ';
                if (c == '*' && n == '/') {
                    out[i + 1] = ' ';
                    state = 0;
                    i++;
                }
            } else if (state == 3) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    state = 0;
                }
            } else if (state == 4) {
                if (c == '\\') {
                    i++;
                } else if (c == '\'') {
                    state = 0;
                }
            }
        }
        return new String(out);
    }

    /** Records a local alias "typedef T name;". */
    static void recordTypedef(StructInfo info, String rest) {
        String r = rest.trim();
        String alias = lastIdentifier(r);
        String target = typePart(r);
        if (!alias.isEmpty() && !target.isEmpty()) {
            info.typedefs.add(new String[]{alias, target});
        }
    }

    /** Records "using X = T;" and ignores namespace usings. */
    static void recordUsing(StructInfo info, String rest) {
        String r = rest.trim();
        int eq = r.indexOf('=');
        if (eq > 0) {
            info.typedefs.add(new String[]{r.substring(0, eq).trim(), r.substring(eq + 1).trim()});
        }
    }

    /** Index of the brace matching the one at {@code open}. */
    static int matchingClose(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return text.length();
    }

    /**
     * Heuristic: a '{' opens a function body when the statement before it
     * already contains a ')', i.e. a call signature.
     */
    static boolean looksLikeBody(String text, int from, int pos) {
        String pre = text.substring(Math.max(0, from), pos).trim();
        if (pre.isEmpty()) {
            return false;
        }
        if (pre.endsWith("=")) {
            return false;
        }
        return pre.contains(")");
    }

    /** Bare name of the last identifier in a declarator. */
    static String lastIdentifier(String s) {
        return lastIdentifierSimple(s);
    }

    /**
     * Everything except the final identifier, plus stars and references
     * that belong to the type: "int *p" -> "int *", "vector<int> v" -> "vector<int>".
     */
    static String typePart(String s) {
        String name = lastIdentifierSimple(s);
        if (name.isEmpty()) {
            return s.trim();
        }
        int idx = s.lastIndexOf(name);
        if (idx < 0) {
            return s.trim();
        }
        String t = s.substring(0, idx).trim();
        String tail = s.substring(idx + name.length()).trim();
        return (t + " " + tail).replaceAll("\\s+", " ").trim();
    }

    /** Checks if a character is a valid identifier character. */
    static boolean isIdChar(char c) {
        return Character.isJavaIdentifierPart(c);
    }

    /** Plain last-identifier scan used by {@code typePart}. */
    static String lastIdentifierSimple(String s) {
        // Ignore anything inside <...>.
        int angle = 0;
        StringBuilder flat = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') {
                angle++;
            }
            if (c == '>' && angle > 0) {
                angle--;
            }
            if (angle == 0) {
                flat.append(c);
            }
        }
        String t = flat.toString();
        int end = t.length();
        while (end > 0 && !isIdChar(t.charAt(end - 1))) {
            end--;
        }
        int begin = end;
        while (begin > 0 && isIdChar(t.charAt(begin - 1))) {
            begin--;
        }
        return t.substring(begin, end);
    }

    /** Index of a character at parenthesis/bracket/template depth zero. */
    static int topLevelIndexOf(String s, char c) {
        int depth = 0;
        int angle = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '(' || ch == '[' || ch == '{') {
                depth++;
            } else if (ch == ')' || ch == ']' || ch == '}') {
                depth--;
            } else if (ch == '<') {
                angle++;
            } else if (ch == '>' && angle > 0) {
                angle--;
            }
            if (depth == 0 && angle == 0 && ch == c) {
                return i;
            }
        }
        return -1;
    }

    /** Text after the closing brace of a nested block. */
    static String afterBrace(String s) {
        int open = s.indexOf('{');
        if (open < 0) {
            return "";
        }
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            if (s.charAt(i) == '{') {
                depth++;
            } else if (s.charAt(i) == '}') {
                depth--;
                if (depth == 0) {
                    return s.substring(i + 1).replace(",", "").trim();
                }
            }
        }
        return "";
    }

    /** Removes a "{...}" block from a statement, keeping surrounding text. */
    static String stripBraceBlock(String s) {
        int open = s.indexOf('{');
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            if (s.charAt(i) == '{') {
                depth++;
            } else if (s.charAt(i) == '}') {
                depth--;
                if (depth == 0) {
                    return (s.substring(0, open) + " " + s.substring(i + 1)).trim();
                }
            }
        }
        return s.substring(0, open).trim();
    }

    /** True when the text contains a standalone word. */
    static boolean containsWord(String s, String word) {
        return (" " + s + " ").contains(" " + word + " ");
    }

    /** Alias of {@code containsWord} used for header checks. */
    static boolean headerWord(String header, String word) {
        return containsWord(header, word);
    }

    /** True when the statement starts with the given word. */
    static boolean startsWithWord(String s, String word) {
        return s.equals(word) || s.startsWith(word + " ");
    }

    /** Counts words outside brackets, used to spot "enum Color c;". */
    static int countTopLevelWords(String s) {
        String flat = s.replaceAll("<[^>]*>", " X ");
        return flat.trim().split("\\s+").length;
    }

    /** Extracts the struct name from a header like "struct alignas(16) Order : Base". */
    static String extractName(String header) {
        String h = header.replaceAll("\\([^)]*\\)", "  ");
        int colon = h.indexOf(':');
        if (colon >= 0) {
            h = h.substring(0, colon);
        }
        String[] parts = h.trim().split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals("struct") || parts[i].equals("class") || parts[i].equals("union")) {
                if (i + 1 < parts.length) {
                    String cand = parts[i + 1];
                    if (cand.equals("final") && i + 2 < parts.length) {
                        return parts[i + 2];
                    }
                    if (cand.matches("[A-Za-z_][A-Za-z0-9_:]*")) {
                        int scope = cand.lastIndexOf("::");
                        return scope >= 0 ? cand.substring(scope + 2) : cand;
                    }
                }
                return " ";
            }
        }
        return " ";
    }

    /** Shortens a statement for note messages. */
    static String shorten(String s) {
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() <= 30 ? s : s.substring(0, 30) + "...";
    }
}