package optimization;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import dev.hotpath.HotRegionFinder;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;

/**
 * Alt+Enter action: Opens the current struct in Compiler Explorer (Godbolt)
 * to inspect assembly differences with -O3 -std=c++20 -march=native.
 */
public class GodboltIntention extends PsiElementBaseIntentionAction implements DumbAware, IntentionAction {

    private String targetStructName = null;
    private String targetStructCode = null;

    @NotNull
    @Override
    public String getText() {
        if (targetStructName != null) {
            return "Inspect struct '" + targetStructName + "' in Compiler Explorer (Godbolt)";
        }
        return "Inspect struct in Compiler Explorer (Godbolt)";
    }

    @NotNull
    @Override
    public String getFamilyName() {
        return "HFT Optimizations";
    }

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, @NotNull PsiElement element) {
        if (editor == null) return false;

        String text = editor.getDocument().getText();
        int offset = editor.getCaretModel().getOffset();
        ArrayList<HotRegionFinder.Region> regions = HotRegionFinder.findAllStructs(text);

        for (HotRegionFinder.Region r : regions) {
            if (offset >= r.mStart() && offset <= r.bEnd()) {
                this.targetStructName = r.name();
                int start = Math.max(0, r.mStart());
                int end = Math.min(r.bEnd(), text.length());
                this.targetStructCode = text.substring(start, end);
                return true;
            }
        }
        return false;
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, @NotNull PsiElement element) {
        if (targetStructCode == null || targetStructName == null) return;
        GodboltExporter.openInGodbolt(targetStructCode, targetStructName);
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }
}