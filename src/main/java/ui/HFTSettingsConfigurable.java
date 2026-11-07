package ui;

import com.intellij.openapi.options.SearchableConfigurable;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

public class HFTSettingsConfigurable implements SearchableConfigurable {

    private HFTSettingsPanel settingsPanel;

    @NotNull
    @Override
    public String getId() {
        return "com.irumka.hft.optimizer.settings";
    }

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "HFT Optimizer";
    }

    @Nullable
    @Override
    public JComponent createComponent() {
        if (settingsPanel == null) {
            settingsPanel = new HFTSettingsPanel();
        }
        reset();
        return settingsPanel.getRootComponent();
    }

    @Override
    public boolean isModified() {
        return settingsPanel != null && settingsPanel.isModified(HFTSettings.getInstance().getState());
    }

    @Override
    public void apply() {
        if (settingsPanel != null) {
            HFTSettings settings = HFTSettings.getInstance();
            HFTSettings.State stateCopy = new HFTSettings.State(settings.getState());
            settingsPanel.applyTo(stateCopy);
            settings.updateState(stateCopy);
        }
    }

    @Override
    public void reset() {
        if (settingsPanel != null) {
            settingsPanel.loadFrom(HFTSettings.getInstance().getState());
        }
    }

    @Override
    public void disposeUIResources() {
        settingsPanel = null;
    }
}