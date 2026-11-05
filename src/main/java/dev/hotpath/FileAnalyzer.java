package dev.hotpath;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * One-shot analysis of a whole file: find marked regions, parse them,
 * compute layouts and false sharing conflicts. The result is cached by
 * {@link HotPathHighlighter} keyed by document text hash, so typing does
 * not re-parse everything every keystroke. Heavy work runs off the EDT.
 */
public class FileAnalyzer {

    /**
     * Analysis of one marked struct inside a file.
     */
    public static class MarkedStruct {
        /** Region bounds in document offsets. */
        public HotRegionFinder.Region region;
        /** Parsed fields and notes. */
        public CppStructParser.StructInfo info;
        /** Computed layout. */
        public LayoutCalculator.Layout layout;
        /** False sharing conflicts. */
        public ArrayList<FalseSharingAnalyzer.Conflict> conflicts = new ArrayList<>();
        /** Field index to warning message for risky fields. */
        public HashMap<Integer, String> risky = new HashMap<>();
        /** Offsets of field semicolons in document coordinates, parallel to layout.fields. */
        public int[] fieldEnds = new int[0];

        /**
         * True when the layout is complete and has no conflicts.
         *
         * @return green-state flag for the gutter icon
         */
        public boolean compact() {
            return layout != null && layout.complete && conflicts.isEmpty()
                    && layout.wasted <= Math.max(4, layout.totalSize / 8);
        }

        /**
         * Status label used in hints and the tool window.
         *
         * @return OK / WARNING / UNKNOWN / FALSE SHARING
         */
        public String status() {
            if (layout == null || !layout.complete) {
                return "UNKNOWN";
            }
            if (!conflicts.isEmpty()) {
                return "FALSE SHARING";
            }
            if (layout.wasted > 0 || !info.notes.isEmpty()) {
                return "WARNING";
            }
            return "OK";
        }
    }

    /**
     * Full file analysis result.
     */
    public static class Result {
        /** All marked structs found in the file. */
        public ArrayList<MarkedStruct> structs = new ArrayList<>();
        /** Sizes of all structs defined in the file, name -> {size, align}. */
        public HashMap<String, int[]> locals = new HashMap<>();
    }

    /**
     * Closed because all methods are static.
     */
    private FileAnalyzer() {
    }

    /**
     * Analyzes a whole document text. Safe to call on a background thread.
     *
     * @param text full file text
     * @return analysis result, never null
     */
    public static Result analyze(String text) {
        Result r = new Result();
        ArrayList<HotRegionFinder.Region> regions = HotPathHighlighter.findAll(text);
        // Cap the work on absurdly large files: first 200 marked structs.
        int limit = Math.min(regions.size(), 200);

        // First pass: parse everything so that cross-references between
        // structs in the same file can be resolved in the second pass.
        ArrayList<CppStructParser.StructInfo> infos = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            HotRegionFinder.Region reg = regions.get(i);
            String block = safeSubstring(text, reg.bStart(), reg.bEnd());
            CppStructParser.StructInfo info = CppStructParser.parse(block);
            infos.add(info);
            if (info != null && !info.name.isEmpty()) {
                // Provisional entry refined later; mark unknown until computed.
                r.locals.putIfAbsent(info.name, new int[]{0, 1});
            }
        }

        // Second pass: compute layouts, innermost-first would be ideal but
        // two rounds over the list resolve most nesting already.
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < limit; i++) {
                CppStructParser.StructInfo info = infos.get(i);
                if (info == null) {
                    continue;
                }
                LayoutCalculator.Layout l = LayoutCalculator.compute(info, r.locals);
                if (l.complete && !info.name.isEmpty()) {
                    r.locals.put(info.name, new int[]{l.totalSize, maxAlignOf(l)});
                }
            }
        }

        // Third pass: final layouts + conflicts with resolved locals.
        for (int i = 0; i < limit; i++) {
            CppStructParser.StructInfo info = infos.get(i);
            if (info == null) {
                continue;
            }
            HotRegionFinder.Region reg = regions.get(i);
            String block = safeSubstring(text, reg.bStart(), reg.bEnd());

            LayoutCalculator.Layout l = LayoutCalculator.compute(info, r.locals);

            MarkedStruct m = new MarkedStruct();
            m.region = reg;
            m.info = info;
            m.layout = l;
            m.conflicts = FalseSharingAnalyzer.analyze(l, block);
            LayoutCalculator.ConflictHolder.conflicts = m.conflicts.size();
            m.layout.score = LayoutCalculator.healthScore(m.layout, HotPathSettings.get());
            m.risky = FalseSharingAnalyzer.riskyFields(m.conflicts);
            m.fieldEnds = new int[l.fields.size()];
            for (int k = 0; k < l.fields.size(); k++) {
                m.fieldEnds[k] = reg.bStart() + l.fields.get(k).end;
            }
            r.structs.add(m);
        }
        return r;
    }

    /**
     * Largest alignment among known fields.
     *
     * @param l layout
     * @return max alignment, at least 1
     */
    static int maxAlignOf(LayoutCalculator.Layout l) {
        int mx = 1;
        for (CppStructParser.Field f : l.fields) {
            if (f.align > mx) {
                mx = f.align;
            }
        }
        return mx;
    }

    /**
     * Bounds-checked substring.
     *
     * @param text source
     * @param a    start
     * @param b    end
     * @return requested slice clamped to the text
     */
    static String safeSubstring(String text, int a, int b) {
        a = Math.max(0, Math.min(a, text.length()));
        b = Math.max(a, Math.min(b, text.length()));
        return text.substring(a, b);
    }
}
