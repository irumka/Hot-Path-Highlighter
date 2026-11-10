package analysis;

import dev.hotpath.CppStructParser;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects latency-killing heap allocations on critical execution paths:
 * explicit new/malloc/posix_memalign and hidden heap-backed STL containers.
 * Prevents false positives by stripping comments and string literals.
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

    private static final Pattern EXPLICIT_ALLOC_PATTERN = Pattern.compile(
            "(?:\\b(?:new\\s*[\\[\\(]|new\\s+[a-zA-Z0-9_:]+|operator\\s+new\\b)|" +
                    "\\b(?:malloc|calloc|realloc|aligned_alloc|posix_memalign)\\s*\\(|" +
                    "\\b(?:std::)?(?:make_unique|make_shared)\\b)"
    );

    private static final Pattern HIDDEN_HEAP_CONTAINER_PATTERN = Pattern.compile(
            "\\b(?:std::)?(?:vector|string|function|shared_ptr|deque|unordered_map|map)\\s*<"
    );

    @NotNull
    public static List<AllocationHazard> inspectMethodBodies(@NotNull String structBlockText, int regionStartOffset) {
        List<AllocationHazard> hazards = new ArrayList<>();
        // Очищаем комментарии и строковые литералы перед сканированием
        String cleanText = CppStructParser.clearCommentsAndStrings(structBlockText);

        // 1. Поиск явных вызовов аллокатора
        Matcher m = EXPLICIT_ALLOC_PATTERN.matcher(cleanText);
        while (m.find()) {
            String kw = m.group().trim();
            int absOffset = regionStartOffset + m.start();
            hazards.add(new AllocationHazard(
                    absOffset,
                    kw,
                    "Zero-Alloc Violation: dynamic heap allocation '" + kw + "' on critical execution path"
            ));
        }

        // 2. Поиск скрытых динамических контейнеров в теле горячих методов
        Matcher cm = HIDDEN_HEAP_CONTAINER_PATTERN.matcher(cleanText);
        while (cm.find()) {
            String kw = cm.group().trim();
            int absOffset = regionStartOffset + cm.start();
            hazards.add(new AllocationHazard(
                    absOffset,
                    kw,
                    "Heap Container Hazard: '" + kw + "' performs hidden dynamic memory allocations"
            ));
        }

        return hazards;
    }

    public static boolean hasZeroAllocHazards(@NotNull CppStructParser.StructInfo info, @NotNull String blockText) {
        String cleanText = CppStructParser.clearCommentsAndStrings(blockText);
        return EXPLICIT_ALLOC_PATTERN.matcher(cleanText).find();
    }
}