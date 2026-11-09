package analysis;

import dev.hotpath.CppStructParser;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects latency-killing heap allocations on critical execution paths:
 * new, new[], placement new, operator new, malloc, calloc, realloc,
 * posix_memalign, aligned_alloc, std::make_unique, std::make_shared.
 */
public class ZeroAllocLinter {

    public static class AllocationHazard {
        public final int offset;
        public final String keyword;
        public final String description;

        public AllocationHazard(int offset, String keyword, String description) {
            this.offset = offset;
            this.keyword = keyword;
            this.description = description;
        }
    }

    private static final Pattern ALLOC_PATTERN = Pattern.compile(
            "(?:\\b(?:new\\s*[\\[\\(]|new\\s+[a-zA-Z0-9_:]+|operator\\s+new\\b)|" +
                    "\\b(?:malloc|calloc|realloc|aligned_alloc|posix_memalign)\\s*\\(|" +
                    "\\b(?:std::)?(?:make_unique|make_shared)\\b)"
    );

    @NotNull
    public static List<AllocationHazard> inspectMethodBodies(@NotNull String structBlockText, int regionStartOffset) {
        List<AllocationHazard> hazards = new ArrayList<>();
        Matcher m = ALLOC_PATTERN.matcher(structBlockText);

        while (m.find()) {
            String kw = m.group().trim();
            int absOffset = regionStartOffset + m.start();
            hazards.add(new AllocationHazard(
                    absOffset,
                    kw,
                    "Zero-Alloc Violation: dynamic heap allocation '" + kw + "' on critical path"
            ));
        }
        return hazards;
    }

    public static boolean hasZeroAllocHazards(@NotNull CppStructParser.StructInfo info, @NotNull String blockText) {
        return ALLOC_PATTERN.matcher(blockText).find();
    }
}