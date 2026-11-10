package optimization;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import core.FalseSharingDetector;
import core.ProducerConsumerAnalyzer;
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
 * Alt+Enter action: Targets conflicting fields causing false sharing
 * and isolates both of them via alignas(64) to strictly guarantee distinct cache lines.
 */
public class FalseSharingFixerIntention extends PsiElementBaseIntentionAction implements DumbAware, IntentionAction {

    private String firstFieldName = null;
    private String secondFieldName = null;

    @NotNull
    @Override
    public String getText() {
        return "Add padding to eliminate false sharing";
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
            if (offset < r.mStart() || offset > r.bEnd()) continue;

            String block = text.substring(r.bStart(), Math.min(r.bEnd(), text.length()));
            CppStructParser.StructInfo info = CppStructParser.parse(block);
            if (info == null || info.fields.size() < 2) return false;

            LayoutCalculator.Layout layout = LayoutCalculator.compute(info, new HashMap<>());
            if (!layout.complete) return false;

            int cacheLineSize = HFTSettings.getInstance().getState().cacheLineSize;
            ProducerConsumerAnalyzer.StructProfile profile = ProducerConsumerAnalyzer.analyze(info, block);

            List<FalseSharingDetector.CacheField> cacheFields = new ArrayList<>();
            for (CppStructParser.Field f : layout.fields) {
                ProducerConsumerAnalyzer.FieldProfile fp = profile.getFieldProfile(f.name);
                boolean isAtomic = fp != null && fp.isAtomic;
                boolean isProducer = fp != null && fp.isProducer();
                boolean isConsumer = fp != null && fp.isConsumer();
                boolean isArray = fp != null && fp.isArray;
                int arrCount = fp != null ? fp.arraySize : 1;

                cacheFields.add(new FalseSharingDetector.CacheField(
                        f.name, f.offset, f.size, cacheLineSize, isAtomic, isProducer, isConsumer, isArray, arrCount
                ));
            }

            FalseSharingDetector.Result result = FalseSharingDetector.detect(cacheFields, cacheLineSize, profile);
            if (result.conflicts.isEmpty()) return false;

            FalseSharingDetector.Conflict firstConflict = result.conflicts.get(0);
            this.firstFieldName = firstConflict.first.name;
            this.secondFieldName = firstConflict.second.name;
            return true;
        }
        return false;
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, @NotNull PsiElement element) {
        if (editor == null || firstFieldName == null || secondFieldName == null) return;

        Document document = editor.getDocument();
        String docText = document.getText();
        int offset = editor.getCaretModel().getOffset();
        ArrayList<HotRegionFinder.Region> regions = HotRegionFinder.findAllStructs(docText);

        for (HotRegionFinder.Region r : regions) {
            if (offset < r.mStart() || offset > r.bEnd()) continue;

            int cacheLineSize = HFTSettings.getInstance().getState().cacheLineSize;

            WriteCommandAction.runWriteCommandAction(project, () -> {
                // Выравниваем оба конфликтующих поля для 100% гарантии разнесения по кэш-линиям
                alignFieldInDoc(document, r, secondFieldName, cacheLineSize);
                alignFieldInDoc(document, r, firstFieldName, cacheLineSize);
            });
            return;
        }
    }

    private void alignFieldInDoc(Document document, HotRegionFinder.Region r, String fieldName, int cacheLineSize) {
        String docText = document.getText();
        int searchStart = r.bStart();
        int searchEnd = Math.min(r.bEnd(), document.getTextLength());
        String structBlock = docText.substring(searchStart, searchEnd);

        Pattern fieldLinePattern = Pattern.compile(
                "^[ \\t]*([^\\n;]*?\\b" + Pattern.quote(fieldName) + "\\b[^;\\n]*;)",
                Pattern.MULTILINE
        );
        Matcher matcher = fieldLinePattern.matcher(structBlock);

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

            Pattern alignPattern = Pattern.compile("alignas\\s*\\([^)]*\\)\\s*");
            Matcher am = alignPattern.matcher(lineContent);
            if (am.find()) {
                int rStart = lineStart + am.start();
                int rEnd = lineStart + am.end();
                document.replaceString(rStart, rEnd, "alignas(" + cacheLineSize + ") ");
            } else {
                document.insertString(lineStart + firstNonWs, "alignas(" + cacheLineSize + ") ");
            }
        }
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }
}