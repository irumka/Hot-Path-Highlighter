package optimization;

import analysis.CacheLineSplitDetector;
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
import ui.HFTSettings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Alt+Enter action: Inserts alignas(64) directly on the split variable line,
 * shifting the field to the start of the next cache line.
 */
public class CacheLineSplitFixerIntention extends PsiElementBaseIntentionAction implements DumbAware, IntentionAction {

    private String targetFieldName = null;

    @NotNull
    @Override
    public String getText() {
        int cacheLineSize = HFTSettings.getInstance().getState().cacheLineSize;
        if (targetFieldName != null) {
            return "Align field '" + targetFieldName + "' to cache line (alignas " + cacheLineSize + ")";
        }
        return "Align field to prevent cache line split (alignas " + cacheLineSize + ")";
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
            if (offset < r.mStart() || offset > r.bEnd()) {
                continue;
            }

            String block = text.substring(r.bStart(), Math.min(r.bEnd(), text.length()));
            CppStructParser.StructInfo info = CppStructParser.parse(block);
            if (info == null || info.fields.size() < 2) return false;

            LayoutCalculator.Layout layout = LayoutCalculator.compute(info, new HashMap<>());
            if (!layout.complete) return false;

            int cacheLineSize = HFTSettings.getInstance().getState().cacheLineSize;
            List<CacheLineSplitDetector.SplitField> splits = CacheLineSplitDetector.findSplits(layout, cacheLineSize);
            if (splits.isEmpty()) return false;

            CacheLineSplitDetector.SplitField target = null;
            for (CacheLineSplitDetector.SplitField sf : splits) {
                int fStart = r.bStart() + sf.field.start;
                int fEnd = r.bStart() + sf.field.end;
                if (offset >= fStart && offset <= fEnd) {
                    target = sf;
                    break;
                }
            }
            if (target == null) {
                target = splits.get(0);
            }

            this.targetFieldName = target.field.name;
            return true;
        }
        return false;
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, @NotNull PsiElement element) {
        if (editor == null || targetFieldName == null) return;

        Document document = editor.getDocument();
        String docText = document.getText();
        int offset = editor.getCaretModel().getOffset();
        ArrayList<HotRegionFinder.Region> regions = HotRegionFinder.findAllStructs(docText);

        for (HotRegionFinder.Region r : regions) {
            if (offset < r.mStart() || offset > r.bEnd()) continue;

            int searchStart = r.bStart();
            int searchEnd = Math.min(r.bEnd(), document.getTextLength());
            String structBlock = docText.substring(searchStart, searchEnd);

            Pattern fieldPattern = Pattern.compile(
                    "^[ \\t]*([^\\n;]*?\\b" + Pattern.quote(targetFieldName) + "\\b[^;\\n]*;)",
                    Pattern.MULTILINE
            );
            Matcher matcher = fieldPattern.matcher(structBlock);

            if (matcher.find()) {
                int lineStartInDoc = searchStart + matcher.start();
                int lineIndex = document.getLineNumber(lineStartInDoc);
                int lineStart = document.getLineStartOffset(lineIndex);
                int lineEnd = document.getLineEndOffset(lineIndex);
                String lineContent = document.getText().substring(lineStart, lineEnd);

                int firstNonWs = 0;
                while (firstNonWs < lineContent.length() && Character.isWhitespace(lineContent.charAt(firstNonWs))) {
                    firstNonWs++;
                }

                final int insertPos = lineStart + firstNonWs;
                final int cacheLineSize = HFTSettings.getInstance().getState().cacheLineSize;

                WriteCommandAction.runWriteCommandAction(project, () -> {
                    Pattern alignPattern = Pattern.compile("alignas\\s*\\([^)]*\\)\\s*");
                    Matcher am = alignPattern.matcher(lineContent);
                    if (am.find()) {
                        int rStart = lineStart + am.start();
                        int rEnd = lineStart + am.end();
                        document.replaceString(rStart, rEnd, "alignas(" + cacheLineSize + ") ");
                    } else {
                        document.insertString(insertPos, "alignas(" + cacheLineSize + ") ");
                    }
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