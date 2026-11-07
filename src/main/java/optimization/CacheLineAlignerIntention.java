package optimization;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import dev.hotpath.HotRegionFinder;
import org.jetbrains.annotations.NotNull;
import ui.HFTSettings;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Alt+Enter action: Aligns the struct or class definition to cache line boundaries.
 * Inserts alignas(64) strictly AFTER 'struct' or 'class' keyword:
 * 'struct alignas(64) Foo { ... };'
 */
public class CacheLineAlignerIntention extends PsiElementBaseIntentionAction implements DumbAware, IntentionAction {

    private String targetStructName = null;

    @NotNull
    @Override
    public String getText() {
        int cacheLineSize = HFTSettings.getInstance().getState().cacheLineSize;
        if (targetStructName != null) {
            return "Align struct '" + targetStructName + "' declaration (alignas " + cacheLineSize + ")";
        }
        return "Align struct declaration (alignas " + cacheLineSize + ")";
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
                int openBrace = text.indexOf('{', r.bStart());
                if (openBrace == -1) openBrace = Math.min(r.bStart() + 120, text.length());

                String header = text.substring(r.mStart(), openBrace);
                int cacheLineSize = HFTSettings.getInstance().getState().cacheLineSize;

                Pattern exactAlign = Pattern.compile("\\balignas\\s*\\(\\s*" + cacheLineSize + "\\s*\\)");
                if (!exactAlign.matcher(header).find()) {
                    this.targetStructName = r.name();
                    return true;
                }
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
            if (offset < r.mStart() || offset > r.bEnd()) continue;

            int openBrace = text.indexOf('{', r.bStart());
            if (openBrace == -1) openBrace = Math.min(r.bStart() + 120, text.length());

            String headerFull = text.substring(r.mStart(), openBrace);
            int cacheLineSize = HFTSettings.getInstance().getState().cacheLineSize;

            // 1. Если был другой alignas — заменяем его точно на месте
            Pattern existingAlign = Pattern.compile("alignas\\s*\\([^)]*\\)");
            Matcher alignMatcher = existingAlign.matcher(headerFull);
            if (alignMatcher.find()) {
                int replaceFrom = r.mStart() + alignMatcher.start();
                int replaceTo = r.mStart() + alignMatcher.end();
                String newAlign = "alignas(" + cacheLineSize + ")";

                WriteCommandAction.runWriteCommandAction(project, () -> {
                    document.replaceString(replaceFrom, replaceTo, newAlign);
                });
                return;
            }

            // 2. Иначе вставляем строго ПОСЛЕ ключевого слова 'struct' или 'class'
            String headerFromStruct = text.substring(r.bStart(), openBrace);
            Pattern kwPattern = Pattern.compile("\\b(struct|class)\\b");
            Matcher kwMatcher = kwPattern.matcher(headerFromStruct);
            if (kwMatcher.find()) {
                int insertPos = r.bStart() + kwMatcher.end();
                String alignDirective = " alignas(" + cacheLineSize + ")";

                WriteCommandAction.runWriteCommandAction(project, () -> {
                    document.insertString(insertPos, alignDirective);
                });
                return;
            }
        }
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }
}