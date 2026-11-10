package optimization;

import analysis.MemoryOrderLinter;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import dev.hotpath.CppStructParser;
import dev.hotpath.HotRegionFinder;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Alt+Enter action: Weakens default/seq_cst atomic operations to relaxed, acquire, or release
 * to eliminate expensive mfence / bus-locking CPU pipeline stalls.
 */
public class MemoryOrderFixerIntention extends PsiElementBaseIntentionAction implements DumbAware, IntentionAction {

    private MemoryOrderLinter.MemoryOrderHazard targetHazard = null;

    @NotNull
    @Override
    public String getText() {
        if (targetHazard != null) {
            return "Optimize memory_order for '" + targetHazard.atomicName + "' (" + targetHazard.suggestedOrder.code + ")";
        }
        return "Optimize atomic memory_order (remove seq_cst)";
    }

    @NotNull
    @Override
    public String getFamilyName() {
        return "HFT Optimizations";
    }

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, @NotNull PsiElement element) {
        if (editor == null) return false;

        Document document = editor.getDocument();
        String text = document.getText();
        int offset = editor.getCaretModel().getOffset();
        ArrayList<HotRegionFinder.Region> regions = HotRegionFinder.findAllStructs(text);

        for (HotRegionFinder.Region r : regions) {
            if (offset >= r.mStart() && offset <= r.bEnd()) {
                String block = text.substring(r.bStart(), Math.min(r.bEnd(), text.length()));
                CppStructParser.StructInfo info = CppStructParser.parse(block);
                if (info == null) return false;

                List<MemoryOrderLinter.MemoryOrderHazard> hazards = MemoryOrderLinter.inspect(info, block, r.bStart());
                for (MemoryOrderLinter.MemoryOrderHazard h : hazards) {
                    if (Math.abs(offset - h.offsetInDoc) < 50 || (offset >= h.offsetInDoc && offset <= h.offsetInDoc + h.matchLength)) {
                        this.targetHazard = h;
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, @NotNull PsiElement element) {
        if (editor == null || targetHazard == null) return;

        Document document = editor.getDocument();
        String replacement;

        if ("load".equals(targetHazard.operation)) {
            replacement = targetHazard.atomicName + ".load(" + targetHazard.suggestedOrder.code + ")";
        } else if ("store".equals(targetHazard.operation)) {
            String orig = targetHazard.fullMatch;
            int open = orig.indexOf('(');
            int close = orig.lastIndexOf(')');
            String val = (open != -1 && close > open) ? orig.substring(open + 1, close).trim() : "val";
            // Очищаем существующий seq_cst, если был
            val = val.replaceAll(",?\\s*std::memory_order_seq_cst", "").trim();
            replacement = targetHazard.atomicName + ".store(" + val + ", " + targetHazard.suggestedOrder.code + ")";
        } else if ("fetch_add".equals(targetHazard.operation) || "fetch_sub".equals(targetHazard.operation)) {
            String orig = targetHazard.fullMatch;
            int open = orig.indexOf('(');
            int close = orig.lastIndexOf(')');
            String val = (open != -1 && close > open) ? orig.substring(open + 1, close).trim() : "1";
            val = val.replaceAll(",?\\s*std::memory_order_seq_cst", "").trim();
            replacement = targetHazard.atomicName + "." + targetHazard.operation + "(" + val + ", " + targetHazard.suggestedOrder.code + ")";
        } else {
            // Оператор инкремента var++ или ++var
            replacement = targetHazard.atomicName + ".fetch_add(1, " + targetHazard.suggestedOrder.code + ")";
        }

        final String finalReplacement = replacement;
        WriteCommandAction.runWriteCommandAction(project, () -> {
            document.replaceString(targetHazard.offsetInDoc, targetHazard.offsetInDoc + targetHazard.matchLength, finalReplacement);
        });
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }
}