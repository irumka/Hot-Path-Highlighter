package analysis;

import dev.hotpath.CppStructParser;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies struct fields into HOT (critical execution path) and COLD (diagnostic, logging, rare errors).
 */
public class AccessPatternAnalyzer {

    public enum Temperature {
        HOT,
        COLD,
        NEUTRAL
    }

    private static final Pattern COLD_NAME_PATTERN = Pattern.compile(
            ".*(err|error|diag|debug|log|reason|comment|desc|description|dump|client_name|account_name|metadata|msg|message|status_text).*",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern HOT_NAME_PATTERN = Pattern.compile(
            ".*(price|qty|volume|size|seq|sequence|order_id|head|tail|counter|count|timestamp|ts|time_ns|flags|mask).*",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern COLD_METHOD_PATTERN = Pattern.compile(
            ".*(print|log|dump|to_string|inspect|to_json|format|serialize|on_error|handle_failure).*",
            Pattern.CASE_INSENSITIVE
    );

    public static class FieldAnalysis {
        public final String name;
        public final Temperature temperature;
        public final String reason;

        public FieldAnalysis(String name, Temperature temperature, String reason) {
            this.name = name;
            this.temperature = temperature;
            this.reason = reason;
        }
    }

    @NotNull
    public static Map<String, FieldAnalysis> analyze(@NotNull CppStructParser.StructInfo info, @NotNull String structBlockText) {
        Map<String, FieldAnalysis> result = new HashMap<>();

        for (CppStructParser.Field field : info.fields) {
            String name = field.name;
            if (name == null || name.isEmpty()) continue;

            if (COLD_NAME_PATTERN.matcher(name).matches()) {
                result.put(name, new FieldAnalysis(name, Temperature.COLD, "Matches cold naming pattern (diagnostic/error/log)"));
                continue;
            }

            if (HOT_NAME_PATTERN.matcher(name).matches() || field.atomic) {
                result.put(name, new FieldAnalysis(name, Temperature.HOT, "Matches hot naming pattern or atomic"));
                continue;
            }

            boolean usedInColdMethodOnly = isUsedOnlyInColdMethods(name, structBlockText);
            if (usedInColdMethodOnly) {
                result.put(name, new FieldAnalysis(name, Temperature.COLD, "Only accessed in diagnostic/logging methods"));
                continue;
            }

            String decl = field.decl != null ? field.decl.toLowerCase() : "";
            if (decl.contains("string") || decl.contains("char[") || decl.contains("vector")) {
                result.put(name, new FieldAnalysis(name, Temperature.COLD, "Heavy container/buffer inside critical struct"));
                continue;
            }

            result.put(name, new FieldAnalysis(name, Temperature.NEUTRAL, "General scalar data"));
        }

        return result;
    }

    private static boolean isUsedOnlyInColdMethods(String fieldName, String structBlock) {
        Pattern methodDef = Pattern.compile(
                "([a-zA-Z_0-9]+)\\s*\\([^)]*\\)\\s*(?:const)?\\s*\\{([^}]+)\\}",
                Pattern.MULTILINE
        );
        Matcher matcher = methodDef.matcher(structBlock);

        boolean foundUsage = false;
        boolean foundInHotMethod = false;

        while (matcher.find()) {
            String methodName = matcher.group(1);
            String body = matcher.group(2);

            if (body.contains(fieldName)) {
                foundUsage = true;
                if (!COLD_METHOD_PATTERN.matcher(methodName).matches()) {
                    foundInHotMethod = true;
                }
            }
        }

        return foundUsage && !foundInHotMethod;
    }
}