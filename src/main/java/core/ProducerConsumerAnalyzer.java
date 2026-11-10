package core;

import dev.hotpath.CppStructParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Robust concurrency and access-pattern analyzer.
 * Pre-compiles field access patterns to minimize GC overhead and parse complex C++ method bodies.
 */
public class ProducerConsumerAnalyzer {

    public enum ConcurrencyModel {
        EXPLICIT_CONCURRENT,
        QUEUE_OR_RINGBUFFER,
        CONTROL_PLANE,
        SINGLE_THREADED
    }

    public enum AccessType {
        READ_ONLY,
        WRITE_ONLY,
        READ_WRITE,
        NONE
    }

    public static class FieldProfile {
        public final String name;
        public final boolean isAtomic;
        public final boolean isMutex;
        public final boolean isArray;
        public final int arraySize;
        public AccessType accessType = AccessType.NONE;
        public final Set<String> writerMethods = new HashSet<>();
        public final Set<String> readerMethods = new HashSet<>();

        public FieldProfile(String name, boolean isAtomic, boolean isMutex, boolean isArray, int arraySize) {
            this.name = name;
            this.isAtomic = isAtomic;
            this.isMutex = isMutex;
            this.isArray = isArray;
            this.arraySize = Math.max(1, arraySize);
        }

        public boolean isProducer() {
            return accessType == AccessType.WRITE_ONLY || (!writerMethods.isEmpty() && readerMethods.isEmpty());
        }

        public boolean isConsumer() {
            return accessType == AccessType.READ_ONLY || (!readerMethods.isEmpty() && writerMethods.isEmpty());
        }
    }

    public static class StructProfile {
        public final String name;
        public ConcurrencyModel concurrencyModel;
        public final Map<String, FieldProfile> fields = new HashMap<>();
        public boolean hasAtomicsOrLocks = false;

        public StructProfile(String name, ConcurrencyModel concurrencyModel) {
            this.name = name;
            this.concurrencyModel = concurrencyModel;
        }

        @Nullable
        public FieldProfile getFieldProfile(String fieldName) {
            return fields.get(fieldName);
        }

        public boolean isConcurrent() {
            return concurrencyModel == ConcurrencyModel.EXPLICIT_CONCURRENT ||
                    concurrencyModel == ConcurrencyModel.QUEUE_OR_RINGBUFFER ||
                    hasAtomicsOrLocks;
        }
    }

    public static class ExtractedMethod {
        public final String name;
        public final String body;

        public ExtractedMethod(String name, String body) {
            this.name = name;
            this.body = body;
        }
    }

    private static final Pattern METHOD_START_PATTERN = Pattern.compile(
            "\\b([a-zA-Z_0-9]+)\\s*\\("
    );

    private static final Pattern ARRAY_DECL_PATTERN = Pattern.compile("\\[(\\d+)\\]");

    private static final Pattern QUEUE_NAME_PATTERN = Pattern.compile(
            ".*(queue|ringbuffer|ring_buffer|spsc|mpmc|spmc|mpsc|channel|disruptor|mailbox|worker|pool|pingpong|ping_pong|ping|invalidation|producer|consumer|concurrent).*",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern ORCHESTRATOR_PATTERN = Pattern.compile(
            ".*(manager|orchestrator|config|context|builder|factory|registry|service).*",
            Pattern.CASE_INSENSITIVE
    );

    @NotNull
    public static StructProfile analyze(@NotNull CppStructParser.StructInfo info, @NotNull String structBlockText) {
        String structName = info.name != null && !info.name.isEmpty() ? info.name : "AnonymousStruct";

        ConcurrencyModel model = ConcurrencyModel.SINGLE_THREADED;
        if (QUEUE_NAME_PATTERN.matcher(structName).matches()) {
            model = ConcurrencyModel.QUEUE_OR_RINGBUFFER;
        } else if (ORCHESTRATOR_PATTERN.matcher(structName).matches()) {
            model = ConcurrencyModel.CONTROL_PLANE;
        }

        StructProfile profile = new StructProfile(structName, model);

        for (CppStructParser.Field field : info.fields) {
            String decl = field.decl != null ? field.decl.toLowerCase() : "";
            boolean isAtomic = decl.contains("atomic") || decl.contains("atomic_flag");
            boolean isMutex = decl.contains("mutex") || decl.contains("spinlock") || decl.contains("lock");

            boolean isArray = false;
            int arrayCount = field.arrayCount > 1 ? field.arrayCount : 1;
            Matcher arrayMatch = ARRAY_DECL_PATTERN.matcher(field.decl != null ? field.decl : "");
            if (arrayMatch.find()) {
                isArray = true;
                try {
                    arrayCount = Integer.parseInt(arrayMatch.group(1));
                } catch (NumberFormatException ignored) {}
            }

            String lowerType = field.type != null ? field.type.toLowerCase() : "";
            String lowerName = field.name != null ? field.name.toLowerCase() : "";
            if (isArray && (lowerType.contains("worker") || lowerType.contains("ctx") || lowerName.contains("worker")
                    || decl.contains("worker") || decl.contains("thread"))) {
                profile.hasAtomicsOrLocks = true;
            }

            if (isAtomic || isMutex) {
                profile.hasAtomicsOrLocks = true;
            }

            profile.fields.put(field.name, new FieldProfile(field.name, isAtomic, isMutex, isArray, arrayCount));
        }

        // Прекомпиляция регулярных выражений: ровно 1 раз на поле (сложность O(F) вместо O(M * F))
        Map<String, Pattern> writePatterns = new HashMap<>();
        Map<String, Pattern> prefixIncPatterns = new HashMap<>();
        Map<String, Pattern> readPatterns = new HashMap<>();

        for (CppStructParser.Field field : info.fields) {
            String quotedField = Pattern.quote(field.name);
            writePatterns.put(field.name, Pattern.compile(
                    "\\b" + quotedField + "\\s*(?:[+\\-*/%&|^]?=|\\+\\+|--|\\.store|\\.exchange|\\.fetch_add|\\.fetch_sub|\\.fetch_and|\\.fetch_or)",
                    Pattern.MULTILINE
            ));
            prefixIncPatterns.put(field.name, Pattern.compile("(?:\\+\\+|--)\\s*" + quotedField + "\\b"));
            readPatterns.put(field.name, Pattern.compile("\\b" + quotedField + "\\b"));
        }

        List<ExtractedMethod> methods = extractMethods(structBlockText);
        for (ExtractedMethod method : methods) {
            for (CppStructParser.Field field : info.fields) {
                FieldProfile fp = profile.fields.get(field.name);
                if (fp == null) continue;

                String body = method.body;
                Pattern wp = writePatterns.get(field.name);
                Pattern pip = prefixIncPatterns.get(field.name);
                Pattern rp = readPatterns.get(field.name);

                boolean isWrite = (wp != null && wp.matcher(body).find()) ||
                        (pip != null && pip.matcher(body).find());
                boolean isMentioned = (rp != null && rp.matcher(body).find());

                if (isWrite) {
                    fp.writerMethods.add(method.name);
                } else if (isMentioned) {
                    fp.readerMethods.add(method.name);
                }
            }
        }

        int writeFieldCount = 0;
        int readFieldCount = 0;
        for (FieldProfile fp : profile.fields.values()) {
            boolean hasWrites = !fp.writerMethods.isEmpty();
            boolean hasReads = !fp.readerMethods.isEmpty();

            if (hasWrites) writeFieldCount++;
            if (hasReads) readFieldCount++;

            if (hasWrites && hasReads) {
                fp.accessType = AccessType.READ_WRITE;
            } else if (hasWrites) {
                fp.accessType = AccessType.WRITE_ONLY;
            } else if (hasReads) {
                fp.accessType = AccessType.READ_ONLY;
            }
        }

        if (profile.concurrencyModel != ConcurrencyModel.CONTROL_PLANE) {
            if (writeFieldCount >= 2 || (writeFieldCount >= 1 && readFieldCount >= 1)) {
                profile.concurrencyModel = ConcurrencyModel.QUEUE_OR_RINGBUFFER;
                profile.hasAtomicsOrLocks = true;
            }
        }

        if (profile.hasAtomicsOrLocks && profile.concurrencyModel != ConcurrencyModel.QUEUE_OR_RINGBUFFER) {
            profile.concurrencyModel = ConcurrencyModel.EXPLICIT_CONCURRENT;
        }

        return profile;
    }

    @NotNull
    private static List<ExtractedMethod> extractMethods(@NotNull String structBlock) {
        List<ExtractedMethod> methods = new ArrayList<>();
        String clean = CppStructParser.clearCommentsAndStrings(structBlock);
        int len = clean.length();
        Matcher m = METHOD_START_PATTERN.matcher(clean);

        while (m.find()) {
            String methodName = m.group(1);
            if (methodName.equals("if") || methodName.equals("while") || methodName.equals("for")
                    || methodName.equals("switch") || methodName.equals("catch") || methodName.equals("alignas")) {
                continue;
            }

            int openParen = m.end() - 1;
            int closeParen = findMatchingPair(clean, openParen, '(', ')');
            if (closeParen == -1) continue;

            int openBrace = -1;
            for (int i = closeParen + 1; i < len; i++) {
                char c = clean.charAt(i);
                if (c == ';') break;
                if (c == '{') {
                    openBrace = i;
                    break;
                }
            }
            if (openBrace == -1) continue;

            int closeBrace = findMatchingPair(clean, openBrace, '{', '}');
            if (closeBrace != -1) {
                String body = structBlock.substring(openBrace + 1, closeBrace);
                methods.add(new ExtractedMethod(methodName, body));
            }
        }
        return methods;
    }

    private static int findMatchingPair(String text, int startIdx, char openChar, char closeChar) {
        int depth = 0;
        for (int i = startIdx; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == openChar) depth++;
            else if (c == closeChar) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }
}