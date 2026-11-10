package dev.hotpath;

import core.FalseSharingDetector;
import core.ProducerConsumerAnalyzer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Headless file analysis engine with fixed-point dependency convergence.
 */
public class FileAnalyzer {

    public static class MarkedStruct {
        public HotRegionFinder.Region region;
        public CppStructParser.StructInfo info;
        public LayoutCalculator.Layout layout;
        public ArrayList<FalseSharingAnalyzer.Conflict> conflicts = new ArrayList<>();
        public HashMap<Integer, String> risky = new HashMap<>();
        public int[] fieldEnds = new int[0];

        public boolean compact() {
            return layout != null && layout.complete && risky.isEmpty()
                    && layout.wasted <= Math.max(4, layout.totalSize / 8);
        }

        public String status() {
            if (layout == null || !layout.complete) {
                return "UNKNOWN";
            }
            if (!risky.isEmpty()) {
                return "FALSE SHARING";
            }
            if (layout.wasted > 0 || !info.notes.isEmpty()) {
                return "WARNING";
            }
            return "OK";
        }
    }

    public static class Result {
        public ArrayList<MarkedStruct> structs = new ArrayList<>();
        public HashMap<String, int[]> locals = new HashMap<>();
    }

    private FileAnalyzer() {
    }

    public static Result analyze(String text) {
        Result r = new Result();
        ArrayList<HotRegionFinder.Region> regions = HotRegionFinder.findAllStructs(text);
        int limit = Math.min(regions.size(), 300);

        ArrayList<CppStructParser.StructInfo> infos = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            HotRegionFinder.Region reg = regions.get(i);
            String block = safeSubstring(text, reg.bStart(), reg.bEnd());
            CppStructParser.StructInfo info = CppStructParser.parse(block);
            infos.add(info);
            if (info != null && !info.name.isEmpty()) {
                r.locals.putIfAbsent(info.name, new int[]{0, 1});
            }
        }

        // Fix 3.1: Fixed-Point Iteration (Converges in 1-2 passes, halts early if no dependencies changed)
        int maxRounds = 3;
        for (int round = 0; round < maxRounds; round++) {
            boolean changed = false;
            for (int i = 0; i < limit; i++) {
                CppStructParser.StructInfo info = infos.get(i);
                if (info == null || info.name.isEmpty()) continue;

                int[] prev = r.locals.get(info.name);
                LayoutCalculator.Layout l = LayoutCalculator.compute(info, r.locals);
                if (l.complete) {
                    int newAlign = maxAlignOf(l);
                    if (prev == null || prev[0] != l.totalSize || prev[1] != newAlign) {
                        r.locals.put(info.name, new int[]{l.totalSize, newAlign});
                        changed = true;
                    }
                }
            }
            if (!changed) break; // Топологическая сходимость достигнута
        }

        int cacheLineSize = HotPathSettings.get().cacheLine();
        if (cacheLineSize <= 0) cacheLineSize = 64;

        for (int i = 0; i < limit; i++) {
            CppStructParser.StructInfo info = infos.get(i);
            if (info == null) continue;
            HotRegionFinder.Region reg = regions.get(i);
            String block = safeSubstring(text, reg.bStart(), reg.bEnd());

            LayoutCalculator.Layout l = LayoutCalculator.compute(info, r.locals);

            MarkedStruct m = new MarkedStruct();
            m.region = reg;
            m.info = info;
            m.layout = l;

            ProducerConsumerAnalyzer.StructProfile profile = ProducerConsumerAnalyzer.analyze(info, block);
            List<FalseSharingDetector.CacheField> cacheFields = new ArrayList<>();
            for (CppStructParser.Field f : l.fields) {
                ProducerConsumerAnalyzer.FieldProfile fp = profile.getFieldProfile(f.name);
                boolean isAtomic = fp != null && fp.isAtomic;
                boolean isProducer = fp != null && fp.isProducer();
                boolean isConsumer = fp != null && fp.isConsumer();
                boolean isArray = fp != null && fp.isArray;
                int count = fp != null ? fp.arraySize : 1;

                cacheFields.add(new FalseSharingDetector.CacheField(
                        f.name, f.offset, f.size, cacheLineSize, isAtomic, isProducer, isConsumer, isArray, count
                ));
            }

            FalseSharingDetector.Result detectorResult = FalseSharingDetector.detect(cacheFields, cacheLineSize, profile);

            m.risky = new HashMap<>();
            for (FalseSharingDetector.Conflict c : detectorResult.conflicts) {
                int idxA = -1;
                int idxB = -1;
                for (int k = 0; k < l.fields.size(); k++) {
                    CppStructParser.Field f = l.fields.get(k);
                    if (f.name.equals(c.first.name)) {
                        m.risky.put(k, c.description);
                        if (idxA == -1) idxA = k;
                    }
                    if (f.name.equals(c.second.name)) {
                        m.risky.put(k, c.description);
                        if (idxB == -1) idxB = k;
                    }
                }
                m.conflicts.add(new FalseSharingAnalyzer.Conflict(idxA, idxB, c.description, false));
            }

            m.layout.conflicts = detectorResult.conflicts.size();
            m.layout.score = LayoutCalculator.healthScore(m.layout, HotPathSettings.get(), m.layout.conflicts);
            m.fieldEnds = new int[l.fields.size()];
            for (int k = 0; k < l.fields.size(); k++) {
                m.fieldEnds[k] = reg.bStart() + l.fields.get(k).end;
            }
            r.structs.add(m);
        }
        return r;
    }

    static int maxAlignOf(LayoutCalculator.Layout l) {
        int mx = 1;
        for (CppStructParser.Field f : l.fields) {
            if (f.align > mx) mx = f.align;
        }
        return mx;
    }

    static String safeSubstring(String text, int a, int b) {
        a = Math.max(0, Math.min(a, text.length()));
        b = Math.max(a, Math.min(b, text.length()));
        return text.substring(a, b);
    }
}