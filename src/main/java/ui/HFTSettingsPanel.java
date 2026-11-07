package ui;

import analysis.PlatformDetector;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import dev.hotpath.HotPathHighlighter;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ItemEvent;
import java.util.Objects;

public class HFTSettingsPanel {

    private final JPanel rootPanel;

    // Графика карты памяти
    private final JBCheckBox chkShowMemoryMapBar;
    private final JBCheckBox chkHoverTooltips;
    private final ComboBox<String> cmbMaxBarRows;
    private final ComboBox<HFTSettings.ColorScheme> cmbColorScheme;

    // Строчные бейджи
    private final JBCheckBox chkShowFieldOffsets;
    private final JBCheckBox chkShowPaddingBadges;
    private final JBCheckBox chkShowSplitBadges;
    private final JBCheckBox chkShowFalseSharingBadges;
    private final JBCheckBox chkShowHotColdBadges;

    // Аналитические модули
    private final JBCheckBox chkPadding;
    private final JBCheckBox chkCacheLine;
    private final JBCheckBox chkFalseSharing;
    private final JBCheckBox chkHotCold;
    private final JBCheckBox chkZeroAlloc;
    private final JBCheckBox chkGlobalAtomics;
    private final JBCheckBox chkAutoEnableCpp;

    // Архитектура
    private final ComboBox<Integer> cmbCacheLineSize;
    private final ComboBox<PlatformDetector.TargetPlatform> cmbTargetPlatform;

    private boolean isUpdating = false;
    private Runnable changeListener = null;

    public HFTSettingsPanel() {
        // Карта памяти
        chkShowMemoryMapBar = new JBCheckBox("Display 64B cache line memory grid (above struct)");
        chkHoverTooltips = new JBCheckBox("Interactive hover byte inspector tooltip");

        String[] rowLimitOptions = new String[]{"4 lines (256B)", "8 lines (512B)", "16 lines (1KB)", "Unlimited"};
        cmbMaxBarRows = new ComboBox<>(rowLimitOptions);
        cmbColorScheme = new ComboBox<>(HFTSettings.ColorScheme.values());

        // Строчные бейджи
        chkShowFieldOffsets = new JBCheckBox("Field offsets & sizes [off 0, 8B]");
        chkShowPaddingBadges = new JBCheckBox("Padding gap indicators [+7B pad]");
        chkShowSplitBadges = new JBCheckBox("Cache line boundary split badges [split]");
        chkShowFalseSharingBadges = new JBCheckBox("MESI contention badges [ping-pong / invalidation]");
        chkShowHotColdBadges = new JBCheckBox("Hot / Cold data access tags [cold]");

        // Модули
        chkPadding = new JBCheckBox("Padding waste analysis & visual markers");
        chkCacheLine = new JBCheckBox("Cache line boundaries calculation");
        chkFalseSharing = new JBCheckBox("False sharing & lock contention analysis");
        chkHotCold = new JBCheckBox("Hot/Cold execution path separation");
        chkZeroAlloc = new JBCheckBox("Zero-Alloc linter (detect heap allocations on hot path)");
        chkGlobalAtomics = new JBCheckBox("Global atomics audit (detect contiguous static variables)");
        chkAutoEnableCpp = new JBCheckBox("Automatically analyze C/C++ files on open");

        Integer[] cacheLineOptions = new Integer[]{32, 64, 128, 256};
        cmbCacheLineSize = new ComboBox<>(cacheLineOptions);
        cmbTargetPlatform = new ComboBox<>(PlatformDetector.TargetPlatform.values());

        // Пресеты
        JPanel presetBar = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        JButton btnFullHft = new JButton("All Features ON");
        JButton btnLowNoise = new JButton("Low Noise (Critical Only)");
        JButton btnDisableAll = new JButton("Disable All");

        btnFullHft.setFocusable(false);
        btnLowNoise.setFocusable(false);
        btnDisableAll.setFocusable(false);

        btnFullHft.addActionListener(e -> applyPreset(Preset.FULL));
        btnLowNoise.addActionListener(e -> applyPreset(Preset.LOW_NOISE));
        btnDisableAll.addActionListener(e -> applyPreset(Preset.NONE));

        presetBar.add(btnFullHft);
        presetBar.add(btnLowNoise);
        presetBar.add(btnDisableAll);

        JButton btnReanalyze = new JButton("Re-Analyze Active Editor");
        btnReanalyze.setFocusable(false);
        btnReanalyze.addActionListener(e -> triggerReanalysis());

        rootPanel = FormBuilder.createFormBuilder()
                .addComponent(new JBLabel("<html><b>Quick Presets</b></html>"))
                .addComponent(presetBar)
                .addVerticalGap(JBUI.scale(6))
                .addComponent(new JSeparator())
                .addVerticalGap(JBUI.scale(6))

                .addComponent(new JBLabel("<html><b>Memory Map Visualization (Bar Inlay)</b></html>"))
                .addComponent(chkShowMemoryMapBar)
                .addComponent(chkHoverTooltips)
                .addLabeledComponent(new JBLabel("Max visual grid lines:"), cmbMaxBarRows, 1, false)
                .addLabeledComponent(new JBLabel("Color palette:"), cmbColorScheme, 1, false)
                .addVerticalGap(JBUI.scale(6))
                .addComponent(new JSeparator())
                .addVerticalGap(JBUI.scale(6))

                .addComponent(new JBLabel("<html><b>Inline Code Hints (After Line End)</b></html>"))
                .addComponent(chkShowFieldOffsets)
                .addComponent(chkShowPaddingBadges)
                .addComponent(chkShowSplitBadges)
                .addComponent(chkShowFalseSharingBadges)
                .addComponent(chkShowHotColdBadges)
                .addVerticalGap(JBUI.scale(6))
                .addComponent(new JSeparator())
                .addVerticalGap(JBUI.scale(6))

                .addComponent(new JBLabel("<html><b>Analysis Engines & Linters</b></html>"))
                .addComponent(chkPadding)
                .addComponent(chkCacheLine)
                .addComponent(chkFalseSharing)
                .addComponent(chkHotCold)
                .addComponent(chkZeroAlloc)
                .addComponent(chkGlobalAtomics)
                .addVerticalGap(JBUI.scale(6))
                .addComponent(new JSeparator())
                .addVerticalGap(JBUI.scale(6))

                .addComponent(new JBLabel("<html><b>Target Architecture & Compiler</b></html>"))
                .addLabeledComponent(new JBLabel("Cache Line Size:"), cmbCacheLineSize, 1, false)
                .addLabeledComponent(new JBLabel("Target ABI:"), cmbTargetPlatform, 1, false)
                .addComponent(chkAutoEnableCpp)
                .addVerticalGap(JBUI.scale(8))
                .addComponent(btnReanalyze)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();

        rootPanel.setBorder(JBUI.Borders.empty(10));
        hookListeners();
    }

    private void triggerReanalysis() {
        Project[] projects = ProjectManager.getInstance().getOpenProjects();
        for (Project p : projects) {
            Editor ed = FileEditorManager.getInstance(p).getSelectedTextEditor();
            if (ed != null) {
                HotPathHighlighter.update(ed);
            }
        }
        HotPathHighlighter.refreshAllEditors();
    }

    private enum Preset {
        FULL,
        LOW_NOISE,
        NONE
    }

    private void applyPreset(Preset preset) {
        isUpdating = true;
        try {
            if (preset == Preset.FULL) {
                chkShowMemoryMapBar.setSelected(true);
                chkHoverTooltips.setSelected(true);
                chkShowFieldOffsets.setSelected(true);
                chkShowPaddingBadges.setSelected(true);
                chkShowSplitBadges.setSelected(true);
                chkShowFalseSharingBadges.setSelected(true);
                chkShowHotColdBadges.setSelected(true);

                chkPadding.setSelected(true);
                chkCacheLine.setSelected(true);
                chkFalseSharing.setSelected(true);
                chkHotCold.setSelected(true);
                chkZeroAlloc.setSelected(true);
                chkGlobalAtomics.setSelected(true);
            } else if (preset == Preset.LOW_NOISE) {
                // Только критические сбои задержки (False sharing, Split, Heap violations)
                chkShowMemoryMapBar.setSelected(false);
                chkHoverTooltips.setSelected(false);
                chkShowFieldOffsets.setSelected(false);
                chkShowPaddingBadges.setSelected(false);
                chkShowSplitBadges.setSelected(true);
                chkShowFalseSharingBadges.setSelected(true);
                chkShowHotColdBadges.setSelected(false);

                chkPadding.setSelected(false);
                chkCacheLine.setSelected(true);
                chkFalseSharing.setSelected(true);
                chkHotCold.setSelected(false);
                chkZeroAlloc.setSelected(true);
                chkGlobalAtomics.setSelected(true);
            } else {
                chkShowMemoryMapBar.setSelected(false);
                chkHoverTooltips.setSelected(false);
                chkShowFieldOffsets.setSelected(false);
                chkShowPaddingBadges.setSelected(false);
                chkShowSplitBadges.setSelected(false);
                chkShowFalseSharingBadges.setSelected(false);
                chkShowHotColdBadges.setSelected(false);

                chkPadding.setSelected(false);
                chkCacheLine.setSelected(false);
                chkFalseSharing.setSelected(false);
                chkHotCold.setSelected(false);
                chkZeroAlloc.setSelected(false);
                chkGlobalAtomics.setSelected(false);
            }
        } finally {
            isUpdating = false;
        }
        if (changeListener != null) {
            changeListener.run();
        }
    }

    private void hookListeners() {
        ItemEventCallback cb = e -> {
            if (!isUpdating && changeListener != null) {
                changeListener.run();
            }
        };

        chkShowMemoryMapBar.addItemListener(cb::onChange);
        chkHoverTooltips.addItemListener(cb::onChange);

        chkShowFieldOffsets.addItemListener(cb::onChange);
        chkShowPaddingBadges.addItemListener(cb::onChange);
        chkShowSplitBadges.addItemListener(cb::onChange);
        chkShowFalseSharingBadges.addItemListener(cb::onChange);
        chkShowHotColdBadges.addItemListener(cb::onChange);

        chkPadding.addItemListener(cb::onChange);
        chkCacheLine.addItemListener(cb::onChange);
        chkFalseSharing.addItemListener(cb::onChange);
        chkHotCold.addItemListener(cb::onChange);
        chkZeroAlloc.addItemListener(cb::onChange);
        chkGlobalAtomics.addItemListener(cb::onChange);
        chkAutoEnableCpp.addItemListener(cb::onChange);

        cmbMaxBarRows.addActionListener(e -> {
            if (!isUpdating && changeListener != null) changeListener.run();
        });
        cmbCacheLineSize.addActionListener(e -> {
            if (!isUpdating && changeListener != null) changeListener.run();
        });
        cmbTargetPlatform.addActionListener(e -> {
            if (!isUpdating && changeListener != null) changeListener.run();
        });
        cmbColorScheme.addActionListener(e -> {
            if (!isUpdating && changeListener != null) changeListener.run();
        });
    }

    @FunctionalInterface
    private interface ItemEventCallback {
        void onChange(ItemEvent e);
    }

    public void setOnChangeListener(Runnable listener) {
        this.changeListener = listener;
    }

    @NotNull
    public JComponent getRootComponent() {
        return rootPanel;
    }

    public void loadFrom(@NotNull HFTSettings.State state) {
        isUpdating = true;
        try {
            chkShowMemoryMapBar.setSelected(state.showMemoryMapBar);
            chkHoverTooltips.setSelected(state.hoverTooltips);
            setRowLimitCombo(state.maxBarRows);

            chkShowFieldOffsets.setSelected(state.showFieldOffsets);
            chkShowPaddingBadges.setSelected(state.showPaddingBadges);
            chkShowSplitBadges.setSelected(state.showSplitBadges);
            chkShowFalseSharingBadges.setSelected(state.showFalseSharingBadges);
            chkShowHotColdBadges.setSelected(state.showHotColdBadges);

            chkPadding.setSelected(state.paddingVisualization);
            chkCacheLine.setSelected(state.cacheLineBoundaries);
            chkFalseSharing.setSelected(state.falseSharingDetection);
            chkHotCold.setSelected(state.hotColdSeparation);
            chkZeroAlloc.setSelected(state.zeroAllocLinter);
            chkGlobalAtomics.setSelected(state.globalAtomicsAudit);
            chkAutoEnableCpp.setSelected(state.autoEnableCpp);

            cmbCacheLineSize.setSelectedItem(state.cacheLineSize);
            cmbColorScheme.setSelectedItem(state.colorScheme);
            cmbTargetPlatform.setSelectedItem(state.targetPlatform);
        } finally {
            isUpdating = false;
        }
    }

    public void applyTo(@NotNull HFTSettings.State state) {
        state.showMemoryMapBar = chkShowMemoryMapBar.isSelected();
        state.hoverTooltips = chkHoverTooltips.isSelected();
        state.maxBarRows = parseRowLimitCombo();

        state.showFieldOffsets = chkShowFieldOffsets.isSelected();
        state.showPaddingBadges = chkShowPaddingBadges.isSelected();
        state.showSplitBadges = chkShowSplitBadges.isSelected();
        state.showFalseSharingBadges = chkShowFalseSharingBadges.isSelected();
        state.showHotColdBadges = chkShowHotColdBadges.isSelected();

        state.paddingVisualization = chkPadding.isSelected();
        state.cacheLineBoundaries = chkCacheLine.isSelected();
        state.falseSharingDetection = chkFalseSharing.isSelected();
        state.hotColdSeparation = chkHotCold.isSelected();
        state.zeroAllocLinter = chkZeroAlloc.isSelected();
        state.globalAtomicsAudit = chkGlobalAtomics.isSelected();
        state.autoEnableCpp = chkAutoEnableCpp.isSelected();

        Object lineSize = cmbCacheLineSize.getSelectedItem();
        if (lineSize instanceof Integer) {
            state.cacheLineSize = (Integer) lineSize;
        }

        Object color = cmbColorScheme.getSelectedItem();
        if (color instanceof HFTSettings.ColorScheme) {
            state.colorScheme = (HFTSettings.ColorScheme) color;
        }

        Object platform = cmbTargetPlatform.getSelectedItem();
        if (platform instanceof PlatformDetector.TargetPlatform) {
            state.targetPlatform = (PlatformDetector.TargetPlatform) platform;
        }
    }

    public boolean isModified(@NotNull HFTSettings.State state) {
        return chkShowMemoryMapBar.isSelected() != state.showMemoryMapBar ||
                chkHoverTooltips.isSelected() != state.hoverTooltips ||
                parseRowLimitCombo() != state.maxBarRows ||
                chkShowFieldOffsets.isSelected() != state.showFieldOffsets ||
                chkShowPaddingBadges.isSelected() != state.showPaddingBadges ||
                chkShowSplitBadges.isSelected() != state.showSplitBadges ||
                chkShowFalseSharingBadges.isSelected() != state.showFalseSharingBadges ||
                chkShowHotColdBadges.isSelected() != state.showHotColdBadges ||
                chkPadding.isSelected() != state.paddingVisualization ||
                chkCacheLine.isSelected() != state.cacheLineBoundaries ||
                chkFalseSharing.isSelected() != state.falseSharingDetection ||
                chkHotCold.isSelected() != state.hotColdSeparation ||
                chkZeroAlloc.isSelected() != state.zeroAllocLinter ||
                chkGlobalAtomics.isSelected() != state.globalAtomicsAudit ||
                chkAutoEnableCpp.isSelected() != state.autoEnableCpp ||
                !Objects.equals(cmbCacheLineSize.getSelectedItem(), state.cacheLineSize) ||
                cmbColorScheme.getSelectedItem() != state.colorScheme ||
                cmbTargetPlatform.getSelectedItem() != state.targetPlatform;
    }

    private void setRowLimitCombo(int maxRows) {
        if (maxRows == 4) cmbMaxBarRows.setSelectedIndex(0);
        else if (maxRows == 8) cmbMaxBarRows.setSelectedIndex(1);
        else if (maxRows == 16) cmbMaxBarRows.setSelectedIndex(2);
        else cmbMaxBarRows.setSelectedIndex(3);
    }

    private int parseRowLimitCombo() {
        int idx = cmbMaxBarRows.getSelectedIndex();
        if (idx == 0) return 4;
        if (idx == 1) return 8;
        if (idx == 2) return 16;
        return 0; // unlimited
    }
}