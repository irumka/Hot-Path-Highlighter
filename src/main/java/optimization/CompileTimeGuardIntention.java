package optimization;

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
import dev.hotpath.LayoutCalculator;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Alt+Enter action: Generates a complete block of compile-time static_assert guards
 * checking sizeof, alignof, and offsetof for every struct member.
 * Protects against ABI drifts and compiler optimization layout changes in CI/CD.
 */
public class CompileTimeGuardIntention extends PsiElementBaseIntentionAction implements DumbAware, IntentionAction {

    private String targetStructName = null;

    @NotNull
    @Override
    public String getText() {
        if (targetStructName != null) {
            return "Generate compile-time static_assert guards for '" + targetStructName + "'";
        }
        return "Generate compile-time static_assert layout guards";
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
                String block = text.substring(r.bStart(), Math.min(r.bEnd(), text.length()));
                CppStructParser.StructInfo info = CppStructParser.parse(block);
                if (info == null || info.fields.isEmpty() || info.name.trim().isEmpty()) {
                    return false;
                }

                LayoutCalculator.Layout layout = LayoutCalculator.compute(info, new HashMap<>());
                if (!layout.complete) {
                    return false;
                }

                this.targetStructName = info.name.trim();
                return true;
            }
        }
        return false;
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, @NotNull PsiElement element) {
        if (editor == null) return;
        Document document = editor.getDocument();
        String text = document.getText();
        int offset = editor.getCaretModel().getOffset();

        ArrayList<HotRegionFinder.Region> regions = HotRegionFinder.findAllStructs(text);
        for (HotRegionFinder.Region r : regions) {
            if (offset < r.mStart() || offset > r.bEnd()) {
                continue;
            }

            String block = text.substring(r.bStart(), Math.min(r.bEnd(), text.length()));
            CppStructParser.StructInfo info = CppStructParser.parse(block);
            if (info == null || info.fields.isEmpty()) return;

            LayoutCalculator.Layout layout = LayoutCalculator.compute(info, new HashMap<>());
            if (!layout.complete) return;

            String sName = info.name.trim();
            StringBuilder sb = new StringBuilder();
            sb.append("\n\n// --- HFT Compile-Time Layout Guard: ").append(sName).append(" ---\n");
            sb.append("static_assert(sizeof(").append(sName).append(") == ")
                    .append(layout.totalSize).append(", \"Size check failed for ").append(sName).append("\");\n");

            int maxAlign = 1;
            for (CppStructParser.Field f : layout.fields) {
                if (f.align > maxAlign) {
                    maxAlign = f.align;
                }
            }
            sb.append("static_assert(alignof(").append(sName).append(") == ")
                    .append(maxAlign).append(", \"Alignment check failed for ").append(sName).append("\");\n");

            for (CppStructParser.Field f : layout.fields) {
                if (f.bits >= 0) {
                    continue; // offsetof нельзя вызывать на bitfields
                }
                sb.append("static_assert(offsetof(").append(sName).append(", ").append(f.name)
                        .append(") == ").append(f.offset)
                        .append(", \"Offset drift on field: ").append(f.name).append("\");\n");
            }

            // r.bEnd() уже включает завершающую точку с запятой структуры
            final int finalInsertPos = Math.min(r.bEnd(), document.getTextLength());
            final String assertions = sb.toString();

            WriteCommandAction.runWriteCommandAction(project, () -> {
                document.insertString(finalInsertPos, assertions);

                String currentDoc = document.getText();
                if (!currentDoc.contains("<cstddef>")) {
                    int firstInc = currentDoc.indexOf("#include");
                    if (firstInc != -1) {
                        document.insertString(firstInc, "#include <cstddef>\n");
                    } else {
                        document.insertString(0, "#include <cstddef>\n");
                    }
                }
            });
            return;
        }
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }
}