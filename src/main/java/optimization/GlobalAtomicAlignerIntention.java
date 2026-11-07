package optimization;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import core.GlobalAtomicsAudit;
import org.jetbrains.annotations.NotNull;
import ui.HFTSettings;

import java.util.List;

/**
 * Alt+Enter action: Aligns static atomic variables in .bss/.data section to 64 bytes.
 */
public class GlobalAtomicAlignerIntention extends PsiElementBaseIntentionAction implements DumbAware, IntentionAction {

    private String targetAtomicName = null;
    private int targetOffset = -1;

    @NotNull
    @Override
    public String getText() {
        int cacheLineSize = HFTSettings.getInstance().getState().cacheLineSize;
        if (targetAtomicName != null) {
            return "Align global atomic '" + targetAtomicName + "' to " + cacheLineSize + "B (prevent .bss collision)";
        }
        return "Align global atomic to cache line";
    }

    @NotNull
    @Override
    public String getFamilyName() {
        return "HFT Optimizations";
    }

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, @NotNull PsiElement element) {
        if (editor == null) return false;

        Document doc = editor.getDocument();
        String text = doc.getText();
        int offset = editor.getCaretModel().getOffset();
        int cacheLineSize = HFTSettings.getInstance().getState().cacheLineSize;

        List<GlobalAtomicsAudit.AuditConflict> conflicts = GlobalAtomicsAudit.audit(text, cacheLineSize);
        for (GlobalAtomicsAudit.AuditConflict c : conflicts) {
            int a1 = c.first.offsetInDoc;
            int a2 = c.second.offsetInDoc;
            if (Math.abs(offset - a1) < 80 || Math.abs(offset - a2) < 80) {
                this.targetAtomicName = (Math.abs(offset - a2) < Math.abs(offset - a1)) ? c.second.name : c.first.name;
                this.targetOffset = (Math.abs(offset - a2) < Math.abs(offset - a1)) ? a2 : a1;
                return true;
            }
        }
        return false;
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, @NotNull PsiElement element) {
        if (editor == null || targetOffset == -1) return;

        Document document = editor.getDocument();
        int cacheLineSize = HFTSettings.getInstance().getState().cacheLineSize;
        String alignPrefix = "alignas(" + cacheLineSize + ") ";

        WriteCommandAction.runWriteCommandAction(project, () -> {
            int lineNum = document.getLineNumber(targetOffset);
            int lineStart = document.getLineStartOffset(lineNum);
            String line = document.getText().substring(lineStart, document.getLineEndOffset(lineNum));

            int nonWs = 0;
            while (nonWs < line.length() && Character.isWhitespace(line.charAt(nonWs))) {
                nonWs++;
            }
            document.insertString(lineStart + nonWs, alignPrefix);
        });
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }
}