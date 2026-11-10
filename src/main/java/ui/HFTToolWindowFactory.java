package ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

public class HFTToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        // Модуль 1: Интерактивный дашборд задержек и метрик памяти
        HFTDashboardPanel dashboardPanel = new HFTDashboardPanel(project);

        // Модуль 2: Панель настроек, пресетов и параметров кремния
        HFTSettingsPanel settingsPanel = new HFTSettingsPanel();
        settingsPanel.loadFrom(HFTSettings.getInstance().getState());

        // Связываем изменение настроек с сохранением и перерасчетом дашборда
        settingsPanel.setOnChangeListener(() -> {
            HFTSettings settings = HFTSettings.getInstance();
            HFTSettings.State stateCopy = new HFTSettings.State(settings.getState());
            settingsPanel.applyTo(stateCopy);
            settings.updateState(stateCopy);
            dashboardPanel.refreshDashboard();
        });

        // Слушаем изменение настроек из других окон IDE
        ApplicationManager.getApplication().getMessageBus()
                .connect(toolWindow.getDisposable())
                .subscribe(HFTSettingsListener.TOPIC, newState -> {
                    settingsPanel.loadFrom(newState);
                    dashboardPanel.refreshDashboard();
                });

        JPanel settingsWrapper = new JPanel(new BorderLayout());
        settingsWrapper.add(settingsPanel.getRootComponent(), BorderLayout.NORTH);

        JBScrollPane settingsScroll = new JBScrollPane(settingsWrapper);
        settingsScroll.setBorder(BorderFactory.createEmptyBorder());

        // Гарантированное переключение между двумя модулями прямо вверху панели
        JBTabbedPane tabbedPane = new JBTabbedPane();
        tabbedPane.addTab("📊 Latency Dashboard", dashboardPanel);
        tabbedPane.addTab("⚙️ Settings & Presets", settingsScroll);

        Content content = ContentFactory.getInstance().createContent(tabbedPane, "", false);
        toolWindow.getContentManager().addContent(content);
    }
}