package analysis;

import dev.hotpath.CppStructParser;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects virtual function dispatches (vtable indirect calls) on hot execution paths.
 * Calling virtual methods costs ~30-50 cycles due to vptr dereference, L1i/d miss,
 * and inability of the compiler to inline the call.
 */
public class VirtualCallLinter {

    public static class VirtualCallHazard {
        public final int offsetInBlock;
        public final String expression;
        public final String description;

        public VirtualCallHazard(int offsetInBlock, String expression, String description) {
            this.offsetInBlock = offsetInBlock;
            this.expression = expression;
            this.description = description;
        }
    }

    private static final Pattern INDIRECT_DISPATCH_PATTERN = Pattern.compile(
            "(?:->|\\.)\\s*([a-zA-Z_0-9]+)\\s*\\("
    );

    private static final Pattern DYNAMIC_CAST_PATTERN = Pattern.compile(
            "\\bdynamic_cast\\s*<"
    );

    @NotNull
    public static List<VirtualCallHazard> checkMethodBodies(@NotNull CppStructParser.StructInfo info,
                                                            @NotNull String structBlockText) {
        List<VirtualCallHazard> hazards = new ArrayList<>();
        String clean = CppStructParser.clearCommentsAndStrings(structBlockText);

        // 1. Поиск runtime dynamic_cast (RTTI lookup) на горячем пути
        Matcher dcm = DYNAMIC_CAST_PATTERN.matcher(clean);
        while (dcm.find()) {
            hazards.add(new VirtualCallHazard(
                    dcm.start(),
                    "dynamic_cast",
                    "RTTI Hazard: dynamic_cast on critical path involves runtime typeinfo traversal"
            ));
        }

        // 2. Если сама структура имеет virtual методы или vptr
        if (info.hasVptr) {
            for (CppStructParser.Field f : info.fields) {
                // Если вызывается метод через базовый интерфейсный указатель
                String namePattern = "\\b" + Pattern.quote(f.name) + "->([a-zA-Z_0-9]+)\\s*\\(";
                Matcher m = Pattern.compile(namePattern).matcher(clean);
                while (m.find()) {
                    hazards.add(new VirtualCallHazard(
                            m.start(),
                            m.group(),
                            "Virtual Dispatch Hazard: calling virtual method via '" + f.name +
                                    "->' causes indirect branch and inhibits inlining"
                    ));
                }
            }
        }

        return hazards;
    }

    public static boolean hasVirtualCallHazards(@NotNull CppStructParser.StructInfo info,
                                                @NotNull String blockText) {
        return !checkMethodBodies(info, blockText).isEmpty();
    }
}