package dev.hotpath;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Finds false sharing risks in a computed layout. Uses explicit thread
 * annotations from comments first, then name/type heuristics for hot
 * fields. Pure text analysis, no runtime data.
 */
public class FalseSharingAnalyzer {

    /**
     * Closed because all methods are static.
     */
    private FalseSharingAnalyzer() {
    }

    /**
     * One reported conflict between two fields or one field and itself.
     */
    public static class Conflict {
        /** Index of the first field, -1 for none. */
        public int a;
        /** Index of the second field, -1 for none. */
        public int b;
        /** Short message shown in hints and the tool window. */
        public String message;
        /** True when it is only about crossing a cache line boundary. */
        public boolean crosses;

        /**
         * Creates a conflict record.
         *
         * @param a       first field index
         * @param b       second field index
         * @param message human-readable description
         * @param crosses true for boundary-crossing warnings
         */
        Conflict(int a, int b, String message, boolean crosses) {
            this.a = a;
            this.b = b;
            this.message = message;
            this.crosses = crosses;
        }
    }

    /** Hot-looking field names matched case-insensitively as substrings. */
    static final String[] HOT_NAMES = {
            "counter", "count", "index", "idx", "head", "tail", "next", "prev",
            "write", "read", "lock", "mutex", "flag", "seq", "sequence",
            "cursor", "owner"
    };

    /** Type fragments that look synchronization-related. */
    static final String[] HOT_TYPES = {"atomic", "mutex", "spin"};

    /**
     * Runs all checks over one layout.
     *
     * @param l      computed layout (fields must have offsets)
     * @param region raw region text, used to expand "@threads a,b" lists
     * @return list of conflicts, possibly empty
     */
    public static ArrayList<Conflict> analyze(LayoutCalculator.Layout l, String region) {
        ArrayList<Conflict> out = new ArrayList<>();
        HotPathSettings st = HotPathSettings.get();
        if (!st.falseSharingOn() || l.cells == null || !l.complete) {
            return out;
        }
        int line = st.cacheLine();

        // Effective thread tag per field: explicit annotation wins,
        // otherwise heuristic may mark the field as hot ("*hot*").
        String[] tags = new String[l.fields.size()];
        boolean[] hot = new boolean[l.fields.size()];
        for (int i = 0; i < l.fields.size(); i++) {
            CppStructParser.Field f = l.fields.get(i);
            tags[i] = effectiveTag(f, region);
            hot[i] = isHot(f, tags[i], st);
        }

        // Rule: two fields with different explicit tags on the same line.
        for (int i = 0; i < l.fields.size(); i++) {
            CppStructParser.Field fi = l.fields.get(i);
            if (!fi.known()) {
                continue;
            }
            int liStart = fi.offset / line;
            int liEnd = (fi.offset + fi.size - 1) / line;
            if (liStart != liEnd) {
                out.add(new Conflict(i, -1, "'" + fi.name + "' crosses a cache line boundary", true));
            }
            for (int j = i + 1; j < l.fields.size(); j++) {
                CppStructParser.Field fj = l.fields.get(j);
                if (!fj.known()) {
                    continue;
                }
                boolean sameLine = sharesLine(fi, fj, line);
                if (!sameLine) {
                    continue;
                }
                boolean explicitPair = tags[i] != null && tags[j] != null && !tags[i].equals(tags[j]);
                if (explicitPair) {
                    out.add(new Conflict(i, j, "false sharing risk with '" + fj.name + "' ("
                            + tags[i] + " vs " + tags[j] + ")", false));
                } else if (tags[i] != null && hot[j] && tags[j] == null) {
                    out.add(new Conflict(i, j, "tagged field '" + fi.name + "' shares a line with hot field '"
                            + fj.name + "'", false));
                } else if (tags[j] != null && hot[i] && tags[i] == null) {
                    out.add(new Conflict(i, j, "tagged field '" + fj.name + "' shares a line with hot field '"
                            + fi.name + "'", false));
                }
            }
            // Atomic next to other mutable state on the same line.
            if (fi.atomic) {
                for (int j = 0; j < l.fields.size(); j++) {
                    if (j == i || !l.fields.get(j).known()) {
                        continue;
                    }
                    if (sharesLine(fi, l.fields.get(j), line)) {
                        out.add(new Conflict(i, j, "atomic '" + fi.name + "' shares a cache line with '"
                                + l.fields.get(j).name + "'", false));
                        break;
                    }
                }
            }
        }

        // Heuristic pair: two hot fields packed tightly without padding.
        if (st.heuristicsOn() && st.hotAutoOn()) {
            for (int i = 0; i < l.fields.size(); i++) {
                for (int j = i + 1; j < l.fields.size(); j++) {
                    if (!hot[i] || !hot[j] || tags[i] != null || tags[j] != null) {
                        continue;
                    }
                    if (sharesLine(l.fields.get(i), l.fields.get(j), line) && !already(out, i, j)) {
                        out.add(new Conflict(i, j, "hot fields '" + l.fields.get(i).name + "' and '"
                                + l.fields.get(j).name + "' share a cache line", false));
                    }
                }
            }
        }
        return out;
    }

    /**
     * True when both byte ranges touch the same cache line.
     *
     * @param a    first field
     * @param b    second field
     * @param line cache line size
     * @return true when they share at least one line
     */
    static boolean sharesLine(CppStructParser.Field a, CppStructParser.Field b, int line) {
        int aStart = a.offset / line;
        int aEnd = (a.offset + a.size - 1) / line;
        int bStart = b.offset / line;
        int bEnd = (b.offset + b.size - 1) / line;
        return aStart <= bEnd && bStart <= aEnd;
    }

    /**
     * Explicit tag for a field. "@threads a,b" on the field expands to
     * the whole comma list joined, so pairs can be compared.
     *
     * @param f      parsed field
     * @param region raw region text
     * @return tag string or null
     */
    static String effectiveTag(CppStructParser.Field f, String region) {
        if (f.thread != null) {
            return f.thread;
        }
        // A block-level "@threads a,b" comment before the field line applies
        // to the nearest following declaration.
        return null;
    }

    /**
     * Heuristic hot-field test: atomics, sync-like types, hot names.
     *
     * @param f    field
     * @param tag  effective explicit tag
     * @param st   settings
     * @return true when the field looks contended
     */
    static boolean isHot(CppStructParser.Field f, String tag, HotPathSettings st) {
        if (tag != null) {
            return true;
        }
        if (!st.heuristicsOn()) {
            return false;
        }
        if (f.atomic) {
            return true;
        }
        String lower = f.type.toLowerCase();
        for (String t : HOT_TYPES) {
            if (lower.contains(t)) {
                return true;
            }
        }
        String n = f.name.toLowerCase();
        for (String h : HOT_NAMES) {
            if (n.contains(h)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether an (i,j) pair was already reported.
     *
     * @param list existing conflicts
     * @param i    first index
     * @param j    second index
     * @return true when present
     */
    static boolean already(ArrayList<Conflict> list, int i, int j) {
        for (Conflict c : list) {
            if ((c.a == i && c.b == j) || (c.a == j && c.b == i)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Collects the set of field indexes involved in conflicts, used to
     * paint red stripes on the memory map.
     *
     * @param conflicts found conflicts
     * @return map from field index to its first message
     */
    public static HashMap<Integer, String> riskyFields(ArrayList<Conflict> conflicts) {
        HashMap<Integer, String> map = new HashMap<>();
        for (Conflict c : conflicts) {
            if (c.a >= 0 && !map.containsKey(c.a)) {
                map.put(c.a, c.message);
            }
            if (c.b >= 0 && !map.containsKey(c.b)) {
                map.put(c.b, c.message);
            }
        }
        return map;
    }
}
