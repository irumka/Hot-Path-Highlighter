package ui;

import com.intellij.util.messages.Topic;
import org.jetbrains.annotations.NotNull;

public interface HFTSettingsListener {
    Topic<HFTSettingsListener> TOPIC = Topic.create("HFT Settings Changed", HFTSettingsListener.class);

    void settingsChanged(@NotNull HFTSettings.State newState);
}