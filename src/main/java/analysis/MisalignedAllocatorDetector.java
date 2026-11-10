package analysis;

import dev.hotpath.CppStructParser;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects over-aligned structs (alignas > 16) used inside std::vector or std::deque
 * without an explicit aligned allocator (causes SIGSEGV or breaks cache alignment).
 */
public class MisalignedAllocatorDetector {

    public static class MisalignedContainerIssue {
        public final String structName;
        public final int alignment;
        public final String containerType;
        public final String description;

        public MisalignedContainerIssue(String structName, int alignment, String containerType, String description) {
            this.structName = structName;
            this.alignment = alignment;
            this.containerType = containerType;
            this.description = description;
        }
    }

    @NotNull
    public static List<MisalignedContainerIssue> checkFile(@NotNull String fileContent,
                                                           @NotNull List<CppStructParser.StructInfo> parsedStructs) {
        List<MisalignedContainerIssue> issues = new ArrayList<>();
        String clean = CppStructParser.clearCommentsAndStrings(fileContent);

        for (CppStructParser.StructInfo info : parsedStructs) {
            if (info.explicitStructAlign > 16) {
                String sName = Pattern.quote(info.name);
                // Проверяем std::vector<StructName> без второго шаблонного аргумента аллокатора
                Pattern vecPattern = Pattern.compile("\\b(?:std::)?(?:vector|deque)<\\s*" + sName + "\\s*>");
                Matcher m = vecPattern.matcher(clean);

                if (m.find()) {
                    issues.add(new MisalignedContainerIssue(
                            info.name,
                            info.explicitStructAlign,
                            m.group(),
                            "Misaligned Allocator Hazard: " + m.group() + " uses default allocator which does NOT guarantee "
                                    + info.explicitStructAlign + "-byte alignment in heap"
                    ));
                }
            }
        }
        return issues;
    }
}