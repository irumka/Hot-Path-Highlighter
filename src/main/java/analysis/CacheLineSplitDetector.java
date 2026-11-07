package analysis;

import dev.hotpath.CppStructParser;
import dev.hotpath.LayoutCalculator;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects scalar fields that cross 64-byte L1 Data Cache line boundaries (Cache Line Split).
 * Such splits cause hardware penalties (dual L1 cache line accesses and split locks).
 */
public class CacheLineSplitDetector {

    public static class SplitField {
        public final CppStructParser.Field field;
        public final int offset;
        public final int size;
        public final int startLineIndex;
        public final int endLineIndex;
        public final int paddingNeeded;

        public SplitField(@NotNull CppStructParser.Field field, int offset, int size, int cacheLineSize) {
            this.field = field;
            this.offset = offset;
            this.size = size;
            this.startLineIndex = offset / cacheLineSize;
            this.endLineIndex = (offset + size - 1) / cacheLineSize;
            int offsetInLine = offset % cacheLineSize;
            this.paddingNeeded = cacheLineSize - offsetInLine;
        }

        public boolean isSplit() {
            return startLineIndex != endLineIndex;
        }
    }

    @NotNull
    public static List<SplitField> findSplits(@NotNull LayoutCalculator.Layout layout, int cacheLineSize) {
        int lineSize = cacheLineSize > 0 ? cacheLineSize : 64;
        List<SplitField> splits = new ArrayList<>();
        if (layout.fields == null) {
            return splits;
        }

        for (CppStructParser.Field f : layout.fields) {
            if (f.size > 1) {
                SplitField sf = new SplitField(f, f.offset, f.size, lineSize);
                if (sf.isSplit()) {
                    splits.add(sf);
                }
            }
        }
        return splits;
    }
}