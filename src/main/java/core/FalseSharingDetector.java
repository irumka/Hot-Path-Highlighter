package core;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Intelligent False Sharing Detector with MESI Hazard Grading.
 * Operates strictly on multi-threaded code. Single-threaded structs always evaluate to SAFE.
 */
public class FalseSharingDetector {

    public enum HazardLevel {
        WRITE_WRITE_PING_PONG,
        READ_INVALIDATION,
        ARRAY_HAZARD,
        BUFFER_COLLISION,
        POTENTIAL,
        SAFE
    }

    public static class CacheField {
        public final String name;
        public final int offset;
        public final int size;
        public final int cacheLineIndex;
        public final boolean isAtomic;
        public final boolean isProducer;
        public final boolean isConsumer;
        public final boolean isArray;
        public final int arrayCount;

        public CacheField(String name, int offset, int size, int cacheLineSize,
                          boolean isAtomic, boolean isProducer, boolean isConsumer,
                          boolean isArray, int arrayCount) {
            this.name = name;
            this.offset = offset;
            this.size = Math.max(0, size);
            int line = cacheLineSize > 0 ? cacheLineSize : 64;
            this.cacheLineIndex = offset / line;
            this.isAtomic = isAtomic;
            this.isProducer = isProducer;
            this.isConsumer = isConsumer;
            this.isArray = isArray;
            this.arrayCount = Math.max(1, arrayCount);
        }

        public int elementSize() {
            if (size <= 0 || arrayCount <= 0) return 0;
            return size / arrayCount;
        }

        public boolean known() {
            return size > 0;
        }
    }

    public static class Conflict {
        public final CacheField first;
        public final CacheField second;
        public final int cacheLineIndex;
        public final HazardLevel level;
        public final String description;

        public Conflict(CacheField first, CacheField second, int cacheLineIndex, HazardLevel level, String description) {
            this.first = first;
            this.second = second;
            this.cacheLineIndex = cacheLineIndex;
            this.level = level;
            this.description = description;
        }
    }

    public static class Result {
        public final int cacheLineSize;
        public final List<Conflict> conflicts = new ArrayList<>();
        public HazardLevel status = HazardLevel.SAFE;

        public Result(int cacheLineSize) {
            this.cacheLineSize = cacheLineSize;
        }
    }

    @NotNull
    public static Result detect(@NotNull List<CacheField> fields,
                                int cacheLineSize,
                                @NotNull ProducerConsumerAnalyzer.StructProfile profile) {
        int lineSize = cacheLineSize > 0 ? cacheLineSize : 64;
        Result result = new Result(lineSize);

        // ЖЕСТКАЯ ОТСЕЧКА: Однопоточный код НИКОГДА не генерирует False Sharing
        if (!profile.isConcurrent()) {
            result.status = HazardLevel.SAFE;
            return result;
        }

        // 1. Детекция Array False Sharing (только если размер элемента < строки кэша)
        for (CacheField f : fields) {
            if (!f.known() || f.size <= 0) continue;

            if (f.isArray && f.arrayCount > 1) {
                int elemSize = f.elementSize();
                if (elemSize > 0 && elemSize < lineSize) {
                    Conflict c = new Conflict(f, f, f.cacheLineIndex, HazardLevel.ARRAY_HAZARD,
                            "Array False Sharing: worker elements (" + elemSize + "B) share cache lines");
                    result.conflicts.add(c);
                    result.status = HazardLevel.ARRAY_HAZARD;
                }
            }
        }

        // 2. Межполевой анализ многопоточной коллизии
        for (int i = 0; i < fields.size(); i++) {
            CacheField f1 = fields.get(i);
            if (!f1.known()) continue;
            int f1Start = f1.offset / lineSize;
            int f1End = (f1.offset + Math.max(1, f1.size) - 1) / lineSize;

            for (int j = i + 1; j < fields.size(); j++) {
                CacheField f2 = fields.get(j);
                if (!f2.known()) continue;
                int f2Start = f2.offset / lineSize;
                int f2End = (f2.offset + Math.max(1, f2.size) - 1) / lineSize;

                boolean sharesLine = f1Start <= f2End && f2Start <= f1End;
                if (!sharesLine) continue;

                int conflictLine = Math.max(f1Start, f2Start);

                // Очередь / кольцевой буфер (хранилище делит строку с атомарным курсором)
                if ((f1.isAtomic && f2.isArray) || (f2.isAtomic && f1.isArray)) {
                    Conflict c = new Conflict(f1, f2, conflictLine, HazardLevel.BUFFER_COLLISION,
                            "Queue Collision: buffer storage shares line with atomic pointer");
                    result.conflicts.add(c);
                    if (result.status != HazardLevel.WRITE_WRITE_PING_PONG) {
                        result.status = HazardLevel.BUFFER_COLLISION;
                    }
                }
                // Write-Write Ping-Pong (два потока пишут в одну строку)
                else if (f1.isProducer && f2.isProducer) {
                    Conflict c = new Conflict(f1, f2, conflictLine, HazardLevel.WRITE_WRITE_PING_PONG,
                            "MESI Write-Write Ping-Pong: concurrent stores on line #" + conflictLine +
                                    " (" + f1.name + " and " + f2.name + ")");
                    result.conflicts.add(c);
                    result.status = HazardLevel.WRITE_WRITE_PING_PONG;
                }
                // Read Invalidation (один поток пишет, второй читает)
                else if ((f1.isProducer && f2.isConsumer) || (f1.isConsumer && f2.isProducer)) {
                    Conflict c = new Conflict(f1, f2, conflictLine, HazardLevel.READ_INVALIDATION,
                            "MESI Read Invalidation: store to " + (f1.isProducer ? f1.name : f2.name) +
                                    " invalidates reader of " + (f1.isConsumer ? f1.name : f2.name));
                    result.conflicts.add(c);
                    if (result.status != HazardLevel.WRITE_WRITE_PING_PONG) {
                        result.status = HazardLevel.READ_INVALIDATION;
                    }
                }
                // Потенциальный конфликт атомика с обычным полем
                else if (f1.isAtomic || f2.isAtomic) {
                    Conflict c = new Conflict(f1, f2, conflictLine, HazardLevel.POTENTIAL,
                            "Potential False Sharing: atomic shares line with " + (f1.isAtomic ? f2.name : f1.name));
                    result.conflicts.add(c);
                    if (result.status == HazardLevel.SAFE) {
                        result.status = HazardLevel.POTENTIAL;
                    }
                }
            }
        }

        return result;
    }
}