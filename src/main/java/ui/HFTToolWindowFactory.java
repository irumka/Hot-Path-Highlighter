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
        HFTSettingsPanel panel = new HFTSettingsPanel();
        panel.loadFrom(HFTSettings.getInstance().getState());

        // При любом клике на чекбокс в ToolWindow сразу сохраняем состояние
        panel.setOnChangeListener(() -> {
            HFTSettings settings = HFTSettings.getInstance();
            HFTSettings.State stateCopy = new HFTSettings.State(settings.getState());
            panel.applyTo(stateCopy);
            settings.updateState(stateCopy);
        });

        // Слушаем изменение настроек из глобальной шины приложения
        ApplicationManager.getApplication().getMessageBus()
                .connect(toolWindow.getDisposable())
                .subscribe(HFTSettingsListener.TOPIC, panel::loadFrom);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(panel.getRootComponent(), BorderLayout.NORTH);

        JBScrollPane scrollPane = new JBScrollPane(wrapper);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());

        Content content = ContentFactory.getInstance().createContent(scrollPane, "Quick Configuration", false);
        toolWindow.getContentManager().addContent(content);
    }
}