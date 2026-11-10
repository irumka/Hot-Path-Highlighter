package dev.hotpath;

import java.awt.Color;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Configuration settings for the Hot Path Highlighter.
 * Thread-safe concurrent custom sizes and ABI parameters.
 */
public class HotPathSettings {
    private static final HotPathSettings INSTANCE = new HotPathSettings();

    public int pointerSize = 8;
    public int longSize = 8;
    public int longDoubleSize = 16;
    public int longDoubleAlign = 16;
    public int wcharSize = 4;
    public int maxAlign = 16;
    public int stringSize = 32;
    public int vectorSize = 24;

    private boolean falseSharing = true;
    private int cacheLineBytes = 64;
    private boolean heuristics = true;
    private boolean hotAuto = true;
    private int palette = 0;

    private final Map<String, int[]> customSizes = new ConcurrentHashMap<>();

    private HotPathSettings() {
    }

    public static HotPathSettings get() {
        return INSTANCE;
    }

    public int pointerSize() {
        return pointerSize;
    }

    public int maxAlign() {
        return maxAlign;
    }

    public int paletteMode() {
        return palette;
    }

    public Color cacheLineColor() {
        return new Color(140, 140, 140, 90);
    }

    public Color paddingColor() {
        return new Color(230, 70, 70, 160);
    }

    public Color falseShareColor() {
        return new Color(255, 90, 60, 200);
    }

    public boolean falseSharingOn() {
        return falseSharing;
    }

    public void setFalseSharingOn(boolean on) {
        falseSharing = on;
    }

    public int cacheLine() {
        return cacheLineBytes;
    }

    public void setCacheLine(int bytes) {
        cacheLineBytes = Math.max(16, bytes);
    }

    public boolean heuristicsOn() {
        return heuristics;
    }

    public void setHeuristicsOn(boolean on) {
        heuristics = on;
    }

    public boolean hotAutoOn() {
        return hotAuto;
    }

    public void setHotAutoOn(boolean on) {
        hotAuto = on;
    }

    public int[] customSize(String type) {
        return customSizes.get(type);
    }

    public void setCustomSize(String type, int size, int align) {
        customSizes.put(type, new int[]{size, align, 0});
    }
}