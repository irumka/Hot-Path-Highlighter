package analysis;

import dev.hotpath.CppStructParser;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies struct fields into HOT (critical path) and COLD (diagnostic, logging, rare errors).
 * Uses multi-factor semantic analysis: loop bodies, error handlers, and naming heuristics.
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
        String cleanBlock = CppStructParser.clearCommentsAndStrings(structBlockText);

        for (CppStructParser.Field field : info.fields) {
            String name = field.name;
            if (name == null || name.isEmpty() || name.startsWith("_unnamed_bitfield")) continue;

            // 1. Проверка по эвристикам имен
            if (COLD_NAME_PATTERN.matcher(name).matches()) {
                result.put(name, new FieldAnalysis(name, Temperature.COLD, "Diagnostic/logging naming pattern"));
                continue;
            }

            if (HOT_NAME_PATTERN.matcher(name).matches() || field.atomic) {
                result.put(name, new FieldAnalysis(name, Temperature.HOT, "Critical path naming or atomic"));
                continue;
            }

            // 2. Семантический контекстный анализ (циклы vs обработчики ошибок)
            boolean usedInLoop = isAccessedInsideLoop(name, cleanBlock);
            if (usedInLoop) {
                result.put(name, new FieldAnalysis(name, Temperature.HOT, "Accessed inside hot processing loop"));
                continue;
            }

            boolean usedInColdOnly = isUsedOnlyInColdContext(name, cleanBlock);
            if (usedInColdOnly) {
                result.put(name, new FieldAnalysis(name, Temperature.COLD, "Only accessed in diagnostic/error methods"));
                continue;
            }

            // 3. Тяжелые контейнеры по умолчанию считаются COLD в горячей структуре
            String decl = field.decl != null ? field.decl.toLowerCase() : "";
            if (decl.contains("string") || decl.contains("vector")) {
                result.put(name, new FieldAnalysis(name, Temperature.COLD, "Heavy heap container in struct"));
                continue;
            }

            result.put(name, new FieldAnalysis(name, Temperature.NEUTRAL, "General data"));
        }

        return result;
    }

    private static boolean isAccessedInsideLoop(String fieldName, String code) {
        Pattern loopPattern = Pattern.compile("\\b(for|while|do)\\s*\\([^)]*\\)\\s*\\{([^}]+)\\}");
        Matcher m = loopPattern.matcher(code);
        String pattern = "\\b" + Pattern.quote(fieldName) + "\\b";
        while (m.find()) {
            if (Pattern.compile(pattern).matcher(m.group(2)).find()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUsedOnlyInColdContext(String fieldName, String code) {
        Pattern methodDef = Pattern.compile("([a-zA-Z_0-9]+)\\s*\\([^)]*\\)[^{]*\\{([^}]+)\\}");
        Matcher matcher = methodDef.matcher(code);

        boolean foundUsage = false;
        boolean foundInHotMethod = false;
        String fieldRegex = "\\b" + Pattern.quote(fieldName) + "\\b";

        while (matcher.find()) {
            String methodName = matcher.group(1);
            String body = matcher.group(2);

            if (Pattern.compile(fieldRegex).matcher(body).find()) {
                foundUsage = true;
                if (!COLD_METHOD_PATTERN.matcher(methodName).matches()) {
                    foundInHotMethod = true;
                }
            }
        }
        return foundUsage && !foundInHotMethod;
    }
}