package dev.hotpath;

import analysis.PlatformDetector;
import analysis.TypeResolver;
import ui.HFTSettings;

import java.util.HashMap;
import java.util.Map;

/**
 * Compatibility bridge and layout calculation helper that delegates
 * C++ type resolution to analysis.TypeResolver with live target platform detection.
 */
public class CppTypeResolver {

    public static int[] sizeAndAlign(String type) {
        return resolve(type, null);
    }

    public static int[] sizeAndAlign(String type, HotPathSettings st, HashMap<String, int[]> locals) {
        return resolve(type, locals);
    }

    public static int[] sizeAndAlign(String type, HotPathSettings st, Map<String, int[]> locals) {
        return resolve(type, locals);
    }

    public static int[] resolve(String type) {
        return resolve(type, null);
    }

    public static int[] resolve(String type, Map<String, int[]> customTypes) {
        if (type == null || type.trim().isEmpty()) {
            return new int[]{8, 8, 0};
        }
        PlatformDetector.TargetPlatform platform = HFTSettings.getInstance().getState().targetPlatform;
        TypeResolver.TypeInfo info = TypeResolver.resolve(type, platform, customTypes);
        return info.toArray();
    }

    public static int roundUp(int pos, int align) {
        if (align <= 1) {
            return pos;
        }
        int rem = pos % align;
        return rem == 0 ? pos : pos + (align - rem);
    }

    public static int size(String type) {
        return resolve(type)[0];
    }

    public static int align(String type) {
        return resolve(type)[1];
    }
}