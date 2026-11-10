package core;

import dev.hotpath.CppStructParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Context-aware concurrency and access-pattern analyzer.
 * Strictly separates single-threaded structures from concurrent hot paths.
 * Prevents False Sharing false positives on single-threaded code.
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
        public final String explicitThreadTag;
        public AccessType accessType = AccessType.NONE;
        public final Set<String> writerMethods = new HashSet<>();
        public final Set<String> readerMethods = new HashSet<>();

        public FieldProfile(String name, boolean isAtomic, boolean isMutex, boolean isArray, int arraySize, String explicitThreadTag) {
            this.name = name;
            this.isAtomic = isAtomic;
            this.isMutex = isMutex;
            this.isArray = isArray;
            this.arraySize = Math.max(1, arraySize);
            this.explicitThreadTag = explicitThreadTag != null ? explicitThreadTag.toLowerCase() : null;
        }

        public boolean isProducer() {
            if (explicitThreadTag != null) {
                return explicitThreadTag.contains("producer") || explicitThreadTag.contains("write");
            }
            return accessType == AccessType.WRITE_ONLY || (!writerMethods.isEmpty() && readerMethods.isEmpty());
        }

        public boolean isConsumer() {
            if (explicitThreadTag != null) {
                return explicitThreadTag.contains("consumer") || explicitThreadTag.contains("read");
            }
            return accessType == AccessType.READ_ONLY || (!readerMethods.isEmpty() && writerMethods.isEmpty());
        }
    }

    public static class StructProfile {
        public final String name;
        public ConcurrencyModel concurrencyModel;
        public final Map<String, FieldProfile> fields = new HashMap<>();
        public boolean hasAtomicsOrLocks = false;
        public boolean hasThreadAnnotations = false;

        public StructProfile(String name, ConcurrencyModel concurrencyModel) {
            this.name = name;
            this.concurrencyModel = concurrencyModel;
        }

        @Nullable
        public FieldProfile getFieldProfile(String fieldName) {
            return fields.get(fieldName);
        }

        /**
         * Структура считается многопоточной ТОЛЬКО при наличии явных признаков конкурентности.
         * Однопоточный код гарантированно возвращает false.
         */
        public boolean isConcurrent() {
            if (concurrencyModel == ConcurrencyModel.CONTROL_PLANE) {
                return false;
            }
            return concurrencyModel == ConcurrencyModel.EXPLICIT_CONCURRENT ||
                    concurrencyModel == ConcurrencyModel.QUEUE_OR_RINGBUFFER ||
                    hasAtomicsOrLocks ||
                    hasThreadAnnotations;
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

    // Имена многопоточных контейнеров
    private static final Pattern QUEUE_NAME_PATTERN = Pattern.compile(
            ".*(queue|ringbuffer|ring_buffer|spsc|mpmc|spmc|mpsc|channel|disruptor|mailbox|worker|pool|pingpong|ping_pong|invalidation).*",
            Pattern.CASE_INSENSITIVE
    );

    // Имена методов с явной многопоточной семантикой (воркеры, писатели/читатели)
    private static final Pattern CONCURRENT_METHOD_PATTERN = Pattern.compile(
            "^(worker|thread|producer|consumer|produce|consume|enqueue|dequeue|on_thread).*",
            Pattern.CASE_INSENSITIVE
    );

    // Классы управления и оркестраторы (Control Plane)
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

        // 1. Сканирование полей на атомики, блокировки и потоковые теги (@threads)
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

            if (field.thread != null && !field.thread.isEmpty()) {
                profile.hasThreadAnnotations = true;
            }

            profile.fields.put(field.name, new FieldProfile(field.name, isAtomic, isMutex, isArray, arrayCount, field.thread));
        }

        // 2. Извлечение методов
        List<ExtractedMethod> methods = extractMethods(structBlockText);
        boolean hasExplicitConcurrentMethods = false;

        for (ExtractedMethod method : methods) {
            if (CONCURRENT_METHOD_PATTERN.matcher(method.name).matches()) {
                hasExplicitConcurrentMethods = true;
            }

            for (CppStructParser.Field field : info.fields) {
                FieldProfile fp = profile.fields.get(field.name);
                if (fp == null) continue;

                analyzeFieldUsageInMethod(method.name, method.body, fp);
            }
        }

        // 3. Вычисление типов доступа
        for (FieldProfile fp : profile.fields.values()) {
            boolean hasWrites = !fp.writerMethods.isEmpty();
            boolean hasReads = !fp.readerMethods.isEmpty();

            if (hasWrites && hasReads) {
                fp.accessType = AccessType.READ_WRITE;
            } else if (hasWrites) {
                fp.accessType = AccessType.WRITE_ONLY;
            } else if (hasReads) {
                fp.accessType = AccessType.READ_ONLY;
            }
        }

        // 4. Повышение статуса до многопоточного ТОЛЬКО если есть явные маркеры
        if (profile.concurrencyModel != ConcurrencyModel.CONTROL_PLANE) {
            if (profile.hasAtomicsOrLocks || profile.hasThreadAnnotations) {
                profile.concurrencyModel = ConcurrencyModel.EXPLICIT_CONCURRENT;
            } else if (hasExplicitConcurrentMethods && profile.concurrencyModel == ConcurrencyModel.QUEUE_OR_RINGBUFFER) {
                profile.hasAtomicsOrLocks = true;
            } else if (hasExplicitConcurrentMethods) {
                // Если методы явно названы worker_a / produce / consume
                profile.concurrencyModel = ConcurrencyModel.QUEUE_OR_RINGBUFFER;
                profile.hasAtomicsOrLocks = true;
            }
            // Обычные геттеры/сеттеры (get/set) в обычных классах БОЛЬШЕ НЕ ДЕЛАЮТ класс многопоточным!
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