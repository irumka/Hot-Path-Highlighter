package analysis;

import dev.hotpath.CppStructParser;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects unannotated error branches on hot execution paths and suggests
 * C++20 [[unlikely]] / [[likely]] branch predictor attributes.
 */
public class BranchPredictorLinter {

    public static class BranchHazard {
        public final int offsetInBlock;
        public final String conditionText;
        public final String description;

        public BranchHazard(int offsetInBlock, String conditionText, String description) {
            this.offsetInBlock = offsetInBlock;
            this.conditionText = conditionText;
            this.description = description;
        }
    }

    private static final Pattern UNANNOTATED_ERROR_BRANCH = Pattern.compile(
            "\\bif\\s*\\(([^)]*(?:err|error|fail|invalid|overflow|unlikely|timeout)[^)]*)\\)\\s*\\{",
            Pattern.CASE_INSENSITIVE
    );

    @NotNull
    public static List<BranchHazard> checkHotMethods(@NotNull String structBlockText) {
        List<BranchHazard> hazards = new ArrayList<>();
        String clean = CppStructParser.clearCommentsAndStrings(structBlockText);
        Matcher m = UNANNOTATED_ERROR_BRANCH.matcher(clean);

        while (m.find()) {
            String cond = m.group(1).trim();
            // Если атрибут уже проставлен — пропускаем
            if (cond.contains("unlikely") && cond.contains("[[")) continue;
            if (cond.contains("__builtin_expect")) continue;

            hazards.add(new BranchHazard(
                    m.start(),
                    cond,
                    "Branch Prediction Hazard: error branch 'if (" + cond + ")' on hot path lacks [[unlikely]] attribute"
            ));
        }
        return hazards;
    }
}