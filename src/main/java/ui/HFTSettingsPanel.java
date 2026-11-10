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

    // Единая кнопка-тумблер Вкл/Выкл
    private final JButton btnToggleAll;

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

    // Кремниевые детекторы
    private final JBCheckBox chkStoreForwarding;
    private final JBCheckBox chkBranchPredictor;
    private final JBCheckBox chkVirtualCalls;
    private final JBCheckBox chkMisalignedAlloc;

    // Архитектура
    private final ComboBox<Integer> cmbCacheLineSize;
    private final ComboBox<PlatformDetector.TargetPlatform> cmbTargetPlatform;

    private boolean isUpdating = false;
    private Runnable changeListener = null;

    public HFTSettingsPanel() {
        chkShowMemoryMapBar = new JBCheckBox("Display 64B cache line memory grid (above struct)");
        chkHoverTooltips = new JBCheckBox("Interactive hover byte inspector tooltip");

        String[] rowLimitOptions = new String[]{"4 lines (256B)", "8 lines (512B)", "16 lines (1KB)", "Unlimited"};
        cmbMaxBarRows = new ComboBox<>(rowLimitOptions);
        cmbColorScheme = new ComboBox<>(HFTSettings.ColorScheme.values());

        chkShowFieldOffsets = new JBCheckBox("Field offsets & sizes [off 0, 8B]");
        chkShowPaddingBadges = new JBCheckBox("Padding gap indicators [+7B pad]");
        chkShowSplitBadges = new JBCheckBox("Cache line boundary split badges [split]");
        chkShowFalseSharingBadges = new JBCheckBox("MESI contention badges [ping-pong / invalidation]");
        chkShowHotColdBadges = new JBCheckBox("Hot / Cold data access tags [cold] / [hot]");

        chkPadding = new JBCheckBox("Padding waste analysis & visual markers");
        chkCacheLine = new JBCheckBox("Cache line boundaries calculation");
        chkFalseSharing = new JBCheckBox("False sharing & lock contention analysis");
        chkHotCold = new JBCheckBox("Hot/Cold execution path separation");
        chkZeroAlloc = new JBCheckBox("Zero-Alloc linter (detect heap allocations on hot path)");
        chkGlobalAtomics = new JBCheckBox("Global atomics audit (detect contiguous static variables)");
        chkAutoEnableCpp = new JBCheckBox("Automatically analyze C/C++ files on open");

        chkStoreForwarding = new JBCheckBox("Store-to-Load Forwarding (STLF) & 4K Aliasing detector");
        chkBranchPredictor = new JBCheckBox("Branch predictor hot path linter ([[unlikely]] hints)");
        chkVirtualCalls = new JBCheckBox("Virtual dispatch / vtable call linter on hot path");
        chkMisalignedAlloc = new JBCheckBox("Misaligned container allocator linter (alignas > 16 in std::vector)");

        Integer[] cacheLineOptions = new Integer[]{32, 64, 128, 256};
        cmbCacheLineSize = new ComboBox<>(cacheLineOptions);
        cmbTargetPlatform = new ComboBox<>(PlatformDetector.TargetPlatform.values());

        // Единая кнопка переключения Вкл/Выкл
        btnToggleAll = new JButton("Disable All");
        btnToggleAll.setFocusable(false);
        btnToggleAll.addActionListener(e -> toggleAllFeatures());

        JButton btnLowNoise = new JButton("Low Noise (Critical Only)");
        btnLowNoise.setFocusable(false);
        btnLowNoise.addActionListener(e -> applyPreset(Preset.LOW_NOISE));

        JPanel presetBar = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        presetBar.add(btnToggleAll);
        presetBar.add(btnLowNoise);

        JButton btnReanalyze = new JButton("Re-Analyze Active Editor");
        btnReanalyze.setFocusable(false);
        btnReanalyze.addActionListener(e -> triggerReanalysis());

        rootPanel = FormBuilder.createFormBuilder()
                .addComponent(new JBLabel("<html><b>Quick Actions & Presets</b></html>"))
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

                .addComponent(new JBLabel("<html><b>Hardware Penalties & Silicon Linters</b></html>"))
                .addComponent(chkPadding)
                .addComponent(chkCacheLine)
                .addComponent(chkFalseSharing)
                .addComponent(chkHotCold)
                .addComponent(chkStoreForwarding)
                .addComponent(chkBranchPredictor)
                .addComponent(chkVirtualCalls)
                .addComponent(chkMisalignedAlloc)
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
        updateToggleAllButtonText();
    }

    private boolean areAnyEnabled() {
        return chkPadding.isSelected() ||
                chkCacheLine.isSelected() ||
                chkFalseSharing.isSelected() ||
                chkHotCold.isSelected() ||
                chkStoreForwarding.isSelected() ||
                chkBranchPredictor.isSelected() ||
                chkVirtualCalls.isSelected() ||
                chkMisalignedAlloc.isSelected() ||
                chkZeroAlloc.isSelected() ||
                chkGlobalAtomics.isSelected() ||
                chkShowMemoryMapBar.isSelected();
    }

    private void updateToggleAllButtonText() {
        if (btnToggleAll != null) {
            btnToggleAll.setText(areAnyEnabled() ? "Disable All" : "Enable All");
        }
    }

    private void toggleAllFeatures() {
        boolean enable = !areAnyEnabled();
        isUpdating = true;
        try {
            chkShowMemoryMapBar.setSelected(enable);
            chkHoverTooltips.setSelected(enable);
            chkShowFieldOffsets.setSelected(enable);
            chkShowPaddingBadges.setSelected(enable);
            chkShowSplitBadges.setSelected(enable);
            chkShowFalseSharingBadges.setSelected(enable);
            chkShowHotColdBadges.setSelected(enable);

            chkPadding.setSelected(enable);
            chkCacheLine.setSelected(enable);
            chkFalseSharing.setSelected(enable);
            chkHotCold.setSelected(enable);
            chkStoreForwarding.setSelected(enable);
            chkBranchPredictor.setSelected(enable);
            chkVirtualCalls.setSelected(enable);
            chkMisalignedAlloc.setSelected(enable);
            chkZeroAlloc.setSelected(enable);
            chkGlobalAtomics.setSelected(enable);
        } finally {
            isUpdating = false;
        }

        updateToggleAllButtonText();
        if (changeListener != null) {
            changeListener.run();
        }
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
        LOW_NOISE
    }

    private void applyPreset(Preset preset) {
        isUpdating = true;
        try {
            if (preset == Preset.LOW_NOISE) {
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
                chkStoreForwarding.setSelected(true);
                chkBranchPredictor.setSelected(false);
                chkVirtualCalls.setSelected(true);
                chkMisalignedAlloc.setSelected(true);
                chkZeroAlloc.setSelected(true);
                chkGlobalAtomics.setSelected(true);
            }
        } finally {
            isUpdating = false;
        }

        updateToggleAllButtonText();
        if (changeListener != null) {
            changeListener.run();
        }
    }

    private void hookListeners() {
        ItemEventCallback cb = e -> {
            if (!isUpdating) {
                updateToggleAllButtonText();
                if (changeListener != null) {
                    changeListener.run();
                }
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
        chkStoreForwarding.addItemListener(cb::onChange);
        chkBranchPredictor.addItemListener(cb::onChange);
        chkVirtualCalls.addItemListener(cb::onChange);
        chkMisalignedAlloc.addItemListener(cb::onChange);
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
            chkStoreForwarding.setSelected(state.storeForwardingDetection);
            chkBranchPredictor.setSelected(state.branchPredictorLinter);
            chkVirtualCalls.setSelected(state.virtualCallLinter);
            chkMisalignedAlloc.setSelected(state.misalignedAllocatorDetection);
            chkZeroAlloc.setSelected(state.zeroAllocLinter);
            chkGlobalAtomics.setSelected(state.globalAtomicsAudit);
            chkAutoEnableCpp.setSelected(state.autoEnableCpp);

            cmbCacheLineSize.setSelectedItem(state.cacheLineSize);
            cmbColorScheme.setSelectedItem(state.colorScheme);
            cmbTargetPlatform.setSelectedItem(state.targetPlatform);
        } finally {
            isUpdating = false;
        }
        updateToggleAllButtonText();
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
        state.storeForwardingDetection = chkStoreForwarding.isSelected();
        state.branchPredictorLinter = chkBranchPredictor.isSelected();
        state.virtualCallLinter = chkVirtualCalls.isSelected();
        state.misalignedAllocatorDetection = chkMisalignedAlloc.isSelected();
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
                chkStoreForwarding.isSelected() != state.storeForwardingDetection ||
                chkBranchPredictor.isSelected() != state.branchPredictorLinter ||
                chkVirtualCalls.isSelected() != state.virtualCallLinter ||
                chkMisalignedAlloc.isSelected() != state.misalignedAllocatorDetection ||
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
        return 0;
    }
}