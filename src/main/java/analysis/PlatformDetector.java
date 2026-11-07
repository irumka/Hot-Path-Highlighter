package analysis;

import org.jetbrains.annotations.NotNull;

public class PlatformDetector {

    public enum TargetPlatform {
        AUTO("Auto (Host OS)"),
        LINUX_GCC("Linux (GCC/Clang, LP64)"),
        WINDOWS_MSVC("Windows (MSVC, LLP64)"),
        MACOS_CLANG("macOS (Apple Clang, LP64)");

        private final String displayName;

        TargetPlatform(String displayName) {
            this.displayName = displayName;
        }

        @NotNull
        public String getDisplayName() {
            return displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    @NotNull
    public static TargetPlatform getEffectivePlatform(TargetPlatform target) {
        if (target == null || target == TargetPlatform.AUTO) {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                return TargetPlatform.WINDOWS_MSVC;
            } else if (os.contains("mac")) {
                return TargetPlatform.MACOS_CLANG;
            } else {
                return TargetPlatform.LINUX_GCC;
            }
        }
        return target;
    }

    public static boolean isWindows(TargetPlatform target) {
        return getEffectivePlatform(target) == TargetPlatform.WINDOWS_MSVC;
    }

    public static boolean isMac(TargetPlatform target) {
        return getEffectivePlatform(target) == TargetPlatform.MACOS_CLANG;
    }

    public static boolean isLinux(TargetPlatform target) {
        return getEffectivePlatform(target) == TargetPlatform.LINUX_GCC;
    }
}