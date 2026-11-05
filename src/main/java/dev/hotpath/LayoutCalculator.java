package dev.hotpath;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Turns a parsed struct into a byte layout: offsets, padding gaps,
 * total size and a per-byte map. Works together with
 * {@link CppTypeResolver} and the settings service; no compilation.
 */
public class LayoutCalculator {

    /**
     * Closed because all methods are static.
     */
    private LayoutCalculator() {
    }

    /**
     * One gap of unused bytes between fields or at the tail.
     */
    public static class Gap {
        /** First byte of the gap. */
        public int from;
        /** Last byte of the gap, exclusive. */
        public int to;

        /**
         * Creates a gap.
         *
         * @param from start byte
         * @param to   end byte, exclusive
         */
        Gap(int from, int to) {
            this.from = from;
            this.to = to;
        }

        /** @return length in bytes */
        public int length() {
            return to - from;
        }
    }

    /**
     * Complete result of one layout computation.
     */
    public static class Layout {
        /** Fields with offsets filled in declaration order. */
        public ArrayList<CppStructParser.Field> fields;
        /** Padding gaps between and after fields. */
        public ArrayList<Gap> gaps = new ArrayList<>();
        /** Total struct size in bytes, 0 when unknown. */
        public int totalSize;
        /** Bytes lost to padding. */
        public int wasted;
        /** True when every field was resolved. */
        public boolean complete = true;
        /** Names of fields whose type is unknown. */
        public ArrayList<String> unknown = new ArrayList<>();
        /** Explanations for partially unknown layout. */
        public ArrayList<String> notes = new ArrayList<>();
        /** Byte map: index of owning field, -1 for padding, null if unknown. */
        public int[] cells;
        /** Cache line count, 0 when size unknown. */
        public int cacheLines;
        /** Health score 0..100, -1 when not computable. */
        public int score = -1;
    }

    /**
     * Computes the layout of one struct.
     *
     * @param info   parsed struct
     * @param locals sizes of other structs in the same file, may be null
     * @return layout result, never null
     */
    public static Layout compute(CppStructParser.StructInfo info, HashMap<String, int[]> locals) {
        HotPathSettings st = HotPathSettings.get();
        Layout l = new Layout();
        l.fields = info.fields;
        l.notes.addAll(info.notes);

        // Resolve types first, remembering local typedefs of this struct.
        for (CppStructParser.Field f : info.fields) {
            resolveField(f, st, locals, info);
            if (!f.known()) {
                l.complete = false;
                l.unknown.add(f.name + " : " + f.type);
            }
        }

        // Virtual table pointer occupies the first slot of a polymorphic object.
        int vptr = info.hasVptr ? st.pointerSize() : 0;

        if (info.union) {
            computeUnion(l, st, vptr);
            return l;
        }
        computeSequential(l, st, vptr);
        return l;
    }

    /**
     * Resolves one field's type through local typedefs and the resolver.
     *
     * @param f      field to fill
     * @param st     settings
     * @param locals known struct sizes
     * @param info   owning struct, for its local typedefs
     */
    static void resolveField(CppStructParser.Field f, HotPathSettings st,
                             HashMap<String, int[]> locals, CppStructParser.StructInfo info) {
        String t = f.type.trim();
        // Follow up to 5 alias hops declared inside the struct body.
        for (int hop = 0; hop < 5; hop++) {
            int[] sa = CppTypeResolver.sizeAndAlign(t, st, locals);
            if (sa[0] > 0) {
                f.size = sa[0];
                f.align = sa[1];
                if (sa[2] == 1) {
                    f.atomic = true;
                }
                applyExplicitAlign(f, st);
                return;
            }
            String target = findTypedef(info, t);
            if (target == null) {
                break;
            }
            t = target;
        }
        // An enum defined in this very file defaults to int-sized.
        if (locals != null && locals.containsKey("__enum_" + baseName(t))) {
            f.size = 4;
            f.align = 4;
            applyExplicitAlign(f, st);
            return;
        }
        f.size = 0;
        f.align = 0;
    }

    /**
     * Applies alignas(N) on top of natural alignment.
     *
     * @param f  field
     * @param st settings
     */
    static void applyExplicitAlign(CppStructParser.Field f, HotPathSettings st) {
        if (f.explicitAlign > f.align) {
            f.align = Math.min(f.explicitAlign, st.cacheLine());
        }
    }

    /**
     * Looks up a local typedef/using alias.
     *
     * @param info struct info
     * @param name alias name
     * @return target type text or null
     */
    static String findTypedef(CppStructParser.StructInfo info, String name) {
        for (String[] td : info.typedefs) {
            if (td[0].equals(name)) {
                return td[1];
            }
        }
        return null;
    }

    /**
     * Sequential (non-union) layout with classic alignment rules.
     *
     * @param l    layout being filled
     * @param st   settings
     * @param vptr bytes reserved for a virtual table pointer
     */
    static void computeSequential(Layout l, HotPathSettings st, int vptr) {
        int pos = vptr;
        int maxAl = Math.max(1, vptr > 0 ? st.pointerSize() : 1);
        if (vptr > 0) {
            l.notes.add("virtual methods present: assumed " + vptr + "-byte vptr at offset 0");
        }
        for (CppStructParser.Field f : l.fields) {
            if (!f.known()) {
                continue;
            }
            int aligned = CppTypeResolver.roundUp(pos, f.align);
            if (aligned > pos) {
                l.gaps.add(new Gap(pos, aligned));
            }
            f.offset = aligned;
            pos = aligned + f.size;
            if (f.align > maxAl) {
                maxAl = f.align;
            }
        }
        if (maxAl > st.maxAlign()) {
            maxAl = st.maxAlign();
        }
        int total = CppTypeResolver.roundUp(pos, maxAl);
        if (total > pos) {
            l.gaps.add(new Gap(pos, total));
        }
        l.totalSize = l.complete ? total : 0;
        if (!l.complete) {
            // Keep partial numbers visible but clearly marked.
            l.totalSize = 0;
            l.notes.add("layout incomplete because some types are unknown");
        }
        finish(l, st);
    }

    /**
     * Union layout: all fields start at zero, size is the largest field.
     *
     * @param l    layout being filled
     * @param st   settings
     * @param vptr reserved vptr bytes
     */
    static void computeUnion(Layout l, HotPathSettings st, int vptr) {
        int maxSize = vptr;
        int maxAl = Math.max(1, st.pointerSize());
        for (CppStructParser.Field f : l.fields) {
            if (!f.known()) {
                continue;
            }
            f.offset = vptr;
            if (f.size > maxSize) {
                maxSize = f.size;
            }
            if (f.align > maxAl) {
                maxAl = f.align;
            }
        }
        int total = CppTypeResolver.roundUp(maxSize, maxAl);
        l.totalSize = l.complete ? total : 0;
        finish(l, st);
    }

    /**
     * Shared tail work: wasted bytes, byte map, cache lines and score.
     *
     * @param l  layout
     * @param st settings
     */
    static void finish(Layout l, HotPathSettings st) {
        int sum = 0;
        for (CppStructParser.Field f : l.fields) {
            if (f.known()) {
                sum += f.size;
            }
        }
        if (l.totalSize > 0) {
            // Wasted = padding gaps between fields plus tail rounding.
            int gapBytes = 0;
            for (Gap g : l.gaps) {
                gapBytes += g.length();
            }
            l.wasted = Math.max(0, l.totalSize - sum);
            l.cacheLines = (l.totalSize + st.cacheLine() - 1) / st.cacheLine();
            makeCells(l);
            l.score = healthScore(l, st);
        }
    }

    /**
     * Builds the per-byte owner map used by the memory map renderer.
     *
     * @param l layout with fields and total size
     */
    static void makeCells(Layout l) {
        int n = l.totalSize;
        if (n <= 0 || n > 65536) {
            l.cells = null;
            return;
        }
        int[] cells = new int[n];
        for (int i = 0; i < n; i++) {
            cells[i] = -1; // padding by default
        }
        for (int i = 0; i < l.fields.size(); i++) {
            CppStructParser.Field f = l.fields.get(i);
            if (!f.known()) {
                continue;
            }
            for (int b = f.offset; b < f.offset + f.size && b < n; b++) {
                cells[b] = i;
            }
        }
        l.cells = cells;
    }

    /**
     * Struct health score: starts at 100 and loses points for waste,
     * extra cache lines, conflicts and unknown fields.
     *
     * @param l  computed layout
     * @param st settings
     * @return score 0..100
     */
    public static int healthScore(Layout l, HotPathSettings st) {
        int s = 100;
        if (l.totalSize > 0) {
            double waste = (double) l.wasted / l.totalSize;
            s -= (int) (waste * 120);
        }
        s -= Math.max(0, l.cacheLines - 1) * 8;
        s -= l.unknown.size() * 25;
        s -= ConflictHolder.conflicts * 15;
        return Math.max(0, s);
    }

    /**
     * Tiny mutable holder so {@link #healthScore} can see the conflict
     * count without threading it through every call. The highlighter
     * refreshes it right before scoring.
     */
    static class ConflictHolder {
        /** Number of false sharing conflicts of the current struct. */
        static int conflicts;
    }

    /**
     * Name without template arguments and scope.
     *
     * @param t type text
     * @return bare base name
     */
    static String baseName(String t) {
        int lt = t.indexOf('<');
        if (lt > 0) {
            t = t.substring(0, lt);
        }
        int sc = t.lastIndexOf("::");
        if (sc >= 0) {
            t = t.substring(sc + 2);
        }
        return t.trim().replace("enum ", "");
    }
}
