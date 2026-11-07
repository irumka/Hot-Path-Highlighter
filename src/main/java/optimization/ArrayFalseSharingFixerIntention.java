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
 * Alt+Enter action: Fixes Array False Sharing in thread pools / worker arrays
 * by aligning the element struct definition to 64 bytes (1 cache line per core).
 */
public class ArrayFalseSharingFixerIntention extends PsiElementBaseIntentionAction implements DumbAware, IntentionAction {

    private String conflictingArrayField = null;

    @NotNull
    @Override
    public String getText() {
        int cacheLineSize = HFTSettings.getInstance().getState().cacheLineSize;
        if (conflictingArrayField != null) {
            return "Align worker array '" + conflictingArrayField + "' elements to " + cacheLineSize + "B";
        }
        return "Align array element struct to " + cacheLineSize + "B";
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
            if (offset < r.bStart() || offset > r.bEnd()) {
                continue;
            }

            String block = text.substring(r.bStart(), Math.min(r.bEnd(), text.length()));
            CppStructParser.StructInfo info = CppStructParser.parse(block);
            if (info == null || info.fields.isEmpty()) return false;

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
                int count = fp != null ? fp.arraySize : 1;

                cacheFields.add(new FalseSharingDetector.CacheField(
                        f.name, f.offset, f.size, cacheLineSize, isAtomic, isProducer, isConsumer, isArray, count
                ));
            }

            FalseSharingDetector.Result result = FalseSharingDetector.detect(cacheFields, cacheLineSize, profile);
            for (FalseSharingDetector.Conflict c : result.conflicts) {
                if (c.level == FalseSharingDetector.HazardLevel.ARRAY_HAZARD) {
                    this.conflictingArrayField = c.first.name;
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, @NotNull PsiElement element) {
        if (editor == null || conflictingArrayField == null) return;

        Document document = editor.getDocument();
        String text = document.getText();
        int offset = editor.getCaretModel().getOffset();
        int cacheLineSize = HFTSettings.getInstance().getState().cacheLineSize;

        ArrayList<HotRegionFinder.Region> regions = HotRegionFinder.findAllStructs(text);
        for (HotRegionFinder.Region r : regions) {
            if (offset < r.bStart() || offset > r.bEnd()) {
                continue;
            }

            String block = text.substring(r.bStart(), Math.min(r.bEnd(), text.length()));
            CppStructParser.StructInfo info = CppStructParser.parse(block);
            if (info == null) return;

            CppStructParser.Field targetField = null;
            for (CppStructParser.Field f : info.fields) {
                if (f.name.equals(conflictingArrayField)) {
                    targetField = f;
                    break;
                }
            }
            if (targetField == null) return;

            String elemType = targetField.type.trim();

            for (HotRegionFinder.Region targetReg : regions) {
                if (targetReg.name().equals(elemType)) {
                    int openBrace = text.indexOf('{', targetReg.bStart());
                    if (openBrace == -1) openBrace = Math.min(targetReg.bStart() + 100, text.length());
                    String header = text.substring(targetReg.bStart(), openBrace);

                    Pattern kwPattern = Pattern.compile("\\b(struct|class)\\b");
                    Matcher kwMatcher = kwPattern.matcher(header);
                    if (kwMatcher.find()) {
                        int insertPos = targetReg.bStart() + kwMatcher.end();
                        String alignDirective = " alignas(" + cacheLineSize + ")";

                        WriteCommandAction.runWriteCommandAction(project, () -> {
                            document.insertString(insertPos, alignDirective);
                        });
                        return;
                    }
                }
            }

            int fieldInsertPos = r.bStart() + targetField.start;
            WriteCommandAction.runWriteCommandAction(project, () -> {
                document.insertString(fieldInsertPos, "alignas(" + cacheLineSize + ") ");
            });
            return;
        }
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }
}