package analysis;

import dev.hotpath.CppStructParser;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects "Trojan Horse" container types in structs that appear flat but
 * implicitly perform non-deterministic heap allocations (std::function, std::shared_ptr,
 * std::any, or std::string beyond Small String Optimization).
 */
public class TrojanAllocDetector {

    public static class TrojanHazard {
        public final String fieldName;
        public final String typeName;
        public final String alternative;
        public final String description;

        public TrojanHazard(String fieldName, String typeName, String alternative, String description) {
            this.fieldName = fieldName;
            this.typeName = typeName;
            this.alternative = alternative;
            this.description = description;
        }
    }

    private static final Pattern TROJAN_PATTERN = Pattern.compile(
            "\\b(?:std::)?(function|shared_ptr|any|string|vector|deque)\\b"
    );

    @NotNull
    public static List<TrojanHazard> inspectFields(@NotNull CppStructParser.StructInfo info) {
        List<TrojanHazard> hazards = new ArrayList<>();

        for (CppStructParser.Field f : info.fields) {
            if (f.type == null) continue;
            Matcher m = TROJAN_PATTERN.matcher(f.type);

            if (m.find()) {
                String matched = m.group(1);
                String alt;
                String desc;

                switch (matched) {
                    case "function":
                        alt = "template <typename F> or function_ref";
                        desc = "std::function triggers dynamic heap allocation if capture exceeds SOO (16-24B)";
                        break;
                    case "shared_ptr":
                        alt = "std::unique_ptr with custom arena or raw reference";
                        desc = "std::shared_ptr allocates control block on heap and performs atomic seq_cst refcounting";
                        break;
                    case "any":
                        alt = "std::variant or tagged union";
                        desc = "std::any allocates dynamically for types exceeding small-buffer size";
                        break;
                    case "string":
                        alt = "std::string_view or std::array<char, N>";
                        desc = "std::string triggers heap allocation when length exceeds SSO limit (15-22 chars)";
                        break;
                    case "vector":
                    case "deque":
                        alt = "std::array<T, N> or boost::container::small_vector";
                        desc = "Dynamic container breaks cache locality and causes unbounded heap latency spikes";
                        break;
                    default:
                        alt = "fixed flat buffer";
                        desc = "Hidden heap allocator container";
                        break;
                }

                hazards.add(new TrojanHazard(f.name, f.type, alt, desc));
            }
        }
        return hazards;
    }
}