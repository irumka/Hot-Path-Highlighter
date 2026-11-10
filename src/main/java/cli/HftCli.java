package cli;

import analysis.StoreForwardingDetector;
import analysis.ZeroAllocLinter;
import dev.hotpath.CppStructParser;
import dev.hotpath.FileAnalyzer;
import dev.hotpath.HotPathSettings;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

/**
 * Standalone CI/CD Command Line Tool for HFT latency analysis.
 * Usage:
 *   java -cp plugin.jar cli.HftCli --path=./src --fail-on=false-sharing,split,alloc
 */
public class HftCli {

    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_RED = "\u001B[31m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_BOLD = "\u001B[1m";

    public static void main(String[] args) {
        String targetPath = ".";
        Set<String> failOn = new HashSet<>(Arrays.asList("false-sharing", "split", "alloc"));
        int cacheLineSize = 64;

        for (String arg : args) {
            if (arg.startsWith("--path=")) {
                targetPath = arg.substring(7).trim();
            } else if (arg.startsWith("--fail-on=")) {
                failOn.clear();
                for (String token : arg.substring(10).split(",")) {
                    failOn.add(token.trim().toLowerCase());
                }
            } else if (arg.startsWith("--cache-line=")) {
                try {
                    cacheLineSize = Integer.parseInt(arg.substring(13).trim());
                } catch (NumberFormatException ignored) {}
            }
        }

        HotPathSettings.get().setCacheLine(cacheLineSize);

        System.out.println(ANSI_BOLD + "=======================================================" + ANSI_RESET);
        System.out.println(ANSI_BOLD + "   HFT Memory Layout & Silicon Latency CI/CD Checker   " + ANSI_RESET);
        System.out.println(ANSI_BOLD + "=======================================================" + ANSI_RESET);
        System.out.println("Scanning path: " + targetPath);
        System.out.println("Fail conditions: " + failOn);
        System.out.println("Cache Line Size: " + cacheLineSize + "B\n");

        List<Path> files = findCppFiles(Paths.get(targetPath));
        if (files.isEmpty()) {
            System.out.println(ANSI_YELLOW + "No C++ files (.cpp, .hpp, .h, .cxx) found." + ANSI_RESET);
            System.exit(0);
        }

        int violationsCount = 0;
        int totalStructs = 0;

        for (Path filePath : files) {
            String content;
            try {
                content = new String(Files.readAllBytes(filePath));
            } catch (IOException e) {
                System.err.println("Failed to read: " + filePath + " (" + e.getMessage() + ")");
                continue;
            }

            FileAnalyzer.Result result = FileAnalyzer.analyze(content);
            if (result.structs.isEmpty()) continue;

            for (FileAnalyzer.MarkedStruct m : result.structs) {
                totalStructs++;
                String sName = m.info.name;
                List<String> structErrors = new ArrayList<>();

                // 1. Проверка False Sharing
                if (failOn.contains("false-sharing") && !m.risky.isEmpty()) {
                    for (String desc : m.risky.values()) {
                        structErrors.add("FALSE SHARING: " + desc);
                    }
                }

                // 2. Проверка Cache Line Splits
                if (failOn.contains("split") && m.layout != null && m.layout.fields != null) {
                    for (CppStructParser.Field f : m.layout.fields) {
                        if (f.size > 1 && (f.offset / cacheLineSize != (f.offset + f.size - 1) / cacheLineSize)) {
                            structErrors.add("CACHE LINE SPLIT: field '" + f.name + "' crosses 64B line (off " + f.offset + ".." + (f.offset + f.size - 1) + ")");
                        }
                    }
                }

                // 3. Проверка Zero-Alloc
                if (failOn.contains("alloc")) {
                    String blockText = content.substring(Math.max(0, m.region.bStart()), Math.min(m.region.bEnd(), content.length()));
                    if (ZeroAllocLinter.hasZeroAllocHazards(m.info, blockText)) {
                        structErrors.add("ZERO-ALLOC VIOLATION: heap allocation (new/malloc) detected on critical path");
                    }
                }

                // 4. Проверка STLF
                if (failOn.contains("stlf") && m.layout != null) {
                    List<StoreForwardingDetector.StlfHazard> stlf = StoreForwardingDetector.detect(m.layout);
                    for (StoreForwardingDetector.StlfHazard sh : stlf) {
                        structErrors.add("STLF STALL: " + sh.description);
                    }
                }

                if (!structErrors.isEmpty()) {
                    violationsCount += structErrors.size();
                    System.out.println(ANSI_RED + "[FAIL] " + filePath.getFileName() + " -> struct " + sName + ANSI_RESET);
                    for (String err : structErrors) {
                        System.out.println("       * " + err);
                    }
                }
            }
        }

        System.out.println("\n-------------------------------------------------------");
        System.out.println("Total structs inspected: " + totalStructs);
        if (violationsCount > 0) {
            System.out.println(ANSI_RED + ANSI_BOLD + "CI Check FAILED: " + violationsCount + " latency violations detected!" + ANSI_RESET);
            System.exit(1);
        } else {
            System.out.println(ANSI_GREEN + ANSI_BOLD + "CI Check PASSED: All structs comply with zero-latency SLA." + ANSI_RESET);
            System.exit(0);
        }
    }

    private static List<Path> findCppFiles(Path root) {
        List<Path> result = new ArrayList<>();
        if (!Files.exists(root)) return result;

        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.toString().toLowerCase();
                        return name.endsWith(".cpp") || name.endsWith(".hpp") || name.endsWith(".h") || name.endsWith(".cxx") || name.endsWith(".cc");
                    })
                    .forEach(result::add);
        } catch (IOException ignored) {}
        return result;
    }
}