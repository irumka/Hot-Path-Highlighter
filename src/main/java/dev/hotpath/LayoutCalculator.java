package dev.hotpath;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Turns a parsed struct into an exact byte layout.
 * Supports C++ inheritance (base subobjects), bitfields allocation units,
 * explicit alignment and standard ABI packing rules.
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
        public ArrayList<CppStructParser.Field> fields = new ArrayList<>();
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
        l.notes.addAll(info.notes);

        // 1. Разрешаем поля текущей структуры
        for (CppStructParser.Field f : info.fields) {
            resolveField(f, st, locals, info);
            if (!f.known()) {
                l.complete = false;
                l.unknown.add(f.name + " : " + f.type);
            }
        }

        if (info.union) {
            computeUnion(l, st, info);
            return l;
        }

        computeSequential(l, st, info, locals);
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
                applyExplicitAlign(f, st);
                return;
            }
            String target = findTypedef(info, t);
            if (target == null) break;
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

        if (f.isBitfield) {
            if (f.align == 0) f.align = 4;
            if (f.size == 0) f.size = 4;
            applyExplicitAlign(f, st);
            return;
        }

        f.size = 0;
        f.align = 0;
    }

    static void applyExplicitAlign(CppStructParser.Field f, HotPathSettings st) {
        if (f.explicitAlign > f.align) {
            f.align = f.explicitAlign;
        }
    }

    static String findTypedef(CppStructParser.StructInfo info, String name) {
        for (String[] td : info.typedefs) {
            if (td[0].equals(name)) return td[1];
        }
        return null;
    }

    static int detectPack(CppStructParser.StructInfo info) {
        for (String note : info.notes) {
            if (note.contains("pack(1)") || note.contains("packed")) return 1;
            if (note.contains("pack(2)")) return 2;
            if (note.contains("pack(4)")) return 4;
        }
        return 0;
    }

    static void computeSequential(Layout l, HotPathSettings st, CppStructParser.StructInfo info, HashMap<String, int[]> locals) {
        int pos = 0;
        int maxAl = 1;

        // 1. Обработка наследования (Base Subobjects)
        if (info.baseClasses != null && !info.baseClasses.isEmpty()) {
            for (String baseName : info.baseClasses) {
                if (locals != null && locals.containsKey(baseName)) {
                    int[] baseSA = locals.get(baseName);
                    int baseSize = baseSA[0];
                    int baseAlign = baseSA.length > 1 ? baseSA[1] : 8;

                    int aligned = CppTypeResolver.roundUp(pos, baseAlign);
                    if (aligned > pos) {
                        l.gaps.add(new Gap(pos, aligned));
                    }
                    pos = aligned + baseSize;
                    if (baseAlign > maxAl) maxAl = baseAlign;

                    CppStructParser.Field baseField = new CppStructParser.Field();
                    baseField.name = ":" + baseName;
                    baseField.type = baseName;
                    baseField.offset = aligned;
                    baseField.size = baseSize;
                    baseField.align = baseAlign;
                    l.fields.add(baseField);
                }
            }
        }

        // 2. Обработка собственного vptr
        if (info.hasVptr && pos == 0) {
            int vptr = st.pointerSize();
            l.notes.add("virtual methods present: assumed " + vptr + "-byte vptr at offset 0");
            pos = vptr;
            maxAl = Math.max(maxAl, vptr);
        }

        int pack = detectPack(info);

        // 3. Размещение собственных полей и битовых единиц (Bitfield Allocation Units)
        int currentBitUnitOffset = -1;
        int currentBitUnitSize = 0;
        int currentBitUnitBitsUsed = 0;

        for (CppStructParser.Field f : info.fields) {
            if (!f.known()) continue;

            if (f.isBitfield) {
                int unitSize = f.size > 0 ? f.size : 4;
                int unitBitsTotal = unitSize * 8;

                boolean canFit = (currentBitUnitOffset >= 0) &&
                        (currentBitUnitBitsUsed + f.bits <= unitBitsTotal) &&
                        (f.explicitAlign == 0);

                if (!canFit) {
                    if (currentBitUnitOffset >= 0) {
                        pos = currentBitUnitOffset + currentBitUnitSize;
                    }

                    int effectiveAlign = pack > 0 ? Math.min(f.align, pack) : f.align;
                    if (f.explicitAlign > 0) effectiveAlign = Math.max(effectiveAlign, f.explicitAlign);

                    int aligned = CppTypeResolver.roundUp(pos, effectiveAlign);
                    if (aligned > pos) l.gaps.add(new Gap(pos, aligned));

                    currentBitUnitOffset = aligned;
                    currentBitUnitSize = unitSize;
                    currentBitUnitBitsUsed = 0;
                    if (effectiveAlign > maxAl) maxAl = effectiveAlign;
                }

                f.offset = currentBitUnitOffset;
                f.bitOffsetInUnit = currentBitUnitBitsUsed;
                currentBitUnitBitsUsed += f.bits;
                l.fields.add(f);
                continue;
            }

            // Закрываем предыдущую битовую ячейку перед обычным полем
            if (currentBitUnitOffset >= 0) {
                pos = currentBitUnitOffset + currentBitUnitSize;
                currentBitUnitOffset = -1;
                currentBitUnitSize = 0;
                currentBitUnitBitsUsed = 0;
            }

            int effectiveAlign = pack > 0 ? Math.min(f.align, pack) : f.align;
            if (f.explicitAlign > 0) effectiveAlign = Math.max(effectiveAlign, f.explicitAlign);

            int aligned = CppTypeResolver.roundUp(pos, effectiveAlign);
            if (aligned > pos) {
                l.gaps.add(new Gap(pos, aligned));
            }
            f.offset = aligned;
            pos = aligned + f.size;
            if (effectiveAlign > maxAl) maxAl = effectiveAlign;
            l.fields.add(f);
        }

        if (currentBitUnitOffset >= 0) {
            pos = currentBitUnitOffset + currentBitUnitSize;
        }

        if (info.explicitStructAlign > 0) {
            maxAl = Math.max(maxAl, info.explicitStructAlign);
        }

        if (pack > 0) {
            maxAl = Math.min(maxAl, pack);
        }

        int total = CppTypeResolver.roundUp(pos, maxAl);
        if (total > pos) {
            l.gaps.add(new Gap(pos, total));
        }

        l.totalSize = l.complete ? total : 0;
        finish(l, st);
    }

    static void computeUnion(Layout l, HotPathSettings st, CppStructParser.StructInfo info) {
        int maxSize = 0;
        int maxAl = 1;
        for (CppStructParser.Field f : info.fields) {
            if (!f.known()) continue;
            f.offset = 0;
            if (f.size > maxSize) maxSize = f.size;
            if (f.align > maxAl) maxAl = f.align;
            l.fields.add(f);
        }
        int total = CppTypeResolver.roundUp(maxSize, maxAl);
        l.totalSize = l.complete ? total : 0;
        finish(l, st);
    }

    static void finish(Layout l, HotPathSettings st) {
        int sum = 0;
        for (CppStructParser.Field f : l.fields) {
            if (f.known() && !f.isBitfield) {
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
        for (int i = 0; i < n; i++) cells[i] = -1;

        for (int i = 0; i < l.fields.size(); i++) {
            CppStructParser.Field f = l.fields.get(i);
            if (!f.known()) continue;
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
}