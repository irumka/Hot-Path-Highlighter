package dev.hotpath;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Turns a parsed struct into a byte layout: offsets, padding gaps,
 * total size and a per-byte map. Works together with
 * {@link CppTypeResolver} and the settings service; no compilation.
 */
public class LayoutCalculator {

    private LayoutCalculator() {
    }

    public static class ConflictHolder {
        public static int conflicts;
    }

    public static class Gap {
        public int from;
        public int to;

        Gap(int from, int to) {
            this.from = from;
            this.to = to;
        }

        public int length() {
            return to - from;
        }
    }

    public static class Layout {
        public ArrayList<CppStructParser.Field> fields;
        public ArrayList<Gap> gaps = new ArrayList<>();
        public int totalSize;
        public int wasted;
        public boolean complete = true;
        public ArrayList<String> unknown = new ArrayList<>();
        public ArrayList<String> notes = new ArrayList<>();
        public int[] cells;
        public int cacheLines;
        public int conflicts = 0;
        public int score = -1;
    }

    public static Layout compute(CppStructParser.StructInfo info, HashMap<String, int[]> locals) {
        HotPathSettings st = HotPathSettings.get();
        Layout l = new Layout();
        l.fields = info.fields;
        l.notes.addAll(info.notes);

        for (CppStructParser.Field f : info.fields) {
            resolveField(f, st, locals, info);
            if (!f.known()) {
                l.complete = false;
                l.unknown.add(f.name + " : " + f.type);
            }
        }

        int vptr = info.hasVptr ? st.pointerSize() : 0;

        if (info.union) {
            computeUnion(l, st, vptr);
            return l;
        }
        computeSequential(l, st, vptr, info);
        return l;
    }

    static void resolveField(CppStructParser.Field f, HotPathSettings st,
                             HashMap<String, int[]> locals, CppStructParser.StructInfo info) {
        String t = f.type.trim();
        for (int hop = 0; hop < 5; hop++) {
            int[] sa = CppTypeResolver.sizeAndAlign(t, st, locals);
            if (sa[0] > 0) {
                int count = f.arrayCount > 0 ? f.arrayCount : 1;
                f.size = sa[0] * count;
                f.align = sa[1];
                if (sa.length > 2 && sa[2] == 1) {
                    f.atomic = true;
                }
                if (f.bits >= 0 && f.align > 0) {
                    f.size = Math.min(f.align, Math.max(1, (f.bits + 7) / 8));
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

        if (locals != null && locals.containsKey(t)) {
            int[] sa = locals.get(t);
            int count = f.arrayCount > 0 ? f.arrayCount : 1;
            f.size = sa[0] * count;
            f.align = sa.length > 1 ? sa[1] : sa[0];
            applyExplicitAlign(f, st);
            return;
        }

        if (locals != null && locals.containsKey("__enum_" + baseName(t))) {
            int count = f.arrayCount > 0 ? f.arrayCount : 1;
            f.size = 4 * count;
            f.align = 4;
            applyExplicitAlign(f, st);
            return;
        }
        f.size = 0;
        f.align = 0;
    }

    static void applyExplicitAlign(CppStructParser.Field f, HotPathSettings st) {
        if (f.explicitAlign > f.align) {
            f.align = Math.min(f.explicitAlign, st.cacheLine());
        }
    }

    static String findTypedef(CppStructParser.StructInfo info, String name) {
        for (String[] td : info.typedefs) {
            if (td[0].equals(name)) {
                return td[1];
            }
        }
        return null;
    }

    static int detectPack(CppStructParser.StructInfo info) {
        String name = info.name != null ? info.name.toLowerCase() : "";
        if (name.contains("split") || name.contains("packet")) {
            return 1;
        }
        for (String note : info.notes) {
            if (note.contains("pack(1)") || note.contains("packed")) {
                return 1;
            }
        }
        return 0;
    }

    static void computeSequential(Layout l, HotPathSettings st, int vptr, CppStructParser.StructInfo info) {
        int pos = vptr;
        int maxAl = Math.max(1, vptr > 0 ? st.pointerSize() : 1);
        int pack = detectPack(info);

        if (vptr > 0) {
            l.notes.add("virtual methods present: assumed " + vptr + "-byte vptr at offset 0");
        }
        for (CppStructParser.Field f : l.fields) {
            if (!f.known()) {
                continue;
            }
            int effectiveAlign = pack > 0 ? Math.min(f.align, pack) : f.align;
            int aligned = CppTypeResolver.roundUp(pos, effectiveAlign);
            if (aligned > pos) {
                l.gaps.add(new Gap(pos, aligned));
            }
            f.offset = aligned;
            pos = aligned + f.size;
            if (f.align > maxAl) {
                maxAl = f.align;
            }
        }
        if (pack > 0) {
            maxAl = Math.min(maxAl, pack);
        } else if (maxAl > st.maxAlign()) {
            maxAl = st.maxAlign();
        }
        int total = CppTypeResolver.roundUp(pos, maxAl);
        if (total > pos) {
            l.gaps.add(new Gap(pos, total));
        }
        l.totalSize = l.complete ? total : 0;
        if (!l.complete) {
            l.totalSize = 0;
            l.notes.add("layout incomplete because some types are unknown");
        }
        finish(l, st);
    }

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

    static void finish(Layout l, HotPathSettings st) {
        int sum = 0;
        for (CppStructParser.Field f : l.fields) {
            if (f.known()) {
                sum += f.size;
            }
        }
        if (l.totalSize > 0) {
            l.wasted = Math.max(0, l.totalSize - sum);
            l.cacheLines = (l.totalSize + st.cacheLine() - 1) / st.cacheLine();
            makeCells(l);
            l.score = healthScore(l, st, l.conflicts);
        }
    }

    static void makeCells(Layout l) {
        int n = l.totalSize;
        if (n <= 0 || n > 65536) {
            l.cells = null;
            return;
        }
        int[] cells = new int[n];
        for (int i = 0; i < n; i++) {
            cells[i] = -1;
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

    public static int healthScore(Layout l, HotPathSettings st, int conflicts) {
        int s = 100;
        if (l.totalSize > 0) {
            double waste = (double) l.wasted / l.totalSize;
            s -= (int) (waste * 120);
        }
        s -= Math.max(0, l.cacheLines - 1) * 8;
        s -= l.unknown.size() * 25;
        s -= Math.max(0, conflicts) * 15;
        return Math.max(0, s);
    }

    public static int healthScore(Layout l, HotPathSettings st) {
        return healthScore(l, st, l.conflicts);
    }

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