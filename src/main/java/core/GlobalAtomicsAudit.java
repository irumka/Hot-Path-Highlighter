package core;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Audits static and global atomic variables in .bss and .data sections.
 * Uses an O(N) linear state machine for comment stripping and supports qualifiers.
 */
public class GlobalAtomicsAudit {

    public static class ContiguousAtomic {
        public final String name;
        public final int offsetInDoc;
        public final int startOffset;
        public final int endOffset;
        public final int lineNum;
        public final boolean isAligned;

        public ContiguousAtomic(String name, int startOffset, int endOffset, boolean isAligned) {
            this(name, startOffset, endOffset, 0, isAligned);
        }

        public ContiguousAtomic(String name, int startOffset, int endOffset, int lineNum, boolean isAligned) {
            this.name = name;
            this.offsetInDoc = startOffset;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
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

    // Fix 3.2: Supports const, volatile and constexpr qualifiers
    private static final Pattern GLOBAL_ATOMIC_PATTERN = Pattern.compile(
            "^[ \\t]*(?:static\\s+)?(?:(?:const|volatile|constexpr)\\s+)*(?:alignas\\s*\\(([^)]+)\\)\\s+)?(?:const|volatile\\s+)*(?:std::)?(?:atomic<[^>]+>|atomic_[a-zA-Z0-9_]+)\\s+([a-zA-Z0-9_]+)",
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
            int endOffset = m.end();
            atomics.add(new ContiguousAtomic(varName, startOffset, endOffset, isAligned));
        }

        for (int i = 0; i < atomics.size() - 1; i++) {
            ContiguousAtomic a1 = atomics.get(i);
            ContiguousAtomic a2 = atomics.get(i + 1);

            if (!a1.isAligned && !a2.isAligned) {
                if (a2.startOffset > a1.endOffset) {
                    String between = fullDocumentText.substring(a1.endOffset, a2.startOffset);
                    String stripped = stripCommentsAndInitializersO_N(between);

                    if (isContiguousDeclaration(stripped)) {
                        conflicts.add(new AuditConflict(
                                a1,
                                a2,
                                "Global Atomics Collision: contiguous in .bss/.data segment ('" + a1.name + "' and '" + a2.name + "')"
                        ));
                    }
                }
            }
        }
        return conflicts;
    }

    // Fix 2.2: O(N) deterministic scanner replacing ReDoS-prone regexes
    private static String stripCommentsAndInitializersO_N(String text) {
        char[] in = text.toCharArray();
        StringBuilder sb = new StringBuilder(in.length);
        int state = 0; // 0=code, 1=lineComment, 2=blockComment, 3=string, 4=initBrace

        for (int i = 0; i < in.length; i++) {
            char c = in[i];
            char n = (i + 1 < in.length) ? in[i + 1] : 0;

            if (state == 0) {
                if (c == '/' && n == '/') {
                    state = 1;
                    i++;
                } else if (c == '/' && n == '*') {
                    state = 2;
                    i++;
                } else if (c == '"' || c == '\'') {
                    state = 3;
                } else if (c == '{') {
                    state = 4;
                } else {
                    sb.append(c);
                }
            } else if (state == 1) {
                if (c == '\n') state = 0;
            } else if (state == 2) {
                if (c == '*' && n == '/') {
                    state = 0;
                    i++;
                }
            } else if (state == 3) {
                if (c == '"' || c == '\'') state = 0;
            } else if (state == 4) {
                if (c == '}') state = 0;
            }
        }
        return sb.toString().trim();
    }

    private static boolean isContiguousDeclaration(String stripped) {
        for (int i = 0; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            if (c != ';' && c != '=' && c != '0' && !Character.isWhitespace(c)) {
                return false;
            }
        }
        return true;
    }
}