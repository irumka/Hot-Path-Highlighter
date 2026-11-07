package core;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Audits static and global atomic variables in .bss and .data sections.
 * Detects adjacent atomic counters located within the same 64-byte memory span.
 */
public class GlobalAtomicsAudit {

    public static class ContiguousAtomic {
        public final String name;
        public final int offsetInDoc;
        public final int lineNum;
        public final boolean isAligned;

        public ContiguousAtomic(String name, int offsetInDoc, int lineNum, boolean isAligned) {
            this.name = name;
            this.offsetInDoc = offsetInDoc;
            this.lineNum = lineNum;
            this.isAligned = isAligned;
        }
    }

    public static class AuditConflict {
        public final ContiguousAtomic first;
        public final ContiguousAtomic second;
        public final String message;

        public AuditConflict(ContiguousAtomic first, ContiguousAtomic second, String message) {
            this.first = first;
            this.second = second;
            this.message = message;
        }
    }

    private static final Pattern GLOBAL_ATOMIC_PATTERN = Pattern.compile(
            "^[ \\t]*(?:static\\s+)?(?:alignas\\s*\\(([^)]+)\\)\\s+)?(?:std::)?(?:atomic<[^>]+>|atomic_[a-zA-Z0-9_]+)\\s+([a-zA-Z0-9_]+)",
            Pattern.MULTILINE
    );

    @NotNull
    public static List<AuditConflict> audit(@NotNull String fullDocumentText, int cacheLineSize) {
        List<AuditConflict> conflicts = new ArrayList<>();
        List<ContiguousAtomic> atomics = new ArrayList<>();

        Matcher m = GLOBAL_ATOMIC_PATTERN.matcher(fullDocumentText);
        while (m.find()) {
            boolean isAligned = m.group(1) != null;
            String varName = m.group(2);
            int startOffset = m.start();
            atomics.add(new ContiguousAtomic(varName, startOffset, 0, isAligned));
        }

        for (int i = 0; i < atomics.size() - 1; i++) {
            ContiguousAtomic a1 = atomics.get(i);
            ContiguousAtomic a2 = atomics.get(i + 1);

            if (!a1.isAligned && !a2.isAligned) {
                if (Math.abs(a2.offsetInDoc - a1.offsetInDoc) < 250) {
                    conflicts.add(new AuditConflict(
                            a1,
                            a2,
                            "Global Atomics Collision: adjacent in .bss/.data segment ('" + a1.name + "' and '" + a2.name + "')"
                    ));
                }
            }
        }
        return conflicts;
    }
}