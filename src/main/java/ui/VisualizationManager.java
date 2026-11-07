package ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import dev.hotpath.HotPathHighlighter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

@Service(Service.Level.PROJECT)
public final class VisualizationManager implements Disposable {

    private static final Logger LOG = Logger.getInstance(VisualizationManager.class);
    private static final Set<String> CPP_EXTENSIONS = Set.of("cpp", "cxx", "cc", "c", "h", "hpp", "hxx");

    private final Project myProject;

    public VisualizationManager(@NotNull Project project) {
        this.myProject = project;
        initListeners();
    }

    public static VisualizationManager getInstance(@NotNull Project project) {
        return project.getService(VisualizationManager.class);
    }

    private void initListeners() {
        myProject.getMessageBus().connect(this).subscribe(HFTSettingsListener.TOPIC, newState -> {
            LOG.debug("Settings changed, updating hot path inlays...");
            refreshAllOpenEditors();
        });

        EditorFactory.getInstance().addEditorFactoryListener(new EditorFactoryListener() {
            @Override
            public void editorCreated(@NotNull EditorFactoryEvent event) {
                Editor editor = event.getEditor();
                if (myProject.equals(editor.getProject())) {
                    refreshEditor(editor);
                }
            }
        }, this);
    }

    public void refreshAllOpenEditors() {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (myProject.isDisposed()) {
                return;
            }
            Editor[] editors = EditorFactory.getInstance().getAllEditors();
            for (Editor editor : editors) {
                if (myProject.equals(editor.getProject())) {
                    refreshEditor(editor);
                }
            }
        });
    }

    public void refreshEditor(@NotNull Editor editor) {
        if (!shouldAnalyze(editor)) {
            HotPathHighlighter.removeOldHints(editor, editor.getDocument().getTextLength());
            HotPathHighlighter.removeOldBars(editor, editor.getDocument().getTextLength());
            return;
        }
        HotPathHighlighter.update(editor);
    }

    public boolean shouldAnalyze(@NotNull Editor editor) {
        HFTSettings.State state = HFTSettings.getInstance().getState();
        if (!state.autoEnableCpp) {
            return true;
        }

        VirtualFile virtualFile = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (virtualFile == null) {
            return false;
        }

        @Nullable String extension = virtualFile.getExtension();
        return extension != null && CPP_EXTENSIONS.contains(extension.toLowerCase());
    }

    @Override
    public void dispose() {
    }
}