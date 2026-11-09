package core;

import dev.hotpath.CppStructParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Context-aware concurrency and access-pattern analyzer.
 * Uses robust brace-depth matching to parse method bodies with arbitrary control flow (if/for/while).
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

    private static final Pattern METHOD_SIG_PATTERN = Pattern.compile(
            "(?:[a-zA-Z_0-9:<>&*]+\\s+)+([a-zA-Z_0-9]+)\\s*\\([^)]*\\)\\s*(?:const)?\\s*\\{"
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

        // Итеративно извлекаем методы с корректным балансом фигурных скобок {}
        List<ExtractedMethod> methods = extractMethods(structBlockText);
        for (ExtractedMethod method : methods) {
            for (CppStructParser.Field field : info.fields) {
                FieldProfile fp = profile.fields.get(field.name);
                if (fp == null) continue;

                analyzeFieldUsageInMethod(method.name, method.body, fp);
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
        Matcher matcher = METHOD_SIG_PATTERN.matcher(structBlock);

        while (matcher.find()) {
            String methodName = matcher.group(1);
            int openBracePos = matcher.end() - 1;
            int closeBracePos = findMatchingCloseBrace(structBlock, openBracePos);

            if (closeBracePos > openBracePos) {
                String body = structBlock.substring(openBracePos + 1, closeBracePos);
                methods.add(new ExtractedMethod(methodName, body));
            }
        }
        return methods;
    }

    private static int findMatchingCloseBrace(@NotNull String text, int openPos) {
        int depth = 0;
        for (int i = openPos; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static void analyzeFieldUsageInMethod(String methodName, String body, FieldProfile fp) {
        String quotedField = Pattern.quote(fp.name);

        Pattern writePattern = Pattern.compile(
                "\\b" + quotedField + "\\s*(?:[+\\-*/%&|^]?=|\\+\\+|--|\\.store|\\.exchange|\\.fetch_add|\\.fetch_sub|\\.fetch_and|\\.fetch_or)",
                Pattern.MULTILINE
        );
        Pattern prefixIncPattern = Pattern.compile("(?:\\+\\+|--)\\s*" + quotedField + "\\b");

        boolean isWrite = writePattern.matcher(body).find() || prefixIncPattern.matcher(body).find();
        Pattern readPattern = Pattern.compile("\\b" + quotedField + "\\b");
        boolean isMentioned = readPattern.matcher(body).find();

        if (isWrite) {
            fp.writerMethods.add(methodName);
        } else if (isMentioned) {
            fp.readerMethods.add(methodName);
        }
    }
}