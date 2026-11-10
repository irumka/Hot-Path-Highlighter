package ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

public class HFTToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ContentFactory contentFactory = ContentFactory.getInstance();

        // 1. Вкладка HFT Latency Dashboard
        HFTDashboardPanel dashboardPanel = new HFTDashboardPanel(project);
        Content dashboardContent = contentFactory.createContent(dashboardPanel, "Latency Dashboard", false);
        toolWindow.getContentManager().addContent(dashboardContent);

        // 2. Вкладка настроек и конфигурации
        HFTSettingsPanel settingsPanel = new HFTSettingsPanel();
        settingsPanel.loadFrom(HFTSettings.getInstance().getState());

        settingsPanel.setOnChangeListener(() -> {
            HFTSettings settings = HFTSettings.getInstance();
            HFTSettings.State stateCopy = new HFTSettings.State(settings.getState());
            settingsPanel.applyTo(stateCopy);
            settings.updateState(stateCopy);
            dashboardPanel.refreshDashboard();
        });

        ApplicationManager.getApplication().getMessageBus()
                .connect(toolWindow.getDisposable())
                .subscribe(HFTSettingsListener.TOPIC, newState -> {
                    settingsPanel.loadFrom(newState);
                    dashboardPanel.refreshDashboard();
                });

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(settingsPanel.getRootComponent(), BorderLayout.NORTH);

        JBScrollPane scrollPane = new JBScrollPane(wrapper);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());

        Content settingsContent = contentFactory.createContent(scrollPane, "Settings & Presets", false);
        toolWindow.getContentManager().addContent(settingsContent);
    }
}