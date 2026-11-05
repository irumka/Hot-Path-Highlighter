package dev.hotpath;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Resolves C++ types to size and alignment using pure text rules. No
 * compiler is called: the resolver uses built-in tables, the ABI chosen
 * in settings, and the user-maintained custom type database. The result
 * of {@code sizeAndAlign} is a small int array: {size, align, atomicFlag},
 * where size 0 means "unknown".
 */
public class CppTypeResolver {

    /**
     * Closed because all methods are static.
     */
    private CppTypeResolver() {
    }

    /**
     * Looks up one type string. Tries, in order: the custom table from
     * settings, built-in basics, fixed-width integers, pointers,
     * references, STL containers, known structs in the same file, enums,
     * local typedefs and standard integer typedefs.
     *
     * @param type type text as written in the declaration
     * @return {size, align, atomicFlag}, size 0 when unknown
     */
    public static int[] sizeAndAlign(String type) {
        HotPathSettings st = HotPathSettings.get();
        return sizeAndAlign(type, st, null);
    }

    /**
     * Full resolution with context.
     *
     * @param type   type text as written
     * @param st     current settings (custom table and ABI sizes)
     * @param locals struct names found in the same file, may be null
     * @return {size, align, atomicFlag}, size 0 when unknown
     */
    public static int[] sizeAndAlign(String type, HotPathSettings st, HashMap<String, int[]> locals) {
        String t = normalize(type);
        if (t.isEmpty()) {
            return new int[]{0, 0, 0};
        }

        // 1. Custom table wins over everything: the user knows best.
        int[] cu = st.customSize(t);
        if (cu != null) {
            return cu;
        }

        // 2. Strip const/volatile/mutable and re-check.
        String bare = stripQualifiers(t);
        if (!bare.equals(t)) {
            int[] cu2 = st.customSize(bare);
            if (cu2 != null) {
                return cu2;
            }
            t = bare;
        }

        // 3. Pointer or reference: pointer-sized.
        if (t.endsWith("*") || t.endsWith("&")) {
            return new int[]{st.pointerSize, st.pointerSize, 0};
        }

        // 4. Function-pointer type like "void (*)(int)" - pointer sized.
        if (t.contains("(*)")) {
            return new int[]{st.pointerSize, st.pointerSize, 0};
        }

        // 5. Multi-word basic types: "unsigned long long", "long double"...
        int[] multi = multiWord(t, st);
        if (multi != null) {
            return multi;
        }

        // 6. Templates: std::vector<T>, std::array<T,N>, std::atomic<T>...
        int lt = t.indexOf('<');
        if (lt > 0) {
            String base = t.substring(0, lt).trim();
            String arg = t.substring(lt + 1, lastAngle(t));
            return templateSize(base, arg, st, locals);
        }

        // 7. Plain single-word types.
        int[] basic = basicSize(t, st);
        if (basic[0] > 0) {
            return basic;
        }

        // 8. Enum: underlying type is not written down, assume int but warn.
        if (t.startsWith("enum ")) {
            return new int[]{4, 4, 0};
        }

        // 9. Known struct/class defined in the same file.
        if (locals != null) {
            String simple = t;
            int scope = simple.lastIndexOf("::");
            if (scope >= 0) {
                simple = simple.substring(scope + 2);
            }
            int[] loc = locals.get(simple);
            if (loc != null) {
                return new int[]{loc[0], loc[1], 0};
            }
        }

        // 10. Standard integer typedefs that map onto basics.
        int[] std = stdTypedef(t, st);
        if (std != null) {
            return std;
        }

        return new int[]{0, 0, 0};
    }

    /**
     * Removes leading qualifiers and trailing cv notes.
     *
     * @param t normalized type
     * @return type without const/volatile/mutable
     */
    static String stripQualifiers(String t) {
        String r = t;
        while (r.startsWith("const ") || r.startsWith("volatile ") || r.startsWith("mutable ")) {
            r = r.substring(r.indexOf(' ') + 1).trim();
        }
        return r;
    }

    /**
     * Collapses whitespace and drops "std::" only for the lookup of
     * non-qualified names later. Keeps the text otherwise intact.
     *
     * @param type raw type text
     * @return cleaned type text
     */
    static String normalize(String type) {
        String t = type.replaceAll("\\s+", " ").trim();
        // "struct Order" -> "Order": keyword prefix does not change layout.
        if (t.startsWith("struct ") || t.startsWith("class ") || t.startsWith("union ")) {
            t = t.substring(t.indexOf(' ') + 1).trim();
        }
        return t;
    }

    /**
     * Size of two- and three-word basic types.
     *
     * @param t  normalized type
     * @param st settings for ABI numbers
     * @return {size, align, 0} or null when t is not multi-word basic
     */
    static int[] multiWord(String t, HotPathSettings st) {
        switch (t) {
            case "signed char":
            case "short int":
            case "signed short":
                return pair(2);
            case "unsigned char":
                return pair(1);
            case "unsigned short":
            case "unsigned short int":
                return pair(2);
            case "unsigned int":
                return pair(4);
            case "long int":
            case "signed long":
                return pair(st.longSize);
            case "unsigned long":
            case "unsigned long int":
                return pair(st.longSize);
            case "long long":
            case "long long int":
            case "signed long long":
                return pair(8);
            case "unsigned long long":
            case "unsigned long long int":
                return pair(8);
            case "long double":
                return new int[]{st.longDoubleSize, st.longDoubleAlign, 0};
            default:
                return null;
        }
    }

    /**
     * Single-word type table.
     *
     * @param t  normalized type
     * @param st settings for ABI numbers
     * @return {size, align, atomicFlag}, size 0 when unknown
     */
    static int[] basicSize(String t, HotPathSettings st) {
        switch (t) {
            case "char":
            case "signed char":
            case "bool":
                return pair(1);
            case "wchar_t":
                return pair(st.wcharSize);
            case "char8_t":
                return pair(1);
            case "char16_t":
                return pair(2);
            case "char32_t":
                return pair(4);
            case "short":
                return pair(2);
            case "int":
            case "float":
                return pair(4);
            case "long":
                return pair(st.longSize);
            case "double":
                return pair(8);
            case "void":
                return new int[]{0, 0, 0};
            default:
                return new int[]{0, 0, 0};
        }
    }

    /**
     * Fixed-width and common std typedefs.
     *
     * @param t  type name
     * @param st settings for pointer width
     * @return sizes or null
     */
    static int[] stdTypedef(String t, HotPathSettings st) {
        switch (t) {
            case "int8_t":
            case "uint8_t":
                return pair(1);
            case "int16_t":
            case "uint16_t":
                return pair(2);
            case "int32_t":
            case "uint32_t":
                return pair(4);
            case "int64_t":
            case "uint64_t":
                return pair(8);
            case "size_t":
            case "ptrdiff_t":
            case "intptr_t":
            case "uintptr_t":
                return pair(st.pointerSize);
            case "nullptr_t":
                return pair(1);
            default:
                return null;
        }
    }

    /**
     * Handles std:: templates. Unknown template bases fall back to the
     * custom table entry without arguments when present.
     *
     * @param base   name before &lt;
     * @param arg    first-level argument text
     * @param st     settings
     * @param locals known struct sizes
     * @return {size, align, atomicFlag}
     */
    static int[] templateSize(String base, String arg, HotPathSettings st, HashMap<String, int[]> locals) {
        String b = base.startsWith("std::") ? base.substring(5) : base;
        switch (b) {
            case "atomic": {
                // atomic<T> has the size of T (lock-free case).
                int[] inner = sizeAndAlign(arg.trim(), st, locals);
                if (inner[0] == 0) {
                    return new int[]{st.pointerSize, st.pointerSize, 1};
                }
                return new int[]{inner[0], Math.min(inner[1], st.maxAlign), 1};
            }
            case "string":
                return new int[]{st.stringSize, st.pointerSize, 0};
            case "wstring":
                return new int[]{st.stringSize, st.pointerSize, 0};
            case "vector":
            case "deque":
                return new int[]{st.vectorSize, st.pointerSize, 0};
            case "unique_ptr":
            case "shared_ptr":
            case "weak_ptr":
                return new int[]{st.pointerSize, st.pointerSize, 0};
            case "optional": {
                int[] inner = sizeAndAlign(arg.trim(), st, locals);
                if (inner[0] == 0) {
                    return new int[]{0, 0, 0};
                }
                int sz = roundUp(inner[0] + 1, inner[1]);
                return new int[]{sz, inner[1], 0};
            }
            case "pair": {
                String[] two = splitTopLevel(arg);
                if (two.length != 2) {
                    return new int[]{0, 0, 0};
                }
                int[] a = sizeAndAlign(two[0].trim(), st, locals);
                int[] c = sizeAndAlign(two[1].trim(), st, locals);
                if (a[0] == 0 || c[0] == 0) {
                    return new int[]{0, 0, 0};
                }
                int al = Math.max(a[1], c[1]);
                int off = roundUp(a[0], Math.max(c[1], 1));
                return new int[]{roundUp(off + c[0], al), al, 0};
            }
            case "map":
            case "set":
            case "unordered_map":
            case "unordered_set":
                // Tree/hash container header: treat as pointer-sized handle.
                return new int[]{st.pointerSize * 3, st.pointerSize, 0};
            case "array": {
                String[] two = splitTopLevel(arg);
                if (two.length != 2) {
                    return new int[]{0, 0, 0};
                }
                int[] elem = sizeAndAlign(two[0].trim(), st, locals);
                int n;
                try {
                    n = Integer.parseInt(two[1].trim());
                } catch (Exception e) {
                    return new int[]{0, 0, 0};
                }
                if (elem[0] == 0) {
                    return new int[]{0, 0, 0};
                }
                return new int[]{elem[0] * n, elem[1], 0};
            }
            case "function":
                // std::function target buffer, typical libc++/libstdc++ size.
                return new int[]{st.pointerSize * 3, st.pointerSize, 0};
            default: {
                int[] cu = st.customSize(base);
                if (cu != null) {
                    return cu;
                }
                return new int[]{0, 0, 0};
            }
        }
    }

    /**
     * Splits template arguments at top-level commas, so that
     * "pair<vector<int>, int>" splits into two parts.
     *
     * @param arg inside of &lt;...&gt;
     * @return argument pieces
     */
    static String[] splitTopLevel(String arg) {
        ArrayList<String> parts = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < arg.length(); i++) {
            char c = arg.charAt(i);
            if (c == '<' || c == '(') {
                depth++;
            } else if (c == '>' || c == ')') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        parts.add(cur.toString());
        return parts.toArray(new String[0]);
    }

    /**
     * Index of the closing angle bracket of the outermost template.
     *
     * @param t type text containing '&lt;'
     * @return index of '&gt;' or end of string
     */
    static int lastAngle(String t) {
        int end = t.lastIndexOf('>');
        return end < 0 ? t.length() : end;
    }

    /**
     * Convenience pair with alignment equal to size capped by 8.
     *
     * @param s size in bytes
     * @return {s, min(s,8), 0}
     */
    static int[] pair(int s) {
        return new int[]{s, Math.min(s, 8), 0};
    }

    /**
     * Rounds {@code v} up to a multiple of {@code a}.
     *
     * @param v value
     * @param a positive power-of-two alignment
     * @return rounded value
     */
    public static int roundUp(int v, int a) {
        if (a <= 1) {
            return v;
        }
        int r = v % a;
        return r == 0 ? v : v + a - r;
    }
}
