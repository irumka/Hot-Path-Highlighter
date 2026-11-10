package analysis;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves C++ type sizes, alignments and atomic traits across ABIs.
 * Thread-safe with computeIfAbsent, correct regexes, and recursive std::array support.
 */
public class TypeResolver {

    public static class TypeInfo {
        public final int size;
        public final int align;
        public final boolean isAtomic;

        public TypeInfo(int size, int align) {
            this(size, align, false);
        }

        public TypeInfo(int size, int align, boolean isAtomic) {
            this.size = size;
            this.align = align;
            this.isAtomic = isAtomic;
        }

        public int[] toArray() {
            return new int[]{size, align, isAtomic ? 1 : 0};
        }
    }

    private static final Map<String, TypeInfo> CACHE = new ConcurrentHashMap<>();

    private static final Pattern CONST_PATTERN = Pattern.compile("\\bconst\\b");
    private static final Pattern VOLATILE_PATTERN = Pattern.compile("\\bvolatile\\b");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private static final Pattern ARRAY_PATTERN = Pattern.compile("^(.*?)\\[(\\d+)\\]\\s*$");
    private static final Pattern STD_ARRAY_PATTERN = Pattern.compile("^(?:std::)?array<\\s*(.+)\\s*,\\s*(\\d+)\\s*>\\s*$");
    private static final Pattern STD_OPTIONAL_PATTERN = Pattern.compile("^(?:std::)?optional<\\s*(.+)\\s*>\\s*$");
    private static final Pattern ATOMIC_PATTERN = Pattern.compile("^(?:std::)?atomic<([^>]+)>$");

    public static void clearCache() {
        CACHE.clear();
    }

    @NotNull
    public static TypeInfo resolve(@NotNull String rawType,
                                   @Nullable PlatformDetector.TargetPlatform platform,
                                   @Nullable Map<String, int[]> customTypes) {
        String type = normalizeType(rawType);
        PlatformDetector.TargetPlatform effPlatform = PlatformDetector.getEffectivePlatform(platform);

        if (customTypes != null && customTypes.containsKey(type)) {
            int[] sa = customTypes.get(type);
            return new TypeInfo(sa[0], sa.length > 1 ? sa[1] : sa[0], sa.length > 2 && sa[2] == 1);
        }

        String cacheKey = effPlatform.name() + ":" + type;
        // Fix 3.3: computeIfAbsent eliminates thundering herd redundancy
        return CACHE.computeIfAbsent(cacheKey, k -> resolveDirect(type, effPlatform, customTypes));
    }

    private static boolean isPowerOfTwo(int n) {
        return n > 0 && (n & (n - 1)) == 0;
    }

    private static int roundUp(int pos, int align) {
        if (align <= 1) return pos;
        int rem = pos % align;
        return rem == 0 ? pos : pos + (align - rem);
    }

    private static TypeInfo resolveDirect(String type,
                                          PlatformDetector.TargetPlatform effPlatform,
                                          Map<String, int[]> customTypes) {
        boolean isWin = PlatformDetector.isWindows(effPlatform);
        boolean isMac = PlatformDetector.isMac(effPlatform);
        int ptrSize = 8;

        if (type.endsWith("*") || type.endsWith("&")) {
            return new TypeInfo(ptrSize, ptrSize);
        }

        Matcher atomicMatcher = ATOMIC_PATTERN.matcher(type);
        if (atomicMatcher.find()) {
            String innerType = atomicMatcher.group(1).trim();
            TypeInfo inner = resolve(innerType, effPlatform, customTypes);
            int atomicAlign = inner.align;
            if (isPowerOfTwo(inner.size) && inner.size <= 16) {
                atomicAlign = Math.max(atomicAlign, inner.size);
            }
            return new TypeInfo(inner.size, atomicAlign, true);
        }

        Matcher stdArrayMatcher = STD_ARRAY_PATTERN.matcher(type);
        if (stdArrayMatcher.find()) {
            String elemType = stdArrayMatcher.group(1).trim();
            int count = 1;
            try {
                count = Integer.parseInt(stdArrayMatcher.group(2).trim());
            } catch (NumberFormatException ignored) {}

            TypeInfo elemInfo = resolve(elemType, effPlatform, customTypes);
            return new TypeInfo(elemInfo.size * count, elemInfo.align);
        }

        Matcher optMatcher = STD_OPTIONAL_PATTERN.matcher(type);
        if (optMatcher.find()) {
            String innerType = optMatcher.group(1).trim();
            TypeInfo inner = resolve(innerType, effPlatform, customTypes);
            int payloadSize = inner.size + 1;
            int totalSize = roundUp(payloadSize, inner.align);
            return new TypeInfo(totalSize, inner.align);
        }

        // Fix 1.3: ARRAY_PATTERN with literal brackets \\[ and \\]
        Matcher arrayMatcher = ARRAY_PATTERN.matcher(type);
        if (arrayMatcher.find()) {
            String elemType = arrayMatcher.group(1).trim();
            int count = Integer.parseInt(arrayMatcher.group(2));
            TypeInfo elem = resolve(elemType, effPlatform, customTypes);
            return new TypeInfo(elem.size * count, elem.align);
        }

        switch (type) {
            case "bool":
            case "char":
            case "signed char":
            case "unsigned char":
            case "int8_t":
            case "uint8_t":
            case "std::byte":
                return new TypeInfo(1, 1);

            case "atomic_bool":
            case "std::atomic_bool":
            case "atomic_flag":
            case "std::atomic_flag":
                return new TypeInfo(1, 1, true);

            case "short":
            case "short int":
            case "signed short":
            case "unsigned short":
            case "int16_t":
            case "uint16_t":
            case "char16_t":
                return new TypeInfo(2, 2);

            case "atomic_short":
            case "std::atomic_short":
                return new TypeInfo(2, 2, true);

            case "wchar_t":
                return isWin ? new TypeInfo(2, 2) : new TypeInfo(4, 4);

            case "int":
            case "signed int":
            case "unsigned int":
            case "unsigned":
            case "int32_t":
            case "uint32_t":
            case "float":
            case "char32_t":
                return new TypeInfo(4, 4);

            case "atomic_int":
            case "std::atomic_int":
            case "atomic_uint":
            case "std::atomic_uint":
                return new TypeInfo(4, 4, true);

            case "long":
            case "long int":
            case "signed long":
            case "signed long int":
            case "unsigned long":
            case "unsigned long int":
                return isWin ? new TypeInfo(4, 4) : new TypeInfo(8, 8);

            case "atomic_long":
            case "std::atomic_long":
            case "atomic_ulong":
            case "std::atomic_ulong":
                return isWin ? new TypeInfo(4, 4, true) : new TypeInfo(8, 8, true);

            case "long long":
            case "long long int":
            case "signed long long":
            case "unsigned long long":
            case "int64_t":
            case "uint64_t":
            case "double":
            case "size_t":
            case "ssize_t":
            case "uintptr_t":
            case "intptr_t":
            case "ptrdiff_t":
            case "time_t":
                return new TypeInfo(8, 8);

            case "atomic_llong":
            case "std::atomic_llong":
            case "atomic_int64_t":
            case "std::atomic_int64_t":
            case "atomic_uint64_t":
            case "std::atomic_uint64_t":
                return new TypeInfo(8, 8, true);

            case "long double":
                return isWin ? new TypeInfo(8, 8) : new TypeInfo(16, 16);

            case "int128_t":
            case "uint128_t":
            case "__int128":
            case "unsigned __int128":
                return new TypeInfo(16, 16);

            case "__m128":
            case "__m128i":
            case "__m128d":
                return new TypeInfo(16, 16);

            case "__m256":
            case "__m256i":
            case "__m256d":
                return new TypeInfo(32, 32);

            case "__m512":
            case "__m512i":
            case "__m512d":
                return new TypeInfo(64, 64);

            case "string":
            case "std::string":
                return new TypeInfo(isMac ? 3 * ptrSize : 4 * ptrSize, ptrSize);

            case "string_view":
            case "std::string_view":
                return new TypeInfo(2 * ptrSize, ptrSize);

            case "vector":
            case "std::vector":
                return new TypeInfo(3 * ptrSize, ptrSize);

            case "unique_ptr":
            case "std::unique_ptr":
                return new TypeInfo(ptrSize, ptrSize);

            case "shared_ptr":
            case "std::shared_ptr":
                return new TypeInfo(2 * ptrSize, ptrSize);

            default:
                return new TypeInfo(ptrSize, ptrSize);
        }
    }

    private static String normalizeType(String type) {
        if (type == null) return "";
        // Fix 2.1: Double-escaped word boundaries
        String t = CONST_PATTERN.matcher(type).replaceAll("");
        t = VOLATILE_PATTERN.matcher(t).replaceAll("");
        t = WHITESPACE_PATTERN.matcher(t).replaceAll(" ");
        return t.trim();
    }
}