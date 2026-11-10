package analysis;

import dev.hotpath.CppStructParser;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects performance-degrading std::memory_order_seq_cst usage in atomic operations.
 * Default seq_cst forces CPU pipeline drains and memory barriers (mfence / lock prefix).
 * Suggests relaxed, acquire, or release semantics where appropriate.
 */
public class MemoryOrderLinter {

    public enum SuggestedOrder {
        RELAXED("std::memory_order_relaxed"),
        ACQUIRE("std::memory_order_acquire"),
        RELEASE("std::memory_order_release"),
        ACQ_REL("std::memory_order_acq_rel");

        public final String code;

        SuggestedOrder(String code) {
            this.code = code;
        }
    }

    public static class MemoryOrderHazard {
        public final int offsetInDoc;
        public final int matchLength;
        public final String atomicName;
        public final String operation;
        public final String fullMatch;
        public final SuggestedOrder suggestedOrder;
        public final String description;

        public MemoryOrderHazard(int offsetInDoc, int matchLength, String atomicName, String operation,
                                 String fullMatch, SuggestedOrder suggestedOrder, String description) {
            this.offsetInDoc = offsetInDoc;
            this.matchLength = matchLength;
            this.atomicName = atomicName;
            this.operation = operation;
            this.fullMatch = fullMatch;
            this.suggestedOrder = suggestedOrder;
            this.description = description;
        }
    }

    private static final Pattern ATOMIC_OP_PATTERN = Pattern.compile(
            "\\b([a-zA-Z0-9_]+)\\s*\\.\\s*(load|store|fetch_add|fetch_sub|exchange)\\s*\\(([^)]*)\\)|" +
                    "(?:\\+\\+|--)\\s*([a-zA-Z0-9_]+)|\\b([a-zA-Z0-9_]+)\\s*(?:\\+\\+|--)"
    );

    @NotNull
    public static List<MemoryOrderHazard> inspect(@NotNull CppStructParser.StructInfo info,
                                                  @NotNull String structBlockText,
                                                  int blockStartOffset) {
        List<MemoryOrderHazard> hazards = new ArrayList<>();
        String clean = CppStructParser.clearCommentsAndStrings(structBlockText);

        Set<String> atomicNames = new HashSet<>();
        for (CppStructParser.Field f : info.fields) {
            if (f.atomic || f.type.contains("atomic")) {
                atomicNames.add(f.name);
            }
        }
        if (atomicNames.isEmpty()) return hazards;

        Matcher m = ATOMIC_OP_PATTERN.matcher(clean);
        while (m.find()) {
            String dotVar = m.group(1);
            String op = m.group(2);
            String args = m.group(3);
            String preVar = m.group(4);
            String postVar = m.group(5);

            String varName = dotVar != null ? dotVar : (preVar != null ? preVar : postVar);
            if (!atomicNames.contains(varName)) continue;

            int matchOffset = blockStartOffset + m.start();
            int matchLen = m.end() - m.start();
            String fullMatch = m.group();

            if (op != null) {
                boolean hasExplicitOrder = args != null && args.contains("memory_order");
                boolean isSeqCst = args != null && args.contains("memory_order_seq_cst");

                if (!hasExplicitOrder || isSeqCst) {
                    SuggestedOrder suggested;
                    String reason;
                    if ("load".equals(op)) {
                        suggested = SuggestedOrder.ACQUIRE;
                        reason = "Default seq_cst load on '" + varName + "'. Consider std::memory_order_acquire or relaxed";
                    } else if ("store".equals(op)) {
                        suggested = SuggestedOrder.RELEASE;
                        reason = "Default seq_cst store on '" + varName + "'. Consider std::memory_order_release";
                    } else {
                        suggested = SuggestedOrder.RELAXED;
                        reason = "Default seq_cst on '" + varName + "." + op + "()'. Consider std::memory_order_relaxed";
                    }
                    hazards.add(new MemoryOrderHazard(matchOffset, matchLen, varName, op, fullMatch, suggested, reason));
                }
            } else {
                hazards.add(new MemoryOrderHazard(
                        matchOffset, matchLen, varName, "++", fullMatch, SuggestedOrder.RELAXED,
                        "Operator ++ on atomic '" + varName + "' invokes heavy seq_cst. Consider fetch_add(1, std::memory_order_relaxed)"
                ));
            }
        }
        return hazards;
    }
}