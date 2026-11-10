package ui;

import analysis.PlatformDetector;
import analysis.TypeResolver;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import dev.hotpath.HotPathHighlighter;
import dev.hotpath.HotPathSettings;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

@Service(Service.Level.APP)
@State(
        name = "HFTOptimizerSettings",
        storages = @Storage("hft_optimizer_settings.xml")
)
public final class HFTSettings implements PersistentStateComponent<HFTSettings.State> {

    public enum ColorScheme {
        CLASSIC("Classic (Red/Yellow/Green)"),
        HIGH_CONTRAST("High Contrast (Accessible)"),
        NEON_HFT("Neon Terminal (Cyberpunk)");

        private final String displayName;

        ColorScheme(@NotNull String displayName) {
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

    public static class State {
        // 1. Графическая карта памяти над структурой
        public boolean showMemoryMapBar = true;
        public boolean hoverTooltips = true;
        public int maxBarRows = 8;

        // 2. Строчные бейджи
        public boolean showFieldOffsets = true;
        public boolean showPaddingBadges = true;
        public boolean showSplitBadges = true;
        public boolean showFalseSharingBadges = true;
        public boolean showHotColdBadges = true;

        // 3. Аналитические модули и линтеры
        public boolean paddingVisualization = true;
        public boolean cacheLineBoundaries = true;
        public boolean falseSharingDetection = true;
        public boolean hotColdSeparation = true;
        public boolean zeroAllocLinter = true;
        public boolean globalAtomicsAudit = true;
        public boolean autoEnableCpp = true;

        // 4. Новые микроархитектурные детекторы (Phase 1 & 2)
        public boolean storeForwardingDetection = true;
        public boolean branchPredictorLinter = true;
        public boolean virtualCallLinter = true;
        public boolean misalignedAllocatorDetection = true;

        // 5. Архитектурные параметры
        public int cacheLineSize = 64;
        @NotNull
        public ColorScheme colorScheme = ColorScheme.CLASSIC;
        @NotNull
        public PlatformDetector.TargetPlatform targetPlatform = PlatformDetector.TargetPlatform.AUTO;

        public State() {
        }

        public State(@NotNull State other) {
            this.showMemoryMapBar = other.showMemoryMapBar;
            this.hoverTooltips = other.hoverTooltips;
            this.maxBarRows = other.maxBarRows;

            this.showFieldOffsets = other.showFieldOffsets;
            this.showPaddingBadges = other.showPaddingBadges;
            this.showSplitBadges = other.showSplitBadges;
            this.showFalseSharingBadges = other.showFalseSharingBadges;
            this.showHotColdBadges = other.showHotColdBadges;

            this.paddingVisualization = other.paddingVisualization;
            this.cacheLineBoundaries = other.cacheLineBoundaries;
            this.falseSharingDetection = other.falseSharingDetection;
            this.hotColdSeparation = other.hotColdSeparation;
            this.zeroAllocLinter = other.zeroAllocLinter;
            this.globalAtomicsAudit = other.globalAtomicsAudit;
            this.autoEnableCpp = other.autoEnableCpp;

            this.storeForwardingDetection = other.storeForwardingDetection;
            this.branchPredictorLinter = other.branchPredictorLinter;
            this.virtualCallLinter = other.virtualCallLinter;
            this.misalignedAllocatorDetection = other.misalignedAllocatorDetection;

            this.cacheLineSize = other.cacheLineSize;
            this.colorScheme = other.colorScheme;
            this.targetPlatform = other.targetPlatform;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            State state = (State) o;
            return showMemoryMapBar == state.showMemoryMapBar &&
                    hoverTooltips == state.hoverTooltips &&
                    maxBarRows == state.maxBarRows &&
                    showFieldOffsets == state.showFieldOffsets &&
                    showPaddingBadges == state.showPaddingBadges &&
                    showSplitBadges == state.showSplitBadges &&
                    showFalseSharingBadges == state.showFalseSharingBadges &&
                    showHotColdBadges == state.showHotColdBadges &&
                    paddingVisualization == state.paddingVisualization &&
                    cacheLineBoundaries == state.cacheLineBoundaries &&
                    falseSharingDetection == state.falseSharingDetection &&
                    hotColdSeparation == state.hotColdSeparation &&
                    zeroAllocLinter == state.zeroAllocLinter &&
                    globalAtomicsAudit == state.globalAtomicsAudit &&
                    autoEnableCpp == state.autoEnableCpp &&
                    storeForwardingDetection == state.storeForwardingDetection &&
                    branchPredictorLinter == state.branchPredictorLinter &&
                    virtualCallLinter == state.virtualCallLinter &&
                    misalignedAllocatorDetection == state.misalignedAllocatorDetection &&
                    cacheLineSize == state.cacheLineSize &&
                    colorScheme == state.colorScheme &&
                    targetPlatform == state.targetPlatform;
        }

        @Override
        public int hashCode() {
            return Objects.hash(showMemoryMapBar, hoverTooltips, maxBarRows,
                    showFieldOffsets, showPaddingBadges, showSplitBadges,
                    showFalseSharingBadges, showHotColdBadges,
                    paddingVisualization, cacheLineBoundaries, falseSharingDetection,
                    hotColdSeparation, zeroAllocLinter, globalAtomicsAudit, autoEnableCpp,
                    storeForwardingDetection, branchPredictorLinter, virtualCallLinter, misalignedAllocatorDetection,
                    cacheLineSize, colorScheme, targetPlatform);
        }
    }

    private State myState = new State();

    public static HFTSettings getInstance() {
        return ApplicationManager.getApplication().getService(HFTSettings.class);
    }

    @NotNull
    @Override
    public State getState() {
        return myState;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.myState = new State(state);
        notifySettingsChanged();
    }

    public void updateState(@NotNull State newState) {
        if (!this.myState.equals(newState)) {
            this.myState = new State(newState);
            notifySettingsChanged();
        }
    }

    public static void syncWithHotPathSettings(@NotNull State state) {
        HotPathSettings hp = HotPathSettings.get();
        hp.setCacheLine(state.cacheLineSize);
        hp.setFalseSharingOn(state.falseSharingDetection);
        hp.pointerSize = 8;

        boolean isWin = PlatformDetector.isWindows(state.targetPlatform);
        boolean isMac = PlatformDetector.isMac(state.targetPlatform);

        hp.longSize = isWin ? 4 : 8;
        hp.longDoubleSize = isWin ? 8 : 16;
        hp.longDoubleAlign = isWin ? 8 : 16;
        hp.wcharSize = isWin ? 2 : 4;
        hp.stringSize = isMac ? 24 : 32;
    }

    public void notifySettingsChanged() {
        syncWithHotPathSettings(this.myState);
        TypeResolver.clearCache();

        ApplicationManager.getApplication().getMessageBus()
                .syncPublisher(HFTSettingsListener.TOPIC)
                .settingsChanged(new State(myState));

        HotPathHighlighter.refreshAllEditors();
    }
}